package skitrak.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Consumer: takes a fixed number of swipes off the queue, POSTs each one and
 * records its latency. Retries network errors and 5xx responses with exponential backoff.
 */
public class SwipeSender implements Runnable {

    private static final int MAX_RETRIES = 5;
    private static final long BASE_BACKOFF_MS = 100;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final BlockingQueue<SwipeEvent> queue;
    private final HttpClient client;   // shared by every sender so connections get reused
    private final String baseUrl;
    private final int quota;
    private final Stats stats;

    // only this sender's thread writes to it, so no locking needed
    private final List<RequestRecord> records;

    public SwipeSender(BlockingQueue<SwipeEvent> queue, HttpClient client,
                       String baseUrl, int quota, Stats stats) {
        this.queue = queue;
        this.client = client;
        this.baseUrl = baseUrl;
        this.quota = quota;
        this.stats = stats;
        this.records = new ArrayList<>(quota);
    }

    /** Safe to read once the sender has finished. */
    List<RequestRecord> records() {
        return records;
    }

    @Override
    public void run() {
        try {
            for (int i = 0; i < quota; i++) {
                SwipeEvent swipe = queue.take();
                long sentAtMs = System.currentTimeMillis();
                long start = System.nanoTime();
                int status = send(swipe);
                long latencyMicros = (System.nanoTime() - start) / 1_000;

                records.add(new RequestRecord(sentAtMs, latencyMicros, status, swipe.resortID));
                if (status == 201) {
                    stats.successful.increment();
                } else {
                    stats.failed.increment();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Returns the final status code, or -1 if no attempt got a response. */
    private int send(SwipeEvent swipe) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + swipe.path))
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(swipe.body))
                .build();

        int lastStatus = -1;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                stats.retries.increment();
                backoff(attempt);
            }
            try {
                int status = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status < 500) {
                    return status;   // 201 = done; 4xx won't get better by retrying
                }
                lastStatus = status; // 5xx: server trouble, worth another try
            } catch (IOException e) {
                lastStatus = -1;     // connection refused, reset, timed out... retry
            }
        }
        return lastStatus;
    }

    // 100, 200, 400, 800, 1600 ms plus a little jitter so threads don't retry in lockstep
    private static void backoff(int attempt) throws InterruptedException {
        long delay = BASE_BACKOFF_MS * (1L << (attempt - 1));
        long jitter = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MS);
        Thread.sleep(delay + jitter);
    }
}