package skitrak.consumer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

// same json shape the WebSocket clients already parse
final class LeaderboardMessage {

    private static final DateTimeFormatter ISO_MILLIS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    final String resortID;
    final String dayID;
    final String serverTimestamp;   // set when the board is built
    final List<Rank> top;

    private LeaderboardMessage(String resortID, String dayID, String serverTimestamp, List<Rank> top) {
        this.resortID = resortID;
        this.dayID = dayID;
        this.serverTimestamp = serverTimestamp;
        this.top = top;
    }

    static LeaderboardMessage of(int resortID, String dayID, List<TopN.Entry> entries) {
        List<Rank> top = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            TopN.Entry e = entries.get(i);
            top.add(new Rank(i + 1, Integer.parseInt(e.skierID), e.vertical));
        }
        return new LeaderboardMessage(String.valueOf(resortID), dayID,
                ISO_MILLIS.format(Instant.now()), top);
    }

    static final class Rank {
        final int rank;
        final int skierID;
        final long vertical;

        Rank(int rank, int skierID, long vertical) {
            this.rank = rank;
            this.skierID = skierID;
            this.vertical = vertical;
        }
    }
}