package skitrak.client;

import com.google.gson.JsonParser;

import javax.websocket.ClientEndpointConfig;
import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Watches one resort's leaderboard for the whole run. If the connection drops,
 * it reconnects (with backoff) until stop() is called.
 */
public class LeaderboardSubscriber implements Runnable {

    private static final long BASE_BACKOFF_MS = 100;
    private static final long MAX_BACKOFF_MS = 5_000;

    private final WebSocketContainer container;
    private final URI uri;
    private final String resortID;

    private volatile boolean running = true;
    private volatile Session session;

    private final CountDownLatch firstConnection = new CountDownLatch(1);
    private final AtomicInteger updates = new AtomicInteger();
    private final AtomicInteger connections = new AtomicInteger();
    private final List<Long> connectTimesMs = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> pushLatenciesMs = Collections.synchronizedList(new ArrayList<>());

    public LeaderboardSubscriber(WebSocketContainer container, URI uri, String resortID) {
        this.container = container;
        this.uri = uri;
        this.resortID = resortID;
    }

    @Override
    public void run() {
        int failedAttempts = 0;
        while (running) {
            CountDownLatch closed = new CountDownLatch(1);
            try {
                long start = System.nanoTime();
                session = container.connectToServer(
                        new Listener(closed), ClientEndpointConfig.Builder.create().build(), uri);
                connectTimesMs.add((System.nanoTime() - start) / 1_000_000);
                connections.incrementAndGet();
                firstConnection.countDown();
                failedAttempts = 0;
                closed.await();   // sit here until the connection closes (dropped, or we closed it)
            } catch (DeploymentException | IOException e) {
                failedAttempts++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (running) {
                backoff(failedAttempts);
            }
        }
    }

    /** Waits until the first connection is up. Returns false if it didn't happen in time. */
    public boolean awaitConnected(long timeoutMs) throws InterruptedException {
        return firstConnection.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Ends the subscription for good. */
    public void stop() {
        running = false;
        Session s = session;
        if (s != null && s.isOpen()) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
    }

    // 100 ms after a normal drop, then doubling per failed attempt, capped at 5 s
    private void backoff(int failedAttempts) {
        long delay = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS << Math.min(failedAttempts, 6));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    public String resortID() {
        return resortID;
    }

    public int updates() {
        return updates.get();
    }

    public int connections() {
        return connections.get();
    }

    /** Every successful connect after the first one is a reconnect. */
    public int reconnections() {
        return Math.max(0, connections.get() - 1);
    }

    public List<Long> connectTimesMs() {
        synchronized (connectTimesMs) {
            return new ArrayList<>(connectTimesMs);
        }
    }

    public List<Long> pushLatenciesMs() {
        synchronized (pushLatenciesMs) {
            return new ArrayList<>(pushLatenciesMs);
        }
    }

    private class Listener extends Endpoint {

        private final CountDownLatch closed;

        Listener(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public void onOpen(Session s, EndpointConfig config) {
            s.addMessageHandler(String.class, this::onMessage);
        }

        private void onMessage(String json) {
            long receivedAt = System.currentTimeMillis();   // wall clock, to compare with the server's
            updates.incrementAndGet();
            try {
                String ts = JsonParser.parseString(json).getAsJsonObject()
                        .get("serverTimestamp").getAsString();
                pushLatenciesMs.add(receivedAt - Instant.parse(ts).toEpochMilli());
            } catch (RuntimeException e) {
                // bad message - still counted as an update, just no latency for it
            }
        }

        @Override
        public void onClose(Session s, CloseReason reason) {
            closed.countDown();
        }

        @Override
        public void onError(Session s, Throwable t) {
            closed.countDown();   // don't leave run() waiting if close never arrives
        }
    }
}