package com.distributedsystems.loadtester;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoadTestClient {

    private static final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record Result(int statusCode, long latencyMs, String backendInstance, boolean success) {}

    public static void main(String[] args) throws Exception {
        Map<String, String> params = parseArgs(args);

        String host = params.getOrDefault("host", "http://localhost:8080");
        String strategy = params.getOrDefault("strategy", "pessimistic").toLowerCase();
        int totalRequests = Integer.parseInt(params.getOrDefault("requests", "300"));
        int concurrency = Integer.parseInt(params.getOrDefault("threads", "30"));
        long eventId = Long.parseLong(params.getOrDefault("eventId", "1"));
        boolean reset = Boolean.parseBoolean(params.getOrDefault("reset", "true"));
        int initialTickets = Integer.parseInt(params.getOrDefault("tickets", "100"));

        System.out.println("================================================================================");
        System.out.println("   CSC 2405: DISTRIBUTED SYSTEMS CONCURRENCY & OVERSELLING BENCHMARK");
        System.out.println("================================================================================");
        System.out.printf(" Target URL       : %s/api/bookings?strategy=%s%n", host, strategy);
        System.out.printf(" Concurrency Mode : %s%n", strategy.toUpperCase());
        System.out.printf(" Total Requests   : %d concurrent ticket buyers%n", totalRequests);
        System.out.printf(" Worker Threads   : %d simultaneous threads%n", concurrency);
        System.out.printf(" Initial Tickets  : %d%n", initialTickets);
        System.out.println("================================================================================");

        if (reset) {
            System.out.printf("[SETUP] Resetting event %d to %d tickets and clearing bookings...%n", eventId, initialTickets);
            resetEvent(host, eventId, initialTickets);
            System.out.println("[SETUP] Reset completed successfully.\n");
        }

        System.out.println("[BENCHMARK] Firing simultaneous requests across Nginx load balancer...");
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(totalRequests);

        List<Future<Result>> futures = new ArrayList<>(totalRequests);
        AtomicInteger counter = new AtomicInteger(0);

        for (int i = 1; i <= totalRequests; i++) {
            final String userId = "student_" + i;
            futures.add(executor.submit(() -> {
                try {
                    // Block until all threads are created and ready
                    startGate.await();

                    String jsonBody = String.format(
                            "{\"eventId\": %d, \"userId\": \"%s\", \"ticketsCount\": 1}",
                            eventId, userId
                    );

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(host + "/api/bookings?strategy=" + strategy))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(30))
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                            .build();

                    long startNanos = System.nanoTime();
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;

                    String backend = extractHandledBy(response.body());
                    if (backend == null || backend.isBlank()) {
                        backend = response.headers().firstValue("X-Upstream-Server").orElse("unknown");
                    }

                    boolean isSuccess = (response.statusCode() == 201 || response.statusCode() == 200);
                    return new Result(response.statusCode(), latencyMs, backend, isSuccess);
                } catch (Exception e) {
                    return new Result(500, 0, "error", false);
                } finally {
                    doneGate.countDown();
                }
            }));
        }

        long overallStart = System.currentTimeMillis();
        // Release the gate: all threads fire at once!
        startGate.countDown();

        doneGate.await();
        long totalElapsedMs = System.currentTimeMillis() - overallStart;
        executor.shutdown();

        List<Result> results = new ArrayList<>();
        for (Future<Result> f : futures) {
            results.add(f.get());
        }

        printReport(host, eventId, strategy, initialTickets, totalRequests, concurrency, totalElapsedMs, results);
    }

    private static void resetEvent(String host, long eventId, int tickets) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/api/events/%d/reset?tickets=%d", host, eventId, tickets)))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Failed to reset event: HTTP " + resp.statusCode() + " -> " + resp.body());
        }
    }

    private static void printReport(String host, long eventId, String strategy,
                                    int initialTickets, int totalRequests, int concurrency,
                                    long totalElapsedMs, List<Result> results) throws Exception {

        int successCount = 0;
        int conflictCount = 0;
        int errorCount = 0;
        Map<String, Integer> instanceCounts = new HashMap<>();
        List<Long> latencies = new ArrayList<>();

        for (Result r : results) {
            latencies.add(r.latencyMs());
            instanceCounts.put(r.backendInstance(), instanceCounts.getOrDefault(r.backendInstance(), 0) + 1);

            if (r.success()) {
                successCount++;
            } else if (r.statusCode() == 409 || r.statusCode() == 400) {
                conflictCount++;
            } else {
                errorCount++;
            }
        }

        Collections.sort(latencies);
        long minLatency = latencies.isEmpty() ? 0 : latencies.get(0);
        long maxLatency = latencies.isEmpty() ? 0 : latencies.get(latencies.size() - 1);
        double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        long p50 = getPercentile(latencies, 50);
        long p90 = getPercentile(latencies, 90);
        long p95 = getPercentile(latencies, 95);
        long p99 = getPercentile(latencies, 99);

        // Fetch final database state
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(String.format("%s/api/events/%d", host, eventId)))
                .GET()
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        String body = resp.body();

        int dbAvailableTickets = extractInt(body, "availableTickets");
        int dbConfirmedBookings = extractInt(body, "confirmedBookings");
        int oversoldTickets = Math.max(0, dbConfirmedBookings - initialTickets);

        System.out.println("\n================================================================================");
        System.out.println("                         BENCHMARK RESULTS SUMMARY                              ");
        System.out.println("================================================================================");
        System.out.printf(" Concurrency Strategy      : %s%n", strategy.toUpperCase());
        System.out.printf(" Total Test Duration       : %d ms (%.2f requests/sec)%n",
                totalElapsedMs, (totalRequests * 1000.0) / Math.max(1, totalElapsedMs));
        System.out.printf(" Successful Bookings (201) : %d%n", successCount);
        System.out.printf(" Rejected/Sold Out   (409) : %d%n", conflictCount);
        System.out.printf(" System Errors       (500) : %d%n", errorCount);

        System.out.println("\n--- Latency Breakdown (Client-Side) ---");
        System.out.printf(" Min Latency  : %d ms%n", minLatency);
        System.out.printf(" Avg Latency  : %.1f ms%n", avgLatency);
        System.out.printf(" Median (p50) : %d ms%n", p50);
        System.out.printf(" 90th %% (p90) : %d ms%n", p90);
        System.out.printf(" 95th %% (p95) : %d ms%n", p95);
        System.out.printf(" 99th %% (p99) : %d ms%n", p99);
        System.out.printf(" Max Latency  : %d ms%n", maxLatency);

        System.out.println("\n--- Load Balancer Distribution (Nginx Round-Robin) ---");
        for (Map.Entry<String, Integer> e : instanceCounts.entrySet()) {
            System.out.printf(" Instance [%s] : %d requests (%.1f%%)%n",
                    e.getKey(), e.getValue(), (e.getValue() * 100.0) / totalRequests);
        }

        System.out.println("\n--- PostgreSQL Shared State Verification ---");
        System.out.printf(" Initial Tickets Available : %d%n", initialTickets);
        System.out.printf(" Final Tickets in DB       : %d%n", dbAvailableTickets);
        System.out.printf(" Total Confirmed in DB     : %d%n", dbConfirmedBookings);
        System.out.printf(" Oversold Tickets Count    : %d%n", oversoldTickets);

        System.out.println("--------------------------------------------------------------------------------");
        if (oversoldTickets > 0) {
            System.out.printf(" [INTEGRITY VIOLATION DETECTED]%n");
            System.out.printf(" The system OVER-SOLD %d tickets beyond the available %d!%n", oversoldTickets, initialTickets);
            System.out.println(" Explanation: In naive mode, concurrent transactions interleaved their reads");
            System.out.println(" before committing writes (lost updates / check-then-act race condition).");
        } else if (dbConfirmedBookings == initialTickets && dbAvailableTickets == 0) {
            System.out.println(" [OVERSELL-PROOF GUARANTEE VERIFIED]");
            System.out.printf(" Exactly %d tickets were sold. Zero overselling occurred.%n", initialTickets);
            System.out.printf(" Consistency preserved across 3 JVM instances sharing one database!%n");
        } else if (dbConfirmedBookings < initialTickets) {
            System.out.printf(" [NOTE] Confirmed %d tickets (under capacity).%n", dbConfirmedBookings);
            if ("optimistic".equalsIgnoreCase(strategy)) {
                System.out.println(" Explanation: Optimistic concurrency rejected concurrent version conflicts.");
                System.out.println(" In a real app, clients would retry rejected requests with exponential backoff.");
            }
        }
        System.out.println("================================================================================\n");
    }

    private static long getPercentile(List<Long> sorted, double percentile) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String extractHandledBy(String body) {
        if (body == null) return null;
        Matcher m = Pattern.compile("\"handledBy\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static int extractInt(String body, String key) {
        if (body == null) return 0;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(body);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    map.put(key, args[i + 1]);
                    i++;
                } else {
                    map.put(key, "true");
                }
            }
        }
        return map;
    }
}
