# server-v2 (ingest server)

Tomcat 9 web app (`ski-servlet.war`). On a valid swipe it looks up the lift's vertical, publishes the swipe to RabbitMQ and answers 201 only after the broker confirms it. It also serves the live leaderboard over WebSocket, with boards coming from the consumer through the backplane.

## Endpoints

| Endpoint | What it does |
| --- | --- |
| `POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}` | body `{"time":217,"liftID":21}`. 201 = in the queue, 400 = invalid (incl. resort > RESORT_COUNT), 503 = broker problem, retry |
| `GET /health` | status JSON, used by the ALB health check |
| `ws /leaderboard/{resortID}` | pushes the resort's top-N on every change; sends the latest board on connect |

## Main classes

| Class | Role |
| --- | --- |
| `SkierServlet` | validation, vertical lookup, publish, 201/400/503 |
| `LiftVerticals` | `lift_vertical.json` loaded once into an array |
| `messaging.ChannelPool` | one connection + N channels with confirms; borrow with timeout; auto recovery |
| `messaging.SwipePublisher` | builds the message, publishes, waits for the confirm |
| `messaging.SwipeTopology` | exchange/queue names and declares (same as the consumer's copy) |
| `messaging.LeaderboardSubscriber` | per-server queue on `leaderboard.fanout`, hands boards to the endpoint |
| `LeaderboardEndpoint` | WebSocket sessions per resort, coalesced sends |
| `AppLifecycleListener` | connects and declares on deploy, closes on undeploy |

## Config (environment variables, set in `setenv.sh`)

| Variable | Default |
| --- | --- |
| `RABBITMQ_HOST` | localhost |
| `RABBITMQ_USER` | skitrak |
| `RABBITMQ_PASS` | required |
| `CHANNEL_POOL_SIZE` | 64 (we run 128) |
| `CHANNEL_BORROW_TIMEOUT_MS` | 1000 |
| `CONFIRM_TIMEOUT_MS` | 1000 |
| `PERSISTENT_MESSAGES` | true |
| `RESORT_COUNT` | 10 |
| `SERVER_ID` | hostname |

The deploy fails on purpose if the broker isn't reachable, so start RabbitMQ first (`tomcat.service` waits for it).

## Build and run

```bash
mvn clean package                      # target/ski-servlet-2.0-SNAPSHOT.war
```

Locally (IntelliJ Tomcat run config): set the env vars in the run config and the application context to `/ski-servlet`.

On EC2 see `../deployment/README.md`. Logs: `sudo journalctl -u tomcat`.
