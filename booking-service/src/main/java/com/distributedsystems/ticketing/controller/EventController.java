package com.distributedsystems.ticketing.controller;

import com.distributedsystems.ticketing.model.EventDetails;
import com.distributedsystems.ticketing.service.BookingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventController {

    private final BookingService bookingService;

    public EventController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<EventDetails> getEvent(@PathVariable Long id) {
        try {
            EventDetails details = bookingService.getEventDetails(id);
            return ResponseEntity.ok(details);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/reset")
    public ResponseEntity<Map<String, Object>> resetEvent(
            @PathVariable Long id,
            @RequestParam(defaultValue = "100") int tickets) {
        bookingService.resetEventInventory(id, tickets);
        return ResponseEntity.ok(Map.of(
                "message", "Event reset successfully",
                "eventId", id,
                "totalTickets", tickets,
                "availableTickets", tickets,
                "version", 0,
                "handledBy", bookingService.getInstanceId()
        ));
    }
}
