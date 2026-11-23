package messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * One RabbitMQ connection + a fixed set of channels shared by the request threads.
 * One thread uses a channel at a time: borrowChannel() -> publish -> returnChannel().
 */
public class ChannelPool {

    private static final Logger logger = Logger.getLogger(ChannelPool.class.getName());

    private final ConnectionFactory factory;
    private final int poolSize;
    private final long borrowTimeoutMs;
    private Connection connection;
    private BlockingQueue<Channel> pool;

    public ChannelPool(String host, String user, String pass, int poolSize, long borrowTimeoutMs) {
        this.factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setPort(5672);
        factory.setUsername(user);
        factory.setPassword(pass);
        factory.setAutomaticRecoveryEnabled(true);   // reconnect after a dropped connection
        factory.setNetworkRecoveryInterval(5000);    // retry every 5 s
        factory.setTopologyRecoveryEnabled(true);    // re-declare exchange/queues after recovery
        this.poolSize = poolSize;
        this.borrowTimeoutMs = borrowTimeoutMs;
    }

    public void init(String connectionName) throws IOException, TimeoutException {
        connection = factory.newConnection(connectionName);   // name shows in the management UI

        // Make drops and recoveries visible in catalina.out
        connection.addShutdownListener(cause -> {
            if (!cause.isInitiatedByApplication()) {
                logger.warning("RabbitMQ connection lost: " + cause.getMessage());
            }
        });
        if (connection instanceof Recoverable) {
            ((Recoverable) connection).addRecoveryListener(new RecoveryListener() {
                @Override public void handleRecoveryStarted(Recoverable r) {
                    logger.info("RabbitMQ recovery started");
                }
                @Override public void handleRecovery(Recoverable r) {
                    logger.info("RabbitMQ connection recovered");
                }
            });
        }

        // Invariant: channels in pool + channels held by threads == poolSize.
        // The bound only matters if a caller returns the same channel twice.
        pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.add(newChannel());
        }
    }

    /** The shared connection, e.g. for declaring the exchange and queues at startup. */
    public Connection connection() {
        return connection;
    }

    private Channel newChannel() throws IOException {
        Channel ch = connection.createChannel();
        ch.confirmSelect();                          // publisher confirms on every channel
        return ch;
    }

    /**
     * Returns an open channel, or null if none is usable within the timeout
     * (all busy, or broker connection down). Caller should answer 503.
     */
    public Channel borrowChannel() throws InterruptedException {
        Channel ch = pool.poll(borrowTimeoutMs, TimeUnit.MILLISECONDS);
        if (ch == null) {
            return null;                             // FIX 1: never wait forever
        }
        if (ch.isOpen()) {
            return ch;
        }
        try {
            Channel fresh = newChannel();            // closed by a channel-level error -> swap
            try {
                ch.abort();                          // stop recovery from reviving the old one
            } catch (Exception ignored) {            // (would be an orphan channel outside the pool)
            }
            return fresh;
        } catch (IOException | RuntimeException e) {
            pool.offer(ch);                          // FIX 2: connection down -> keep the slot;
            return null;                             // automatic recovery reopens this channel
        }
    }

    /** Always call in a finally block. Never drops a slot, open or not. */
    public void returnChannel(Channel ch) {
        if (ch != null) {
            pool.offer(ch);
        }
    }

    public void close() throws IOException {
        if (connection != null && connection.isOpen()) {
            connection.close();                      // closes all its channels too
        }
    }
}