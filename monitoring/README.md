# Monitoring

- `queue_monitor.py` — run during each test (from any machine that can reach port 15672):
  `python3 queue_monitor.py <rabbit-host> skitrak <pass> 5 run1-queue.csv`.
  Gives per-queue depth and publish/deliver/ack rates over time (for plateau vs. sawtooth plots).
- `system_monitor.sh` — run on each server/consumer/RabbitMQ instance: CPU and memory (vmstat), network bytes.
- Consumer log (`journalctl -u skitrak-consumer -f`) prints processed count, rate, average consumer lag,
  total queue depth and current thread count every 5 seconds.
- Consumer health: `curl http://<consumer-host>:8081/health`.
- RabbitMQ console (http://<rabbit-host>:15672) — take screenshots of the queue graphs for the report.
