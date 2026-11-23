# Deployment

Everything runs in us-west-2. All instances are in us-west-2c except the consumer (us-west-2b), and they talk to the broker over its private IP.

| Instance | Type | What runs |
| --- | --- | --- |
| RabbitMQ | t3.small | RabbitMQ 4.4.0 in Docker (`rabbitmq/setup.sh`) |
| Ingest x1-4 | t3.micro, unlimited credits | Tomcat 9 + `ski-servlet.war` from server-v2, as a systemd service |
| Consumer | t3.micro | `skitrak-consumer-1.0.jar` as a systemd service |
| ALB | - | HTTP:80 -> target group on 8080 (`alb.md`) |

## Start order

1. RabbitMQ instance (Docker brings the broker up on its own).
2. Consumer instance (systemd retries every 5s until the broker answers).
3. Ingest instances (`tomcat.service` waits for port 5672 before starting Tomcat).

Only public IPs change after a stop/start. Configs use the broker's private IP, so nothing needs editing.

## Files

| File | Where it goes |
| --- | --- |
| `rabbitmq/setup.sh` | run once on the broker instance |
| `ingest/setenv.sh.example` | `/opt/tomcat9/bin/setenv.sh` on each ingest server (set the password) |
| `ingest/tomcat.service` | `/etc/systemd/system/tomcat.service` |
| `consumer/consumer.env.example` | `/etc/skitrak/consumer.env` (chmod 600, set the password) |
| `consumer/skitrak-consumer.service` | `/etc/systemd/system/skitrak-consumer.service` |
| `alb.md` | target group, ALB and security group settings |

## Ingest server

```bash
sudo dnf install -y java-11-amazon-corretto-headless   # Tomcat 9 as in Lab 1, under /opt/tomcat9
cp setenv.sh.example /opt/tomcat9/bin/setenv.sh && chmod 700 /opt/tomcat9/bin/setenv.sh
cp ski-servlet-2.0-SNAPSHOT.war /opt/tomcat9/webapps/ski-servlet.war   # file name = context path
sudo cp tomcat.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now tomcat
curl -s http://localhost:8080/ski-servlet/health
```

Extra ingest servers were cloned from an AMI of a working one. If you change `setenv.sh` after making the AMI, change it on every clone too (or make a new AMI), otherwise the fleet ends up with different settings.

To update code on a running server, copy the new WAR over `webapps/ski-servlet.war` and `sudo systemctl restart tomcat`.

## Consumer

```bash
sudo dnf install -y java-11-amazon-corretto-headless
sudo mkdir -p /opt/skitrak /etc/skitrak
sudo cp skitrak-consumer-1.0.jar /opt/skitrak/
sudo cp consumer.env.example /etc/skitrak/consumer.env && sudo chmod 600 /etc/skitrak/consumer.env
sudo cp skitrak-consumer.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now skitrak-consumer
sudo journalctl -u skitrak-consumer -f
```

Env file changes only apply after `sudo systemctl restart skitrak-consumer`.
