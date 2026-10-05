#!/usr/bin/env bash
# Portability check — run Stage 1 against whichever DICOM endpoint the profile names.
#
# The point of this script is that the *only* thing that changes between two
# endpoints (the local TeleRad SCP and a throwaway mock, or a real hospital PACS) is
# the profile file it is handed. The commands are identical.
#
#   # against the real/mock endpoint named by the console profile
#   scripts/portability/portability_check.sh --profile /home/hermes/.telerad-acceptance.env
#
#   # against the mock, with only the endpoint changed
#   scripts/portability/portability_check.sh --profile /tmp/mock.env
#
#   # real PACS: connection + C-ECHO only, store nothing (never spend a fresh fixture)
#   scripts/portability/portability_check.sh --profile /home/hermes/.telerad-acceptance.env --dry-run-only
#
# Profile format is the same KEY=VALUE file the console uses (profile.example.env):
# PACS_HOST, PACS_PORT, PACS_CALLED_AE, PACS_CALLING_AE, plus optional FIXTURES_DIR,
# SENDER, PYTHON. Real environment variables win over the profile file.
#
# Exit 0 only when every requested step passed. A dry run is PASS when it proves the
# association + C-ECHO; a limited send is PASS when every selected instance was stored
# with zero failures/warnings (warnings are reported, never a silent green).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"

PROFILE="${TELERAD_ACCEPTANCE_ENV:-}"
FIXTURE=""
LIMIT=5
DRY_RUN_ONLY=0
JSON_OUT=""
VERBOSE=0

usage() {
  # print the header comment block only (up to, not including, `set -euo pipefail`)
  sed -n '2,/^set -euo pipefail$/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  cat <<'EOF'

Options:
  --profile FILE     profile to read (default: $TELERAD_ACCEPTANCE_ENV, then
                     scripts/acceptance-console/profile.env,
                     then /home/hermes/.telerad-acceptance.env)
  --fixture PATH     study .zip to use (default: smallest non-source fixture found)
  --limit N          instances to store in the limited send (default 5)
  --dry-run-only     only the connection+C-ECHO step; store nothing (use for real PACS)
  --json-out FILE    write a machine-readable result
  -v, --verbose      per-instance logging
  -h, --help         this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile) PROFILE="$2"; shift 2 ;;
    --fixture) FIXTURE="$2"; shift 2 ;;
    --limit) LIMIT="$2"; shift 2 ;;
    --dry-run-only) DRY_RUN_ONLY=1; shift ;;
    --json-out) JSON_OUT="$2"; shift 2 ;;
    -v|--verbose) VERBOSE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ -z "$PROFILE" ]]; then
  if [[ -f "$REPO/scripts/acceptance-console/profile.env" ]]; then
    PROFILE="$REPO/scripts/acceptance-console/profile.env"
  elif [[ -f /home/hermes/.telerad-acceptance.env ]]; then
    PROFILE=/home/hermes/.telerad-acceptance.env
  fi
fi

