package skitrak.consumer;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;

import java.io.IOException;

// copy of messaging.SwipeTopology from server-v2, keep declare() the same in both
public final class SwipeTopology {

    public static final String EXCHANGE = "skitrak.exchange";
    public static final String LEADERBOARD_EXCHANGE = "leaderboard.fanout";
    public static final String DLX = "skitrak.dlx";
    public static final String DEAD_QUEUE = "skitrak.dead";

    private SwipeTopology() {}

    public static String routingKey(int resortID) {
        return "resort." + resortID;
    }

    public static String queueName(int resortID) {
        return "resort." + resortID;
    }

    public static void declare(Channel ch, int resortCount) throws IOException {
        ch.exchangeDeclare(EXCHANGE, BuiltinExchangeType.TOPIC, true);
        for (int r = 1; r <= resortCount; r++) {
            ch.queueDeclare(queueName(r), true, false, false, null);
            ch.queueBind(queueName(r), EXCHANGE, routingKey(r));
        }
    }

    // dead letters end up here (via the policy). don't name it resort.* or the policy applies to it
    public static void declareDeadLetter(Channel ch) throws IOException {
        ch.exchangeDeclare(DLX, BuiltinExchangeType.FANOUT, true);
        ch.queueDeclare(DEAD_QUEUE, true, false, false, null);
        ch.queueBind(DEAD_QUEUE, DLX, "");
    }

    public static void declareLeaderboard(Channel ch) throws IOException {
        ch.exchangeDeclare(LEADERBOARD_EXCHANGE, BuiltinExchangeType.FANOUT, true);
    }
}