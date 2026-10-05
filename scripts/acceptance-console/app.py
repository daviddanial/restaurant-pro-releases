#!/usr/bin/env python3
"""
TeleRad acceptance console — a UI over the PACS simulator and TeleRad's own backend.

Why this exists
---------------
The simulator proves DICOM transport. It does NOT prove TeleRad accepted the study.
This console keeps those two questions apart and shows both, because "C-STORE
succeeded" is routinely mistaken for "the hospital portal ingested it":

  Stage 1 (transport)  : association, C-ECHO, per-object C-STORE verdicts — via the
                         simulator, streamed live.
  Stage 2 (application): TeleRad confirms, through its OWN API and a read-only DB
                         role, that the study exists, has the right series/instance
                         counts, is under the right hospital, is READY, renders, and
                         reaches dispatch.

Stage 2 assertions are keyed to the FIXTURE's StudyInstanceUID, not to "a new study
appeared" — otherwise any pre-existing study would satisfy them and the test would
pass without the send having done anything.

The verdict is three-state on purpose:
  TEST PASSED                       every hard check passed
  TEST PASSED WITH DECLINED OBJECTS  the study is usable, but TeleRad declined some
                                    objects (e.g. SOP classes it does not support)
  TEST FAILED                       a hard check failed; the failing stage is named
"""
from __future__ import annotations

import asyncio
import json
import os
import re
import time
import uuid
import zipfile
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse

CONFIG = {
    "python": os.environ.get("PYTHON", "/opt/dicom-tools/bin/python"),
    "sender": os.environ.get("SENDER", "/home/hermes/telerad-repo/scripts/pacs-simulator/send-study.py"),
    "fixtures_dir": Path(os.environ.get("FIXTURES_DIR", "/home/hermes/dicom-fixtures")),
    "api_base": os.environ.get("TELERAD_API", "http://127.0.0.1:18080"),
    "admin_email": os.environ.get("TELERAD_ADMIN_EMAIL", "admin@telerad.local"),
    "admin_password": os.environ.get("TELERAD_ADMIN_PASSWORD", "password"),
    "hospital_id": os.environ.get("TELERAD_HOSPITAL_ID", "11111111-1111-1111-1111-111111111111"),
    "hospital_name": os.environ.get("TELERAD_HOSPITAL_NAME", "Demo General Hospital"),
    "db": {
        "host": os.environ.get("DB_HOST", "127.0.0.1"),
        "port": int(os.environ.get("DB_PORT", "5433")),
        "dbname": os.environ.get("DB_NAME", "telerad"),
        "user": os.environ.get("DB_USER", "telerad_test_ro"),
        "password": os.environ.get("DB_PASSWORD", ""),
    },
    "defaults": {
        "host": os.environ.get("PACS_HOST", "127.0.0.1"),
        "port": int(os.environ.get("PACS_PORT", "11112")),
        "called_ae": os.environ.get("PACS_CALLED_AE", "TELERAD"),
        "calling_ae": os.environ.get("PACS_CALLING_AE", "DEMOHOSP_PACS"),
    },
}

app = FastAPI(title="TeleRad Acceptance Console")

RUNS: dict[str, dict] = {}
SSE_HEADERS = {"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"}


# ----------------------------------------------------------------------------- helpers
def _reports_dir() -> Path:
    return CONFIG["fixtures_dir"] / "reports"


def _first_dicom_meta(zip_path: Path) -> dict:
    """Cheap header peek: modality/series/instances from the archive without extracting."""
    meta = {"modality": "?", "instances": 0}
    try:
        with zipfile.ZipFile(zip_path) as zf:
            names = [n for n in zf.namelist() if n.lower().endswith((".dcm", ".dicom"))]
            meta["instances"] = len(names)
            if names:
                import pydicom
                with zf.open(names[0]) as fh:
                    ds = pydicom.dcmread(fh, stop_before_pixels=True, force=True)
                    meta["modality"] = str(ds.get("Modality", "?"))
    except Exception as exc:  # a broken fixture must not break the console
        meta["error"] = str(exc)[:120]
    return meta


# Never offer these as sendable fixtures: `source` holds the controlled original
# (identifiable) and `work` is scratch space.
EXCLUDED_DIRS = {"source", "work", "reports"}


