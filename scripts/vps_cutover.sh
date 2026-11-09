#!/usr/bin/env bash
# Switch TeleRad on the VPS (compose project tele2) from the old fork to main.
# Prepared beforehand: backup in /opt/health/unified/backups/tele2-pre-main-20261017-133619,
# new images tele2-api / tele2-dicom-scp / tele2-frontend :latest already built on the VPS.
# Downtime ~2 min for TeleRad only. Rollback: scripts/vps_rollback.sh
set -euo pipefail
VPS=${VPS:-root@13.140.56.88}

ssh -o BatchMode=yes "$VPS" 'bash -s' <<'REMOTE'
set -euo pipefail
O=/opt/health/unified/deploy/overrides/uniris.vps.yml
cd /opt/health/unified/uniris
set -a; . ../deploy/.env; set +a
dc() { docker compose -p tele2 -f docker-compose.yml -f "$O" "$@"; }

echo "== 1/4 share-link URL in the VPS override"
if ! grep -q TELERAD_SHARE_PUBLIC_BASE_URL "$O"; then
  python3 - "$O" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
a = "      TELERAD_SCP_ENABLED: ${TELERAD_SCP_ENABLED:-false}\n"
assert s.count(a) == 1, "anchor line not found"
s = s.replace(a, a + "      # Patient-share links printed on slips (main branch feature).\n"
                     "      TELERAD_SHARE_PUBLIC_BASE_URL: https://${TELERAD_HOST}\n")
open(p, "w").write(s)
PY
fi
grep -n "SHARE_PUBLIC" "$O"

echo "== 2/4 stop old api + dicom-scp"
dc stop api dicom-scp

echo "== 3/4 align migration history with main (one transaction)"
docker exec -i tele2-postgres-1 psql -v ON_ERROR_STOP=1 -U telerad -d telerad <<'SQL'
BEGIN;
-- fork-only trial migrations (their columns stay, nullable and unused)
DELETE FROM flyway_schema_history WHERE version = '6' AND description = 'order feedback availability';
DELETE FROM flyway_schema_history WHERE version = '7' AND description = 'service user';
-- fork V8/V9 files are byte-identical to main V6/V7
UPDATE flyway_schema_history SET version = '6', script = 'V6__instance_image_plane.sql'
 WHERE version = '8' AND description = 'instance image plane';
UPDATE flyway_schema_history SET version = '7', script = 'V7__export_jobs.sql'
 WHERE version = '9' AND description = 'export jobs';
-- trial integration admin account with the well-known default password
UPDATE users SET active = false WHERE id = '00000000-0000-0000-0000-000000000001';
SELECT version, description, checksum FROM flyway_schema_history ORDER BY version::int;
COMMIT;
SQL

echo "== 4/4 start main"
dc up -d --no-build api dicom-scp frontend
for i in $(seq 1 60); do
  if curl -sf -m 3 http://127.0.0.1:18080/actuator/health >/dev/null; then echo "api healthy after ${i}x3s"; break; fi
  sleep 3
done
curl -s -m 3 http://127.0.0.1:18080/actuator/health; echo
docker exec tele2-postgres-1 psql -U telerad -d telerad -Atc "select max(version::int) from flyway_schema_history"
docker logs --since 5m tele2-api-1 2>&1 | grep -E " ERROR |Migrating schema|Started Telerad" | cut -c1-200 | tail -12
REMOTE
echo "cutover script finished"
