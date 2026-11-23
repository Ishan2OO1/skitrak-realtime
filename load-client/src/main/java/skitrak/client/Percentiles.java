package skitrak.client;

import java.util.List;

/** Small stats helpers. Inputs must already be sorted. */
final class Percentiles {

    private Percentiles() {
    }

    /** Nearest-rank percentile, e.g. p = 99 for p99. */
    static long of(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        return sorted.get(rankIndex(sorted.size(), p));
    }

    static long of(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        return sorted[rankIndex(sorted.length, p)];
    }

    static double mean(List<Long> values) {
        if (values.isEmpty()) {
            return 0;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.size();
    }

    static double mean(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    private static int rankIndex(int n, double p) {
        return Math.max(0, (int) Math.ceil(p / 100.0 * n) - 1);
    }
}