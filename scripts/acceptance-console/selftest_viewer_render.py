#!/usr/bin/env python3
"""P1 acceptance proof: two-state selftest for the viewer-render check.

It proves the check can tell a healthy viewer from a broken one, using the real
deployment — no service restart, no code change to the app:

  1. HEALTHY   newest READY study, real frontend            -> EXPECT PASS
  2. BROKEN    `tele2-frontend-1` assets directory moved    -> EXPECT FAIL
               (empty #root / no canvas / browser errors)
  3. RESTORE   assets moved back, verified non-empty, and
               the check run again                          -> EXPECT PASS

The move and the broken-state check live inside a ``try`` whose ``finally``
always moves the assets back and verifies them: whatever happens, the frontend
is left working, and if it cannot be, the script prints a loud
``NEEDS ATTENTION`` line rather than leaving it broken silently.

Evidence (per-step JSON + screenshots) lands in ``selftest-evidence/``.

Exit codes: 0 = all three expectations held; 1 = a state behaved unexpectedly;
            2 = could not run a required state (e.g. docker unreachable) — never
            a pass.

Run:  /opt/dicom-tools/bin/python selftest_viewer_render.py [--study-id ID]
"""
from __future__ import annotations

import argparse
import json
import os
import shlex
import subprocess
import sys
import time
from pathlib import Path

import httpx

import viewer_render

HERE = Path(__file__).resolve().parent
EVIDENCE_DIR = HERE / "selftest-evidence"
ENV_FILE = Path(os.environ.get("TELERAD_ACCEPTANCE_ENV",
                               "/home/hermes/.telerad-acceptance.env"))
FRONTEND_CONTAINER = "tele2-frontend-1"
ASSETS = "/usr/share/nginx/html/assets"
ASSETS_BAK = "/usr/share/nginx/html/assets.bak"


# ----------------------------------------------------------------- env helpers
def load_env_file(path: Path = ENV_FILE) -> None:
    """Load the console env without shell-evaluating it.

    ``set -a; . file`` breaks on the unquoted ``TELERAD_HOSPITAL_NAME=Demo
    General Hospital`` line; a simple KEY=VALUE parser does not, and it never
    executes anything. External environment always wins.
    """
    if not path.is_file():
        return
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def login_full(api_base: str) -> dict:
    """Return the FULL login JSON — the frontend stores it verbatim."""
    r = httpx.post(f"{api_base}/api/auth/login", timeout=15, json={
        "email": os.environ["TELERAD_ADMIN_EMAIL"],
        "password": os.environ["TELERAD_ADMIN_PASSWORD"],
    })
    r.raise_for_status()
    return r.json()


def newest_ready_study(api_base: str, token: str) -> dict | None:
    hid = os.environ["TELERAD_HOSPITAL_ID"]
    r = httpx.get(f"{api_base}/api/hospitals/{hid}/studies", timeout=20,
                  headers={"Authorization": f"Bearer {token}"})
    r.raise_for_status()
    rows = [s for s in r.json() if s.get("status") == "READY"]
    rows.sort(key=lambda s: (s.get("readyAt") or s.get("studyDate") or ""), reverse=True)
    return rows[0] if rows else None


# ----------------------------------------------------------------- docker
def run_docker(args: list[str]) -> dict:
    """Run ``docker ...`` via ``sg docker`` first (the documented path), then direct.

    Returns ``{ran, returncode, stdout, stderr, via}`` and never raises for a
    missing docker — the caller decides how to report it.
    """
    attempts = [
        ("sg-docker", ["sg", "docker", "-c", shlex.join(["docker"] + args)]),
        ("direct", ["docker"] + args),
    ]
    last = {"ran": False, "returncode": None, "stdout": "", "stderr": "", "via": None}
    for via, cmd in attempts:
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
        except Exception as exc:
            last = {"ran": False, "returncode": None, "stdout": "",
                    "stderr": f"{type(exc).__name__}: {exc}", "via": via}
            continue
        out = (p.stdout or "").strip()
        err = (p.stderr or "").strip()
        if p.returncode == 0:
            return {"ran": True, "returncode": 0, "stdout": out[:800],
                    "stderr": err[:400], "via": via}
        last = {"ran": False, "returncode": p.returncode, "stdout": out[:800],
                "stderr": err[:400], "via": via}
    return last


def move_assets() -> dict:
    return run_docker(["exec", FRONTEND_CONTAINER, "mv", ASSETS, ASSETS_BAK])


def restore_assets() -> dict:
    return run_docker(["exec", FRONTEND_CONTAINER, "mv", ASSETS_BAK, ASSETS])


def assets_nonempty() -> dict:
    return run_docker(["exec", FRONTEND_CONTAINER, "ls", "-A", ASSETS])


# ----------------------------------------------------------------- reporting
def dump(name: str, payload: dict) -> str:
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    path = EVIDENCE_DIR / name
    clean = json.loads(json.dumps(payload, default=str))
    path.write_text(json.dumps(clean, indent=2))
    return str(path)


