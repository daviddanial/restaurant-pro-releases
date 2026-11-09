#!/usr/bin/env bash
# Create gzip copies for DICOM objects stored before the gzip feature (POST /api/admin/storage/gzip-backfill).
# Runs on the VPS against the local API (no traffic over the slow link), 2 worker threads to spare the
# shared VPS. Needs a TeleRad ADMIN login:
#   ADMIN_EMAIL=you@x ADMIN_PASSWORD='...' bash scripts/vps_gzip_backfill.sh
set -euo pipefail
VPS=${VPS:-root@13.140.56.88}
: "${ADMIN_EMAIL:?set ADMIN_EMAIL}" "${ADMIN_PASSWORD:?set ADMIN_PASSWORD}"
BODY=$(python3 -c 'import json,os;print(json.dumps({"email":os.environ["ADMIN_EMAIL"],"password":os.environ["ADMIN_PASSWORD"]}))')

# The remote script arrives on stdin (bash -s), so the login body never shows up in a process list.
ssh -o BatchMode=yes "$VPS" 'bash -s' <<REMOTE
set -euo pipefail
API=http://127.0.0.1:18080
TOKEN=\$(curl -sf "\$API/api/auth/login" -H 'Content-Type: application/json' --data-binary @- <<'JSON' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])'
$BODY
JSON
)
while :; do
  R=\$(curl -sf -X POST -H "Authorization: Bearer \$TOKEN" "\$API/api/admin/storage/gzip-backfill?limit=1000&threads=2")
  echo "\$R"
  [ "\$(echo "\$R" | python3 -c 'import sys,json;print(json.load(sys.stdin)["remaining"])')" = 0 ] && break
done
docker exec tele2-postgres-1 psql -U telerad -d telerad -Atc \
  "select transfer_syntax_uid, count(*), count(gzip_storage_key) from instances group by 1 order by 2 desc"
REMOTE
