# Server v2 (ingest + WebSocket)

Build: `mvn clean package` -> `target/ski-servlet.war` (context path `/ski-servlet`).
Configure with env vars in `<tomcat>/bin/setenv.sh` (see `../deployment/server/setenv.sh`).

Endpoints:
- `POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}` — validates, looks up the lift
  vertical, publishes to `skitrak.exchange` with routing key `resort.{resortID}`, waits for the broker
  confirm, then returns 201 (503 if the broker is unavailable).
- `GET /health`
- `WebSocket /leaderboard/{resortID}` — pushes real leaderboards received from the `leaderboard.fanout` backplane.

Code:
- `mq/ChannelPool` — one connection, pooled channels, reconnect on drop
- `mq/SwipePublisher` — borrow channel, publish, wait for confirm, return channel
- `mq/Topology` — exchange/queue declarations (identical copy in `consumer`)
- `BackplaneListener` — per-server queue bound to the fanout; forwards to `LeaderboardEndpoint`
- `AppLifecycle` — opens/closes RabbitMQ resources with the webapp
