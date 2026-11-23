package skitrak.consumer;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

// one per resort queue. callbacks for a channel run one at a time, so order is kept
// and the maps below are only touched by one thread at a time (no locks needed)
final class ResortConsumer extends DefaultConsumer {

    private static final Logger logger = Logger.getLogger(ResortConsumer.class.getName());
    private static final Gson gson = new Gson();

    // boards: not persistent, no confirms. a newer board replaces a lost one
    private static final AMQP.BasicProperties BOARD_PROPS = new AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(1)
            .build();

    private final Channel boardChannel;
    private final int resortID;
    private final int topN;
    private final Stats stats;

    // "day:skier" -> total vertical for this resort
    private final Map<String, Long> skierVertical = new HashMap<>();

    // concurrent only because the reporter reads it
    private final Map<String, TopN> boards = new ConcurrentHashMap<>();

    // only unacked msgs (<= prefetch) can be redelivered, so a bounded window is enough
    private final Set<String> seen;

    ResortConsumer(Channel channel, Channel boardChannel, int resortID, int topN, int dedupeWindow, Stats stats) {
        super(channel);
        this.boardChannel = boardChannel;
        this.resortID = resortID;
        this.topN = topN;
        this.stats = stats;
        this.seen = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > dedupeWindow;
            }
        });
    }

    Map<String, TopN> boards() {
        return boards;
    }

    int resortID() {
        return resortID;
    }

    @Override
    public void handleDelivery(String consumerTag, Envelope envelope,
                               AMQP.BasicProperties properties, byte[] body) throws IOException {
        long tag = envelope.getDeliveryTag();

        SwipeMessage msg;
        try {
            msg = gson.fromJson(new String(body, StandardCharsets.UTF_8), SwipeMessage.class);
            if (msg == null || msg.eventId == null || msg.skierID == null || msg.dayID == null) {
                throw new JsonParseException("missing required fields");
            }
            if (msg.vertical <= 0) {
                // TopN relies on totals only going up
                throw new JsonParseException("vertical must be > 0, got " + msg.vertical);
            }
        } catch (RuntimeException e) {
            // bad msg. requeue would just loop forever, so reject -> goes to skitrak.dead
            logger.warning("resort " + resortID + ": bad message -> dead-letter: " + e.getMessage());
            getChannel().basicReject(tag, false);
            return;
        }

        try {
            if (seen.contains(msg.eventId)) {
                // already counted, just ack it
                stats.duplicates.increment();
            } else {
                long total = skierVertical.merge(msg.dayID + ":" + msg.skierID,
                        (long) msg.vertical, Long::sum);
                TopN board = boards.computeIfAbsent(msg.dayID, d -> new TopN(topN));
                if (board.update(msg.skierID, total)) {
                    stats.boardChanges.increment();
                    publishBoard(msg.dayID, board);
                }
                seen.add(msg.eventId);   // only after it's applied
                stats.processed.increment();
            }
        } catch (RuntimeException e) {
            // an exception escaping here closes the channel and the resort stops consuming
            logger.warning("resort " + resortID + ": processing failed -> dead-letter: " + e);
            getChannel().basicNack(tag, false, false);
            return;
        }

        // ack only after it's counted, so a crash before this means redelivery not loss
        getChannel().basicAck(tag, false);
    }

    private void publishBoard(String dayID, TopN board) {
        try {
            String json = gson.toJson(LeaderboardMessage.of(resortID, dayID, board.snapshot()));
            boardChannel.basicPublish(SwipeTopology.LEADERBOARD_EXCHANGE, SwipeTopology.routingKey(resortID),
                    BOARD_PROPS, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            // swipe is already counted, don't fail it over a broadcast
            stats.broadcastFailures.increment();
        }
    }
}