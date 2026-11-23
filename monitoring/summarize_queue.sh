#!/bin/bash
# peak and average queue depth from a queue_monitor csv
# usage: ./summarize_queue.sh queue_<run-name>.csv
awk -F, 'NR > 1 { n++; s += $2; if ($2 > p) p = $2; if ($4 > q) q = $4 }
         END { printf "samples=%d  peak_total=%d  avg_total=%.1f  peak_single_queue=%d\n", n, p, s / n, q }' "$1"
