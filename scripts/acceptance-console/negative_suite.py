#!/usr/bin/env python3
"""P3 negative acceptance suite — a clean rejection is a PASS.

Why this exists
---------------
Stages 1–3 of the acceptance console prove the *happy* path: a study arrives,
TeleRad ingests it, the report is signed and delivered. They cannot score a
refusal, because a refusal looks like a failure to transport-only tooling. This
module is the other half: nine cases where TeleRad is *supposed* to refuse
something, each judged against **its own recorded expectation** rather than the
happy-path verdict.

  neg-non-dicom          garbage with a .dcm name  -> refused, no study
  neg-zero-byte          an empty object            -> nothing stored, no study
  neg-truncated          half a DICOM object         -> refus  OR  store (either)
  neg-partial            5 good + 1 corrupt          -> 5 stored, 1 reported
  neg-wrong-called-ae    C-STORE to WRONG_AE         -> association refused
  neg-unknown-calling-ae GHOST_PACS caller           -> each C-STORE NotAuthorized
  neg-duplicate          re-send a spent fixture     -> no second study
  neg-interrupted        kill the sender mid-send    -> consistent, reported
  neg-unsupported-sop    CT + SR objects             -> CT stored, SR declined (F1)

Every assertion is **behavioural** and measured on the real platform: the
simulator's per-object verdicts plus TeleRad's read-only database keyed to the
fixture's StudyInstanceUID and the platform's total study count. Nothing is
inferred from "a study appeared".

A refusal for a SOP class TeleRad does not support is amber (finding F1), never
red and never a silent green. An expected rejection is green.

This is a pure module: no FastAPI imports, so the console and the CLI can both
use it. It never logs a password or a token.

Usage (host-side / sandbox, the simulator reaches the SCP either way):

    /opt/dicom-tools/bin/python negative_suite.py                 # every case
    /opt/dicom-tools/bin/python negative_suite.py --case neg-duplicate
    /opt/dicom-tools/bin/python negative_suite.py --list
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
ENV_FILE = Path("/home/hermes/.telerad-acceptance.env")
DEFAULT_EVIDENCE = HERE / "negative-evidence"
DEFAULT_SR_FIXTURE = HERE / "negative-fixtures" / "sr-unsupported" / "sr-unsupported.zip"

# The nine cases, in run order. ``expected`` is the plain-language expectation the
# console shows; ``kind`` only drives presentation, never the verdict.
CASES: list[dict] = [
    {"id": "neg-non-dicom", "label": "Non-DICOM payload with a .dcm name",
     "expected": "payload refused; no study created; clear message", "kind": "reject"},
    {"id": "neg-zero-byte", "label": "Zero-byte object",
     "expected": "nothing stored; no study created", "kind": "reject"},
    {"id": "neg-truncated", "label": "Truncated DICOM object",
     "expected": "clear error; store-or-refuse both acceptable (manifest: either)",
     "kind": "observe"},
    {"id": "neg-partial", "label": "One corrupt object among five good ones",
     "expected": "good objects stored, corrupt one reported, not fatal; study usable",
     "kind": "reject"},
    {"id": "neg-wrong-called-ae", "label": "Wrong called AE title (WRONG_AE)",
     "expected": "association refused cleanly; nothing stored", "kind": "reject"},
    {"id": "neg-unknown-calling-ae", "label": "Unknown calling AE (GHOST_PACS)",
     "expected": "association forms, every C-STORE refused NotAuthorized; nothing stored; "
                 "clear message", "kind": "reject"},
    {"id": "neg-duplicate", "label": "Duplicate send (same SOP UIDs)",
     "expected": "no second study; duplicate absorbed/reported cleanly", "kind": "observe"},
    {"id": "neg-interrupted", "label": "Sender killed mid-transfer",
     "expected": "platform consistent, no crash, result reported as interrupted — never "
                 "silently green", "kind": "interrupt"},
    {"id": "neg-unsupported-sop", "label": "Unsupported SR object beside CT images",
     "expected": "SR objects declined (no accepted presentation context); CT stored; study "
                 "usable; amber (finding F1)", "kind": "amber"},
]
BY_ID = {c["id"]: c for c in CASES}

# C-STORE statuses that mean "the peer refused this object" rather than a transport fault.
NOT_AUTHORIZED = "0x0124"
NO_ACCEPTED_CONTEXT = "no-accepted-context"


# --------------------------------------------------------------------------- config
def load_env_file(path: Path = ENV_FILE) -> None:
    """Load KEY=VALUE lines without shell-evaluating them; real env wins.

    ``set -a; . file`` breaks on the unquoted ``TELERAD_HOSPITAL_NAME=Demo General
    Hospital`` line. A plain parser does not, and it never executes anything.
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


