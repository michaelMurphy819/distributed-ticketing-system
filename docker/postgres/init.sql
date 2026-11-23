-- Schema initialization for Distributed Ticketing System

DROP TABLE IF EXISTS bookings;
DROP TABLE IF EXISTS events;

CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    total_tickets INT NOT NULL,
    available_tickets INT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE bookings (
    id UUID PRIMARY KEY,
    event_id BIGINT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id VARCHAR(100) NOT NULL,
    tickets_count INT NOT NULL DEFAULT 1,
    status VARCHAR(50) NOT NULL DEFAULT 'CONFIRMED',
    concurrency_strategy VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(255) UNIQUE
);

CREATE INDEX idx_bookings_event_id ON bookings(event_id);
CREATE INDEX idx_bookings_created_at ON bookings(created_at);

-- Seed initial test event with 100 tickets
INSERT INTO events (id, name, total_tickets, available_tickets, version)
VALUES (1, 'Spring Campus Concert (CSC 2405)', 100, 100, 0);

-- Reset sequence for events table
SELECT setval('events_id_seq', (SELECT MAX(id) FROM events));
