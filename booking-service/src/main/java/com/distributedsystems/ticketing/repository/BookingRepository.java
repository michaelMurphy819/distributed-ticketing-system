package com.distributedsystems.ticketing.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class BookingRepository {

    private final JdbcTemplate jdbcTemplate;

    public BookingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ExistingBooking(UUID id, Long eventId, String userId, int ticketsCount, String status, String strategy) {}

    public Optional<ExistingBooking> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT id, event_id, user_id, tickets_count, status, concurrency_strategy " +
                     "FROM bookings WHERE idempotency_key = ?";
        try {
            ExistingBooking booking = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                new ExistingBooking(
                    (UUID) rs.getObject("id"),
                    rs.getLong("event_id"),
                    rs.getString("user_id"),
                    rs.getInt("tickets_count"),
                    rs.getString("status"),
                    rs.getString("concurrency_strategy")
                ), idempotencyKey);
            return Optional.ofNullable(booking);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public void insertBooking(UUID bookingId, Long eventId, String userId, int ticketsCount,
                              String strategy, String idempotencyKey) {
        String sql = "INSERT INTO bookings (id, event_id, user_id, tickets_count, status, concurrency_strategy, idempotency_key, created_at) " +
                     "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?)";
        jdbcTemplate.update(sql,
                bookingId,
                eventId,
                userId,
                ticketsCount,
                strategy,
                idempotencyKey,
                Timestamp.from(Instant.now()));
    }

    public int countConfirmedBookings(Long eventId) {
        String sql = "SELECT COALESCE(SUM(tickets_count), 0) FROM bookings WHERE event_id = ? AND status = 'CONFIRMED'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, eventId);
        return count != null ? count : 0;
    }

    public void deleteBookingsByEventId(Long eventId) {
        String sql = "DELETE FROM bookings WHERE event_id = ?";
        jdbcTemplate.update(sql, eventId);
    }
}