def build_config(**overrides) -> dict:
    """Resolve the suite's configuration: env file, then explicit overrides."""
    load_env_file()
    fixtures_dir = Path(overrides.get("fixtures_dir") or os.environ.get(
        "FIXTURES_DIR", "/home/hermes/dicom-fixtures"))
    cfg = {
        "python": str(overrides.get("python") or os.environ.get(
            "PYTHON", "/opt/dicom-tools/bin/python")),
        "sender": str(overrides.get("sender") or os.environ.get(
            "SENDER", REPO / "scripts" / "pacs-simulator" / "send-study.py")),
        "fixtures_dir": fixtures_dir,
        "reports_dir": Path(overrides.get("reports_dir") or fixtures_dir / "reports"),
        "host": str(overrides.get("host") or os.environ.get("PACS_HOST", "127.0.0.1")),
        "port": int(overrides.get("port") or os.environ.get("PACS_PORT", "11112")),
        "called_ae": str(overrides.get("called_ae") or os.environ.get(
            "PACS_CALLED_AE", "TELERAD")),
        "calling_ae": str(overrides.get("calling_ae") or os.environ.get(
            "PACS_CALLING_AE", "DEMOHOSP_PACS")),
        "used_fixture": overrides.get("used_fixture"),
        "sr_fixture": overrides.get("sr_fixture"),
        "evidence_dir": Path(overrides.get("evidence_dir") or DEFAULT_EVIDENCE),
        "db": overrides.get("db") or {
            "host": os.environ.get("DB_HOST", "127.0.0.1"),
            "port": int(os.environ.get("DB_PORT", "5433")),
            "dbname": os.environ.get("DB_NAME", "telerad"),
            "user": os.environ.get("DB_USER", "telerad_test_ro"),
            "password": os.environ.get("DB_PASSWORD", ""),
        },
    }
    return cfg


# --------------------------------------------------------------------------- db
def _db_connect(cfg: dict):
    import psycopg
    d = cfg["db"]
    return psycopg.connect(host=d["host"], port=d["port"], dbname=d["dbname"],
                           user=d["user"], password=d["password"], connect_timeout=8)


def study_count(cfg: dict) -> int | None:
    """Total studies, or None when the read-only role is unavailable."""
    d = cfg["db"]
    if not d.get("password"):
        return None
    try:
        with _db_connect(cfg) as conn, conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM studies")
            return int(cur.fetchone()[0])
    except Exception:
        return None


def study_row(cfg: dict, study_uid: str | None) -> dict | None:
    """The study row for a StudyInstanceUID, or None (also None on DB failure)."""
    if not study_uid:
        return None
    try:
        with _db_connect(cfg) as conn, conn.cursor() as cur:
            cur.execute("""
                SELECT s.id, s.status, s.modality, s.source, s.instance_count,
                       (SELECT count(*) FROM series se WHERE se.study_id = s.id) AS series,
                       (SELECT count(*) FROM instances i
                          JOIN series se2 ON i.series_id = se2.id
                         WHERE se2.study_id = s.id) AS instances
                  FROM studies s WHERE s.study_instance_uid = %s
            """, (study_uid,))
            row = cur.fetchone()
            if not row:
                return None
            cols = ["id", "status", "modality", "source", "instance_count", "series", "instances"]
            return dict(zip(cols, row))
    except Exception:
        return None


def ingested_uids(cfg: dict) -> set[str]:
    d = cfg["db"]
    if not d.get("password"):
        return set()
    try:
        with _db_connect(cfg) as conn, conn.cursor() as cur:
            cur.execute("SELECT study_instance_uid FROM studies")
            return {r[0] for r in cur.fetchall()}
    except Exception:
        return set()


# --------------------------------------------------------------------------- fixtures
EXCLUDED_DIRS = {"source", "work", "reports"}


def fixture_meta(zip_path: Path, cfg: dict) -> dict:
    """UID / instance counts / expectation for a fixture, manifest first, deid next."""
    meta: dict = {"path": str(zip_path), "uid": None, "instances": None,
                  "series": None, "expected": None, "modality": None}
    manifest = zip_path.parent / "manifest.json"
    if manifest.is_file():
        try:
            m = json.loads(manifest.read_text())
            meta["uid"] = m.get("study_instance_uid")
            exp = m.get("expected") or {}
            meta["expected"] = exp or None
            meta["instances"] = exp.get("instances")
            meta["series"] = exp.get("series")
            meta["modality"] = exp.get("modality")
        except Exception:
            pass
    deid = cfg["reports_dir"] / f"{zip_path.parent.name}-deid.json"
    if deid.is_file():
        try:
            d = json.loads(deid.read_text())
            meta["uid"] = meta["uid"] or d.get("study_instance_uid_new")
            meta["instances"] = meta["instances"] or d.get("instances_converted")
            meta["series"] = meta["series"] if meta["series"] is not None else d.get("series_count")
        except Exception:
            pass
    return meta


def corrupt_fixture(cfg: dict, name: str) -> Path | None:
    p = cfg["fixtures_dir"] / "V" / name / f"{name}.zip"
    return p if p.is_file() else None


