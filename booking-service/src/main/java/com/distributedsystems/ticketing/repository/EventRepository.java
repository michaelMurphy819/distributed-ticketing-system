package com.distributedsystems.ticketing.repository;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class EventRepository {

    private final JdbcTemplate jdbcTemplate;

    public EventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record EventRecord(Long id, String name, int totalTickets, int availableTickets, long version) {}

    public Optional<EventRecord> findById(Long eventId) {
        String sql = "SELECT id, name, total_tickets, available_tickets, version FROM events WHERE id = ?";
        try {
            EventRecord record = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                new EventRecord(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getInt("total_tickets"),
                    rs.getInt("available_tickets"),
                    rs.getLong("version")
                ), eventId);
            return Optional.ofNullable(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Naive read: Reads current available_tickets with NO locks or version checks.
     */
    public int getAvailableTicketsNaive(Long eventId) {
        String sql = "SELECT available_tickets FROM events WHERE id = ?";
        Integer available = jdbcTemplate.queryForObject(sql, Integer.class, eventId);
        return available != null ? available : 0;
    }

    /**
     * Pessimistic read: Uses row-level lock SELECT ... FOR UPDATE in PostgreSQL.
     * Blocks other concurrent transactions trying to lock or update this row.
     */
    public int getAvailableTicketsForUpdate(Long eventId) {
        String sql = "SELECT available_tickets FROM events WHERE id = ? FOR UPDATE";
        Integer available = jdbcTemplate.queryForObject(sql, Integer.class, eventId);
        return available != null ? available : 0;
    }

    /**
     * Optimistic read: Reads both available_tickets and version for Compare-And-Swap check.
     */
    public record VersionedInventory(int availableTickets, long version) {}

    public Optional<VersionedInventory> getVersionedInventory(Long eventId) {
        String sql = "SELECT available_tickets, version FROM events WHERE id = ?";
        try {
            VersionedInventory vi = jdbcTemplate.queryForObject(sql, (rs, rowNum) ->
                new VersionedInventory(
                    rs.getInt("available_tickets"),
                    rs.getLong("version")
                ), eventId);
            return Optional.ofNullable(vi);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Naive decrement: Direct update without locks.
     */
    public void decrementTicketsNaive(Long eventId, int decrement) {
        String sql = "UPDATE events SET available_tickets = available_tickets - ? WHERE id = ?";
        jdbcTemplate.update(sql, decrement, eventId);
    }

    /**
     * Pessimistic decrement: Safe update within the transaction that holds FOR UPDATE lock.
     */
    public void decrementTicketsPessimistic(Long eventId, int decrement) {
        String sql = "UPDATE events SET available_tickets = available_tickets - ? WHERE id = ?";
        jdbcTemplate.update(sql, decrement, eventId);
    }

    /**
     * Optimistic decrement: Conditional update checking that version matches expectedVersion.
     * Returns 1 if successful, 0 if another concurrent transaction updated the record first.
     */
    public int decrementTicketsOptimistic(Long eventId, int decrement, long expectedVersion) {
        String sql = "UPDATE events " +
                     "SET available_tickets = available_tickets - ?, version = version + 1 " +
                     "WHERE id = ? AND version = ?";
        return jdbcTemplate.update(sql, decrement, eventId, expectedVersion);
    }

    public void resetEvent(Long eventId, int totalTickets) {
        String sql = "UPDATE events SET total_tickets = ?, available_tickets = ?, version = 0 WHERE id = ?";
        jdbcTemplate.update(sql, totalTickets, totalTickets, eventId);
    }
}