def banner(text: str) -> None:
    print("\n" + "=" * 72)
    print(text)
    print("=" * 72, flush=True)


def show(label: str, result: dict) -> None:
    print(f"{label}: ok={result['ok']} ran={result['ran']} "
          f"root={result['rootChildren']} canvas={result['canvasCount']} "
          f"errors={len(result['pageErrors'])} {result['seconds']}s")
    if result.get("screenshotError"):
        print("  screenshot not saved:", result["screenshotError"])
    if result.get("reason"):
        print("  reason:", result["reason"])
    print("  screenshot:", result.get("screenshot"))


def main() -> int:
    ap = argparse.ArgumentParser(description="P1 viewer-render two-state selftest")
    ap.add_argument("--study-id", default=None,
                    help="study UUID to test (default: newest READY study)")
    ap.add_argument("--no-fallback", action="store_true",
                    help="do not simulate a broken bundle when docker is unavailable")
    args = ap.parse_args()

    load_env_file()
    api_base = os.environ.get("TELERAD_API", "http://127.0.0.1:18080")
    app_url = os.environ.get("TELERAD_APP_URL", "https://telerad.13.140.56.88.sslip.io")
    browserless_url = os.environ.get("BROWSERLESS_URL", "http://127.0.0.1:3000")
    browserless_token = os.environ.get("BROWSERLESS_TOKEN", "")
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=True)
    stamp = int(time.time())
    started = time.time()

    banner("P1 viewer-render selftest")
    print(f"app url        : {app_url}")
    print(f"browserless    : {browserless_url}")
    print(f"browserless tok: {'present' if browserless_token else 'MISSING'}")

    try:
        login = login_full(api_base)
    except Exception as exc:
        print(f"NEEDS ATTENTION: could not log in to {api_base}: {exc}")
        return 2

    if args.study_id:
        study = {"id": args.study_id, "status": "provided"}
    else:
        try:
            study = newest_ready_study(api_base, login["accessToken"])
        except Exception as exc:
            print(f"NEEDS ATTENTION: could not list studies: {exc}")
            return 2
    if not study:
        print("NEEDS ATTENTION: no READY study to test — nothing to prove the viewer on.")
        return 2
    study_id = study["id"]
    print(f"study          : {study_id} ({study.get('modality', '?')}, "
          f"{study.get('instanceCount', '?')} instances, status {study.get('status')})")

    def check(**kwargs) -> dict:
        return viewer_render.check_viewer_render(
            app_url, study_id, login, browserless_url, browserless_token,
            EVIDENCE_DIR, **kwargs)

    results: dict[str, dict] = {}
    exit_code = 0
    fallback_used = False
    assets_were_moved = False
    restore_failed = False
    assets_restored_nonempty = None

    # ------------------------------------------------------------- step 1: healthy
    banner("STEP 1/3  HEALTHY state — expect PASS")
    healthy = check()
    results["healthy"] = healthy
    show("healthy", healthy)
    dump(f"selftest-{stamp}-01-healthy.json", healthy)
    if not healthy["ran"]:
        print("NEEDS ATTENTION: the healthy check could not run at all — environment "
              "problem (browserless), not a viewer problem.")
        return 2
    if not healthy["ok"]:
        print("UNEXPECTED: the healthy viewer did not render. Stopping before touching "
              "the frontend container.")
        return 1

    # ------------------------------------------- step 2: broken (restore in finally)
    docker_probe = run_docker(["exec", FRONTEND_CONTAINER, "ls", "-A", "/usr/share/nginx/html"])
    docker_ok = bool(docker_probe.get("ran"))
    broken = None

    try:
        if docker_ok:
            banner("STEP 2/3  BROKEN state — move the frontend assets, expect FAIL")
            move = move_assets()
            print("assets move:", "ok" if move.get("ran") else "FAILED",
                  "| via", move.get("via"))
            if move.get("ran"):
                assets_were_moved = True
                broken = check()
                results["broken"] = broken
                show("broken", broken)
                if broken["ran"] and not broken["ok"]:
                    print("EXPECTATION HELD: the check failed on the broken frontend.")
                else:
                    print("UNEXPECTED: the check did not fail on a broken frontend bundle.")
                    exit_code = 1
                dump(f"selftest-{stamp}-02-broken.json",
                     {"move": move, "check": broken})
            else:
                print("move error:", move.get("stderr"))
                print("NEEDS ATTENTION: could not move the frontend assets — the canonical "
                      "broken state was NOT run.")
                dump(f"selftest-{stamp}-02-broken.json", {"move": move})
                exit_code = 2
        else:
            banner("STEP 2/3  BROKEN state — docker unreachable, canonical proof NOT run")
            print("docker error:", docker_probe.get("stderr"))
            print("NEEDS ATTENTION: docker is not reachable from this session, so the real "
                  "frontend bundle cannot be broken here. The canonical broken-state proof "
                  "was NOT run.")
            exit_code = 2
            if not args.no_fallback:
                banner("STEP 2/3 (fallback)  SIMULATED broken bundle (aborted /assets/) "
                       "— expect FAIL")
                fb = check(block_url_regex=r".*/assets/.*")
                results["broken_fallback"] = fb
                fallback_used = True
                show("fallback", fb)
                print("FALLBACK expectation (fail on broken bundle):",
                      "HELD" if (fb["ran"] and not fb["ok"]) else "DID NOT HOLD")
            dump(f"selftest-{stamp}-02-broken.json",
                 {"canonical": "not-run (docker unavailable)",
                  "docker": docker_probe,
                  "fallback": results.get("broken_fallback")})
    finally:
        if assets_were_moved:
            banner("RESTORE (finally)  put the frontend assets back")
            restored = restore_assets()
            print("restore move:", "ok" if restored.get("ran") else "FAILED",
                  "| via", restored.get("via"))
            listing = assets_nonempty()
            assets_restored_nonempty = bool(listing.get("ran") and listing.get("stdout"))
            print("assets dir non-empty:", assets_restored_nonempty,
                  "| files:", (listing.get("stdout") or "").replace("\n", ", ")[:160])
            if not restored.get("ran") or not assets_restored_nonempty:
                restore_failed = True
                print("NEEDS ATTENTION: the frontend assets could not be restored/verified — "
                      f"tele2-frontend-1 may still serve a broken UI. Restore manually with: "
                      f"docker exec {FRONTEND_CONTAINER} mv {ASSETS_BAK} {ASSETS}")
            dump(f"selftest-{stamp}-03-restore-docker.json",
                 {"restore": restored, "listing": listing})

    # ------------------------------------------------------------- step 3: restored
    banner("STEP 3/3  RESTORED state — expect PASS")
    if assets_were_moved:
        print("assets were moved and restored above.")
    else:
        print("nothing was moved (canonical broken state not run) — restore is a no-op.")
    restored_check = check()
    results["restored"] = restored_check
    show("restored", restored_check)
    if assets_were_moved and not (restored_check["ran"] and restored_check["ok"]):
        restore_failed = True
        print("UNEXPECTED: the viewer did not render again after restore.")

    # ------------------------------------------------------------------ summary
    healthy_held = healthy["ran"] and healthy["ok"]
    broken_held = bool(results.get("broken", {}).get("ran")
                       and not results["broken"].get("ok"))
    fallback_held = bool(fallback_used
                         and results.get("broken_fallback", {}).get("ran")
                         and not results["broken_fallback"].get("ok"))
    restored_held = bool(restored_check["ran"] and restored_check["ok"])

    if restore_failed:
        print("\nNEEDS ATTENTION: frontend restore is unverified — check tele2-frontend-1 now.")

    summary = {
        "study_id": study_id,
        "app_url": app_url,
        "seconds": round(time.time() - started, 2),
        "healthy_expect_pass": healthy_held,
        "broken_canonical_expect_fail": broken_held,
        "broken_fallback_expect_fail": fallback_held,
        "broken_canonical_run": bool(results.get("broken")),
        "broken_fallback_run": fallback_used,
        "restore_expect_pass": restored_held if assets_were_moved else None,
        "assets_were_moved": assets_were_moved,
        "assets_restored_nonempty": assets_restored_nonempty,
        "restore_failed": restore_failed,
        "steps": results,
    }
    summary_path = dump(f"selftest-{stamp}-summary.json", summary)

    banner("RESULT")
    print(f"healthy  -> PASS : {'HELD' if healthy_held else 'NOT HELD'}")
    if results.get("broken"):
        print(f"broken   -> FAIL : {'HELD' if broken_held else 'NOT HELD'}")
    else:
        print("broken   -> FAIL : NOT RUN (docker unavailable)")
    print("restore  -> PASS : "
          + ("NOT RUN (assets never moved)" if not assets_were_moved
             else ("HELD" if restored_held and not restore_failed else "NOT HELD")))
    print("evidence:", summary_path)
    print("screenshots:", EVIDENCE_DIR)

    if exit_code == 1:
        print("RESULT: a state behaved unexpectedly — see above.")
        return 1
    if exit_code == 2:
        if (fallback_used and results.get("broken_fallback", {}).get("ran")
                and not results["broken_fallback"].get("ok")):
            print("RESULT: BLOCKED. The canonical broken-frontend proof needs docker, which "
                  "this session cannot reach. The fallback aborted the JS bundle and the "
                  "check did fail on it, so the check's fail-path is demonstrated — but that "
                  "is not the acceptance proof. Re-run where `sg docker` works.")
        else:
            print("RESULT: BLOCKED. A required state could not run — see NEEDS ATTENTION above.")
        return 2
    if restore_failed:
        print("RESULT: expectations held, but the restore could not be verified.")
        return 1
    print("RESULT: all three expectations held.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
