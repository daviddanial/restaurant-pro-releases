#!/usr/bin/env bash
# Run one scenario: loadtest/run.sh <modality> <readers> <rate_mbps> <latency_ms> <cold|warm> [label]
# Readers 1..N each open their own study. For N>=5 the last 2 readers are real headless browsers
# running the TeleRad viewer; the rest are simulated readers. N=1 runs a simulated reader, then a browser reader.
# cold = MinIO restarted right before the run (OS page cache cannot be dropped without root).
set -uo pipefail
cd "$(dirname "$0")/.."
MOD=$1 N=$2 RATE=$3 LAT=$4 TEMP=$5
NET=$([[ $RATE == 0 ]] && echo unl || echo "${RATE}mbps")
LABEL=${6:-$(echo "$MOD-$N-$NET-$TEMP" | tr 'A-Z' 'a-z')}
export PLAYWRIGHT_BROWSERS_PATH=loadtest/.browsers

loadtest/proxyctl.sh "$RATE" "$LAT" >/dev/null
if [[ $TEMP == cold ]]; then
  docker restart teleradiology-minio-1 >/dev/null
  until curl -sf -m 2 http://127.0.0.1:9000/minio/health/ready >/dev/null; do sleep 1; done
  sleep 2
fi

ERR0=$(grep -cE " ERROR |OutOfMemory" loadtest/logs/backend.log)
node loadtest/monitor.mjs --out "loadtest/results/$LABEL.monitor.jsonl" & MON=$!
sleep 2  # baseline samples
START=$(date +%s)
if (( N == 1 )); then
  node loadtest/reader.mjs --readers 1 --modality "$MOD" --label "$LABEL"
  [[ $TEMP == cold ]] && { docker restart teleradiology-minio-1 >/dev/null; until curl -sf -m 2 http://127.0.0.1:9000/minio/health/ready >/dev/null; do sleep 1; done; sleep 2; }
  node loadtest/browser.mjs --readers 1 --modality "$MOD" --label "$LABEL"
else
  SIM=$(( N - 2 ))  # run 3: 2 browsers (3 x ~2 GB Chromium overran this laptop's memory once the viewer prefetches)
  node loadtest/reader.mjs --readers "$SIM" --first 1 --modality "$MOD" --label "$LABEL" & R=$!
  node loadtest/browser.mjs --readers "$((N-1)),$N" --modality "$MOD" --label "$LABEL" & B=$!
  wait $R; wait $B
fi
echo "$LABEL wall=$(( $(date +%s) - START ))s"
sleep 2
kill $MON; wait $MON 2>/dev/null
ERR1=$(grep -cE " ERROR |OutOfMemory" loadtest/logs/backend.log)
echo "$LABEL new backend ERROR lines: $((ERR1-ERR0))" | tee "loadtest/results/$LABEL.backend_errors.txt"
sed -n "$((ERR0+1)),\$p" <(grep -E " ERROR |OutOfMemory" loadtest/logs/backend.log) | cut -c1-240 | sort | uniq -c | sort -rn | head -5 >> "loadtest/results/$LABEL.backend_errors.txt"
