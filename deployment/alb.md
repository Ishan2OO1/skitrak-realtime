# ALB and target group

## Security groups

| SG | Inbound |
| --- | --- |
| ALB SG | HTTP 80 from my IP |
| Ingest SG | 8080 from the ALB SG, 8080 + 22 from my IP (debugging) |
| RabbitMQ SG | 5672 from the VPC CIDR (172.31.0.0/16), 15672 + 22 from my IP |
| Consumer SG | 22 from my IP only (it only makes outgoing connections) |

## Target group `skitrak-ingest-tg`

| Setting | Value |
| --- | --- |
| Target type / protocol / port | Instances, HTTP, 8080 |
| Health check path | `/ski-servlet/health` (the context path is part of it, plain `/health` gives 404) |
| Interval / timeout / healthy / unhealthy | 30s / 5s / 2 / 3 |
| Stickiness | on, load balancer generated cookie, 1 day |
| Algorithm | round robin |
| Deregistration delay | 30s |

For the 1/2/4 server runs, targets were registered/deregistered here. Wait for all targets to be Healthy, and don't register a server while its Tomcat is restarting (it gets traffic before it's ready).

## ALB `skitrak-alb`

| Setting | Value |
| --- | --- |
| Scheme | internet-facing, IPv4 |
| AZs | us-west-2b + us-west-2c (ALB needs 2; servers are in 2c) |
| Listener | HTTP:80 -> forward to `skitrak-ingest-tg` |
| Idle timeout | 120s (WebSockets stay open past 60s) |

WebSocket upgrades pass through on the HTTP listener, no extra setting. Client URL: `http://<alb-dns>/ski-servlet`, leaderboard: `ws://<alb-dns>/ski-servlet/leaderboard/{resortID}`.

The ALB bills hourly even when idle, delete it after testing.