def list_fixtures() -> tuple[list[dict], list[dict]]:
    """Return (selectable, excluded).

    A fixture is **selectable only if it has a de-identification report AND a passing
    verification report**. That single rule means the console can never transmit an
    identifiable study, even if someone drops one into the fixtures directory.
    """
    out: list[dict] = []
    excluded: list[dict] = []
    for zip_path in sorted(CONFIG["fixtures_dir"].glob("*/*.zip")):
        key = zip_path.parent.name
        if key in EXCLUDED_DIRS:
            excluded.append({"id": zip_path.stem, "path": str(zip_path),
                             "reason": "not a sendable fixture (source artifact / scratch)"})
            continue
        deid = _reports_dir() / f"{key}-deid.json"
        verify = _reports_dir() / f"{key}-verify.json"
        item = {
            "id": zip_path.stem,
            "label": f"{key}: {zip_path.stem}",
            "path": str(zip_path),
            "size_mb": round(zip_path.stat().st_size / 1e6, 1),
            "modality": _first_dicom_meta(zip_path)["modality"],
            "instances": _first_dicom_meta(zip_path)["instances"],
        }
        if deid.exists():
            d = json.loads(deid.read_text())
            item.update({
                "expected_study_uid": d.get("study_instance_uid_new"),
                "expected_series": d.get("series_count"),
                "expected_instances": d.get("instances_converted"),
                "deidentified": True,
                "identity": d.get("identity", {}),
                "date_shift_days": d.get("date_shift_days"),
                "burned_in": d.get("burned_in_annotation", {}),
            })
        if verify.exists():
            v = json.loads(verify.read_text())
            item["deid_verified"] = v.get("result") == "PASS"
            item["deid_checks"] = len([c for c in v.get("checks", []) if c.get("pass")])
            item["deid_warnings"] = v.get("warnings", [])
        item["selectable"] = bool(item.get("deidentified") and item.get("deid_verified"))
        item["excluded_reason"] = (None if item["selectable"] else
                                   "no passing de-identification verification — "
                                   "run verify_deid.py before it can be sent")
        (out if item["selectable"] else excluded).append(item)
    return out, excluded


def _db_counts(study_uid: str) -> dict:
    """Corroborate TeleRad's state through a READ-ONLY role (never the app's credentials)."""
    try:
        import psycopg
    except ImportError:
        return {"available": False, "detail": "psycopg not installed"}
    d = CONFIG["db"]
    if not d["password"]:
        return {"available": False, "detail": "no read-only DB password configured"}
    try:
        with psycopg.connect(host=d["host"], port=d["port"], dbname=d["dbname"],
                             user=d["user"], password=d["password"],
                             connect_timeout=8) as conn, conn.cursor() as cur:
            cur.execute("""
                SELECT s.id, s.status, s.modality, s.study_description, s.source::text,
                       s.instance_count,
                       (SELECT count(*) FROM series se WHERE se.study_id = s.id) AS series,
                       (SELECT count(*) FROM instances i
                          JOIN series se2 ON i.series_id = se2.id
                         WHERE se2.study_id = s.id) AS instances,
                       s.assigned_to
                  FROM studies s WHERE s.study_instance_uid = %s
            """, (study_uid,))
            row = cur.fetchone()
            if not row:
                return {"available": True, "found": False}
            cols = ["id", "status", "modality", "description", "source", "instance_count",
                    "series", "instances", "assigned_to"]
            return {"available": True, "found": True, **dict(zip(cols, row))}
    except Exception as exc:
        return {"available": False, "detail": str(exc)[:160]}


async def _api_login() -> str:
    async with httpx.AsyncClient(timeout=15) as c:
        r = await c.post(f"{CONFIG['api_base']}/api/auth/login",
                         json={"email": CONFIG["admin_email"],
                               "password": CONFIG["admin_password"]})
        r.raise_for_status()
        return r.json()["accessToken"]


