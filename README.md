# CS6650 Assignment 2

- `/server-v2` – ingest server: validates swipes, looks up lift vertical, publishes to RabbitMQ through a channel pool; WebSocket leaderboard fed by the backplane
- `/consumer` – aggregation (per-skier vertical, per-resort top 10, eventId dedupe) and leaderboard broadcaster
- `/client` – Assignment 1 load client (ingest + subscribe), with total-swipes and run-label arguments
- `/deployment` – RabbitMQ install, server and consumer deploy scripts, systemd unit, ALB setup
- `/monitoring` – queue metrics collector/summarizer, system metrics
- `/results` – test outputs, CSVs, screenshots
- `ARCHITECTURE.md` – architecture document
- `TEST_RESULTS.md` – test results and analysis (1 / 2 / 4 instances)
- `AI_USAGE.md` – AI usage reflection

Build (each project): `mvn clean package`.
