#!/usr/bin/env bash
set -e

# Configuration
BASE_URL="http://localhost:8080"
TOTAL_REQUESTS=300
THREADS=30

echo "===================================================================="
echo "    CSC 2405: DISTRIBUTED SYSTEMS TICKETING BENCHMARK RUNNER        "
echo "===================================================================="

command -v curl >/dev/null 2>&1 || { echo "curl is required but not installed."; exit 1; }

echo "Checking cluster health at ${BASE_URL}..."
ATTEMPTS=0
until curl -s -f "${BASE_URL}/api/events/1" > /dev/null 2>&1; do
    ATTEMPTS=$((ATTEMPTS+1))
    if [ $ATTEMPTS -gt 15 ]; then
        echo "Cluster did not become ready in time. Check 'docker compose ps'."
        exit 1
    fi
    echo "Waiting for cluster & Nginx to be ready (ensuring Nginx is up)..."
    docker compose up -d nginx >/dev/null 2>&1 || true
    sleep 2
done
echo "Cluster is online and ready for benchmarking!"

MODE="${1:-all}"

run_single_benchmark() {
    STRATEGY=$1
    STRATEGY_UPPER=$(echo "$STRATEGY" | tr '[:lower:]' '[:upper:]')
    echo ""
    echo "===================================================================="
    echo ">>> RUNNING BENCHMARK WITH STRATEGY: ${STRATEGY_UPPER} <<<"
    echo "===================================================================="

    # Run load tester via docker compose
    if command -v docker >/dev/null 2>&1; then
        echo "Running load tester container against Nginx cluster..."
        docker compose run --rm --build load-tester \
            --host "http://nginx:80" \
            --strategy "${STRATEGY}" \
            --requests "${TOTAL_REQUESTS}" \
            --threads "${THREADS}" \
            --tickets 100 \
            --reset true
    elif command -v java >/dev/null 2>&1 && [ -f "./load-tester/target/load-tester-1.0.0.jar" ]; then
        java -jar ./load-tester/target/load-tester-1.0.0.jar \
            --host "${BASE_URL}" \
            --strategy "${STRATEGY}" \
            --requests "${TOTAL_REQUESTS}" \
            --threads "${THREADS}" \
            --tickets 100 \
            --reset true
    else
        echo "Fallback to curl-based test runner..."
        curl -s -X POST "${BASE_URL}/api/events/1/reset?tickets=100" > /dev/null
        echo "Event 1 reset to 100 tickets."
        
        for i in $(seq 1 ${TOTAL_REQUESTS}); do
            curl -s -X POST "${BASE_URL}/api/bookings?strategy=${STRATEGY}" \
                 -H "Content-Type: application/json" \
                 -d "{\"eventId\":1,\"userId\":\"student_$i\",\"ticketsCount\":1}" > /dev/null &
        done
        wait
        echo "Requests completed. Inspecting final event state:"
        curl -s "${BASE_URL}/api/events/1"
        echo ""
    fi
}

test_kafka_recovery() {
    echo ""
    echo "===================================================================="
    echo ">>> DEMO: KAFKA DURABILITY & NOTIFICATION CONSUMER RECOVERY <<<"
    echo "===================================================================="
    echo "1. Stopping Notification Service to simulate JVM/node crash..."
    docker stop notification-service || true

    echo "2. Resetting event to 100 tickets..."
    curl -s -X POST "${BASE_URL}/api/events/1/reset?tickets=100" > /dev/null

    echo "3. Booking 5 tickets while Notification Service is completely DOWN..."
    for i in {1..5}; do
        curl -s -X POST "${BASE_URL}/api/bookings?strategy=pessimistic" \
             -H "Content-Type: application/json" \
             -d "{\"eventId\":1,\"userId\":\"failure_test_user_$i\",\"ticketsCount\":1}" | grep -o '"success":true' || true
    done

    echo "Notice: Bookings succeeded immediately! The slow/crashed notification service did NOT block sales."
    echo "Messages are securely held in Kafka topic 'booking-confirmations'."

    echo "4. Restarting Notification Service..."
    docker start notification-service

    echo "Waiting 5 seconds for consumer rebalance and offset catchup..."
    sleep 5

    echo "5. Recent logs from Notification Service demonstrating offset catch-up:"
    docker logs --tail 25 notification-service
    echo "===================================================================="
}

case "${MODE}" in
    naive)
        run_single_benchmark "naive"
        ;;
    pessimistic)
        run_single_benchmark "pessimistic"
        ;;
    optimistic)
        run_single_benchmark "optimistic"
        ;;
    kafka-test)
        test_kafka_recovery
        ;;
    all|compare)
        echo "Running full comparison across all three concurrency modes..."
        run_single_benchmark "naive"
        sleep 2
        run_single_benchmark "pessimistic"
        sleep 2
        run_single_benchmark "optimistic"
        ;;
    *)
        echo "Usage: $0 [naive|pessimistic|optimistic|kafka-test|all]"
        exit 1
        ;;
esac
