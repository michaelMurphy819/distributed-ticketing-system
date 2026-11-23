package com.distributedsystems.ticketing.model;

import java.util.UUID;

public class BookingResponse {
    private boolean success;
    private UUID bookingId;
    private Long eventId;
    private String userId;
    private int ticketsBooked;
    private int remainingTickets;
    private String strategy;
    private String handledBy;
    private String message;

    public BookingResponse() {}

    public static BookingResponse successful(UUID bookingId, Long eventId, String userId,
                                            int ticketsBooked, int remainingTickets,
                                            String strategy, String handledBy, String message) {
        BookingResponse resp = new BookingResponse();
        resp.success = true;
        resp.bookingId = bookingId;
        resp.eventId = eventId;
        resp.userId = userId;
        resp.ticketsBooked = ticketsBooked;
        resp.remainingTickets = remainingTickets;
        resp.strategy = strategy;
        resp.handledBy = handledBy;
        resp.message = message;
        return resp;
    }

    public static BookingResponse failed(Long eventId, String userId, String strategy,
                                         String handledBy, String message, int remainingTickets) {
        BookingResponse resp = new BookingResponse();
        resp.success = false;
        resp.eventId = eventId;
        resp.userId = userId;
        resp.ticketsBooked = 0;
        resp.remainingTickets = remainingTickets;
        resp.strategy = strategy;
        resp.handledBy = handledBy;
        resp.message = message;
        return resp;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
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

    public int getTicketsBooked() {
        return ticketsBooked;
    }

    public void setTicketsBooked(int ticketsBooked) {
        this.ticketsBooked = ticketsBooked;
    }

    public int getRemainingTickets() {
        return remainingTickets;
    }

    public void setRemainingTickets(int remainingTickets) {
        this.remainingTickets = remainingTickets;
    }

    public String getStrategy() {
        return strategy;
    }

    public void setStrategy(String strategy) {
        this.strategy = strategy;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public void setHandledBy(String handledBy) {
        this.handledBy = handledBy;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
