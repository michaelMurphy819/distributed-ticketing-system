package com.distributedsystems.ticketing.service;

import com.distributedsystems.ticketing.kafka.BookingKafkaProducer;
import com.distributedsystems.ticketing.model.BookingEvent;
import com.distributedsystems.ticketing.model.BookingRequest;
import com.distributedsystems.ticketing.model.BookingResponse;
import com.distributedsystems.ticketing.model.EventDetails;
import com.distributedsystems.ticketing.repository.BookingRepository;
import com.distributedsystems.ticketing.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final EventRepository eventRepository;
    private final BookingRepository bookingRepository;
    private final BookingKafkaProducer kafkaProducer;
    private final TransactionTemplate transactionTemplate;

    @Value("${instance.id:booking-service-unknown}")
    private String instanceId;

    public BookingService(EventRepository eventRepository,
                          BookingRepository bookingRepository,
                          BookingKafkaProducer kafkaProducer,
                          PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
        this.kafkaProducer = kafkaProducer;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public String getInstanceId() {
        return instanceId;
    }

    /**
     * Dispatch booking request to selected concurrency strategy.
     */
    public BookingResponse processBooking(BookingRequest request, String strategy) {
        String cleanStrategy = (strategy != null) ? strategy.toLowerCase().trim() : "pessimistic";

        // Check idempotency first across all strategies
        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            Optional<BookingRepository.ExistingBooking> existing = bookingRepository.findByIdempotencyKey(request.getIdempotencyKey());
            if (existing.isPresent()) {
                BookingRepository.ExistingBooking b = existing.get();
                log.info("[{}] Idempotent request detected for key={}. Returning existing booking={}",
                        instanceId, request.getIdempotencyKey(), b.id());
                return BookingResponse.successful(
                        b.id(), b.eventId(), b.userId(), b.ticketsCount(),
                        eventRepository.getAvailableTicketsNaive(b.eventId()),
                        b.strategy(), instanceId, "Idempotent response: booking already exists"
                );
            }
        }

        return switch (cleanStrategy) {
            case "naive" -> bookNaive(request);
            case "optimistic" -> bookOptimistic(request);
            case "pessimistic" -> bookPessimistic(request);
            default -> throw new IllegalArgumentException("Unknown strategy: " + strategy + ". Allowed: naive, pessimistic, optimistic");
        };
    }

    /**
     * STRATEGY 1: NAIVE (No Concurrency Control)
     * Demonstrates classic "check-then-act" race condition and lost updates across multiple JVMs.
     */
    public BookingResponse bookNaive(BookingRequest request) {
        Long eventId = request.getEventId();
        int requestedTickets = request.getTicketsCount();

        // 1. Read current available tickets without any lock or version check
        int available = eventRepository.getAvailableTicketsNaive(eventId);

        if (available < requestedTickets) {
            log.warn("[{}] [NAIVE] Sold out: eventId={}, requested={}, available={}",
                    instanceId, eventId, requestedTickets, available);
            return BookingResponse.failed(eventId, request.getUserId(), "naive",
                    instanceId, "Sold out: insufficient tickets available", available);
        }

        // 2. Introduce a small artificial processing think-time (10ms)
        // This simulates realistic network/business processing delay and broadens the race condition
        // window so concurrent requests across different JVM instances interleave their reads before writes.
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 3. Blind decrement without locking
        eventRepository.decrementTicketsNaive(eventId, requestedTickets);

        // 4. Record confirmed booking
        UUID bookingId = UUID.randomUUID();
        bookingRepository.insertBooking(bookingId, eventId, request.getUserId(), requestedTickets, "naive", request.getIdempotencyKey());

        // 5. Asynchronously publish confirmation to Kafka
        publishKafkaEvent(bookingId, eventId, request.getUserId(), requestedTickets, "naive");

        int remaining = eventRepository.getAvailableTicketsNaive(eventId);
        log.info("[{}] [NAIVE] Booked {} ticket(s) for user={}. Reported remaining={}",
                instanceId, requestedTickets, request.getUserId(), remaining);

        return BookingResponse.successful(bookingId, eventId, request.getUserId(),
                requestedTickets, remaining, "naive", instanceId, "Booking confirmed (naive mode)");
    }

    /**
     * STRATEGY 2: PESSIMISTIC (Row-level Locking)
     * Uses PostgreSQL SELECT ... FOR UPDATE wrapped inside an explicit database transaction.
     * Concurrent transactions block and wait in PostgreSQL until the lock is released upon commit.
     */
    public BookingResponse bookPessimistic(BookingRequest request) {
        Long eventId = request.getEventId();
        int requestedTickets = request.getTicketsCount();

        return transactionTemplate.execute(status -> {
            // 1. Acquire row-level lock on the event inventory row in PostgreSQL
            int available = eventRepository.getAvailableTicketsForUpdate(eventId);

            if (available < requestedTickets) {
                log.info("[{}] [PESSIMISTIC] Sold out: eventId={}, requested={}, available={}",
                        instanceId, eventId, requestedTickets, available);
                return BookingResponse.failed(eventId, request.getUserId(), "pessimistic",
                        instanceId, "Sold out: insufficient tickets available", available);
            }

            // 2. Decrement within locked transaction
            eventRepository.decrementTicketsPessimistic(eventId, requestedTickets);

            // 3. Insert confirmed booking
            UUID bookingId = UUID.randomUUID();
            bookingRepository.insertBooking(bookingId, eventId, request.getUserId(), requestedTickets, "pessimistic", request.getIdempotencyKey());

            int remaining = available - requestedTickets;

            // 4. Publish Kafka event
            publishKafkaEvent(bookingId, eventId, request.getUserId(), requestedTickets, "pessimistic");

            log.info("[{}] [PESSIMISTIC] Successfully locked & booked {} ticket(s) for user={}. New remaining={}",
                    instanceId, requestedTickets, request.getUserId(), remaining);

            return BookingResponse.successful(bookingId, eventId, request.getUserId(),
                    requestedTickets, remaining, "pessimistic", instanceId, "Booking confirmed (pessimistic lock)");
        });
    }

    /**
     * STRATEGY 3: OPTIMISTIC (Version-column check / CAS)
     * Reads (available_tickets, version) and attempts conditional UPDATE ... WHERE version = expectedVersion.
     * Rejects conflicting updates without blocking database threads.
     */
    public BookingResponse bookOptimistic(BookingRequest request) {
        Long eventId = request.getEventId();
        int requestedTickets = request.getTicketsCount();

        // 1. Read inventory and current version without acquiring locks
        Optional<EventRepository.VersionedInventory> optInventory = eventRepository.getVersionedInventory(eventId);
        if (optInventory.isEmpty()) {
            return BookingResponse.failed(eventId, request.getUserId(), "optimistic",
                    instanceId, "Event not found", 0);
        }

        EventRepository.VersionedInventory inventory = optInventory.get();
        if (inventory.availableTickets() < requestedTickets) {
            log.info("[{}] [OPTIMISTIC] Sold out: eventId={}, requested={}, available={}",
                    instanceId, eventId, requestedTickets, inventory.availableTickets());
            return BookingResponse.failed(eventId, request.getUserId(), "optimistic",
                    instanceId, "Sold out: insufficient tickets available", inventory.availableTickets());
        }

        // 2. Attempt atomic Compare-And-Swap update with version increment
        int rowsUpdated = eventRepository.decrementTicketsOptimistic(eventId, requestedTickets, inventory.version());

        if (rowsUpdated == 0) {
            // Version mismatch: another concurrent transaction modified the row first!
            log.warn("[{}] [OPTIMISTIC] Version conflict detected for eventId={} at version={}. Transaction rejected.",
                    instanceId, eventId, inventory.version());
            return BookingResponse.failed(eventId, request.getUserId(), "optimistic",
                    instanceId, "Optimistic lock conflict: ticket inventory was updated by another buyer. Please retry.",
                    inventory.availableTickets());
        }

        // 3. Conditional update succeeded; record confirmed booking
        UUID bookingId = UUID.randomUUID();
        bookingRepository.insertBooking(bookingId, eventId, request.getUserId(), requestedTickets, "optimistic", request.getIdempotencyKey());

        int remaining = inventory.availableTickets() - requestedTickets;

        // 4. Publish Kafka event
        publishKafkaEvent(bookingId, eventId, request.getUserId(), requestedTickets, "optimistic");

        log.info("[{}] [OPTIMISTIC] Booked {} ticket(s) for user={} under version {}. New remaining={}",
                instanceId, requestedTickets, request.getUserId(), inventory.version(), remaining);

        return BookingResponse.successful(bookingId, eventId, request.getUserId(),
                requestedTickets, remaining, "optimistic", instanceId, "Booking confirmed (optimistic version check)");
    }

    private void publishKafkaEvent(UUID bookingId, Long eventId, String userId, int count, String strategy) {
        BookingEvent event = new BookingEvent(
                bookingId, eventId, userId, count, strategy, Instant.now(), instanceId
        );
        kafkaProducer.publishBookingConfirmation(event);
    }

    public EventDetails getEventDetails(Long eventId) {
        EventRepository.EventRecord record = eventRepository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("Event not found with id: " + eventId));
        int confirmedBookings = bookingRepository.countConfirmedBookings(eventId);
        int oversold = Math.max(0, confirmedBookings - record.totalTickets());

        return new EventDetails(
                record.id(),
                record.name(),
                record.totalTickets(),
                record.availableTickets(),
                record.version(),
                confirmedBookings,
                oversold,
                instanceId
        );
    }

    @Transactional
    public void resetEventInventory(Long eventId, int totalTickets) {
        bookingRepository.deleteBookingsByEventId(eventId);
        eventRepository.resetEvent(eventId, totalTickets);
        log.info("[{}] Event id={} reset to totalTickets={}, availableTickets={}, version=0",
                instanceId, eventId, totalTickets, totalTickets);
    }
}
