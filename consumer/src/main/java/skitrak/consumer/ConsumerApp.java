package skitrak.consumer;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class ConsumerApp {

    private static final Logger logger = Logger.getLogger(ConsumerApp.class.getName());

    public static void main(String[] args) throws Exception {
        String host = env("RABBITMQ_HOST", "localhost");
        String user = env("RABBITMQ_USER", "skitrak");
        String pass = requiredEnv("RABBITMQ_PASS");
        int resortCount = Integer.parseInt(env("RESORT_COUNT", "10"));
        int threads = Integer.parseInt(env("CONSUMER_THREADS", String.valueOf(resortCount)));
        int prefetch = Integer.parseInt(env("PREFETCH", "100"));
        int topN = Integer.parseInt(env("TOP_N", "20"));
        int dedupeWindow = Integer.parseInt(env("DEDUPE_WINDOW", "50000"));   // per resort

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(5672);
        factory.setUsername(user);
        factory.setPassword(pass);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);

        // runs the handleDelivery calls. more threads than queues won't help
        ExecutorService workers = Executors.newFixedThreadPool(threads, named("consumer-worker-"));
        String name = "consumer-" + InetAddress.getLocalHost().getHostName().split("\\.")[0];
        Connection conn = factory.newConnection(workers, name);

        try (Channel setup = conn.createChannel()) {
            SwipeTopology.declare(setup, resortCount);
            SwipeTopology.declareDeadLetter(setup);
            SwipeTopology.declareLeaderboard(setup);
            for (int r = 1; r <= resortCount; r++) {
                AMQP.Queue.DeclareOk ok = setup.queueDeclarePassive(SwipeTopology.queueName(r));
                logger.info(String.format("%s: %d waiting", ok.getQueue(), ok.getMessageCount()));
            }
        }

        Stats stats = new Stats();
        List<ResortConsumer> consumers = new ArrayList<>();
        for (int r = 1; r <= resortCount; r++) {
            Channel ch = conn.createChannel();
            ch.basicQos(prefetch);
            Channel boardCh = conn.createChannel();   // publishing on its own channel
            ResortConsumer c = new ResortConsumer(ch, boardCh, r, topN, dedupeWindow, stats);
            consumers.add(c);
            ch.basicConsume(SwipeTopology.queueName(r), false,   // false = manual ack
                    name + "-resort-" + r, c);
        }
        logger.info(String.format("Consuming %d queues with %d threads, prefetch %d, top %d as %s",
                resortCount, threads, prefetch, topN, name));

        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(named("reporter-"));
        long[] last = {0};
        reporter.scheduleAtFixedRate(() -> {
            long total = stats.processed.sum();
            long delta = total - last[0];
            last[0] = total;
            if (delta > 0) {
                logger.info(String.format("processed total=%d  rate=%d msg/s  duplicates=%d  boardChanges=%d  broadcastFailures=%d",
                        total, delta / 5, stats.duplicates.sum(), stats.boardChanges.sum(),
                        stats.broadcastFailures.sum()));
                logTop3(consumers.get(0));
            }
        }, 5, 5, TimeUnit.SECONDS);

        // ctrl+c / systemd stop. unacked msgs go back to the queue
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down, processed " + stats.processed.sum());
            try {
                conn.close(5000);
            } catch (Exception ignored) {
            }
            reporter.shutdownNow();
            workers.shutdown();
            stopped.countDown();
        }));
        stopped.await();
    }

    // quick look at one resort's board while testing
    private static void logTop3(ResortConsumer c) {
        c.boards().forEach((day, board) -> {
            StringBuilder sb = new StringBuilder("resort " + c.resortID() + " day " + day + " top3:");
            List<TopN.Entry> top = board.snapshot();
            for (int i = 0; i < Math.min(3, top.size()); i++) {
                sb.append("  #").append(i + 1).append(' ').append(top.get(i).skierID)
                        .append('=').append(top.get(i).vertical);
            }
            logger.info(sb.toString());
        });
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is not set");
        }
        return v;
    }
}