def used_fixture(cfg: dict) -> Path | None:
    """A spent fixture (its UID already in TeleRad). Connection/duplicate/interrupt
    cases must reuse one: a fresh fixture would create a study and pollute the
    assertion with a legitimate first ingest."""
    explicit = cfg.get("used_fixture")
    if explicit:
        p = Path(explicit)
        return p if p.is_file() else None
    ingested = ingested_uids(cfg)
    preferred = [cfg["fixtures_dir"] / "J" / "j-24.zip",
                 cfg["fixtures_dir"] / "E" / "console-E.zip",
                 cfg["fixtures_dir"] / "F" / "f-full.zip"]
    for p in preferred:
        if p.is_file() and (not ingested or fixture_meta(p, cfg)["uid"] in ingested):
            return p
    for p in sorted(cfg["fixtures_dir"].rglob("*.zip")):
        if p.parent.name in EXCLUDED_DIRS or "bad-" in p.stem or "sr-" in p.stem:
            continue
        meta = fixture_meta(p, cfg)
        if meta["uid"] and meta["uid"] in ingested and (meta["instances"] or 0) >= 8:
            return p
    return None


def sr_fixture(cfg: dict) -> Path | None:
    explicit = cfg.get("sr_fixture")
    if explicit:
        p = Path(explicit)
        return p if p.is_file() else None
    for p in (DEFAULT_SR_FIXTURE,
              cfg["fixtures_dir"] / "V" / "sr-unsupported" / "sr-unsupported.zip",
              cfg["fixtures_dir"] / "sr-unsupported" / "sr-unsupported.zip"):
        if p.is_file():
            return p
    return None


# --------------------------------------------------------------------------- simulator
def _parse_events(stdout: str) -> list[dict]:
    out = []
    for line in stdout.splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        try:
            out.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return out


def _last_summary(events: list[dict]) -> dict:
    for ev in reversed(events):
        if ev.get("event") == "summary":
            return ev
    return {}


def run_simulator(cfg: dict, fixture: Path, *, called_ae: str | None = None,
                  calling_ae: str | None = None, limit: int = 0,
                  json_out: Path | None = None, kill_after: int | None = None,
                  timeout: float = 300.0) -> dict:
    """Run the simulator once. ``kill_after`` = SIGINT the process group after N
    progress events (the interrupted-transfer case); otherwise run to completion.

    The process is started in its own session so the kill reaches the sender and
    its DICOM socket handler, not just the Python parent.
    """
    cmd = [cfg["python"], cfg["sender"], str(fixture), "--progress-json",
           "--host", str(cfg["host"]), "--port", str(cfg["port"]),
           "--called-ae", str(called_ae or cfg["called_ae"]),
           "--calling-ae", str(calling_ae or cfg["calling_ae"])]
    if limit:
        cmd += ["--limit", str(limit)]
    if json_out:
        cmd += ["--json-out", str(json_out)]

    result = {"cmd": cmd, "exit_code": None, "events": [], "stderr": "",
              "summary": {}, "received_signal": None, "killed_after": None,
              "json": None}
    tmpdir = Path(tempfile.mkdtemp(prefix="neg-suite-"))
    err_path = tmpdir / "stderr.txt"

    if kill_after is None:
        with open(err_path, "w") as err:
            proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=err,
                                  text=True, timeout=timeout)
        result["exit_code"] = proc.returncode
        result["events"] = _parse_events(proc.stdout or "")
    else:
        with open(err_path, "w") as err:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=err,
                                    text=True, bufsize=1, start_new_session=True)
            progress = 0
            try:
                assert proc.stdout is not None
                for raw in proc.stdout:
                    line = raw.strip()
                    if not line.startswith("{"):
                        continue
                    try:
                        ev = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    result["events"].append(ev)
                    if ev.get("event") == "progress":
                        progress += 1
                        if progress >= kill_after:
                            os.killpg(os.getpgid(proc.pid), signal.SIGINT)
                            result["killed_after"] = progress
                            break
                result["exit_code"] = proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                result["exit_code"] = proc.wait()
                result["kill_escalated"] = True
            except Exception:
                if proc.poll() is None:
                    os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
                    proc.wait()

    if result["exit_code"] is not None and result["exit_code"] < 0:
        result["received_signal"] = -result["exit_code"]
    try:
        result["stderr"] = err_path.read_text()[-3000:]
    except Exception:
        result["stderr"] = ""
    result["summary"] = _last_summary(result["events"])
    if json_out and Path(json_out).is_file():
        try:
            result["json"] = json.loads(Path(json_out).read_text())
        except Exception:
            result["json"] = None
    return result


# --------------------------------------------------------------------------- helpers
def _fixture_summary(res: dict) -> dict:
    """The full summary for a completed send, or the early (association) summary."""
    return res.get("json") or res.get("summary") or {}


def _failed_objects(res: dict) -> list[dict]:
    s = _fixture_summary(res)
    return list(s.get("failures") or [])


def _statuses(res: dict) -> dict:
    s = _fixture_summary(res)
    return {k: int(s.get(k) or 0) for k in
            ("success", "warning", "failure", "no_response")}


