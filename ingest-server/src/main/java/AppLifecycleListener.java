import com.rabbitmq.client.Channel;
import messaging.ChannelPool;
import messaging.LeaderboardSubscriber;
import messaging.SwipePublisher;
import messaging.SwipeTopology;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebListener
public class AppLifecycleListener implements ServletContextListener {

    private static final Logger logger = Logger.getLogger(AppLifecycleListener.class.getName());

    public static final String POOL_ATTR = "skitrak.channelPool";
    public static final String PUBLISHER_ATTR = "skitrak.swipePublisher";
    public static final String SERVER_ID_ATTR = "skitrak.serverId";
    public static final String RESORT_COUNT_ATTR = "skitrak.resortCount";

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        ServletContext ctx = sce.getServletContext();

        LiftVerticals.of(1); // load the json now so a bad file fails the deploy

        String host = env("RABBITMQ_HOST", "localhost");
        String user = env("RABBITMQ_USER", "skitrak");
        String pass = requiredEnv("RABBITMQ_PASS");
        int poolSize = Integer.parseInt(env("CHANNEL_POOL_SIZE", "64"));
        long borrowTimeoutMs = Long.parseLong(env("CHANNEL_BORROW_TIMEOUT_MS", "1000"));
        int resortCount = Integer.parseInt(env("RESORT_COUNT", "10"));
        String serverId = env("SERVER_ID", hostName());
        long confirmTimeoutMs = Long.parseLong(env("CONFIRM_TIMEOUT_MS", "1000"));
        boolean persistent = Boolean.parseBoolean(env("PERSISTENT_MESSAGES", "true"));

        ChannelPool pool = new ChannelPool(host, user, pass, poolSize, borrowTimeoutMs);
        try {
            pool.init("ingest-" + serverId);
            // producer declares queues too, publishing to an exchange with no bound queue drops the msg
            try (Channel ch = pool.connection().createChannel()) {
                SwipeTopology.declare(ch, resortCount);
            }
            LeaderboardSubscriber.start(pool.connection(), LeaderboardEndpoint::broadcast);
        } catch (IOException | TimeoutException e) {
            // close here, don't rely on contextDestroyed after a failed start
            closeQuietly(pool);
            throw new IllegalStateException("Cannot connect to RabbitMQ at " + host + ":5672", e);
        }

        ctx.setAttribute(POOL_ATTR, pool);
        ctx.setAttribute(PUBLISHER_ATTR, new SwipePublisher(pool, serverId, confirmTimeoutMs, persistent));
        ctx.setAttribute(SERVER_ID_ATTR, serverId);
        ctx.setAttribute(RESORT_COUNT_ATTR, resortCount);
        logger.info(String.format("RabbitMQ ready: host=%s pool=%d resorts=%d serverId=%s persistent=%b",
                host, poolSize, resortCount, serverId, persistent));
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        ChannelPool pool = (ChannelPool) sce.getServletContext().getAttribute(POOL_ATTR);
        closeQuietly(pool);   // also stops the leaderboard subscriber (same connection)
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String requiredEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Environment variable " + name + " is not set");
        }
        return v;
    }

    // on EC2 this is ip-172-31-x-x, different for every server
    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-host";
        }
    }

    private static void closeQuietly(ChannelPool pool) {
        if (pool == null) return;
        try {
            pool.close();
        } catch (Exception e) {
            logger.log(Level.FINE, "error closing channel pool", e);
        }
    }
}