# Deployment

Four kinds of EC2 instances:

| Role | What runs | Files |
|---|---|---|
| RabbitMQ | `rabbitmq:3.13-management` in Docker | `rabbitmq/install-rabbitmq.sh` |
| Ingest server (1, 2 or 4) | Tomcat 9 + `ski-servlet.war` (server-v2) | `server/setenv.sh`, `server/deploy-server.sh` |
| Consumer | `consumer.jar` under systemd | `consumer/*` |
| Load balancer | AWS ALB | `alb/create-alb.sh` |

## Order

1. RabbitMQ: run `install-rabbitmq.sh`. Open 5672 to the VPC and 15672 to your IP.
2. Ingest servers: set `RABBIT_HOST` in `server/setenv.sh`, then `./deploy-server.sh key.pem host1 host2 ...`.
   Startup declares the exchanges and queues (`skitrak.exchange`, `resort.1`..`resort.10`, `leaderboard.fanout`, `skitrak.dlx`/`skitrak.dead`).
3. Consumer: copy `consumer/target/consumer.jar`, `consumer.env`, `healthcheck.sh` to the instance; install `skitrak-consumer.service`; add the cron line from `healthcheck.sh`.
4. ALB: fill in `alb/create-alb.sh` and run it. Point the client at `http://<alb-dns>/ski-servlet`.

## Backplane setup

Nothing extra to install: the backplane is the `leaderboard.fanout` exchange on the same RabbitMQ broker.
Each server-v2 instance creates its own temporary queue bound to it on startup.

## Health check path

The assignment says `/health`; the WAR is deployed under the `/ski-servlet` context path, so the
target group uses `/ski-servlet/health`.