def _snapshot(cfg: dict, uid: str | None) -> dict:
    return {"count": study_count(cfg), "study": study_row(cfg, uid)}


def _describe_db(snap: dict) -> str:
    c = snap["count"]
    return "read-only DB unavailable" if c is None else f"studies={c}"


def _evidence(cfg: dict, case_id: str, result: dict, raw: dict | None) -> str:
    cfg["evidence_dir"].mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    path = cfg["evidence_dir"] / f"{case_id}-{stamp}.json"
    payload = {"case": case_id, "when": stamp, "result": result}
    if raw is not None:
        payload["raw"] = raw
    try:
        path.write_text(json.dumps(payload, indent=2, default=str))
    except Exception:
        return ""
    return str(path)


def _result(spec: dict, status: str, observed: str, detail: str, plain: str,
            evidence: str = "", seconds: float | None = None) -> dict:
    return {"id": spec["id"], "label": spec["label"], "expected": spec["expected"],
            "observed": observed, "status": status, "detail": detail, "plain": plain,
            "evidence": evidence, "seconds": seconds}


def _fixture_display(p: Path, cfg: dict) -> str:
    try:
        return str(p.relative_to(cfg["fixtures_dir"]))
    except ValueError:
        return str(p)


# --------------------------------------------------------------------------- cases
def case_non_dicom(cfg: dict, spec: dict) -> dict:
    fixture = corrupt_fixture(cfg, "bad-non-dicom")
    if not fixture:
        return _result(spec, "warn", "fixture missing",
                       "V/bad-non-dicom/bad-non-dicom.zip not found",
                       "The corrupt fixture is not present, so this case could not run.")
    uid = fixture_meta(fixture, cfg)["uid"]
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture)
    after = _snapshot(cfg, uid)
    st = _statuses(res)
    fails = _failed_objects(res)
    rejected = st["success"] == 0 and st["failure"] >= 1
    count_ok = before["count"] is not None and after["count"] == before["count"]
    no_study = after["study"] is None
    observed = (f"association={_fixture_summary(res).get('association', res['summary'].get('association', '?'))}; "
                f"{st['failure']} object(s) refused, {st['success']} stored; "
                f"{_describe_db(before)} -> {_describe_db(after)}; sentinel study "
                f"{'absent' if no_study else 'PRESENT'}")
    if before["count"] is None or after["count"] is None:
        status = "warn"
        plain = ("The send behaved as expected (the payload was refused), but the read-only "
                 "database could not corroborate that no study was created.")
    elif rejected and no_study and count_ok:
        status = "pass"
        plain = ("Correct: the non-DICOM payload was refused and TeleRad created no study.")
    else:
        status = "fail"
        plain = ("WRONG: a payload that is not DICOM either was stored or created a study.")
    status_word = "refused" if rejected else "NOT refused"
    detail = (f"{status_word}: " + (fails[0].get("detail", "") if fails else "no failure recorded")
              + f" · {_fixture_display(fixture, cfg)}")
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {"simulator": res["json"] or res["summary"],
                                                   "events": res["events"], "before": before,
                                                   "after": after})
    return r


def case_zero_byte(cfg: dict, spec: dict) -> dict:
    fixture = corrupt_fixture(cfg, "bad-zero-byte")
    if not fixture:
        return _result(spec, "warn", "fixture missing",
                       "V/bad-zero-byte/bad-zero-byte.zip not found",
                       "The corrupt fixture is not present, so this case could not run.")
    uid = fixture_meta(fixture, cfg)["uid"]
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture)
    after = _snapshot(cfg, uid)
    st = _statuses(res)
    fails = _failed_objects(res)
    rejected = st["success"] == 0 and st["failure"] >= 1
    no_study = after["study"] is None
    count_ok = before["count"] is not None and after["count"] == before["count"]
    observed = (f"{st['failure']} object(s) refused, {st['success']} stored, "
                f"{st['warning']} warning(s); {_describe_db(before)} -> {_describe_db(after)}; "
                f"sentinel study {'absent' if no_study else 'PRESENT'}")
    if before["count"] is None or after["count"] is None:
        status, plain = "warn", ("Nothing was stored, but the read-only database could not "
                                 "corroborate that no study was created.")
    elif rejected and no_study and count_ok:
        status, plain = "pass", "Correct: a zero-byte object stored nothing and created no study."
    else:
        status, plain = "fail", "WRONG: a zero-byte object produced stored data or a study."
    status_word = "refused" if rejected else "NOT refused"
    detail = (f"{status_word}: " + (fails[0].get("detail", "") if fails else "no failure recorded")
              + f" · {_fixture_display(fixture, cfg)}")
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {"simulator": res["json"] or res["summary"],
                                                   "events": res["events"], "before": before,
                                                   "after": after})
    return r


