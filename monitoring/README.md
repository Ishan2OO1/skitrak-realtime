# monitoring

Scripts used to collect queue and ALB data during the performance runs. CPU and network graphs came from CloudWatch with detailed (1-minute) monitoring enabled on every instance.

## Files

| File | Runs on | What it does |
| --- | --- | --- |
| `queue_monitor.sh` | RabbitMQ instance | samples the resort queues about every 1-2 s into `queue_<run>.csv`: ready total, unacked total, deepest single queue, dead letters |
| `summarize_queue.sh` | RabbitMQ instance | peak and average depth from one of those CSVs |
| `alb_metrics.sh` | AWS CloudShell | ALB request count and 4xx/5xx per minute for a time window |

## Per run

```bash
# on the rabbitmq instance, before starting the client
./queue_monitor.sh p2-s4          # ctrl+c when the client finishes
./summarize_queue.sh queue_p2-s4.csv
# samples=110  peak_total=1003  avg_total=9.1  peak_single_queue=130
```

`peak_total` counts ready messages only. The RabbitMQ UI's queue graph also includes unacked messages (delivered to the consumer but not acked yet), so it shows a higher number for the same moment.

Drain time for the consumer thread sweep (from the moment the queue starts falling until it hits 0):

```bash
awk -F, 'NR==2{prev=$2} NR>2{ if(!s && $2<prev) s=$1; if(s && $2==0){print "drain_seconds=" $1-s; exit} prev=$2 }' queue_p4-c10.csv
```

ALB metrics for a window (in CloudShell):

```bash
./alb_metrics.sh 2026-11-19T10:01:00Z 2026-11-19T10:07:00Z
```

Requests that never show up in `RequestCount` never reached AWS. That is how the client-side stall and the 403s in the test results were told apart from server problems.

## Other things checked during runs

- Consumer: `sudo journalctl -u skitrak-consumer --no-pager | grep "processed total" | tail -1` (processed count, duplicates, board changes).
- Ingest: `sudo journalctl -u tomcat --no-pager | grep -E "RabbitMQ ready|backplane queue|lost|recover"`.
- RabbitMQ UI: Overview (rates, queued messages, node memory), Connections, Queues.

The CSVs from the runs are in `../results/queue/`.

If you saved the scripts on Windows and bash says `$'\r': command not found`, run `sed -i 's/\r$//' *.sh` on the instance.
