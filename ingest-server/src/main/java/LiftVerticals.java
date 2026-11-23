import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Lift -> vertical feet, loaded once from lift_vertical.json
 * Read-only after loading, so every request thread can read it with no locking
 */
public final class LiftVerticals {

    public static final int MAX_LIFT_ID = 40;

    // index = liftID (1..40); index 0 unused. Filled once, never changed afterwards.
    private static final int[] VERTICAL = load();

    private LiftVerticals() {}

    /** Vertical feet for a lift. Caller must have validated 1 <= liftID <= 40. */
    public static int of(int liftID) {
        return VERTICAL[liftID];
    }

    private static int[] load() {
        try (InputStream in = LiftVerticals.class.getResourceAsStream("/lift_vertical.json")) {
            if (in == null) {
                throw new IllegalStateException("lift_vertical.json not found on the classpath");
            }
            JsonObject root = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            JsonObject lifts = root.getAsJsonObject("lifts");

            int[] vertical = new int[MAX_LIFT_ID + 1];
            for (Map.Entry<String, JsonElement> e : lifts.entrySet()) {
                int liftID = Integer.parseInt(e.getKey());
                if (liftID < 1 || liftID > MAX_LIFT_ID) {
                    throw new IllegalStateException("unexpected lift id in JSON: " + liftID);
                }
                vertical[liftID] = e.getValue().getAsInt();
            }
            for (int id = 1; id <= MAX_LIFT_ID; id++) {
                if (vertical[id] <= 0) {
                    throw new IllegalStateException("missing vertical for lift " + id);
                }
            }
            return vertical;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}