def case_truncated(cfg: dict, spec: dict) -> dict:
    fixture = corrupt_fixture(cfg, "bad-truncated")
    if not fixture:
        return _result(spec, "warn", "fixture missing",
                       "V/bad-truncated/bad-truncated.zip not found",
                       "The truncated fixture is not present, so this case could not run.")
    uid = fixture_meta(fixture, cfg)["uid"]
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture)
    after = _snapshot(cfg, uid)
    st = _statuses(res)
    study = after["study"]
    if st["success"] >= 1 and study is not None:
        status = "warn"
        observed = (f"the truncated object was ACCEPTED and stored (C-STORE success); "
                    f"study status={study['status']} instances={study['instances']} "
                    f"source={study['source']}; nothing was reported as an error by transport")
        detail = ("The manifest permits 'either' (store or refuse), so this is within the "
                  "recorded expectation — but a truncated object silently became a usable study; "
                  "no clear error was surfaced.")
        plain = ("Not a clean refusal: the truncated object was accepted and is now a study. "
                 "That is allowed by the fixture's 'either' expectation, but the platform "
                 "reported no error — worth a product finding.")
    elif st["success"] == 0:
        status = "pass"
        observed = (f"the truncated object was refused ({st['failure']} failure, "
                    f"{st['success']} stored); study "
                    f"{'absent' if study is None else 'present'}")
        detail = ("The truncated object produced a per-object failure rather than being stored; "
                  "the manifest permits 'either' and this is the clean-refusal branch.")
        plain = "Correct: the truncated object was refused with a clear per-object error."
    else:
        status = "warn"
        observed = f"unclassified: success={st['success']} failure={st['failure']} study={study}"
        detail = "The send produced no clear refuse/store outcome."
        plain = "The truncated case behaved ambiguously; inspect the evidence."
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {"simulator": res["json"] or res["summary"],
                                                   "events": res["events"], "before": before,
                                                   "after": after})
    return r


def case_partial(cfg: dict, spec: dict) -> dict:
    fixture = corrupt_fixture(cfg, "bad-one-of-six")
    if not fixture:
        return _result(spec, "warn", "fixture missing",
                       "V/bad-one-of-six/bad-one-of-six.zip not found",
                       "The partial-ingest fixture is not present, so this case could not run.")
    meta = fixture_meta(fixture, cfg)
    uid = meta["uid"]
    expected_stored = int((meta.get("expected") or {}).get("expected_stored", 5))
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture)
    after = _snapshot(cfg, uid)
    st = _statuses(res)
    fails = _failed_objects(res)
    corrupt_reported = any(f.get("status") in ("unreadable", "failure") for f in fails)
    count_ok = before["count"] is not None and after["count"] == before["count"]
    study = after["study"]
    study_ok = bool(study) and study["instances"] == expected_stored
    observed = (f"{st['success']} of {st['success'] + st['failure']} objects stored; "
                f"the corrupt object reported as "
                f"'{fails[0].get('status') if fails else 'not reported'}'; "
                f"study instances={study['instances'] if study else 'absent'} "
                f"(expected {expected_stored}); {_describe_db(before)} -> {_describe_db(after)}")
    if st["success"] == expected_stored and corrupt_reported and study_ok and count_ok:
        status = "pass"
        plain = (f"Correct: the {expected_stored} good images are stored, the corrupt one was "
                 f"reported and did not take them with it. The study is usable and no second "
                 f"study was created.")
    else:
        status = "fail"
        plain = "WRONG: the corrupt object disturbed the good images or the study is not usable."
    detail = (f"{st['success']} stored / {st['failure']} refused; corrupt object "
              f"{'reported' if corrupt_reported else 'NOT reported'}; study "
              f"{study['status'] if study else 'MISSING'}")
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {"simulator": res["json"] or res["summary"],
                                                   "events": res["events"], "before": before,
                                                   "after": after})
    return r


def _connection_case(cfg: dict, spec: dict, *, called_ae: str, calling_ae: str) -> dict:
    fixture = used_fixture(cfg)
    if not fixture:
        return _result(spec, "warn", "no spent fixture available",
                       "no used fixture could be resolved for a connection case",
                       "This case needs an already-ingested fixture and none was available.")
    meta = fixture_meta(fixture, cfg)
    uid = meta["uid"]
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture, called_ae=called_ae, calling_ae=calling_ae, limit=2)
    after = _snapshot(cfg, uid)
    s = _fixture_summary(res)
    st = _statuses(res)
    assoc = s.get("association") or res["summary"].get("association", "?")
    echo = s.get("c_echo") or res["summary"].get("echo", "?")
    count_ok = before["count"] is not None and after["count"] == before["count"]
    return {"fixture": fixture, "uid": uid, "before": before, "after": after,
            "res": res, "assoc": assoc, "echo": echo, "st": st, "count_ok": count_ok,
            "seconds": round(time.monotonic() - t0, 2)}