# Load KEY=VALUE without shell-evaluating it: values may legitimately contain spaces
# (TELERAD_HOSPITAL_NAME) and we never want a profile to execute code. Existing
# environment variables are left untouched so a caller can override one value.
load_profile() {
  local file="$1" line key val
  [[ -f "$file" ]] || return 0
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line// /}" || "$line" == \#* || "$line" != *=* ]] && continue
    key="${line%%=*}"
    val="${line#*=}"
    key="${key#"${key%%[![:space:]]*}"}"; key="${key%"${key##*[![:space:]]}"}"
    val="${val#"${val%%[![:space:]]*}"}"; val="${val%"${val##*[![:space:]]}"}"
    val="${val%\"}"; val="${val#\"}"; val="${val%\'}"; val="${val#\'}"
    if [[ -n "$key" && -z "${!key+x}" ]]; then
      export "$key=$val"
    fi
  done < "$file"
  return 0
}

if [[ -n "$PROFILE" ]]; then
  [[ -f "$PROFILE" ]] || { echo "profile not found: $PROFILE" >&2; exit 2; }
  load_profile "$PROFILE"
fi

PYTHON="${PYTHON:-python3}"
SENDER="${SENDER:-$REPO/scripts/pacs-simulator/send-study.py}"
FIXTURES_DIR="${FIXTURES_DIR:-/home/hermes/dicom-fixtures}"
PACS_HOST="${PACS_HOST:-127.0.0.1}"
PACS_PORT="${PACS_PORT:-11112}"
PACS_CALLED_AE="${PACS_CALLED_AE:-TELERAD}"
PACS_CALLING_AE="${PACS_CALLING_AE:-DEMOHOSP_PACS}"

for v in PACS_HOST PACS_PORT PACS_CALLED_AE PACS_CALLING_AE; do
  if [[ -z "${!v:-}" ]]; then echo "profile is missing required $v" >&2; exit 2; fi
done
[[ -f "$SENDER" ]] || { echo "sender not found: $SENDER" >&2; exit 2; }

find_fixture() {
  local dir="$1" cand
  for cand in "$dir/J/j-24.zip" "$dir/S/s-24.zip"; do
    [[ -f "$cand" ]] && { printf '%s\n' "$cand"; return 0; }
  done
  # smallest archive that is not the identifiable original or scratch space
  find "$dir" -name '*.zip' -not -path '*/source/*' -not -path '*/work/*' \
       -printf '%s %p\n' 2>/dev/null | sort -n | head -1 | cut -d' ' -f2-
}

if [[ -z "$FIXTURE" ]]; then
  FIXTURE="$(find_fixture "$FIXTURES_DIR" || true)"
fi
[[ -n "$FIXTURE" && -f "$FIXTURE" ]] || {
  echo "no fixture found under $FIXTURES_DIR (use --fixture PATH)" >&2; exit 2; }

EXTRA=()
[[ "$VERBOSE" == 1 ]] && EXTRA+=(-v)

step_dry_run() {
  echo
  echo "== Stage-1 dry run: association + C-ECHO, stores nothing =="
  "$PYTHON" "$SENDER" "$FIXTURE" \
    --host "$PACS_HOST" --port "$PACS_PORT" \
    --called-ae "$PACS_CALLED_AE" --calling-ae "$PACS_CALLING_AE" \
    --dry-run "${EXTRA[@]}"
}

step_send() {
  echo
  echo "== Stage-1 limited send: first $LIMIT instance(s) =="
  "$PYTHON" "$SENDER" "$FIXTURE" \
    --host "$PACS_HOST" --port "$PACS_PORT" \
    --called-ae "$PACS_CALLED_AE" --calling-ae "$PACS_CALLING_AE" \
    --limit "$LIMIT" "${EXTRA[@]}"
}

echo "=================================================================="
echo " PORTABILITY CHECK"
echo "=================================================================="
if [[ -n "$PROFILE" ]]; then echo "  profile      : $PROFILE"; else echo "  profile      : (environment only)"; fi
echo "  endpoint     : $PACS_CALLED_AE@$PACS_HOST:$PACS_PORT (calling AE $PACS_CALLING_AE)"
echo "  fixture      : $FIXTURE"
echo "  mode         : $([[ "$DRY_RUN_ONLY" == 1 ]] && echo 'dry-run only (stores nothing)' || echo "dry-run + limited send (limit $LIMIT)")"
echo "  python/sender: $PYTHON $SENDER"
echo "=================================================================="

dry_code=0
step_dry_run || dry_code=$?

send_code=0
if [[ "$DRY_RUN_ONLY" != 1 ]]; then
  step_send || send_code=$?
fi

echo
echo "=================================================================="
echo " RESULT"
echo "=================================================================="
echo "  dry run (assoc + C-ECHO) : $([[ "$dry_code" == 0 ]] && echo PASS || echo "FAIL (exit $dry_code)")"
if [[ "$DRY_RUN_ONLY" == 1 ]]; then
  echo "  limited send             : SKIPPED (--dry-run-only)"
else
  echo "  limited send             : $([[ "$send_code" == 0 ]] && echo PASS || echo "FAIL (exit $send_code)")"
fi

if [[ -n "$JSON_OUT" ]]; then
  overall=FAIL; [[ "$dry_code" == 0 && "$send_code" == 0 ]] && overall=PASS
  "$PYTHON" - "$JSON_OUT" "$PROFILE" "$FIXTURE" "$PACS_HOST" "$PACS_PORT" \
      "$PACS_CALLED_AE" "$PACS_CALLING_AE" "$LIMIT" "$DRY_RUN_ONLY" "$dry_code" \
      "$send_code" "$overall" <<'PY'
import json, sys
(out, profile, fixture, host, port, called, calling, limit, dry_only,
 dry_code, send_code, overall) = sys.argv[1:]
json.dump({
    "profile": profile or None, "fixture": fixture,
    "endpoint": {"host": host, "port": int(port), "called_ae": called, "calling_ae": calling},
    "limit": int(limit), "dry_run_only": dry_only == "1",
    "dry_run_exit": int(dry_code), "limited_send_exit": int(send_code),
    "verdict": overall,
}, open(out, "w"), indent=2)
print(f"  json                     : {out}")
PY
fi

overall=FAIL
[[ "$dry_code" == 0 && "$send_code" == 0 ]] && overall=PASS
echo "  VERDICT                  : $overall"
echo "=================================================================="
[[ "$overall" == PASS ]] || exit 1
