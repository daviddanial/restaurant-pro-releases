#!/usr/bin/env bash
# Start TeleRad DICOM SCP (separate process from the HTTP API).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/backend/target/telerad-backend-0.1.0-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
  echo "Jar missing — run ./scripts/build-backend.sh first" >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME:-/opt/java/jdk-21.0.6}"
export PATH="$JAVA_HOME/bin:$PATH"

exec java -jar "$JAR" \
  --spring.profiles.active=scp \
  --server.port=38472 \
  --spring.datasource.url=jdbc:postgresql://localhost:5433/telerad \
  --spring.datasource.username=telerad \
  --spring.datasource.password=telerad \
  --spring.rabbitmq.host=localhost \
  --spring.rabbitmq.username=telerad \
  --spring.rabbitmq.password=telerad \
  --telerad.s3.endpoint=http://localhost:9000 \
  --telerad.s3.access-key=minioadmin \
  --telerad.s3.secret-key=minioadmin \
  --telerad.scp.enabled=true \
  --telerad.scp.port=11112 \
  --telerad.scp.ae-title=TELERAD_SCP \
  "$@"
