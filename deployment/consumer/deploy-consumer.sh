#!/bin/bash
# Usage: ./deploy-consumer.sh <key.pem> <consumer-ec2-host>
# Builds consumer.jar, copies it with its config to the instance and (re)starts the systemd service.
# Set RABBIT_HOST/RABBIT_PASS in consumer.env first. MVN may point at a Maven not on PATH.
set -e

KEY="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
HOST=$2
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR/../../consumer"
"${MVN:-mvn}" -q clean package

scp -i "$KEY" target/consumer.jar "$DIR/consumer.env" "$DIR/healthcheck.sh" \
  "$DIR/skitrak-consumer.service" ec2-user@"$HOST":/home/ec2-user/
ssh -i "$KEY" ec2-user@"$HOST" '
  # Amazon Linux 2023 ships without Java and cron; installs are no-ops when already present
  command -v java > /dev/null || sudo dnf install -y java-17-amazon-corretto-headless
  command -v crontab > /dev/null || { sudo dnf install -y cronie && sudo systemctl enable --now crond; }
  chmod +x /home/ec2-user/healthcheck.sh
  sudo cp /home/ec2-user/skitrak-consumer.service /etc/systemd/system/
  sudo systemctl daemon-reload
  sudo systemctl enable skitrak-consumer
  sudo systemctl restart skitrak-consumer
  (crontab -l 2>/dev/null | grep -v healthcheck.sh; \
   echo "* * * * * /home/ec2-user/healthcheck.sh >> /home/ec2-user/healthcheck.log 2>&1") | crontab -
  sleep 5
  curl -s http://localhost:8081/health; echo'
