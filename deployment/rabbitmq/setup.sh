#!/bin/bash
# run once on the broker instance (Amazon Linux 2023). set the password first
set -e
RABBIT_PASS='change-me'

sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user   # log out and back in after this

# fixed hostname so the data volume is found again if the container is recreated
sudo docker run -d --name rabbitmq --hostname skitrak-rabbit \
  --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 \
  -v rabbitmq-data:/var/lib/rabbitmq \
  -e RABBITMQ_DEFAULT_USER=skitrak -e RABBITMQ_DEFAULT_PASS="$RABBIT_PASS" \
  rabbitmq:4.4.0-management

sleep 30

# limits for the resort queues. reject-publish so a full queue nacks the publisher (503 -> client retry)
# instead of silently dropping the oldest swipe. rejected/expired msgs go to skitrak.dlx
sudo docker exec rabbitmq rabbitmqctl set_policy skitrak-limits '^resort\.' \
  '{"message-ttl":3600000,"max-length":100000,"overflow":"reject-publish","dead-letter-exchange":"skitrak.dlx"}' \
  --apply-to queues

sudo docker exec rabbitmq rabbitmqctl list_policies
# exchanges and queues are declared by the server and consumer at startup, nothing else to create here
