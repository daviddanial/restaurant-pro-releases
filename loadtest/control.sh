#!/usr/bin/env bash
# Control runs without browsers: separates server limits from load-generator CPU contention.
cd "$(dirname "$0")/.."
ctl() {  # label rate latency readers
  loadtest/proxyctl.sh "$2" "$3" >/dev/null
  docker restart teleradiology-minio-1 >/dev/null; until curl -sf -m 2 http://127.0.0.1:9000/minio/health/ready >/dev/null; do sleep 1; done; sleep 2
  node loadtest/monitor.mjs --out "loadtest/results/$1.monitor.jsonl" & MON=$!; sleep 2
  node loadtest/reader.mjs --readers "$4" --modality CT --label "$1"
  sleep 2; kill $MON; wait $MON 2>/dev/null
}
ctl ctrl-ct-1-50mbps-simonly 50 50 1
ctl ctrl-ct-10-50mbps-simonly 50 50 10
ctl ctrl-ct-20-50mbps-simonly 50 50 20
ctl ctrl-ct-10-unl-simonly 0 0 10
ctl ctrl-ct-20-unl-simonly 0 0 20
echo CONTROL DONE
