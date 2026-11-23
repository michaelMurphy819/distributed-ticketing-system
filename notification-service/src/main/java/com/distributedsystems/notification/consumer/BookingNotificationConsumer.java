package com.distributedsystems.notification.consumer;

import com.distributedsystems.notification.model.BookingEvent;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class BookingNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookingNotificationConsumer.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final AtomicLong processedCount = new AtomicLong(0);

    @KafkaListener(
        topics = "booking-confirmations",
        groupId = "${spring.kafka.consumer.group-id:notification-service-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeBookingEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        long currentCount = processedCount.incrementAndGet();
        try {
            String jsonPayload = record.value();
            BookingEvent event = objectMapper.readValue(jsonPayload, BookingEvent.class);

            log.info("==========================================================================");
            log.info("[NOTIFICATION CONSUMER] Received Booking Confirmation #{} from Kafka!", currentCount);
            log.info("  -> Topic: {}, Partition: {}, Offset: {}", record.topic(), record.partition(), record.offset());
            log.info("  -> Booking ID : {}", event.getBookingId());
            log.info("  -> Event ID   : {}", event.getEventId());
            log.info("  -> User ID    : {}", event.getUserId());
            log.info("  -> Tickets    : {}", event.getTicketsCount());
            log.info("  -> Strategy   : {}", event.getConcurrencyStrategy());
            log.info("  -> Booked Via : {}", event.getOriginInstance());

            // Simulate simulated I/O (e.g., SMTP dispatch, SMS gateway)
            simulateDispatch(event);

            // Explicitly acknowledge to advance Kafka consumer group offset
            ack.acknowledge();
            log.info("[NOTIFICATION CONSUMER] Offset {} committed successfully for user={}", record.offset(), event.getUserId());
            log.info("==========================================================================");

        } catch (Exception e) {
            log.error("[NOTIFICATION CONSUMER] Error processing record at offset {}: {}", record.offset(), e.getMessage(), e);
            ack.acknowledge();
        }
    }

    private void simulateDispatch(BookingEvent event) {
        log.info("[DISPATCH] [OK] Simulated email confirmation sent to {}@campus.edu for {} ticket(s)",
                event.getUserId(), event.getTicketsCount());
    }

    public long getProcessedCount() {
        return processedCount.get();
    }
}
