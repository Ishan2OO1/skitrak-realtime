# consumer

Standalone Java app (fat JAR) that consumes the 10 resort queues, keeps each skier's vertical and each resort's top-N, and publishes a resort's board to `leaderboard.fanout` whenever it changes.

## How it works

- One channel per resort queue, push mode (`basicConsume`), manual ack, prefetch 100. A channel's callbacks run one at a time, so each resort is processed in order and its state needs no locks.
- Per swipe: dedupe on `eventId` (last 50,000 per resort), add vertical to `day:skier`, update the top-N, publish the board if it changed, then ack.
- Bad messages (not JSON, missing fields, vertical <= 0) are rejected without requeue and end up in `skitrak.dead` through the broker policy. Any other exception is caught and nacked to the same place, so a resort never stops consuming.
- State is in memory. A restart starts the totals from zero.

## Main classes

| Class | Role |
| --- | --- |
| `ConsumerApp` | config, connection with a fixed thread pool, one channel per queue, reporter, shutdown hook |
| `ResortConsumer` | per-resort handling: parse, dedupe, aggregate, board publish, ack |
| `TopN` | top-N list; immutable snapshot for other threads |
| `LeaderboardMessage` | board JSON, same shape the WebSocket clients expect |
| `SwipeTopology` | names and declares, plus the dead-letter exchange/queue |
| `Stats`, `SwipeMessage` | counters and the swipe message |

## Config (environment variables)

| Variable | Default |
| --- | --- |
| `RABBITMQ_HOST` | localhost |
| `RABBITMQ_USER` | skitrak |
| `RABBITMQ_PASS` | required |
| `RESORT_COUNT` | 10 |
| `CONSUMER_THREADS` | = RESORT_COUNT |
| `PREFETCH` | 100 |
| `TOP_N` | 20 |
| `DEDUPE_WINDOW` | 50000 (per resort) |

## Build and run

```bash
mvn clean package                                  # target/skitrak-consumer-1.0.jar (shaded)
RABBITMQ_HOST=<broker-ip> RABBITMQ_PASS='...' java -jar target/skitrak-consumer-1.0.jar
```

Every 5s it logs `processed total=... rate=... duplicates=... boardChanges=... broadcastFailures=...` and resort 1's top 3.

Only run one consumer at a time. Two consumers on the same queues would split each resort's messages and break the ordering and the totals.

On EC2 it runs as a systemd service, see `../deployment/README.md`. Logs: `sudo journalctl -u skitrak-consumer`.
