package skitrak.client;

/** What we measured for one swipe. */
final class RequestRecord {

    final long sentAtMs;        // wall clock when the first attempt went out
    final long latencyMicros;   // until the final response, retries included
    final int statusCode;       // -1 if we never got a response
    final int resortID;

    RequestRecord(long sentAtMs, long latencyMicros, int statusCode, int resortID) {
        this.sentAtMs = sentAtMs;
        this.latencyMicros = latencyMicros;
        this.statusCode = statusCode;
        this.resortID = resortID;
    }

    long completedAtMs() {
        return sentAtMs + latencyMicros / 1_000;
    }

    /** timestamp,requestType,latency,statusCode,resortID  (latency in ms, 3 decimals) */
    String toCsvLine() {
        long frac = latencyMicros % 1_000;
        String pad = frac < 10 ? "00" : frac < 100 ? "0" : "";
        return sentAtMs + ",SWIPE," + (latencyMicros / 1_000) + "." + pad + frac
                + "," + statusCode + "," + resortID;
    }
}