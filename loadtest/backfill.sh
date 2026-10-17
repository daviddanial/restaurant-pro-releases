#!/usr/bin/env bash
# Create gzip copies for already-stored objects (POST /api/admin/storage/gzip-backfill) until none remain.
# Uses the local dev seed admin (README / PasswordSeedFixer). Local environment only.
set -euo pipefail
API=${API:-http://127.0.0.1:38471}
TOKEN=$(curl -sf "$API/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@telerad.local","password":"password"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
while :; do
  R=$(curl -sf -X POST -H "Authorization: Bearer $TOKEN" "$API/api/admin/storage/gzip-backfill?limit=2000&threads=${THREADS:-4}")
  echo "$R"
  [[ $(python3 -c 'import sys,json;print(json.loads(sys.argv[1])["remaining"])' "$R") == 0 ]] && break
done
