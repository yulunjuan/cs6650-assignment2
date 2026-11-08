# SkiTrak Assignment 2 — Architecture

## 1. System architecture

```
Client (ingest threads + 10 WebSocket subscribers)
   │ HTTP POST / WebSocket upgrade
   ▼
AWS ALB  (sticky lb_cookie, idle timeout 120 s, health check /ski-servlet/health 30s/5s/2/3)
   │
   ├── server-v2 #1 ─┐   Tomcat 9: SkierServlet (producer) + LeaderboardEndpoint (local WebSocket
   ├── server-v2 #2 ─┤   sessions) + BackplaneListener (own temp queue on leaderboard.fanout)
   └── server-v2 #N ─┘
         │ publish + confirm                         ▲ fanout copy to every server
         ▼                                           │
   RabbitMQ (own EC2)                                │
     skitrak.exchange (topic) ──resort.{id}──► resort.1 … resort.10
     leaderboard.fanout (fanout) ────────────────────┘
     skitrak.dlx (fanout) ──► skitrak.dead
         │ deliver (manual ack, prefetch 100)        ▲ publish leaderboard when top-10 changes
         ▼                                           │
   Consumer (own EC2, systemd): 10 channels (one per resort queue) on a resizable thread pool
     Aggregator: skierVertical[resort:day:skier], TopN[resort:day], processedEventIds, swipesProcessed
```

## 2. Swipe → leaderboard sequence

```
Client     ALB    server-v2            RabbitMQ               Consumer             server-v2 (all)   Subscriber
  │ POST ───►│──────►│ validate, vertical lookup
  │          │       │ borrow channel ─ publish resort.{id} ─►│
  │          │       │◄──────────── confirm ──────────────────│
  │◄── 201 ──│◄──────│ return channel                          │── deliver ──────────►│
  │          │       │                                         │                      │ dedupe eventId
  │          │       │                                         │                      │ total += vertical
  │          │       │                                         │                      │ update top-10
  │          │       │                                         │◄─ leaderboard (if changed) ─│
  │          │       │                                         │◄──────────── ack ────│
  │          │       │                                         │── fanout ──────────────────────►│
  │          │       │                                         │                                 │── push ──►│
```

## 3. Queue topology

| Exchange | Type | Binding → queue | Queue arguments |
|---|---|---|---|
| `skitrak.exchange` | topic, durable | `resort.N` → `resort.N` (N = 1..10) | durable; `x-message-ttl` 1 h; `x-max-length` 100,000; `x-overflow reject-publish`; `x-dead-letter-exchange skitrak.dlx` |
| `skitrak.dlx` | fanout | → `skitrak.dead` | durable (expired or rejected swipes) |
| `leaderboard.fanout` | fanout | → one server-named, exclusive, auto-delete queue per server-v2 | auto-ack |

Messages are persistent. The producer uses publisher confirms. `reject-publish` makes a full queue
nack the publisher, so the server answers 503 and the client retries. Messages are never dropped silently.

## 4. Connection locality: broadcast backplane

A subscriber's WebSocket lives on whichever server the ALB picked. The consumer that computes the
standings has no connection to it. The consumer publishes each changed leaderboard to `leaderboard.fanout`
(routing key = resortID). Every server-v2 binds its own temporary queue, so every server receives every
update and pushes it to its local subscribers of that resort.

**Why not co-location:** partitioning by resort would need one ALB rule and target group per resort.
It would pin each resort to one node (no spreading of a hot resort, no failover) and force the consumer
into the WebSocket server. **Tradeoff of the backplane:** each update is delivered to all N servers, even
those with no subscriber for that resort. Fan-out traffic is updates × N. That is fine for N ≤ 4 and small
messages. At larger scale, bind per-resort routing keys only for resorts that have local subscribers.

## 5. Consumer threading model

- **One channel and one consumer per resort queue.** The RabbitMQ client never runs two deliveries of
  the same channel at once and keeps them in order, so each resort's swipes are applied in order. Multiple
  consumers on one queue would reorder a resort's swipes.
- All channels are dispatched on one `ThreadPoolExecutor` (the connection's consumer executor), which
  serves channels with pending work in turn (fair distribution).
- **Dynamic scaling:** every 5 s, the consumer sums the 10 queue depths. Above 500 it doubles the pool
  (up to `CONSUMER_MAX_THREADS`), below 50 it halves it (down to `CONSUMER_MIN_THREADS`). Resizing never
  changes queue ownership, so ordering is unaffected.
- **Consequence:** effective parallelism is capped at 10 (one per resort). Thread counts above 10 are
  expected to give no further gain. This is the price of per-resort ordering.

## 6. Delivery guarantees and failure handling

| Concern | Mechanism |
|---|---|
| At-least-once | Manual `basicAck` only after aggregation (and broadcast) succeeded |
| Idempotency | `processedEventIds` set; checked before and recorded after the state update. The check is race-free because one resort (and hence one eventId) is handled by one thread at a time |
| Failed processing | 3 in-place attempts, then `basicReject(requeue=false)` → `skitrak.dead` (no poison-message loop) |
| Producer channel safety | `ChannelPool`: one connection, `ArrayBlockingQueue` of 64 confirm-mode channels; borrow → publish → `waitForConfirmsOrDie` → return |
| Producer reconnection | On borrow, a closed channel is replaced; a closed connection is re-opened (synchronized, 1 s fail-fast backoff while the broker is down) |
| Consumer / backplane reconnection | Client automatic recovery (connection, channels, consumers, server-named queue + binding); redeliveries are deduped |
| Consumer health | `GET :8081/health` (503 when disconnected); systemd `Restart=always`; cron health check restarts a hung process |
| Graceful shutdown | Consumer shutdown hook closes the connection (unacked messages requeue); `ServletContextListener` closes pool and backplane |
| Server health | ALB `/ski-servlet/health`; the webapp fails to deploy without a broker, so the target turns unhealthy |

## 7. Load-balancing configuration

ALB → one target group of server-v2 instances on port 8080. Stickiness `lb_cookie`, 1 day. Idle timeout
120 s (> 60 s, so idle WebSockets survive). Health check `/ski-servlet/health`, interval 30 s, timeout 5 s,
healthy 2, unhealthy 3. WebSocket upgrades pass through the HTTP listener natively. Once upgraded, a
WebSocket stays on the target that accepted it. Because every server receives every leaderboard update
(section 4), a subscriber is served correctly no matter which target it lands on.
