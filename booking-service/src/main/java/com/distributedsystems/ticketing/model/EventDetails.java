package com.distributedsystems.ticketing.model;

public class EventDetails {
    private Long id;
    private String name;
    private int totalTickets;
    private int availableTickets;
    private long version;
    private int confirmedBookings;
    private int oversoldTickets;
    private String handledBy;

    public EventDetails() {}

    public EventDetails(Long id, String name, int totalTickets, int availableTickets,
                        long version, int confirmedBookings, int oversoldTickets, String handledBy) {
        this.id = id;
        this.name = name;
        this.totalTickets = totalTickets;
        this.availableTickets = availableTickets;
        this.version = version;
        this.confirmedBookings = confirmedBookings;
        this.oversoldTickets = oversoldTickets;
        this.handledBy = handledBy;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getTotalTickets() {
        return totalTickets;
    }

    public void setTotalTickets(int totalTickets) {
        this.totalTickets = totalTickets;
    }

    public int getAvailableTickets() {
        return availableTickets;
    }

    public void setAvailableTickets(int availableTickets) {
        this.availableTickets = availableTickets;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public int getConfirmedBookings() {
        return confirmedBookings;
    }

    public void setConfirmedBookings(int confirmedBookings) {
        this.confirmedBookings = confirmedBookings;
    }

    public int getOversoldTickets() {
        return oversoldTickets;
    }

    public void setOversoldTickets(int oversoldTickets) {
        this.oversoldTickets = oversoldTickets;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public void setHandledBy(String handledBy) {
        this.handledBy = handledBy;
    }
}
