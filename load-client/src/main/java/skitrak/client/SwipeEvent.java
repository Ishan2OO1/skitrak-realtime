package skitrak.client;

/**
 * A single lift swipe. The path and JSON body are built up front so the sender threads only have to fire off the request.
 */
public final class SwipeEvent {

    public final int resortID;
    public final int seasonID;
    public final int dayID;
    public final int skierID;
    public final int time;
    public final int liftID;

    public final String path;   // e.g. /skiers/7/seasons/2026/days/1/skiers/40213
    public final String body;   // e.g. {"time":217,"liftID":21}

    public SwipeEvent(int resortID, int seasonID, int dayID, int skierID, int time, int liftID) {
        this.resortID = resortID;
        this.seasonID = seasonID;
        this.dayID = dayID;
        this.skierID = skierID;
        this.time = time;
        this.liftID = liftID;
        this.path = "/skiers/" + resortID + "/seasons/" + seasonID + "/days/" + dayID + "/skiers/" + skierID;
        this.body = "{\"time\":" + time + ",\"liftID\":" + liftID + "}";
    }
}