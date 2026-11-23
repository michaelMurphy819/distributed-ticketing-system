# Oversell-Proof Distributed Ticketing System
**CSC 2405: Distributed Systems — Final Project**  
*Presenter:* Michael Murphy  
*Topic:* Concurrency Control and Asynchronous Notifications Across Distributed Services  

---

## 1. System Architecture Overview

When high-demand tickets go on sale, thousands of concurrent buyers submit requests within milliseconds. In a modern cloud environment, client requests are balanced across multiple independent JVM service instances. Because each JVM has its own private heap memory, Java-level synchronization (`synchronized`, `ReentrantLock`) is ineffective across process boundaries. Coordination **must** happen through a shared persistent resource: **PostgreSQL**.

```
                           +----------------------------+
                           |  Multi-Threaded Load Test  |
                           |  Client (300 buyers)       |
                           +--------------+-------------+
                                          | HTTP (Round-Robin)
                                          v
                           +----------------------------+
                           |   Nginx Load Balancer      |
                           |   (Port 8080)              |
                           +----+-------------+----+----+
                                |             |    |
           +--------------------+             |    +--------------------+
           |                                  |                         |
           v                                  v                         v
+---------------------+            +---------------------+    +---------------------+
|  Booking Service 1  |            |  Booking Service 2  |    |  Booking Service 3  |
|      (JVM Node)     |            |      (JVM Node)     |    |      (JVM Node)     |
+----------+----------+            +----------+----------+    +----------+----------+
           |                                  |                          |
           |             SQL Transactions     |                          |
           +-------------------+--------------+--------------------------+
                               |
                               v
                     +-------------------+
                     |  PostgreSQL 16    |
                     |  - events         |
                     |  - bookings       |
                     |  (FOR UPDATE/CAS) |
                     +-------------------+
                               |
                               | (Asynchronous Publish upon Booking Confirmation)
                               v
                     +-------------------+
                     |   Apache Kafka    |
                     | (booking-confirm) |
                     +---------+---------+
                               |
                               | (Offset-Tracked Consumer Group)
                               v
                     +-----------------------+
                     |  Notification Service |
                     |  (Asynchronous Worker)|
                     +-----------------------+
```

---

## 2. The Three Concurrency Control Strategies

The core experimental goal is comparing three concurrency strategies under identical load:

| Strategy | DB Mechanism | Overselling Risk | Latency / Behavior under Contention |
| :--- | :--- | :--- | :--- |
| **1. Naive** | Unsynchronized `SELECT` followed by `UPDATE` | **HIGH** (Severe overselling due to lost updates and check-then-act race conditions) | Very low individual latency, but completely corrupts inventory state. |
| **2. Pessimistic** | Row-level locking via `SELECT ... FOR UPDATE` | **ZERO** (Guaranteed consistent) | Slightly higher queueing latency; transactions wait sequentially at PostgreSQL row level. |
| **3. Optimistic** | Atomic Compare-And-Swap: `UPDATE ... WHERE version = expectedVersion` | **ZERO** (Guaranteed consistent) | Zero database lock contention; conflicting updates are cleanly detected and rejected (`409 Conflict`). |

### 1. Naive Strategy
```sql
-- Step 1: Read available tickets (No lock held)
SELECT available_tickets FROM events WHERE id = 1;

-- (Race window: other concurrent JVMs read the exact same count here!)

-- Step 2: Unsafe update
UPDATE events SET available_tickets = available_tickets - 1 WHERE id = 1;
INSERT INTO bookings (id, event_id, user_id, ...) VALUES (...);
```

### 2. Pessimistic Locking Strategy
```sql
BEGIN TRANSACTION;
-- Places exclusive row-level lock on this event row:
SELECT available_tickets FROM events WHERE id = 1 FOR UPDATE;

-- Concurrent transactions attempting FOR UPDATE or UPDATE on this row block and wait.
UPDATE events SET available_tickets = available_tickets - 1 WHERE id = 1;
INSERT INTO bookings (id, event_id, user_id, ...) VALUES (...);
COMMIT;
```

### 3. Optimistic Concurrency Strategy
```sql
-- Step 1: Read without locking
SELECT available_tickets, version FROM events WHERE id = 1;

-- Step 2: Conditional Compare-And-Swap (CAS)
UPDATE events 
SET available_tickets = available_tickets - 1, version = version + 1 
WHERE id = 1 AND version = :expectedVersion;

-- If rows updated == 0, another concurrent transaction committed first! Reject transaction.
```

---

