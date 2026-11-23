package messaging;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

// ingest-server side of the backplane: one queue per server, bound to leaderboard.fanout,
// so every server gets every board and pushes it to its own websocket clients
public final class LeaderboardSubscriber {

    private static final Logger logger = Logger.getLogger(LeaderboardSubscriber.class.getName());

    private LeaderboardSubscriber() {}

    // push is given (resortID, json). passed in because messaging can't see default-package classes
    public static void start(Connection conn, BiConsumer<String, String> push) throws IOException {
        Channel ch = conn.createChannel();
        SwipeTopology.declareLeaderboard(ch);

        // a newer board makes older ones useless, so if we fall behind drop the OLDEST (drop-head).
        // opposite of the swipe queues, where every message matters and we reject-publish instead
        Map<String, Object> args = new HashMap<>();
        args.put("x-max-length", 1000);
        args.put("x-overflow", "drop-head");

        // server-named, exclusive, auto-delete: disappears when this server goes away,
        // so a dead server never leaves a queue filling up with boards
        String queue = ch.queueDeclare("", false, true, true, args).getQueue();
        ch.queueBind(queue, SwipeTopology.LEADERBOARD_EXCHANGE, "");

        // autoAck: losing a board is fine, the next one has the full top-N
        ch.basicConsume(queue, true, (tag, delivery) -> {
            try {
                String key = delivery.getEnvelope().getRoutingKey();   // "resort.3"
                String resortID = key.substring(key.indexOf('.') + 1);
                push.accept(resortID, new String(delivery.getBody(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                // don't let it escape, the client would close this channel and we'd stop getting boards
                logger.log(Level.WARNING, "leaderboard push failed", e);
            }
        }, tag -> { });

        logger.info("Leaderboard backplane queue " + queue + " bound to " + SwipeTopology.LEADERBOARD_EXCHANGE);
    }
}