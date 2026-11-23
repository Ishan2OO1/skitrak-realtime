package skitrak.consumer;

import java.util.concurrent.atomic.LongAdder;

// counters shared by all resort consumers, read by the reporter
final class Stats {
    final LongAdder processed = new LongAdder();
    final LongAdder duplicates = new LongAdder();
    final LongAdder boardChanges = new LongAdder();
    final LongAdder broadcastFailures = new LongAdder();
}