## 3. Asynchronous Messaging & Failure Handling (Kafka)

1. **Fast-path booking response:** The user gets an immediate HTTP response once PostgreSQL commits. Email and SMS notification dispatch is offloaded to Apache Kafka.
2. **Offset Tracking:** The `Notification Service` operates with `enable.auto.commit=false` and manual acknowledgment.
3. **Failure Resilience Demo:** If the `Notification Service` crashes or is stopped (`docker stop notification-service`), the booking services continue operating without degradation. Kafka safely buffers all confirmation events. When the Notification Service restarts, it queries Kafka for its last committed offset and seamlessly processes the backlog.

---

## 4. Quick Start & Execution Guide

### Prerequisites
- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (macOS, Linux, or Windows)

### Step 1: Launch Infrastructure & Services
From the root directory, run:
```bash
docker compose up --build
```
This builds and launches:
- `postgres` (Port 5432 with schema auto-initialized)
- `kafka` (Port 9092 in KRaft mode)
- `booking-service-1` (JVM 1)
- `booking-service-2` (JVM 2)
- `booking-service-3` (JVM 3)
- `notification-service` (Kafka Consumer)
- `nginx` (Port 8080 Load Balancer)

Verify the load balancer is running:
```bash
curl http://localhost:8080/api/events/1
```

---

## 5. Running the Concurrency Benchmarks

We provide an automated benchmark runner script:

```bash
# Run all three benchmarks side-by-side with full summary reports
./run-benchmark.sh all

# Or test individual strategies:
./run-benchmark.sh naive
./run-benchmark.sh pessimistic
./run-benchmark.sh optimistic
```

### Expected Results

#### 1. Naive Mode
- **Initial Inventory:** 100 tickets
- **Concurrent Requests:** 300 requests across 30 threads
- **Result:** ~180-240 bookings confirmed!
- **Available Tickets in DB:** Drops below 0 or finishes with a massive oversell count.
- **Diagnosis:** Classic lost update problem across independent JVMs.

#### 2. Pessimistic Mode
- **Initial Inventory:** 100 tickets
- **Concurrent Requests:** 300 requests across 30 threads
- **Result:** Exactly 100 bookings confirmed, 200 requests rejected with HTTP 409 (Sold Out).
- **Available Tickets in DB:** Exactly 0.
- **Oversold Count:** 0.
- **Diagnosis:** Row-level locks in PostgreSQL completely prevent race conditions.

#### 3. Optimistic Mode
- **Initial Inventory:** 100 tickets
- **Concurrent Requests:** 300 requests across 30 threads
- **Result:** 0 overselling. Conflicting simultaneous requests receive HTTP 409 Version Conflict.
- **Diagnosis:** CAS operation preserves absolute integrity without blocking locks.

---

## 6. Testing Kafka Consumer Failure & Recovery

Run the automated Kafka failure scenario:
```bash
./run-benchmark.sh kafka-test
```
What this demonstrates:
1. Stops the `notification-service` container.
2. Fires booking requests to Nginx. Bookings succeed instantly with HTTP 201.
3. Shows that the user experience is unaffected because notifications are decoupled.
4. Restarts `notification-service`.
5. Prints the consumer logs showing offset catch-up for all pending messages.

---

## 7. Useful HTTP Endpoints

- **Book a ticket:**
  `POST http://localhost:8080/api/bookings?strategy=pessimistic`
  ```json
  {
    "eventId": 1,
    "userId": "student_007",
    "ticketsCount": 1,
    "idempotencyKey": "req-xyz-123"
  }
  ```

- **Inspect event status & oversell metrics:**
  `GET http://localhost:8080/api/events/1`

- **Reset test event to 100 tickets:**
  `POST http://localhost:8080/api/events/1/reset?tickets=100`

---

## 8. Distributed Systems Concepts Demonstrated

1. **JVM Isolation & Shared State Coordination:** Proving that memory-space locks cannot synchronize across container or cluster instances.
2. **Explicit Database Locking:** PostgreSQL row locks (`SELECT ... FOR UPDATE`) vs MVCC / Optimistic Version Checking.
3. **Load Balancing & Reverse Proxying:** Nginx round-robin distribution across multiple upstream nodes.
4. **Asynchronous Messaging:** Decoupling critical path transactions from slow side effects via Apache Kafka.
5. **Offset Tracking & At-Least-Once Delivery:** Consumer group checkpointing ensuring zero lost notifications during node failure.
6. **Idempotency:** Preventing duplicate bookings on network retries.
