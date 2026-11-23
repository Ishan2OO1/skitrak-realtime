package skitrak.client;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counters shared by all sender threads.
 */
public class Stats {

    public final LongAdder successful = new LongAdder();
    public final LongAdder failed = new LongAdder();
    public final LongAdder retries = new LongAdder();
}