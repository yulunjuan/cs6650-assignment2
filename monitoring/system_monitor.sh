#!/bin/bash
# Run on any EC2 instance during a test: logs CPU, memory and network every 5 seconds.
# Usage: ./system_monitor.sh [out-prefix]   (stop with Ctrl+C)
PREFIX=${1:-$(hostname)}

# CPU (us/sy/id/wa) and memory (free/cache), with timestamps
vmstat -t 5 > "${PREFIX}-vmstat.log" &
VMSTAT_PID=$!
trap 'kill $VMSTAT_PID; exit' INT TERM

# Network: cumulative bytes received/sent on the primary interface
IFACE=$(ip route | awk '/default/ {print $5; exit}')
echo "time,rxBytes,txBytes" > "${PREFIX}-net.csv"
while true; do
  echo "$(date -Iseconds),$(cat /sys/class/net/$IFACE/statistics/rx_bytes),$(cat /sys/class/net/$IFACE/statistics/tx_bytes)" >> "${PREFIX}-net.csv"
  sleep 5
done
