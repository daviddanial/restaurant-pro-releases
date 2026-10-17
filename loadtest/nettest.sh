#!/usr/bin/env bash
# Network check: this computer's internet connection -> TeleRad VPS (and reference servers).
# Read-only; downloads ~10-30 MB in total. Run on each connection you want to compare
# (Ethio Telecom, Safaricom hotspot, hospital link):   bash loadtest/nettest.sh
# Output also goes to loadtest/nettest-<isp>-<time>.txt
VPS=${VPS:-https://telerad.13.140.56.88.sslip.io}
FILE=$(curl -s -m 30 "$VPS/" | grep -o 'assets/[^"]*\.js' | head -1)
mbps() { awk '{printf "%.2f Mbps", $1*8/1e6}'; }

info=$(curl -s -m 10 https://ipinfo.io/json)
isp=$(echo "$info" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("org","unknown"))' 2>/dev/null)
out="$(dirname "$0")/nettest-$(echo "$isp" | tr -cs 'A-Za-z0-9' '_' | cut -c1-30)-$(date +%Y%m%d-%H%M).txt"
{
echo "== connection: $isp, $(echo "$info" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("city"),d.get("country"))' 2>/dev/null), $(date)"
echo "== round trip to the VPS (TCP connect, 5 tries)"
for i in 1 2 3 4 5; do curl -s -o /dev/null -m 20 -w "%{time_connect}\n" "$VPS/"; done | awk '{s+=$1; if($1>m)m=$1} END {printf "avg %.0f ms, worst %.0f ms\n", s/NR*1000, m*1000}'
echo "== download from the VPS, one connection (3 x $(basename "$FILE"))"
for i in 1 2 3; do curl -s -o /dev/null -m 60 -w "%{speed_download}\n" "$VPS/$FILE?n=$RANDOM"; done | while read s; do echo "$s" | mbps; echo; done
echo "== download from the VPS, 8 connections at once"
s=$(date +%s.%N); size=0
for i in $(seq 1 8); do curl -s -o /dev/null -m 90 "$VPS/$FILE?p=$i$RANDOM" & done; wait
e=$(date +%s.%N); bytes=$(curl -s -o /dev/null -w "%{size_download}" "$VPS/$FILE?s=1")
echo "$(echo "8*$bytes/($e-$s)" | bc -l)" | mbps; echo
echo "== reference servers (15 s max each)"
for u in "https://speed.cloudflare.com/__down?bytes=25000000 Cloudflare(nearby)" \
         "https://fsn1-speed.hetzner.com/100MB.bin Germany" \
         "https://ash-speed.hetzner.com/100MB.bin USA"; do
  set -- $u
  printf "%-18s " "$2"; curl -s -o /dev/null -m 15 -w "%{time_connect} %{speed_download}\n" "$1" | awk '{printf "connect %.0f ms, %.2f Mbps\n", $1*1000, $2*8/1e6}'
done
echo "== route to the VPS"
if command -v mtr >/dev/null; then mtr -n -r -c 20 -w 13.140.56.88; else tracepath -n -m 20 13.140.56.88 || traceroute -n -m 20 13.140.56.88; fi 2>&1 | head -25
} 2>&1 | tee "$out"
echo; echo "saved: $out"
