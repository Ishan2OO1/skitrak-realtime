#!/bin/bash
# samples the resort queues every ~2s into a csv, run it on the rabbitmq instance
# usage: ./queue_monitor.sh <run-name>      stop with ctrl+c
out="queue_${1:-run}.csv"
echo "epoch,ready_total,unacked_total,max_ready_one_queue,dead_letters" > "$out"
echo "writing $out (ctrl+c to stop)"
while true; do
  docker exec rabbitmq rabbitmqctl list_queues -q --no-table-headers name messages_ready messages_unacknowledged 2>/dev/null \
    | awk -v t="$(date +%s)" '
        $1 ~ /^resort\./ { r += $2; u += $3; if ($2 > m) m = $2 }
        $1 == "skitrak.dead" { d = $2 }
        END { printf "%s,%d,%d,%d,%d\n", t, r, u, m, d }' >> "$out"
  sleep 1
done
