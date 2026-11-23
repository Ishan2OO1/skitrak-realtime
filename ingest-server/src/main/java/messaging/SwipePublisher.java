package messaging;

import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns a valid swipe into a queue message and publishes it with a publisher confirm.
 * The servlet only sees publish(...) -> Result; it never touches channels.
 */
public class SwipePublisher {

    /** What happened to one publish. Everything except OK becomes a 503 (client retries). */
    public enum Result {
        OK,            // broker confirmed: the message is in the queue
        NO_CHANNEL,    // pool busy for the whole timeout, or connection down
        NACKED,        // broker refused it (e.g. queue full -> reject-publish policy)
        TIMED_OUT,     // no confirm in time; message MAY still be in the queue
        ERROR          // anything else (connection dropped mid-publish, ...)
    }

    private static final Logger logger = Logger.getLogger(SwipePublisher.class.getName());
    private static final Gson gson = new Gson();   // Gson is thread-safe

    private final ChannelPool pool;
    private final String serverId;
    private final long confirmTimeoutMs;
    private final AMQP.BasicProperties props;

    public SwipePublisher(ChannelPool pool, String serverId, long confirmTimeoutMs, boolean persistent) {
        this.pool = pool;
        this.serverId = serverId;
        this.confirmTimeoutMs = confirmTimeoutMs;
        this.props = new AMQP.BasicProperties.Builder()
                .contentType("application/json")
                .deliveryMode(persistent ? 2 : 1)   // 2 = written to disk, survives broker restart
                .build();
    }

    public Result publish(int resortID, int dayID, int skierID, int liftID, int vertical, int time) {
        // Build the message BEFORE borrowing, so the channel is held as briefly as possible
        SwipeEvent event = new SwipeEvent(
                UUID.randomUUID().toString(),
                String.valueOf(resortID), String.valueOf(dayID), String.valueOf(skierID),
                String.valueOf(liftID), vertical, time,
                Instant.now().toString(), serverId);
        byte[] body = gson.toJson(event).getBytes(StandardCharsets.UTF_8);

        Channel ch;
        try {
            ch = pool.borrowChannel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.NO_CHANNEL;
        }
        if (ch == null) {
            return Result.NO_CHANNEL;
        }

        try {
            ch.basicPublish(SwipeTopology.EXCHANGE, SwipeTopology.routingKey(resortID), props, body);
            boolean acked = ch.waitForConfirms(confirmTimeoutMs);   // false = broker nacked
            return acked ? Result.OK : Result.NACKED;
        } catch (TimeoutException e) {
            return Result.TIMED_OUT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.ERROR;
        } catch (IOException | RuntimeException e) {
            logger.log(Level.FINE, "publish failed", e);
            return Result.ERROR;
        } finally {
            pool.returnChannel(ch);   // ALWAYS give it back, whatever happened
        }
    }

    /** The queue message format; Gson turns field names into JSON keys. */
    private static final class SwipeEvent {
        final String eventId;
        final String resortID;
        final String dayID;
        final String skierID;
        final String liftID;
        final int vertical;
        final int time;
        final String timestamp;
        final String serverId;

        SwipeEvent(String eventId, String resortID, String dayID, String skierID, String liftID,
                   int vertical, int time, String timestamp, String serverId) {
            this.eventId = eventId;
            this.resortID = resortID;
            this.dayID = dayID;
            this.skierID = skierID;
            this.liftID = liftID;
            this.vertical = vertical;
            this.time = time;
            this.timestamp = timestamp;
            this.serverId = serverId;
        }
    }
}
