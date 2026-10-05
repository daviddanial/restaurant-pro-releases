#!/usr/bin/env bash
# TeleRad PACS simulator wrapper.
#
# Loads ./config.env (if present), ensures the DICOM Python deps exist, then runs
# send-study.py. Command-line flags always win: a flag you pass is never overridden
# by config.env or by the environment.
#
#   ./send-study.sh --study /path/to/study.zip --dry-run
#   ./send-study.sh --study /path/to/study.zip --limit 20 -v
#   ./send-study.sh --study /path/to/study.zip --called-ae TELERAD --calling-ae DEMOHOSP_PACS
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

# Environment provides defaults; config.env overrides the environment.
HOST="${PACS_HOST:-127.0.0.1}"
PORT="${PACS_PORT:-11112}"
CALLED_AE="${PACS_CALLED_AE:-TELERAD}"
CALLING_AE="${PACS_CALLING_AE:-DEMOHOSP_PACS}"

if [[ -f "$HERE/config.env" ]]; then
  # shellcheck disable=SC1091
  set -a; . "$HERE/config.env"; set +a
  HOST="${PACS_HOST:-$HOST}"
  PORT="${PACS_PORT:-$PORT}"
  CALLED_AE="${PACS_CALLED_AE:-$CALLED_AE}"
  CALLING_AE="${PACS_CALLING_AE:-$CALLING_AE}"
fi

PY="${PYTHON:-python3}"
if ! "$PY" -c 'import pydicom, pynetdicom' >/dev/null 2>&1; then
  echo "Installing DICOM dependencies from scripts/requirements-dicom.txt ..." >&2
  "$PY" -m pip install -q -r "$ROOT/scripts/requirements-dicom.txt"
fi

args=("$@")
extra=()
contains() {
  local needle="$1"; shift
  local a
  for a in "$@"; do [[ "$a" == "$needle" ]] && return 0; done
  return 1
}

contains --host "${args[@]}"      || extra+=(--host "$HOST")
contains --port "${args[@]}"      || extra+=(--port "$PORT")
contains --called-ae "${args[@]}" || extra+=(--called-ae "$CALLED_AE")
contains --calling-ae "${args[@]}"|| extra+=(--calling-ae "$CALLING_AE")

exec "$PY" "$HERE/send-study.py" "${args[@]}" "${extra[@]}"
