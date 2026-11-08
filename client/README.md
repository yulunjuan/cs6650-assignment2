# Client (from Assignment 1 Part 2)

Usage: `java -jar client.jar <baseUrl> [mainThreads] [totalSwipes] [runLabel]`

- `baseUrl` — e.g. `http://<alb-dns>/ski-servlet` (or a single server's `http://<ip>:8080/ski-servlet`)
- `mainThreads` — main-phase sender threads (default 200; test 64, 128, 256, 512)
- `totalSwipes` — default 500000; use 1000000 for the stress test
- `runLabel` — prefix for `../results/<runLabel>-latency.csv` and `../results/<runLabel>-throughput.csv`

After sending, it waits 10 s so subscribers receive the final standings, then prints the ingest
metrics, per-resort update counts and the last leaderboard each subscriber received.