def case_wrong_called_ae(cfg: dict, spec: dict) -> dict:
    ctx = _connection_case(cfg, spec, called_ae="WRONG_AE", calling_ae=cfg["calling_ae"])
    if "res" not in ctx:
        return ctx
    stored = ctx["st"]["success"]
    ok = ctx["assoc"] == "rejected" and stored == 0 and ctx["count_ok"]
    observed = (f"association={ctx['assoc']} (called AE WRONG_AE); stored={stored}; "
                f"{_describe_db(ctx['before'])} -> {_describe_db(ctx['after'])}")
    lines = [ln.strip() for ln in ctx["res"]["stderr"].strip().splitlines() if ln.strip()]
    refusal = next((ln for ln in lines if "REJECT" in ln.upper()), "association rejected")
    detail = (f"Connection rejected at the association step (“{refusal}”); "
              f"simulator exit code {ctx['res']['exit_code']}. No store was attempted.")
    if ok:
        status = "pass"
        plain = ("Correct: TeleRad refused the association for the wrong called AE title, and "
                 "nothing was stored.")
    else:
        status = "fail"
        plain = ("WRONG: a wrong called AE title was not cleanly refused, or something was "
                 "stored.")
    r = {"id": spec["id"], "label": spec["label"], "expected": spec["expected"],
         "observed": observed, "status": status, "detail": detail, "plain": plain,
         "evidence": "", "seconds": ctx["seconds"]}
    r["evidence"] = _evidence(cfg, spec["id"], r, {
        "simulator": ctx["res"]["json"] or ctx["res"]["summary"],
        "events": ctx["res"]["events"], "stderr_tail": ctx["res"]["stderr"][-1500:],
        "before": ctx["before"], "after": ctx["after"]})
    return r


def case_unknown_calling_ae(cfg: dict, spec: dict) -> dict:
    ctx = _connection_case(cfg, spec, called_ae=cfg["called_ae"], calling_ae="GHOST_PACS")
    if "res" not in ctx:
        return ctx
    st = ctx["st"]
    fails = _failed_objects(ctx["res"])
    codes = sorted({f.get("status") for f in fails})
    all_na = bool(fails) and all(f.get("status") == NOT_AUTHORIZED for f in fails)
    ok = (ctx["assoc"] == "established" and ctx["echo"] == "success"
          and st["success"] == 0 and all_na and ctx["count_ok"])
    observed = (f"association={ctx['assoc']}, C-ECHO={ctx['echo']}; "
                f"{st['failure']} C-STORE refused with status {codes or '—'}; stored=0; "
                f"{_describe_db(ctx['before'])} -> {_describe_db(ctx['after'])}")
    detail = ("Association formed (the SCP does not gate the calling AE at association time) "
              f"and every C-STORE returned {NOT_AUTHORIZED} (Not Authorized) from the unknown "
              f"caller GHOST_PACS; exit code {ctx['res']['exit_code']}.")
    if ok:
        status = "pass"
        plain = ("Correct: an unknown calling AE could associate and C-ECHO, but every object "
                 "was refused Not Authorized and nothing was stored.")
    else:
        status = "fail"
        plain = ("WRONG: the unknown calling AE was not refused cleanly per object, or "
                 "something was stored.")
    r = {"id": spec["id"], "label": spec["label"], "expected": spec["expected"],
         "observed": observed, "status": status, "detail": detail, "plain": plain,
         "evidence": "", "seconds": ctx["seconds"]}
    r["evidence"] = _evidence(cfg, spec["id"], r, {
        "simulator": ctx["res"]["json"] or ctx["res"]["summary"],
        "events": ctx["res"]["events"], "stderr_tail": ctx["res"]["stderr"][-1500:],
        "before": ctx["before"], "after": ctx["after"]})
    return r


def case_duplicate(cfg: dict, spec: dict) -> dict:
    fixture = used_fixture(cfg)
    if not fixture:
        return _result(spec, "warn", "no spent fixture available",
                       "no used fixture could be resolved for the duplicate case",
                       "This case needs an already-ingested fixture and none was available.")
    meta = fixture_meta(fixture, cfg)
    uid = meta["uid"]
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture)
    after = _snapshot(cfg, uid)
    st = _statuses(res)
    before_study, after_study = before["study"], after["study"]
    instances_same = bool(before_study and after_study
                          and before_study["instances"] == after_study["instances"])
    count_ok = before["count"] is not None and after["count"] == before["count"]
    ok = (st["failure"] == 0 and st["success"] >= 1 and count_ok and instances_same)
    observed = (f"re-sent {st['success']} object(s) with the same SOP UIDs; "
                f"C-STORE success={st['success']}, failure={st['failure']}; "
                f"{_describe_db(before)} -> {_describe_db(after)}; study instances "
                f"{before_study['instances'] if before_study else '?'} -> "
                f"{after_study['instances'] if after_study else '?'}; status preserved="
                f"{before_study['status'] if before_study else '?'}")
    if ok:
        status = "pass"
        plain = ("Correct: the duplicate SOP instances were absorbed — no second study and no "
                 "extra instances. (Note: the platform reports each C-STORE as success; it does "
                 "not surface a 'duplicate' flag.)")
        detail = ("All re-sent objects returned 0x0000 and the study's instance count and status "
                  "are unchanged; TeleRad deduplicated by SOP Instance UID.")
    else:
        status = "fail"
        plain = "WRONG: re-sending a spent fixture changed the study or created a second one."
        detail = f"count_ok={count_ok} instances_same={instances_same} st={st}"
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {"simulator": res["json"] or res["summary"],
                                                   "events": res["events"], "before": before,
                                                   "after": after})
    return r


