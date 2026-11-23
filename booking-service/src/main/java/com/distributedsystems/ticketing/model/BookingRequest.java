package com.distributedsystems.ticketing.model;

public class BookingRequest {
    private Long eventId;
    private String userId;
    private Integer ticketsCount = 1;
    private String idempotencyKey;

    public BookingRequest() {
    }

    public BookingRequest(Long eventId, String userId, Integer ticketsCount, String idempotencyKey) {
        this.eventId = eventId;
        this.userId = userId;
        this.ticketsCount = (ticketsCount != null && ticketsCount > 0) ? ticketsCount : 1;
        this.idempotencyKey = idempotencyKey;
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

    public Integer getTicketsCount() {
        return ticketsCount != null && ticketsCount > 0 ? ticketsCount : 1;
    }

    public void setTicketsCount(Integer ticketsCount) {
        this.ticketsCount = ticketsCount;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }
}