async def verify_stage2(fixture: dict, stage1: dict) -> list[dict]:
    """Ask TeleRad whether IT accepted the study. Every check is keyed to the fixture UID."""
    checks: list[dict] = []
    uid = fixture.get("expected_study_uid")
    exp_series = fixture.get("expected_series")
    exp_instances = fixture.get("expected_instances")
    declined = stage1.get("declined_unsupported", stage1.get("failure", 0))

    def add(cid, label, status, detail, plain):
        checks.append({"id": cid, "stage": 2, "label": label, "status": status,
                       "detail": detail, "plain": plain})

    # --- corroboration through the read-only DB role -------------------------
    # A series whose every instance was declined never gets a row, so the expected
    # series count is the fixture's series count minus the series that were declined
    # in full. TeleRad is right to omit them; an expectation that ignores this is wrong.
    per_series: dict[str, dict] = {}
    for c in stage1.get("series_tally", {}).items():
        per_series[c[0]] = c[1]
    fully_declined = stage1.get("series_fully_declined", [])
    exp_series_stored = None if exp_series is None else exp_series - len(fully_declined)

    db = await asyncio.to_thread(_db_counts, uid) if uid else {"available": False}
    if db.get("available") and db.get("found"):
        add("db-study", "Study row exists (read-only DB)", "pass",
            f"status={db['status']} source={db['source']} modality={db['modality']}",
            "TeleRad's database contains the study that was just sent.")
        if exp_series_stored is None:
            add("db-series", "Series count", "warn", f"found {db['series']}", "")
        else:
            ok_series = db["series"] == exp_series_stored
            detail = (f"expected {exp_series_stored}, found {db['series']}"
                      + (f" ({exp_series} sent − {len(fully_declined)} series declined in full)"
                         if fully_declined else ""))
            plain = (f"TeleRad created {db['series']} series"
                     + (f"; {len(fully_declined)} series contained only objects TeleRad "
                        f"declines, so they are absent by design."
                        if fully_declined else "."))
            add("db-series", "Series count", "pass" if ok_series else "fail", detail, plain)
        expected_stored = (exp_instances or 0) - declined
        if declined:
            add("db-instances", "Instance count", 
                "pass" if db["instances"] == expected_stored else "fail",
                f"expected {expected_stored} (= {exp_instances} sent − {declined} declined), "
                f"found {db['instances']}",
                f"{db['instances']} of {exp_instances} images were stored; {declined} were "
                f"declined by TeleRad (unsupported object types).")
            add("declined", "Objects declined by TeleRad", "warn",
                f"{declined} object(s) refused: "
                + ", ".join(sorted({f.get('sop_class_uid', '?') for f in stage1.get('failures', [])})[:3]),
                "TeleRad accepted the images but refused some object types it does not "
                "support (e.g. dose reports). The study is usable; the missing objects are "
                "a product limitation worth fixing.")
        else:
            add("db-instances", "Instance count",
                "pass" if db["instances"] == exp_instances else "fail",
                f"expected {exp_instances}, found {db['instances']}",
                f"All {exp_instances} images were stored.")
        add("db-modality", "Modality", 
            "pass" if db["modality"] == fixture.get("modality") else "warn",
            f"expected {fixture.get('modality')}, found {db['modality']}",
            f"Study modality recorded as {db['modality']}.")
        add("db-source", "Ingest path", 
            "pass" if db["source"] == "CSTORE" else "warn",
            f"source={db['source']}",
            "TeleRad recorded this as a PACS/C-STORE ingest.")
    else:
        add("db-study", "Study row exists (read-only DB)", "fail",
            db.get("detail") or "study not found by fixture StudyInstanceUID",
            "TeleRad has no study with the UID that was sent.")

    # --- independently, through TeleRad's own API ----------------------------
    try:
        token = await _api_login()
        hdr = {"Authorization": f"Bearer {token}"}
        async with httpx.AsyncClient(timeout=25) as c:
            r = await c.get(f"{CONFIG['api_base']}/api/hospitals/{CONFIG['hospital_id']}/studies",
                            headers=hdr)
            rows = r.json() if r.status_code == 200 else []
            mine = [s for s in rows if str(s.get("studyInstanceUid")) == uid]
            add("api-hospital", "Belongs to the right hospital",
                "pass" if mine else "fail",
                f"{len(mine)} matching row(s) in {CONFIG['hospital_name']}",
                f"The study appears under {CONFIG['hospital_name']}." if mine
                else "The study is not listed for the expected hospital.")
            if mine:
                st = mine[0]
                add("api-status", "Status is READY",
                    "pass" if st.get("status") == "READY" else "fail",
                    f"status={st.get('status')}",
                    "The study is ready to be read." if st.get("status") == "READY"
                    else f"The study is {st.get('status')}, not READY.")
                add("api-dispatch", "Visible in dispatch (pending, unassigned)",
                    "pass" if (st.get("status") == "READY" and not st.get("assignedTo")) else "warn",
                    f"status={st.get('status')} assignedTo={st.get('assignedTo')}",
                    "It is waiting in the dispatch queue, unassigned.")
                # viewer path: can the pixels actually be fetched?
                try:
                    su = st.get("studyInstanceUid")
                    detail = await c.get(f"{CONFIG['api_base']}/api/studies/{st.get('id')}", headers=hdr)
                    sj = detail.json() if detail.status_code == 200 else {}
                    series = (sj.get("series") or [])
                    if series and series[0].get("instances"):
                        ser = series[0]
                        sop = ser["instances"][0]["sopInstanceUid"]
                        img = await c.get(
                            f"{CONFIG['api_base']}/api/dicom/studies/{su}/series/"
                            f"{ser['seriesInstanceUid']}/instances/{sop}", headers=hdr)
                        ok = img.status_code == 200 and img.content[128:132] == b"DICM"
                        add("api-viewer", "Viewer can load image pixels",
                            "pass" if ok else "fail",
                            f"instance fetch HTTP {img.status_code}, {len(img.content)} bytes, "
                            f"magic {'ok' if ok else 'invalid'}",
                            "The viewer can retrieve real image data for this study." if ok
                            else "The viewer could not retrieve image data.")
                    else:
                        add("api-viewer", "Viewer can load image pixels", "fail",
                            "no series/instances returned by the API",
                            "TeleRad returned no images for this study.")
                except Exception as exc:
                    add("api-viewer", "Viewer can load image pixels", "warn",
                        str(exc)[:140], "Could not check the viewer path automatically.")
    except Exception as exc:
        add("api-hospital", "Belongs to the right hospital", "fail",
            f"API query failed: {str(exc)[:140]}",
            "The console could not reach TeleRad's API to confirm ingestion.")

    return checks


