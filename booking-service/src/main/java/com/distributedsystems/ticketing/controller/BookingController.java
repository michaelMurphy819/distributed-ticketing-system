package com.distributedsystems.ticketing.controller;

import com.distributedsystems.ticketing.model.BookingRequest;
import com.distributedsystems.ticketing.model.BookingResponse;
import com.distributedsystems.ticketing.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Book ticket(s) using specified concurrency control strategy:
     * - naive: no locks, vulnerable to overselling
     * - pessimistic: row-level locking with SELECT ... FOR UPDATE
     * - optimistic: version column compare-and-swap
     */
    @PostMapping
    public ResponseEntity<BookingResponse> bookTicket(
            @RequestParam(defaultValue = "pessimistic") String strategy,
            @RequestBody BookingRequest request) {

        try {
            BookingResponse response = bookingService.processBooking(request, strategy);
            if (response.isSuccess()) {
                return ResponseEntity.status(HttpStatus.CREATED).body(response);
            } else {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    BookingResponse.failed(request.getEventId(), request.getUserId(), strategy,
                            bookingService.getInstanceId(), e.getMessage(), 0)
            );
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    BookingResponse.failed(request.getEventId(), request.getUserId(), strategy,
                            bookingService.getInstanceId(), "Internal error: " + e.getMessage(), 0)
            );
        }
    }
}
