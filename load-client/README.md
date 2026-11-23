# client-part2

Copy of client-part1 that also records every request and prints the stats for part 3.

Same main class (`skitrak.client.LoadClient`), with an optional 4th argument for where to write the
output files:

```
http://EC2_IP:8080/ski-servlet 256 500000 ../results
```

What's different from part 1:

- each sender keeps its own list of `RequestRecord`s (send timestamp, latency, status, resort), so
  there's no locking while the test runs. They're merged afterwards
- after the run it writes `latencies.csv` (`timestamp,requestType,latency,statusCode,resortID`),
  then prints mean/median/p95/p99/min/max latency, throughput per resort, and throughput in
  10 second buckets
- the buckets also go to `throughput.csv`, which `results/plot_throughput.py` turns into the chart

Latency is measured with `nanoTime` from the first attempt to the final response, so retries are
included. `latencies.csv` is ~20 MB for a full run so it's in `.gitignore`.

Chart:

```bash
pip install matplotlib
python ../results/plot_throughput.py --warmup-end 16.2
```

(`--warmup-end` is just the warmup time from the run output, used to shade that part of the graph.)
