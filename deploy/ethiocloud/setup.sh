#!/usr/bin/env bash
# One-time setup on the Ethio cloud server: creates /opt/telerad/.env with fresh secrets.
# Run on the server from /opt/telerad:  sudo bash setup.sh <public-ip>
set -euo pipefail
cd "$(dirname "$0")"
IP=${1:?public IP}
if [ -f .env ]; then echo ".env exists, leaving it alone"; exit 0; fi
rnd() { openssl rand -hex "$1"; }
umask 077
cat > .env <<EOF
# Generated $(date -u +%FT%TZ). Keep private.
TELERAD_HOST=telerad.${IP}.sslip.io
TELERAD_DB_USER=telerad
TELERAD_DB_PASSWORD=$(rnd 24)
TELERAD_MINIO_USER=telerad
TELERAD_MINIO_PASSWORD=$(rnd 24)
TELERAD_RABBIT_USER=telerad
TELERAD_RABBIT_PASSWORD=$(rnd 24)
TELERAD_JWT_SECRET=$(rnd 48)
TELERAD_SERVICE_TOKEN=$(rnd 32)
TELERAD_EHR_WEBHOOK_SECRET=$(rnd 32)
EOF
echo "wrote .env for telerad.${IP}.sslip.io"
