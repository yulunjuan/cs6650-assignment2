# AI Usage Reflection — Assignment 2

> DRAFT. The factual log (section 1) was drafted by the AI assistant from the session itself.
> Sections marked TODO must be written by me. They are about my own judgment, and the rubric
> grades honesty and insight. Delete this note before submitting.

## 1. Where and for what I used AI

Tool: Claude Code (Claude Opus 5.5) in VS Code.

| Area | What the AI did |
|---|---|
| Spec intake | Saved the spec and my working rules (minimal v1, English comments, Chinese design notes, no push) |
| `server-v2` | Wrote `ChannelPool` (borrow/return, lazy channel replacement, reconnect with 1 s backoff), `SwipePublisher` (publisher confirms), `Topology`, lift-vertical lookup, rewritten `SkierServlet`, `BackplaneListener`, real `LeaderboardEndpoint`, `AppLifecycle` |
| `consumer` | Wrote the whole app: per-resort channels, resizable thread pool, `Aggregator`/`TopN`, eventId dedupe, retry → dead-letter, load monitor with depth-based scaling, health endpoint, shutdown hook |
| Connection locality | Proposed the RabbitMQ fanout backplane and argued against co-location |
| Deployment / monitoring | RabbitMQ Docker install, Tomcat `setenv.sh`, deploy scripts, systemd unit + health-check cron, ALB AWS CLI script, queue/system monitor scripts |
| Client | Added total-swipes / run-label arguments, a drain wait, and printing the last leaderboard per resort |
| Docs | `ARCHITECTURE.md` and a Chinese design-notes document (26 decisions with alternatives) |
| Starter repo | Searched the course repo and its full history for the lift → vertical table. It is not there, so the table is an even 60–400 ft spread |

Transcript: TODO — export the chat into `transcripts/` or add a share link.

## 2. Suggestions I accepted (at least two) — and why

TODO (my own words). Candidates to evaluate, not conclusions:
- Backplane over co-location (ARCHITECTURE §4): do I agree with the tradeoff?
- `x-overflow reject-publish` instead of the default drop-head, so a full queue never loses a swipe that already got a 201.
- Synchronous publisher confirms for "no swipe loss", at a throughput cost.

## 3. Suggestions I rejected or had to correct (at least two) — and why

TODO. Fill in from what actually happened during review, deployment and tuning
(e.g. a setting changed after measurement, a bug found on EC2, a default I overrode).

## 4. Something the AI got wrong, or a decision I reasoned through myself

TODO. Possible topics: whether 20/40/80 consumer threads can help when each resort queue has exactly one
consumer (measured result?); whether dedupe should mark the eventId before or after the state update;
the claim that the lift table was in the starter repo when it was not.
