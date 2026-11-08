# SkiTrak Assignment 2 — Test Results

All runs are in AWS us-west-2. The client runs on its own EC2 instance in the same VPC and targets
servers by private IP (or the ALB DNS). Full outputs and CSVs are in `results/`.

| Component | Instance |
|---|---|
| RabbitMQ 3.13.7 (Docker) | t3.small |
| server-v2 (Tomcat 9), channel pool 64 | t3.small, ×1 / ×2 / ×4 |
| Consumer, 2–10 threads (depth-based scaling), prefetch 100 | t3.small |
| Client | separate EC2 instance |

## 1. Single ingest server — 500K swipes

Command: `java -jar client.jar http://172.31.41.21:8080/ski-servlet 256 500000 single-500k`
(output: `results/single-500k-output.txt`; CSVs: `results/single-500k-*.csv`)

### Client metrics

| Metric | Value |
|---|---|
| Successful / failed swipes | 500,000 / 0 |
| Retries / connection errors | 0 / 0 |
| Wall time | 101.1 s |
| Overall throughput | **4,944 swipes/s** |
| Main phase (256 threads) | 5,262 swipes/s |
| Warmup phase (32 threads) | 2,627 swipes/s |
| Ingest latency mean / median / p95 / p99 / max | 45 / 25 / 137 / 220 / 2,234 ms |
| Single-thread RTT | 9.4 ms |
| Per-resort split | 491–499 swipes/s each (even) |

Throughput per 10 s window was flat at about 4.8–5.6k swipes/s after warmup (`single-500k-throughput.csv`):

```
t(s):   0     10    20    30    40    50    60    70    80    90
k/s:   2.5   4.8   5.4   4.8   5.6   5.4   5.4   5.4   5.4   4.9
```

### Queue metrics (RabbitMQ console, `results/screenshots/single-500k-rabbitmq-overview.png`)

| Metric | Value |
|---|---|
| Queue depth during the run | ~30–40 messages total (stable plateau), back to 0 at the end |
| Peak depth | ~40 (target < 1000 ✔) |
| Producer (publish) rate | ~5.4k msg/s |
| Consumer (ack) rate | ~5.4k msg/s, overlaps publish |
| Redelivered | 0 |

![single-500k RabbitMQ overview](results/screenshots/single-500k-rabbitmq-overview.png)

Publish and consumer-ack curves overlap for the whole run, and the queue stays at a low plateau instead
of a sawtooth: the consumer keeps up with a single server.

### Leaderboard evidence

All 10 WebSocket subscribers stayed connected (0 reconnections) and received **1,353 real leaderboard
updates** (130–140 per resort). Push latency (consumer → backplane → server → client) was mean 9 ms,
median 4 ms, p95 32 ms, p99 118 ms. The final standings are swipe-driven totals. Example, resort 1:

```
{"resortID":"1","dayID":"1","serverTimestamp":"2026-11-08T16:37:02.835957402Z","top":[
 {"rank":1,"skierID":"94506","vertical":1434},{"rank":2,"skierID":"10517","vertical":1391},
 {"rank":3,"skierID":"29384","vertical":1374}, ... ,{"rank":10,"skierID":"95723","vertical":1294}]}
```

Totals of about 1,300–1,750 ft for the leaders match ~5 rides per top skier (50k swipes over 100k
skierIDs per resort, 60–400 ft per ride).

### Analysis

- **The pipeline is not the bottleneck at this load.** The queue stays near empty and consumer rate equals
  publish rate, so the broker and consumer have headroom.
- **The single ingest server is the limit.** Little's Law with the 9.4 ms single-thread RTT predicts
  ~27k req/s for 256 threads, but only 5.3k req/s was reached, and mean latency grew from 9 ms to 45 ms.
  Requests queue inside the server. Likely causes, each publish holds a pooled channel until the broker
  confirms the persistent message (synchronous confirms, 64 channels), and Tomcat's 200 worker
  threads on a 2-vCPU t3.small. Adding servers behind the ALB (sections 2–3) tests this directly.
- Smoke test from a laptop (`results/smoke-output.txt`, 157 swipes/s at 142 ms RTT) was client-bound
  and is not used for comparison.

## 2. Load balanced — 2 and 4 instances

Not run before the deadline. The ALB setup is scripted in `deployment/alb/create-alb.sh` (target group
health check `/ski-servlet/health` 30s/5s/2/3, `lb_cookie` stickiness, idle timeout 120 s), and the
backplane means any server can hold any subscriber. Based on section 1, I expect throughput to scale
with the number of ingest servers until the broker (persistent messages + confirms) or the 10-way
consumer parallelism becomes the limit. This is not measured.
