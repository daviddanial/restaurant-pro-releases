#!/usr/bin/env bash
# Ship a locally built backend jar to the VPS and restart TeleRad's api + dicom-scp (~1 min downtime).
#   (cd backend && mvn package) && bash scripts/vps_update_api.sh
# Also syncs backend/src to the VPS checkout and makes sure the VPS override lists the public site
# for CORS. The previous image is kept as tele2-api:prev for a quick rollback.
set -euo pipefail
cd "$(dirname "$0")/.."
VPS=${VPS:-root@13.140.56.88}
JAR=backend/target/telerad-backend-0.1.0-SNAPSHOT.jar

rsync -az --delete --chown=hermes:hermes --exclude '*.bak*' backend/src/ "$VPS:/opt/health/unified/uniris/backend/src/"
rsync -a --partial "$JAR" "$VPS:/opt/health/unified/uniris-build/api/app.jar"
echo "local jar  $(md5sum "$JAR" | cut -d' ' -f1)"

ssh -o BatchMode=yes "$VPS" 'bash -s' <<'REMOTE'
set -euo pipefail
echo "remote jar $(md5sum /opt/health/unified/uniris-build/api/app.jar | cut -d' ' -f1)"
O=/opt/health/unified/deploy/overrides/uniris.vps.yml
if ! grep -q TELERAD_CORS_ORIGINS "$O"; then
  python3 - "$O" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
a = "      TELERAD_SHARE_PUBLIC_BASE_URL: https://${TELERAD_HOST}\n"
assert s.count(a) == 1, "anchor line not found"
s = s.replace(a, a + "      # Browser origin of the public site (behind Caddy); without it every login gets 403.\n"
                     "      TELERAD_CORS_ORIGINS: https://${TELERAD_HOST}\n")
open(p, "w").write(s)
PY
fi
grep -n "TELERAD_CORS_ORIGINS\|SHARE_PUBLIC" "$O"
docker tag tele2-api:latest tele2-api:prev
docker build -q -t tele2-api:latest -t tele2-dicom-scp:latest /opt/health/unified/uniris-build/api
cd /opt/health/unified/uniris
set -a; . ../deploy/.env; set +a
docker compose -p tele2 -f docker-compose.yml -f "$O" up -d --no-build api dicom-scp
for i in $(seq 1 60); do
  if curl -sf -m 3 http://127.0.0.1:18080/actuator/health >/dev/null; then echo "api healthy after ${i}x3s"; break; fi
  sleep 3
done
docker logs --since 3m tele2-api-1 2>&1 | grep -cE " ERROR " | sed 's/^/new ERROR lines: /'
REMOTE
