#!/usr/bin/env python3
"""Throwaway DICOM storage SCP for validating an acceptance-suite profile end-to-end.

A new site should be able to prove its *configuration* (host, port, called AE,
calling AE) BEFORE pointing the suite at a real hospital PACS. This script is that
target: it answers C-ECHO and stores C-STORE objects like a PACS would, and reports
exactly what arrived.

It is deliberately faithful about the two things that break first:

* a **wrong called AE** is refused (association rejected, diagnostic 0x07);
* an **unknown calling AE** is refused when ``--allow-calling-ae`` names the allowed
  set (association rejected, diagnostic 0x03) — the same tenant-mapping rule the real
  TeleRad SCP applies through ``dicom_nodes``.

It is NOT a PACS: it does not persist a database, does not verify pixels, and must
never be used for a clinical decision. Stored objects go to ``--store-dir`` (a temp
directory by default) and can be deleted with it.

Run it, point the profile at it, then drive the suite (``portability_check.sh``).

Example — validate a site profile on port 11113::

    P=/opt/dicom-tools/bin/python
    $P scripts/portability/mock_scp.py --port 11113 --ae-title MOCKPACS \\
        --allow-calling-ae DEMOHOSP_PACS --max-stores 5 --json-out /tmp/mock.json
    # in another shell:
    scripts/portability/portability_check.sh --profile my-site.env
"""
from __future__ import annotations

import argparse
import json
import signal
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

try:
    from pydicom import dcmread  # noqa: F401  (sanity import for a clear error)
    from pydicom.dataset import Dataset
    from pynetdicom import AE, AllStoragePresentationContexts, evt
    from pynetdicom.sop_class import Verification
