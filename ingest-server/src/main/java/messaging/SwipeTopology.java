package messaging;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;

// consumer has a copy of this, keep declare() the same in both
public final class SwipeTopology {

    public static final String EXCHANGE = "skitrak.exchange";
    public static final String LEADERBOARD_EXCHANGE = "leaderboard.fanout";

    private SwipeTopology() {}

    public static String routingKey(int resortID) {
        return "resort." + resortID;
    }

    public static String queueName(int resortID) {
        return "resort." + resortID;
    }

    // safe to call again, declaring something that exists with same settings does nothing
    public static void declare(Channel ch, int resortCount) throws IOException {
        ch.exchangeDeclare(EXCHANGE, BuiltinExchangeType.TOPIC, true);
        for (int r = 1; r <= resortCount; r++) {
            // ttl / max-length come from the broker policy
            ch.queueDeclare(queueName(r), true, false, false, null);
            ch.queueBind(queueName(r), EXCHANGE, routingKey(r));
        }
    }

    public static void declareLeaderboard(Channel ch) throws IOException {
        ch.exchangeDeclare(LEADERBOARD_EXCHANGE, BuiltinExchangeType.FANOUT, true);
    }
}