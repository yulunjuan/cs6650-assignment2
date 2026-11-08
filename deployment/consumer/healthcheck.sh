#!/bin/bash
# Restarts the consumer if it is running but unhealthy (e.g. stuck without a broker connection).
# systemd already restarts it on crash; this covers the "alive but broken" case.
# Cron (every minute): * * * * * /home/ec2-user/healthcheck.sh >> /home/ec2-user/healthcheck.log 2>&1
if ! curl -sf --max-time 5 http://localhost:8081/health > /dev/null; then
  echo "$(date -Iseconds) consumer unhealthy, restarting"
  sudo systemctl restart skitrak-consumer
fi
