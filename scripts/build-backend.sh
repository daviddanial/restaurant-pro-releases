#!/usr/bin/env bash
# Build the Spring Boot fat jar with JDK 21+.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  for candidate in /opt/java/jdk-21.0.6 /opt/java/jdk-21* /usr/lib/jvm/java-21-openjdk-amd64; do
    # shellcheck disable=SC2086
    for path in $candidate; do
      if [[ -x "${path}/bin/java" ]]; then
        export JAVA_HOME="$path"
        break 2
      fi
    done
  done
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "ERROR: JAVA_HOME must point to JDK 21+. Default system Java is often 8/11 and breaks the Spring Boot plugin." >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"
java -version
cd "$ROOT/backend"
mvn -s "$ROOT/.mvn-settings.xml" -B -DskipTests package "$@"
ls -lh target/telerad-backend-*.jar
echo "OK: fat jar ready (expect ~80MB+, not ~140KB)"