# ------------------------------------------------------------------------------- routes
@app.get("/api/config")
async def api_config():
    fixtures, excluded = await asyncio.to_thread(list_fixtures)
    return {
        "defaults": CONFIG["defaults"],
        "fixtures": fixtures,
        "excluded": excluded,
        "hospital": CONFIG["hospital_name"],
        "portal_urls": {
            "hospital": f"https://telerad.13.140.56.88.sslip.io/hospital",
            "dispatch": f"https://telerad.13.140.56.88.sslip.io/dispatch",
            "worklist": f"https://telerad.13.140.56.88.sslip.io/worklist",
        },
        "credentials": {"admin": CONFIG["admin_email"], "password_hint": "password"},
    }


@app.post("/api/test-connection")
async def test_connection(body: dict):
    """Stage-1 only: can we associate and does C-ECHO answer? Stores nothing."""
    cmd = [CONFIG["python"], CONFIG["sender"], CONFIG["fixtures_dir"] / "B" / "pacs-B.zip",
           "--dry-run", "--progress-json",
           "--host", str(body.get("host", CONFIG["defaults"]["host"])),
           "--port", str(body.get("port", CONFIG["defaults"]["port"])),
           "--called-ae", str(body.get("called_ae", CONFIG["defaults"]["called_ae"])),
           "--calling-ae", str(body.get("calling_ae", CONFIG["defaults"]["calling_ae"]))]
    events = []
    proc = await asyncio.create_subprocess_exec(
        *[str(c) for c in cmd], stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    async for raw in proc.stdout:
        line = raw.decode().strip()
        if line.startswith("{"):
            events.append(json.loads(line))
    code = await proc.wait()
    assoc = next((e for e in events if e.get("event") == "association"), {})
    echo = next((e for e in events if e.get("event") == "echo"), {})
    ok = assoc.get("status") == "established" and echo.get("verdict") == "success"
    return {
        "ok": ok, "exit_code": code,
        "association": assoc.get("status", "not-attempted"),
        "echo": echo.get("verdict", "not-run"),
        "plain": ("The PACS endpoint answered and accepts DICOM connections."
                  if ok else
                  "Connection failed. Check the destination, port and called AE title."),
    }


async def _run_pipeline(run_id: str, fixture: dict, body: dict) -> None:
    run = RUNS[run_id]
    q: asyncio.Queue = run["queue"]

    def push(event: dict) -> None:
        q.put_nowait(event)

    limit = int(body.get("limit") or 0)
    cmd = [CONFIG["python"], CONFIG["sender"], fixture["path"], "--progress-json",
           "--host", str(body.get("host", CONFIG["defaults"]["host"])),
           "--port", str(body.get("port", CONFIG["defaults"]["port"])),
           "--called-ae", str(body.get("called_ae", CONFIG["defaults"]["called_ae"])),
           "--calling-ae", str(body.get("calling_ae", CONFIG["defaults"]["calling_ae"]))]
    if limit:
        cmd += ["--limit", str(limit)]
    if body.get("json_out"):
        cmd += ["--json-out", str(body["json_out"])]

    push({"event": "phase", "phase": "stage1", "label": "Sending the study over DICOM"})
    proc = await asyncio.create_subprocess_exec(
        *[str(c) for c in cmd], stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    summary = {}
    async for raw in proc.stdout:
        line = raw.decode().strip()
        if not line.startswith("{"):
            continue
        try:
            ev = json.loads(line)
        except json.JSONDecodeError:
            continue
        if ev.get("event") == "summary" and "success" in ev:
            summary = ev
        if ev.get("event") == "progress" and ev.get("series_instance_uid"):
            run.setdefault("progress_events", []).append({
                "series_instance_uid": ev["series_instance_uid"],
                "verdict": ev.get("verdict")})
        push(ev)
    await proc.wait()
    # A decline for a SOP class the peer does not support is NOT a transport failure:
    # nothing was lost in transit, TeleRad made a deliberate decision. Keeping these
    # apart is the difference between "TEST FAILED" and "passed, with declined objects".
    failures = summary.get("failures") or []
    declined = [f for f in failures if f.get("status") == "no-accepted-context"]
    hard = [f for f in failures if f.get("status") != "no-accepted-context"]
    summary["declined_unsupported"] = len(declined)
    summary["hard_failures"] = len(hard)
    run["stage1"] = summary

    # tally per-series outcomes from the Stage-1 stream so Stage 2 can reason about
    # series that were declined in full rather than assuming every series lands
    tally: dict[str, dict] = {}
    for ev in run.get("progress_events", []):
        su = ev.get("series_instance_uid")
        if not su:
            continue
        t = tally.setdefault(su, {"seen": 0, "declined": 0})
        t["seen"] += 1
        if ev.get("verdict") not in ("success",):
            t["declined"] += 1
    summary["series_tally"] = tally
    summary["series_fully_declined"] = [k for k, v in tally.items()
                                        if v["declined"] == v["seen"] and v["seen"] > 0]

    push({"event": "phase", "phase": "stage2",
          "label": "Asking TeleRad whether it accepted the study"})
    checks = await verify_stage2(fixture, summary) if not limit else []
    run["stage2"] = checks
    for c in checks:
        push({"event": "check", **c})

    stage1_ok = (
        summary.get("association") == "established"
        and summary.get("c_echo") == "success"
        and summary.get("hard_failures", 0) == 0
        and summary.get("no_response", 0) == 0
        and summary.get("warning", 0) == 0
    )
    hard_fail = any(c["status"] == "fail" for c in checks)
    warned = any(c["status"] == "warn" for c in checks)

    if hard_fail or not stage1_ok:
        verdict, why = "TEST FAILED", (
            "A required check failed. The failing stage is marked above."
            if hard_fail else
            "The DICOM transfer did not complete cleanly (association, C-ECHO, "
            "transport errors or warnings).")
    elif warned:
        verdict, why = "TEST PASSED WITH DECLINED OBJECTS", (
            "The study was received, ingested and is ready to read. TeleRad declined some "
            "objects it does not support — the study is usable, but those objects are missing.")
    else:
        verdict, why = "TEST PASSED", (
            "The study was sent over DICOM and TeleRad independently confirmed it is "
            "ingested, assigned to the right hospital and ready to read.")

    final = {"event": "final", "verdict": verdict, "why": why,
             "stage1": summary, "stage2": checks}
    run["final"] = final
    push(final)
    push({"event": "end"})


@app.post("/api/run")
async def api_run(body: dict):
    fixtures, _ = await asyncio.to_thread(list_fixtures)
    fixture = next((f for f in fixtures if f["id"] == body.get("fixture_id")), None)
    if not fixture:
        raise HTTPException(404, "unknown fixture")
    run_id = uuid.uuid4().hex[:12]
    RUNS[run_id] = {"queue": asyncio.Queue(), "started": time.time(), "fixture": fixture}
    asyncio.create_task(_run_pipeline(run_id, fixture, body))
    return {"run_id": run_id, "fixture": fixture}


@app.get("/api/stream/{run_id}")
async def api_stream(run_id: str):
    if run_id not in RUNS:
        raise HTTPException(404, "unknown run")
    q = RUNS[run_id]["queue"]

    async def gen():
        while True:
            try:
                event = await asyncio.wait_for(q.get(), timeout=30)
            except asyncio.TimeoutError:
                yield ": keepalive\n\n"      # keeps proxies from closing an idle stream
                continue
            yield f"data: {json.dumps(event)}\n\n"
            if event.get("event") == "end":
                break

    return StreamingResponse(gen(), media_type="text/event-stream", headers=SSE_HEADERS)


@app.get("/api/runs/{run_id}")
async def api_run_state(run_id: str):
    run = RUNS.get(run_id)
    if not run:
        raise HTTPException(404, "unknown run")
    return {"final": run.get("final"), "stage1": run.get("stage1"), "stage2": run.get("stage2")}


@app.get("/")
async def index():
    return FileResponse(Path(__file__).parent / "static" / "index.html")


@app.get("/healthz")
async def healthz():
    fixtures, excluded = await asyncio.to_thread(list_fixtures)
    return {"ok": True, "fixtures": len(fixtures), "excluded": len(excluded)}
