#!/usr/bin/env bash
# loadtest/proxyctl.sh <rate_mbps> <latency_ms>   (restarts the shaping proxy; 0 0 = unlimited)
cd "$(dirname "$0")"
if [[ -f logs/proxy.pid ]] && kill -0 "$(cat logs/proxy.pid)" 2>/dev/null; then kill "$(cat logs/proxy.pid)"; sleep 0.5; fi
RATE_MBPS=$1 LATENCY_MS=$2 nohup node proxy.mjs > logs/proxy.log 2>&1 &
echo $! > logs/proxy.pid
sleep 0.8; cat logs/proxy.log
