import com.google.gson.Gson;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /health - server status. The load balancer calls this to decide whether this server gets traffic.
 */
@WebServlet(urlPatterns = "/health", loadOnStartup = 1)
public class HealthServlet extends HttpServlet {

    private static final Gson gson = new Gson();
    private static final Instant STARTED_AT = Instant.now();   // set when Tomcat loads this servlet

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        Instant now = Instant.now();

        // LinkedHashMap keeps the keys in the order we add them, so the JSON reads nicely
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("status", "UP");
        status.put("service", "skitrak-server");
        status.put("serverTimestamp", now.toString());          // ISO-8601 UTC, same format as the leaderboard
        status.put("uptimeSeconds", now.getEpochSecond() - STARTED_AT.getEpochSecond());

        res.setStatus(HttpServletResponse.SC_OK);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(gson.toJson(status));
    }
}