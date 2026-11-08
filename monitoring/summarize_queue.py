#!/usr/bin/env python3
"""Summarizes a CSV written by queue_monitor.py into the metrics the report asks for.

Usage: python3 summarize_queue.py run1-queue.csv
Prints peak/avg total depth and avg producer (publish) / consumer (ack) rates over the
samples where messages were flowing.
"""
import csv
import sys
from collections import defaultdict


def main():
    # time -> [depth, publish/s, ack/s] summed over the 10 resort queues
    totals = defaultdict(lambda: [0, 0.0, 0.0])
    with open(sys.argv[1], newline="") as f:
        for row in csv.DictReader(f):
            if row["time"] == "time":  # header repeated when the monitor was restarted
                continue
            t = totals[row["time"]]
            t[0] += int(row["depth"])
            t[1] += float(row["publishRate"])
            t[2] += float(row["ackRate"])

    samples = [totals[k] for k in sorted(totals)]
    active = [s for s in samples if s[1] > 0 or s[2] > 0]
    if not active:
        print("No active samples found")
        return

    depths = [s[0] for s in active]
    print(f"Samples (active / total): {len(active)} / {len(samples)}")
    print(f"Peak queue depth:   {max(depths)}")
    print(f"Avg queue depth:    {sum(depths) / len(depths):.0f}")
    print(f"Avg producer rate:  {sum(s[1] for s in active) / len(active):.0f} msg/s")
    print(f"Avg consumer rate:  {sum(s[2] for s in active) / len(active):.0f} msg/s")
    print(f"Peak producer rate: {max(s[1] for s in active):.0f} msg/s")
    print(f"Peak consumer rate: {max(s[2] for s in active):.0f} msg/s")


if __name__ == "__main__":
    main()