except ImportError as exc:  # pragma: no cover - dependency guard
    sys.exit(f"pydicom + pynetdicom are required ({exc}). "
             "Use /opt/dicom-tools/bin/python or pip install -r scripts/requirements-dicom.txt")


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def main() -> int:
    ap = argparse.ArgumentParser(
        description="Throwaway storage SCP for config validation (never a PACS).",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--host", default="127.0.0.1",
                    help="bind address (default 127.0.0.1 — keep it private)")
    ap.add_argument("--port", type=int, default=11113,
                    help="bind port; must differ from the real SCP (default 11113)")
    ap.add_argument("--ae-title", default="MOCKPACS",
                    help="the AE title this mock answers to; the suite's PACS_CALLED_AE "
                         "must match it or the association is refused")
    ap.add_argument("--allow-calling-ae", action="append", default=[],
                    metavar="AE",
                    help="calling AE to accept (repeatable). Omit to accept any caller "
                         "(a logged warning — the real SCP never does this)")
    ap.add_argument("--store-dir", type=Path, default=None,
                    help="where to write received objects (default: a temp directory)")
    ap.add_argument("--max-stores", type=int, default=0,
                    help="stop after storing N objects (0 = no limit)")
    ap.add_argument("--duration", type=float, default=0.0,
                    help="stop after N seconds (0 = run until stopped by signal)")
    ap.add_argument("--expect-echo", type=int, default=0,
                    help="exit non-zero unless at least N C-ECHO responses were sent")
    ap.add_argument("--expect-stores", type=int, default=0,
                    help="exit non-zero unless at least N objects were stored")
    ap.add_argument("--json-out", type=Path, default=None,
                    help="write the run summary as JSON here")
    ap.add_argument("--quiet", action="store_true", help="suppress per-object logging")
    args = ap.parse_args()

    if args.port == 11112 and args.host in ("127.0.0.1", "0.0.0.0", "localhost"):
        print("refusing to bind the real TeleRad SCP port 11112 on this host; "
              "pick a private port for the mock", file=sys.stderr)
        return 2

    store_dir = Path(args.store_dir) if args.store_dir else Path(
        tempfile.mkdtemp(prefix="mock-scp-"))
    store_dir.mkdir(parents=True, exist_ok=True)

    stats: dict = {
        "started": _now(), "ended": None, "host": args.host, "port": args.port,
        "ae_title": args.ae_title, "allow_calling_ae": args.allow_calling_ae,
        "store_dir": str(store_dir), "echoes": 0, "stored": 0,
        "stored_objects": [], "associations_accepted": [], "associations_rejected": [],
    }
    lock = threading.Lock()
    stop = threading.Event()

    def log(*parts) -> None:
        if not args.quiet:
            print(*parts, flush=True)

    # --- DIMSE handlers -----------------------------------------------------
    def handle_echo(event):
        with lock:
            stats["echoes"] += 1
        log(f"[mock] C-ECHO from {event.assoc.requestor.ae_title.strip()!r} -> ok")
        return 0x0000

    def handle_store(event):
        ds: Dataset = event.dataset
        ds.file_meta = event.file_meta
        uid = str(getattr(ds, "SOPInstanceUID", "unknown-uid"))
        out = store_dir / f"{uid}.dcm"
        try:
            ds.save_as(out, enforce_file_format=True)
        except Exception as exc:  # storage fault is a failure, not a silent drop
            with lock:
                stats["stored_objects"].append(
                    {"sop_instance_uid": uid, "file": str(out), "error": str(exc)[:200]})
            log(f"[mock] C-STORE {uid} -> FAILED: {exc}")
            return 0xA700  # Refused: out of resources
        size = out.stat().st_size
        with lock:
            stats["stored"] += 1
            stats["stored_objects"].append(
                {"sop_instance_uid": uid,
                 "sop_class_uid": str(getattr(ds, "SOPClassUID", "")),
                 "series_instance_uid": str(getattr(ds, "SeriesInstanceUID", "")),
                 "file": str(out), "bytes": size})
            reached = args.max_stores and stats["stored"] >= args.max_stores
        log(f"[mock] C-STORE {uid} -> stored ({size:,} bytes)")
        if reached:
            stop.set()
        return 0x0000

    def handle_assoc(event, accepted: bool):
        req = event.assoc.requestor
        entry = {"time": _now(), "calling_ae": req.ae_title.strip(),
                 "address": str(req.address), "accepted": accepted}
        with lock:
            stats["associations_accepted" if accepted else "associations_rejected"].append(entry)

    # --- server -------------------------------------------------------------
    ae = AE(ae_title=args.ae_title)
    ae.add_supported_context(Verification)
    for cx in AllStoragePresentationContexts:
        ae.add_supported_context(cx.abstract_syntax, cx.transfer_syntax)
    # Enforce the two config values that usually break: the called AE (this mock's
    # title) and, when configured, the calling AE (the tenant node).
    ae.require_called_aet = True
    if args.allow_calling_ae:
        ae.require_calling_aet = list(args.allow_calling_ae)
    else:
        log("[mock] WARNING: no --allow-calling-ae given; accepting ANY calling AE. "
            "The real SCP refuses unknown callers — pass the profile's calling AE.")

    handlers = [
        (evt.EVT_C_ECHO, handle_echo),
        (evt.EVT_C_STORE, handle_store),
        (evt.EVT_ACCEPTED, lambda e: handle_assoc(e, True)),
        (evt.EVT_REJECTED, lambda e: handle_assoc(e, False)),
    ]
    server = ae.start_server((args.host, args.port), block=False, evt_handlers=handlers)

    def _shutdown(signum, _frame):
        log(f"[mock] signal {signum}; shutting down")
        stop.set()

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    print(f"[mock] MOCK SCP listening: {args.ae_title}@{args.host}:{args.port} "
          f"(called-AE enforced; calling AE "
          f"{args.allow_calling_ae or 'ANY — relaxed'})", flush=True)
    print(f"[mock] store dir: {store_dir}", flush=True)

    deadline = time.monotonic() + args.duration if args.duration else None
    try:
        while not stop.is_set():
            if deadline and time.monotonic() >= deadline:
                break
            stop.wait(0.2)
    finally:
        server.shutdown()
        server.server_close()

    stats["ended"] = _now()
    ok = stats["echoes"] >= args.expect_echo and stats["stored"] >= args.expect_stores
    stats["verdict"] = "PASS" if ok else "FAIL"
    stats["expected"] = {"echoes": args.expect_echo, "stored": args.expect_stores}

    if args.json_out:
        args.json_out.parent.mkdir(parents=True, exist_ok=True)
        args.json_out.write_text(json.dumps(stats, indent=2))

    print("\n---------- MOCK SCP SUMMARY ----------")
    print(f"  endpoint          : {stats['ae_title']}@{args.host}:{args.port}")
    print(f"  associations      : {len(stats['associations_accepted'])} accepted, "
          f"{len(stats['associations_rejected'])} rejected")
    print(f"  C-ECHO responses  : {stats['echoes']}")
    print(f"  objects stored    : {stats['stored']}")
    print(f"  store dir         : {store_dir}")
    print(f"  expected          : echo>={args.expect_echo}, store>={args.expect_stores}")
    print(f"  RESULT            : {'PASS' if ok else 'FAIL'}")
    print("--------------------------------------")
    return 0 if ok else 3


if __name__ == "__main__":
    raise SystemExit(main())
