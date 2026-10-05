#!/usr/bin/env bash
# Convenience wrapper: ensure DICOM Python deps, then run modality_send.py
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if ! python3 -c 'import pydicom, pynetdicom' 2>/dev/null; then
  echo "Installing scripts/requirements-dicom.txt (user)…"
  pip3 install --user -q -r scripts/requirements-dicom.txt
fi

exec python3 scripts/modality_send.py "$@"
