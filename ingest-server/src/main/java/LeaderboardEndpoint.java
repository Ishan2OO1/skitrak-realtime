import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

// boards come from the consumer via the backplane, we just forward them to our sessions
@ServerEndpoint("/leaderboard/{resortID}")
public class LeaderboardEndpoint {

    private static final Logger logger = Logger.getLogger(LeaderboardEndpoint.class.getName());

    // static, tomcat creates one endpoint object per connection
    private static final Map<String, Set<Session>> subscribers = new ConcurrentHashMap<>();

    // so a new subscriber gets the current board straight away
    private static final Map<String, String> latest = new ConcurrentHashMap<>();

    private static final String SEND_STATE = "sendState";

    // per session: is a send in progress, and the newest board waiting behind it
    private static final class SendState {
        final AtomicBoolean sending = new AtomicBoolean(false);
        final AtomicReference<String> pending = new AtomicReference<>();
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("resortID") String resortID) {
        session.getUserProperties().put(SEND_STATE, new SendState());
        subscribers.computeIfAbsent(resortID, k -> ConcurrentHashMap.newKeySet()).add(session);
        String board = latest.get(resortID);
        if (board != null) {
            send(session, board);
        }
    }

    @OnClose
    public void onClose(Session session, @PathParam("resortID") String resortID) {
        removeSession(session, resortID);
    }

    @OnError
    public void onError(Session session, @PathParam("resortID") String resortID, Throwable t) {
        logger.log(Level.WARNING, "WebSocket error on session " + session.getId(), t);
        removeSession(session, resortID);
    }

    private static void removeSession(Session session, String resortID) {
        Optional.ofNullable(subscribers.get(resortID)).ifPresent(s -> s.remove(session));
    }

    static void broadcast(String resortID, String json) {
        latest.put(resortID, json);
        Set<Session> sessions = subscribers.get(resortID);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (Session s : sessions) {
            send(s, json);
        }
    }

    // if a send is still going, only the newest board is kept and sent right after it.
    // boards in between can be skipped but the client always ends up with the latest one
    private static void send(Session s, String json) {
        SendState st = (SendState) s.getUserProperties().get(SEND_STATE);
        if (st == null || !s.isOpen()) {
            return;
        }
        st.pending.set(json);
        if (st.sending.compareAndSet(false, true)) {
            sendNext(s, st);
        }
    }

    private static void sendNext(Session s, SendState st) {
        String next = st.pending.getAndSet(null);
        if (next == null || !s.isOpen()) {
            st.sending.set(false);
            // a board can arrive between the getAndSet and set(false), don't leave it stuck
            if (st.pending.get() != null && s.isOpen() && st.sending.compareAndSet(false, true)) {
                sendNext(s, st);
            }
            return;
        }
        try {
            s.getAsyncRemote().sendText(next, result -> sendNext(s, st));
        } catch (RuntimeException e) {
            st.sending.set(false);
        }
    }
}