package com.distributedsystems.ticketing.model;

import java.time.Instant;
import java.util.UUID;

public class BookingEvent {
    private UUID bookingId;
    private Long eventId;
    private String userId;
    private int ticketsCount;
    private String concurrencyStrategy;
    private Instant timestamp;
    private String originInstance;

    public BookingEvent() {}

    public BookingEvent(UUID bookingId, Long eventId, String userId, int ticketsCount,
                        String concurrencyStrategy, Instant timestamp, String originInstance) {
        this.bookingId = bookingId;
        this.eventId = eventId;
        this.userId = userId;
        this.ticketsCount = ticketsCount;
        this.concurrencyStrategy = concurrencyStrategy;
        this.timestamp = timestamp;
        this.originInstance = originInstance;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public void setBookingId(UUID bookingId) {
        this.bookingId = bookingId;
    }

    public Long getEventId() {
        return eventId;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getTicketsCount() {
        return ticketsCount;
    }

    public void setTicketsCount(int ticketsCount) {
        this.ticketsCount = ticketsCount;
    }

    public String getConcurrencyStrategy() {
        return concurrencyStrategy;
    }

    public void setConcurrencyStrategy(String concurrencyStrategy) {
        this.concurrencyStrategy = concurrencyStrategy;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getOriginInstance() {
        return originInstance;
    }

    public void setOriginInstance(String originInstance) {
        this.originInstance = originInstance;
    }
}
