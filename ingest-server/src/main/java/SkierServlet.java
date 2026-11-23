import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import messaging.SwipePublisher;
import model.LiftRide;
import model.ResponseMsg;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.logging.Logger;

public class SkierServlet extends HttpServlet {
    private static final Logger logger = Logger.getLogger(SkierServlet.class.getName());
    private Gson gson = new Gson();

    // validation limits
    private static final int MIN_TIME = 1;
    private static final int MAX_TIME = 360;
    private static final int MIN_LIFT_ID = 1;
    private static final int MAX_LIFT_ID = 40;

    // set once in init(), then only read -> safe to share across request threads
    private SwipePublisher publisher;
    private int resortCount;

    @Override
    public void init() throws ServletException {
        publisher = (SwipePublisher) getServletContext().getAttribute(AppLifecycleListener.PUBLISHER_ATTR);
        Integer rc = (Integer) getServletContext().getAttribute(AppLifecycleListener.RESORT_COUNT_ATTR);
        if (publisher == null || rc == null) {
            throw new ServletException("RabbitMQ was not initialised by AppLifecycleListener");
        }
        resortCount = rc;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        logger.fine(() -> "GET request received: " + req.getPathInfo());

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Check we have a URL
        if (urlPath == null || urlPath.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            logger.warning("Invalid URL: " + req.getPathInfo());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
        } else {
            res.setStatus(HttpServletResponse.SC_OK);

            // Extract parameters
            String resortID = urlParts[1];
            String seasonID = urlParts[3];
            String dayID = urlParts[5];
            String skierID = urlParts[7];

            // Create response
            ResponseMsg response = new ResponseMsg(
                    String.format("GET request processed for skier %s at resort %s, season %s, day %s",
                            skierID, resortID, seasonID, dayID)
            );

            res.getWriter().write(gson.toJson(response));
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res)
            throws ServletException, IOException {

        logger.fine(() -> "POST request received: " + req.getPathInfo());

        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");

        String urlPath = req.getPathInfo();

        // Check we have a URL
        if (urlPath == null || urlPath.isEmpty()) {
            res.setStatus(HttpServletResponse.SC_NOT_FOUND);
            res.getWriter().write(gson.toJson(new ResponseMsg("missing parameters")));
            return;
        }

        String[] urlParts = urlPath.split("/");

        if (!isUrlValid(urlParts)) {
            logger.warning("Invalid URL: " + req.getPathInfo());
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid url")));
            return;
        }

        // Parse JSON body
        try {
            StringBuilder jsonBody = new StringBuilder();
            BufferedReader reader = req.getReader();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonBody.append(line);
            }

            LiftRide liftRide = gson.fromJson(jsonBody.toString(), LiftRide.class);

            if (liftRide == null || !isLiftRideValid(liftRide)) {
                logger.warning("Invalid lift ride: " + liftRide);
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON body")));
                return;
            }

            // Extract URL parameters (already validated as positive integers)
            int resortID = Integer.parseInt(urlParts[1]);
            int dayID = Integer.parseInt(urlParts[5]);
            int skierID = Integer.parseInt(urlParts[7]);

            // only resorts that have a queue. resort.11 has no queue -> message silently dropped.
            if (resortID > resortCount) {
                res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                res.getWriter().write(gson.toJson(new ResponseMsg("unknown resort")));
                return;
            }

            // look up vertical, publish to RabbitMQ, answer only after the broker confirms
            int liftID = liftRide.getLiftID();
            SwipePublisher.Result result = publisher.publish(
                    resortID, dayID, skierID, liftID, LiftVerticals.of(liftID), liftRide.getTime());

            if (result == SwipePublisher.Result.OK) {
                res.setStatus(HttpServletResponse.SC_CREATED);
                res.getWriter().write(gson.toJson(new ResponseMsg("swipe accepted")));
            } else {
                res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);   // 503: temporary, please retry
                res.setHeader("Retry-After", "1");
                res.getWriter().write(gson.toJson(new ResponseMsg("broker unavailable: " + result)));
            }

        } catch (JsonSyntaxException e) {
            res.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            res.getWriter().write(gson.toJson(new ResponseMsg("invalid JSON format")));
        }
    }

    private boolean isUrlValid(String[] urlParts) {
        // Expected URL: /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
        // urlParts = ["", "1", "seasons", "2019", "days", "1", "skiers", "123"]

        if (urlParts.length != 8) {
            return false;
        }

        try {
            // Check structure
            if (!urlParts[2].equals("seasons") ||
                    !urlParts[4].equals("days") ||
                    !urlParts[6].equals("skiers")) {
                return false;
            }

            // all four IDs must be positive integers
            return Integer.parseInt(urlParts[1]) > 0   // resortID
                    && Integer.parseInt(urlParts[3]) > 0   // seasonID
                    && Integer.parseInt(urlParts[5]) > 0   // dayID
                    && Integer.parseInt(urlParts[7]) > 0;  // skierID

        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean isLiftRideValid(LiftRide liftRide) {
        // time 1-360, liftID 1-40
        return liftRide.getTime() >= MIN_TIME
                && liftRide.getTime() <= MAX_TIME
                && liftRide.getLiftID() >= MIN_LIFT_ID
                && liftRide.getLiftID() <= MAX_LIFT_ID;
    }
}