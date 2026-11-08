# Consumer (aggregation + leaderboard broadcaster)

Build: `mvn clean package` -> `target/consumer.jar`. Run: `java -jar consumer.jar` with env vars
from `../deployment/consumer/consumer.env` (RABBIT_HOST, CONSUMER_MIN_THREADS, CONSUMER_MAX_THREADS, PREFETCH, HEALTH_PORT).

- One channel and one consumer per resort queue (`resort.1`..`resort.10`): each resort is applied in order.
- All channels run on one resizable thread pool; `LoadMonitor` grows/shrinks it from queue depth.
- `Aggregator` dedupes by `eventId`, sums vertical per `resort:day:skier`, keeps top 10 per `resort:day`.
- When the top 10 changes, the leaderboard is published to `leaderboard.fanout` (the backplane).
- Manual ack after successful processing; 3 attempts, then reject to `skitrak.dead`.
- `GET :8081/health`; logs rate, consumer lag, queue depth and thread count every 5 s.