def case_interrupted(cfg: dict, spec: dict) -> dict:
    fixture = used_fixture(cfg)
    if not fixture:
        return _result(spec, "warn", "no spent fixture available",
                       "no used fixture could be resolved for the interrupted case",
                       "This case needs an already-ingested fixture and none was available.")
    meta = fixture_meta(fixture, cfg)
    uid = meta["uid"]
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture, kill_after=2)
    after = _snapshot(cfg, uid)
    # Prove the platform is still alive after the kill with an independent C-ECHO.
    probe = run_simulator(cfg, fixture, limit=1, timeout=30)
    probe_s = _fixture_summary(probe)
    probe_assoc = probe_s.get("association") or probe["summary"].get("association", "?")
    probe_echo = probe_s.get("c_echo") or probe["summary"].get("echo", "?")
    count_ok = before["count"] is not None and after["count"] == before["count"]
    instances_same = bool(before["study"] and after["study"]
                          and before["study"]["instances"] == after["study"]["instances"])
    killed = res.get("killed_after") is not None and (
        res.get("received_signal") is not None or res["exit_code"] != 0)
    completed = res["exit_code"] == 0 and _fixture_summary(res).get("summary") == "PASS"
    progress = [e for e in res["events"] if e.get("event") == "progress"]
    observed = (f"sender killed after {res.get('killed_after')} progress event(s) of "
                f"{meta.get('instances') or '?'}; exit code {res['exit_code']} "
                f"({'signal ' + str(res['received_signal']) if res.get('received_signal') else 'no signal'}); "
                f"summary emitted={'yes' if res.get('summary', {}).get('study') else 'NO (killed mid-transfer)'}; "
                f"{_describe_db(before)} -> {_describe_db(after)}; study instances "
                f"{before['study']['instances'] if before['study'] else '?'} -> "
                f"{after['study']['instances'] if after['study'] else '?'}; "
                f"post-kill C-ECHO={probe_echo} (association {probe_assoc})")
    if completed:
        status = "warn"
        detail = ("The kill did not land before the sender finished; the transfer completed "
                  "normally, so this run did not demonstrate interruption.")
        plain = ("Inconclusive: the sender finished before it could be killed. Re-run to prove "
                 "the interrupted path; this is not reported as a clean interrupt.")
    elif killed and count_ok and instances_same and probe_echo == "success":
        status = "pass"
        detail = (f"SIGINT delivered to the sender's process group after {res.get('killed_after')} "
                  f"object(s); no summary was written, the study was left consistent, and an "
                  f"independent C-ECHO afterwards confirms the SCP did not crash.")
        plain = ("Correct: the sender was interrupted mid-transfer (evidence: signal and exit "
                 "code), the platform stayed consistent, and the result is reported as "
                 "interrupted — not green.")
    else:
        status = "fail"
        detail = (f"killed={killed} count_ok={count_ok} instances_same={instances_same} "
                  f"probe_echo={probe_echo}")
        plain = "WRONG: the interrupted transfer left the platform inconsistent or unresponsive."
    if not progress:
        observed += "; WARNING: no progress event captured before the kill"
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {
        "sender": {"exit_code": res["exit_code"], "received_signal": res["received_signal"],
                   "killed_after": res.get("killed_after"),
                   "progress_events": len(progress),
                   "stderr_tail": res["stderr"][-1500:]},
        "before": before, "after": after,
        "post_kill_probe": {"association": probe_assoc, "c_echo": probe_echo,
                            "exit_code": probe["exit_code"]}})
    return r


