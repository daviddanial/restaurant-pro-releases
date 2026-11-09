#!/usr/bin/env bash
# Undo scripts/vps_cutover.sh: restore the TeleRad database dump and the pre-main images.
# Only TeleRad (compose project tele2) is touched. Studies added after the cutover are lost.
set -euo pipefail
VPS=${VPS:-root@13.140.56.88}
TS=${TS:-20261017-133619}

ssh -o BatchMode=yes "$VPS" TS="$TS" 'bash -s' <<'REMOTE'
set -euo pipefail
B=/opt/health/unified/backups/tele2-pre-main-$TS
O=/opt/health/unified/deploy/overrides/uniris.vps.yml
cd /opt/health/unified/uniris
set -a; . ../deploy/.env; set +a
dc() { docker compose -p tele2 -f docker-compose.yml -f "$O" "$@"; }

dc stop api dicom-scp frontend
docker exec -i tele2-postgres-1 pg_restore -U telerad -d telerad --clean --if-exists < "$B/telerad.dump"
for s in api dicom-scp frontend; do docker tag "tele2-$s:pre-main-$TS" "tele2-$s:latest"; done
cp "$B/uniris.vps.yml" "$O"
rm -rf /opt/health/unified/uniris && tar -C /opt/health/unified -xzf "$B/uniris-src.tgz"
cd /opt/health/unified/uniris
dc up -d --no-build api dicom-scp frontend
sleep 20; curl -s -m 3 http://127.0.0.1:18080/actuator/health; echo
REMOTE
echo "rollback finished"
