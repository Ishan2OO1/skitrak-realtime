package skitrak.client;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Turns the per-swipe records into latency/throughput stats and CSV files. */
final class Report {

    private static final long BUCKET_MS = 10_000;

    private Report() {
    }

    static void print(List<RequestRecord> records, long runStartMs, double wallSecs,
                      int resorts, Path outDir) throws IOException {
        Files.createDirectories(outDir);

        Path latencyCsv = outDir.resolve("latencies.csv");
        writeLatencyCsv(records, latencyCsv);
        System.out.printf("%nWrote %,d rows to %s%n", records.size(), latencyCsv.toAbsolutePath().normalize());

        printLatency(records);
        printPerResort(records, wallSecs, resorts);
        printTimeline(records, runStartMs, wallSecs, outDir.resolve("throughput.csv"));
    }

    private static void writeLatencyCsv(List<RequestRecord> records, Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file)) {
            out.write("timestamp,requestType,latency,statusCode,resortID");
            out.newLine();
            for (RequestRecord r : records) {
                out.write(r.toCsvLine());
                out.newLine();
            }
        }
    }

    private static void printLatency(List<RequestRecord> records) {
        long[] micros = records.stream().mapToLong(r -> r.latencyMicros).sorted().toArray();
        if (micros.length == 0) {
            return;
        }
        System.out.println();
        System.out.println("Ingest latency (ms):");
        System.out.printf("  mean %.2f   median %.2f   p95 %.2f   p99 %.2f   min %.2f   max %.2f%n",
                Percentiles.mean(micros) / 1_000,
                Percentiles.of(micros, 50) / 1_000.0,
                Percentiles.of(micros, 95) / 1_000.0,
                Percentiles.of(micros, 99) / 1_000.0,
                micros[0] / 1_000.0,
                micros[micros.length - 1] / 1_000.0);
    }

    private static void printPerResort(List<RequestRecord> records, double wallSecs, int resorts) {
        long[] counts = new long[resorts + 1];
        for (RequestRecord r : records) {
            if (r.statusCode == 201 && r.resortID >= 1 && r.resortID <= resorts) {
                counts[r.resortID]++;
            }
        }
        System.out.println();
        System.out.println("Throughput per resort (successful swipes over the whole run):");
        for (int resort = 1; resort <= resorts; resort++) {
            System.out.printf("  resort %2d: %,7d swipes   %,6.0f swipes/s%n",
                    resort, counts[resort], counts[resort] / wallSecs);
        }
    }

    // swipes completed in each 10 s window since the start of the run
    private static void printTimeline(List<RequestRecord> records, long runStartMs,
                                      double wallSecs, Path csv) throws IOException {
        long runMs = (long) (wallSecs * 1_000);
        int buckets = (int) (runMs / BUCKET_MS) + 1;
        long[] counts = new long[buckets];
        for (RequestRecord r : records) {
            if (r.statusCode != 201) {
                continue;
            }
            int b = (int) ((r.completedAtMs() - runStartMs) / BUCKET_MS);
            counts[Math.max(0, Math.min(buckets - 1, b))]++;
        }

        System.out.println();
        System.out.println("Throughput over time (10-second buckets):");
        try (BufferedWriter out = Files.newBufferedWriter(csv)) {
            out.write("bucketStartSec,swipesPerSec");
            out.newLine();
            for (int b = 0; b < buckets; b++) {
                long startMs = b * BUCKET_MS;
                // the last bucket is usually shorter than 10 s
                double lengthSecs = Math.min(BUCKET_MS, runMs - startMs) / 1_000.0;
                if (lengthSecs <= 0) {
                    continue;
                }
                double rate = counts[b] / lengthSecs;
                System.out.printf("  %3d-%3d s: %,7.0f swipes/s%n",
                        startMs / 1_000, (long) (startMs / 1_000 + lengthSecs), rate);
                out.write((startMs / 1_000) + "," + Math.round(rate));
                out.newLine();
            }
        }
        System.out.println("Wrote " + csv.toAbsolutePath().normalize());
    }
}