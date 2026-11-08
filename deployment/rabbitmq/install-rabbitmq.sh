#!/bin/bash
# Run on the RabbitMQ EC2 instance (Amazon Linux 2023).
# Security group: allow 5672 (AMQP) from server/consumer instances, 15672 (console) from your IP.
set -e

RABBIT_USER=${RABBIT_USER:-skitrak}
RABBIT_PASS=${RABBIT_PASS:-change-me}

sudo dnf install -y docker
sudo systemctl enable --now docker

# --restart unless-stopped: the broker comes back after a crash or reboot
sudo docker run -d --name rabbitmq --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER="$RABBIT_USER" \
  -e RABBITMQ_DEFAULT_PASS="$RABBIT_PASS" \
  rabbitmq:3.13-management

echo "RabbitMQ starting. Console: http://<this-host>:15672 ($RABBIT_USER)"
echo "Exchanges and queues are declared by server-v2 and the consumer on startup."
