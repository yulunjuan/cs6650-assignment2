#!/usr/bin/env python3
"""Polls the RabbitMQ management API and appends queue metrics to a CSV.

Usage: python3 queue_monitor.py <rabbit-host> <user> <password> [interval_sec] [out.csv]
Columns: time, queue, depth (ready + unacked), ready, unacked, publish/s, deliver/s, ack/s
"""
import base64
import csv
import json
import sys
import time
import urllib.request
from datetime import datetime


def fetch_queues(host, user, password):
    req = urllib.request.Request(f"http://{host}:15672/api/queues/%2F")
    token = base64.b64encode(f"{user}:{password}".encode()).decode()
    req.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(req, timeout=5) as resp:
        return json.load(resp)


def rate(queue, key):
    return queue.get("message_stats", {}).get(f"{key}_details", {}).get("rate", 0.0)


def main():
    host, user, password = sys.argv[1:4]
    interval = int(sys.argv[4]) if len(sys.argv) > 4 else 5
    out = sys.argv[5] if len(sys.argv) > 5 else "queue-metrics.csv"

    with open(out, "a", newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["time", "queue", "depth", "ready", "unacked",
                         "publishRate", "deliverRate", "ackRate"])
        while True:
            now = datetime.now().isoformat(timespec="seconds")
            total = 0
            try:
                for q in fetch_queues(host, user, password):
                    if not q["name"].startswith("resort."):
                        continue
                    total += q.get("messages", 0)
                    writer.writerow([now, q["name"], q.get("messages", 0),
                                     q.get("messages_ready", 0), q.get("messages_unacknowledged", 0),
                                     rate(q, "publish"), rate(q, "deliver_get"), rate(q, "ack")])
                f.flush()
                print(f"{now} total depth={total}")
            except Exception as e:
                print(f"{now} poll failed: {e}")
            time.sleep(interval)


if __name__ == "__main__":
    main()
