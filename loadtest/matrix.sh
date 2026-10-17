#!/usr/bin/env bash
# Full scenario matrix (CT), then MR/CR spot checks. Logs to loadtest/logs/matrix.log
cd "$(dirname "$0")/.."
run() {  # skip scenarios that already have both simulated and browser results
  local net=$([[ $3 == 0 ]] && echo unl || echo "${3}mbps")
  local label=$(echo "$1-$2-$net-$5" | tr 'A-Z' 'a-z')
  if [[ -f loadtest/results/$label.json && -f loadtest/results/$label.browser.json ]] && ! grep -q '"fatal"' loadtest/results/$label.browser.json; then
    echo "skip $label (done)"; return; fi
  loadtest/run.sh "$@"
}
for N in 1 5 10 20; do
  for NET in "0 0" "50 50" "20 50"; do
    run CT $N $NET cold
    run CT $N $NET warm
  done
done
for MOD in MR CR; do
  for N in 10 20; do run $MOD $N 50 50 cold; done
done
# HTTP cache: 2 browsers read a CT, then reopen it in the same (persistent) profile.
if [[ ! -f loadtest/results/reopen-ct-2-50mbps.browser.json ]]; then
  loadtest/proxyctl.sh 50 50 >/dev/null
  PLAYWRIGHT_BROWSERS_PATH=loadtest/.browsers node loadtest/browser.mjs --readers 1,2 --modality CT --label reopen-ct-2-50mbps --reopen 1
fi
echo MATRIX DONE