def case_unsupported_sop(cfg: dict, spec: dict) -> dict:
    fixture = sr_fixture(cfg)
    if not fixture:
        return _result(spec, "warn", "SR fixture not generated",
                       "run: /opt/dicom-tools/bin/python scripts/make_sr_fixture.py "
                       "--out-root /home/hermes/dicom-fixtures",
                       "The SR fixture is not present. Generate it with make_sr_fixture.py, "
                       "then re-run this case.")
    meta = fixture_meta(fixture, cfg)
    uid = meta["uid"]
    expected_stored = int((meta.get("expected") or {}).get("expected_stored") or 3)
    before = _snapshot(cfg, uid)
    t0 = time.monotonic()
    res = run_simulator(cfg, fixture)
    after = _snapshot(cfg, uid)
    st = _statuses(res)
    fails = _failed_objects(res)
    declined = [f for f in fails if f.get("status") == NO_ACCEPTED_CONTEXT]
    study = after["study"]
    ct_stored = st["success"]
    study_ok = bool(study) and study["instances"] == expected_stored
    declined_classes = sorted({f.get("sop_class_uid", "?") for f in declined})
    observed = (f"CT stored={ct_stored} (expected {expected_stored}); SR declined="
                f"{len(declined)} with status '{NO_ACCEPTED_CONTEXT}' "
                f"(classes {declined_classes}); study "
                f"{'READY/usable' if study else 'absent'} "
                f"instances={study['instances'] if study else '?'}; "
                f"{_describe_db(before)} -> {_describe_db(after)}")
    if ct_stored == expected_stored and declined and study_ok:
        status = "warn"
        detail = ("TeleRad's SCP declares no presentation context for the SR classes "
                  f"({', '.join(declined_classes)}), so they were declined per object while the "
                  f"CT images stored normally. Finding F1 — amber by design.")
        plain = ("Expected, amber: the CT images are stored and the study is usable, but the "
                 "dose/enhanced SR objects are silently dropped because the SCP does not support "
                 "their SOP classes (finding F1).")
    elif ct_stored == expected_stored and not declined and study_ok:
        status = "warn"
        detail = ("The SR objects were ACCEPTED, which contradicts the recorded expectation and "
                  "finding F1 — re-check whether the SCP gained SR capability.")
        plain = ("Unexpected: the SR objects were accepted rather than declined. The platform may "
                 "have changed; re-check finding F1.")
    else:
        status = "fail"
        detail = f"stored={ct_stored} expected={expected_stored} declined={len(declined)} study={study}"
        plain = ("WRONG: the CT images were not stored, or the resulting study is not usable.")
    r = _result(spec, status, observed, detail, plain, seconds=round(time.monotonic() - t0, 2))
    r["evidence"] = _evidence(cfg, spec["id"], r, {"simulator": res["json"] or res["summary"],
                                                   "events": res["events"], "before": before,
                                                   "after": after})
    return r


_DISPATCH = {
    "neg-non-dicom": case_non_dicom,
    "neg-zero-byte": case_zero_byte,
    "neg-truncated": case_truncated,
    "neg-partial": case_partial,
    "neg-wrong-called-ae": case_wrong_called_ae,
    "neg-unknown-calling-ae": case_unknown_calling_ae,
    "neg-duplicate": case_duplicate,
    "neg-interrupted": case_interrupted,
    "neg-unsupported-sop": case_unsupported_sop,
}


# --------------------------------------------------------------------------- api
def list_cases() -> list[dict]:
    """Case metadata for the console: id, label, expected outcome, presentation kind."""
    return [{"id": c["id"], "label": c["label"], "expected": c["expected"],
             "kind": c["kind"]} for c in CASES]


def run_case(case_id: str, cfg: dict | None = None) -> dict:
    """Run one case and judge it against its own expectation."""
    spec = BY_ID.get(case_id)
    if spec is None:
        raise KeyError(f"unknown case: {case_id}")
    cfg = cfg or build_config()
    try:
        result = _DISPATCH[case_id](cfg, spec)
    except Exception as exc:
        result = _result(spec, "warn", f"case could not run: {type(exc).__name__}",
                         str(exc)[:300],
                         "The case could not run, so it is not counted as a pass.")
    return result


def run_all(cfg: dict | None = None, case_ids: list[str] | None = None) -> dict:
    """Run every case sequentially. Returns ``{results, summary}``."""
    cfg = cfg or build_config()
    ids = case_ids or [c["id"] for c in CASES]
    results = [run_case(cid, cfg) for cid in ids]
    counts = {"pass": 0, "warn": 0, "fail": 0}
    for r in results:
        counts[r["status"]] = counts.get(r["status"], 0) + 1
    counts["total"] = len(results)
    return {"results": results, "summary": counts}


# --------------------------------------------------------------------------- cli
def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--case", action="append", default=[],
                    help="case id to run (repeatable); default: all")
    ap.add_argument("--list", action="store_true", help="list the case matrix and exit")
    ap.add_argument("--fixtures-dir", default=None)
    ap.add_argument("--used-fixture", default=None,
                    help="path to an already-ingested fixture for connection/dup/interrupt cases")
    ap.add_argument("--sr-fixture", default=None, help="path to the generated SR fixture")
    ap.add_argument("--evidence-dir", default=None)
    ap.add_argument("--json-out", default=None, help="write the full result set as JSON")
    args = ap.parse_args(argv)

    if args.list:
        for c in list_cases():
            print(f"{c['id']:<24} {c['kind']:<9} expects: {c['expected']}")
        return 0

    cfg = build_config(fixtures_dir=args.fixtures_dir, used_fixture=args.used_fixture,
                       sr_fixture=args.sr_fixture, evidence_dir=args.evidence_dir)
    out = run_all(cfg, args.case or None)
    print(json.dumps(out, indent=2, default=str))
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(out, indent=2, default=str))
    s = out["summary"]
    print(f"\nSUMMARY: {s['pass']} pass · {s['warn']} warn · {s['fail']} fail "
          f"({s['total']} cases)", file=sys.stderr)
    return 1 if s["fail"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
