package skitrak.client;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Load client with full performance analysis.
 *
 * Usage: LoadClient [baseUrl] [mainThreads] [totalSwipes] [outputDir]
 *   defaults: http://localhost:8080/ski-servlet 64 500000 ../results
 */
public class LoadClient {

    private static final int WARMUP_THREADS = 32;
    private static final int WARMUP_PER_THREAD = 1_000;
    private static final int RESORTS = 10;
    private static final int SEASON_ID = 2026;
    private static final int DAY_ID = 1;
    private static final int QUEUE_CAPACITY = 10_000;

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080/ski-servlet";
        int mainThreads = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int total = args.length > 2 ? Integer.parseInt(args[2]) : 500_000;
        Path outDir = Path.of(args.length > 3 ? args[3] : "../results");

        int warmupTotal = WARMUP_THREADS * WARMUP_PER_THREAD;
        if (total < warmupTotal) {
            throw new IllegalArgumentException("total must be at least " + warmupTotal);
        }

        // One client for the whole run. HTTP/1.1 because Tomcat isn't set up for HTTP/2.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        System.out.println("Server: " + baseUrl);
        System.out.printf("Swipes: %,d   main-phase threads: %d%n%n", total, mainThreads);

        // Subscribe path: one leaderboard watcher per resort, connected before the ingest starts
        long subStart = System.nanoTime();
        List<LeaderboardSubscriber> subscribers = startSubscribers(baseUrl);
        int connected = 0;
        for (LeaderboardSubscriber sub : subscribers) {
            if (sub.awaitConnected(15_000)) {
                connected++;
            }
        }
        System.out.printf("Subscribers: %d/%d connected in %.2f s%n%n",
                connected, subscribers.size(), secondsSince(subStart));

        BlockingQueue<SwipeEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        Thread generator = new Thread(
                new SwipeGenerator(queue, total, RESORTS, SEASON_ID, DAY_ID), "swipe-generator");
        Stats stats = new Stats();
        List<SwipeSender> senders = new ArrayList<>();

        long runStartMs = System.currentTimeMillis();   // wall clock, to line up with record timestamps
        long runStart = System.nanoTime();
        generator.start();

        // Warmup: 32 threads x 1,000 each, timed on its own
        long warmupStart = System.nanoTime();
        senders.addAll(runPhase(WARMUP_THREADS, warmupTotal, queue, client, baseUrl, stats));
        double warmupSecs = secondsSince(warmupStart);
        System.out.printf("Warmup: %d threads, %,d swipes in %.2f s (%,.0f swipes/s)%n",
                WARMUP_THREADS, warmupTotal, warmupSecs, warmupTotal / warmupSecs);

        // Main phase: everything that's left
        int mainTotal = total - warmupTotal;
        long mainStart = System.nanoTime();
        senders.addAll(runPhase(mainThreads, mainTotal, queue, client, baseUrl, stats));
        double mainSecs = secondsSince(mainStart);
        System.out.printf("Main:   %d threads, %,d swipes in %.2f s (%,.0f swipes/s)%n",
                mainThreads, mainTotal, mainSecs, mainTotal / mainSecs);

        double totalSecs = secondsSince(runStart);
        generator.join();
        stopSubscribers(subscribers);

        System.out.println();
        System.out.printf("Successful swipes:  %,d%n", stats.successful.sum());
        System.out.printf("Failed swipes:      %,d%n", stats.failed.sum());
        System.out.printf("Retries:            %,d%n", stats.retries.sum());
        System.out.printf("Total wall time:    %.2f s%n", totalSecs);
        System.out.printf("Overall throughput: %,.0f swipes/s%n", stats.successful.sum() / totalSecs);
        System.out.printf("HTTP connections:   shared keep-alive pool, up to %d concurrent "
                + "(one per sender thread)%n", Math.max(WARMUP_THREADS, mainThreads));

        // Analysis happens after the run, so it doesn't slow the senders down
        List<RequestRecord> records = new ArrayList<>(total);
        for (SwipeSender s : senders) {
            records.addAll(s.records());
        }
        Report.print(records, runStartMs, totalSecs, RESORTS, outDir);

        printSubscriberStats(subscribers);

        System.exit(stats.failed.sum() == 0 ? 0 : 1);
    }

    /** Splits `swipes` across `threads` senders and waits until they've all finished. */
    private static List<SwipeSender> runPhase(int threads, int swipes, BlockingQueue<SwipeEvent> queue,
                                              HttpClient client, String baseUrl, Stats stats)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<SwipeSender> senders = new ArrayList<>(threads);
        int base = swipes / threads;
        int extra = swipes % threads;   // first `extra` threads take one more so nothing is left over
        for (int t = 0; t < threads; t++) {
            int quota = base + (t < extra ? 1 : 0);
            SwipeSender sender = new SwipeSender(queue, client, baseUrl, quota, stats);
            senders.add(sender);
            pool.submit(sender);
        }
        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);   // after this, every sender's records are safe to read
        return senders;
    }

    private static List<LeaderboardSubscriber> startSubscribers(String baseUrl) {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();   // shared, like the HttpClient
        String wsBase = baseUrl.replaceFirst("^http", "ws");
        List<LeaderboardSubscriber> subscribers = new ArrayList<>();
        for (int r = 1; r <= RESORTS; r++) {
            LeaderboardSubscriber sub = new LeaderboardSubscriber(
                    container, URI.create(wsBase + "/leaderboard/" + r), String.valueOf(r));
            subscribers.add(sub);
            new Thread(sub, "subscriber-resort-" + r).start();
        }
        return subscribers;
    }

    private static void stopSubscribers(List<LeaderboardSubscriber> subscribers) {
        for (LeaderboardSubscriber sub : subscribers) {
            sub.stop();
        }
    }

    private static void printSubscriberStats(List<LeaderboardSubscriber> subscribers) {
        System.out.println();
        System.out.println("Leaderboard subscribers (WebSocket):");

        long totalUpdates = 0;
        int connections = 0;
        int reconnections = 0;
        List<Long> connectTimes = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();

        for (LeaderboardSubscriber sub : subscribers) {
            System.out.printf("  resort %2s: %,d updates%n", sub.resortID(), sub.updates());
            totalUpdates += sub.updates();
            connections += sub.connections();
            reconnections += sub.reconnections();
            connectTimes.addAll(sub.connectTimesMs());
            latencies.addAll(sub.pushLatenciesMs());
        }
        System.out.printf("Total updates delivered: %,d%n", totalUpdates);
        System.out.printf("WS connections:     %d (%d reconnections)%n", connections, reconnections);

        Collections.sort(connectTimes);
        System.out.printf("WS connect time:    mean %.1f ms, max %d ms%n",
                Percentiles.mean(connectTimes),
                connectTimes.isEmpty() ? 0 : connectTimes.get(connectTimes.size() - 1));

        if (latencies.isEmpty()) {
            System.out.println("Push latency:       no updates received");
            return;
        }
        Collections.sort(latencies);
        System.out.printf("Push latency (ms):  mean %.1f, median %d, p95 %d, p99 %d%n",
                Percentiles.mean(latencies),
                Percentiles.of(latencies, 50),
                Percentiles.of(latencies, 95),
                Percentiles.of(latencies, 99));
        System.out.println("  (client receive time minus serverTimestamp - includes clock skew "
                + "between this machine and the server)");
    }

    private static double secondsSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }
}