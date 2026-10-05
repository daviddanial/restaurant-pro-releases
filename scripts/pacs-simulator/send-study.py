#!/usr/bin/env python3
"""
TeleRad PACS/modality simulator: send a study with C-ECHO + C-STORE and report properly.

Same protocol path as ``scripts/modality_send.py``, but it treats the exchange as a
measurement rather than a one-liner:

* C-ECHO is reported **separately** from the transfer: Verification (PS3.4 A.4) proves
  the endpoint answers, not that it can ingest anything.
* C-STORE responses are classified by DIMSE-C status semantics (PS3.7 ch.9):
  success (0x0000); warning (0x0101/0x0103/0x0107/0x0111/0x0113/0x0115/0x0117/0x0121 and
  0xB000-0xBFFF); failure (everything else, plus no response). A warning is **not** a
  pass: "elements discarded" means it was not ingested faithfully.
* Every failure records filename, SOP Instance UID, SOP Class UID and reason.

Machine-readable mode
---------------------
``--progress-json`` emits one JSON event per line on **stdout** (NDJSON) and moves
human-readable output to stderr, so a UI can show live progress without parsing prose.
Events: ``start``, ``associating``, ``association``, ``echo``, ``progress`` (one per
instance), ``summary``. Without the flag, behaviour is byte-for-byte the old CLI.

Acceptance for a clean ingest: association established, C-ECHO accepted, and **zero
failures** (warnings are reported and block a PASS).
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import tempfile
import time
import zipfile
from pathlib import Path

try:
    from pydicom import dcmread
    from pydicom.dataset import FileMetaDataset
    from pydicom.uid import ExplicitVRLittleEndian, ImplicitVRLittleEndian
except ImportError:  # pragma: no cover
    sys.exit("pydicom is required: pip install -r requirements-dicom.txt")

try:
    from pynetdicom import AE
    from pynetdicom.sop_class import Verification
except ImportError:  # pragma: no cover
    sys.exit("pynetdicom is required: pip install -r requirements-dicom.txt")

SUCCESS = 0x0000
WARNING_CODES = {0x0101, 0x0103, 0x0107, 0x0111, 0x0113, 0x0115, 0x0117, 0x0121}

QUIET = False


def log(*args, **kwargs) -> None:
    print(*args, file=sys.stderr if QUIET else sys.stdout, flush=True, **kwargs)


def emit(**event) -> None:
    if QUIET:
        print(json.dumps(event, default=str), flush=True)


def classify(status) -> str:
    if status is None:
        return "no-response"
    try:
        code = int(status.Status)
    except (AttributeError, TypeError, ValueError):
        return "failure"
    if code == SUCCESS:
        return "success"
    if code in WARNING_CODES or 0xB000 <= code <= 0xBFFF:
        return "warning"
    return "failure"


def status_code(status) -> str:
    if status is None:
        return "none"
    try:
        return f"0x{int(status.Status):04X}"
    except (AttributeError, TypeError, ValueError):
        return "unknown"


def collect(root: Path) -> list[Path]:
    files = sorted(p for p in root.rglob("*") if p.is_file() and not p.name.startswith("."))
    dcm = [p for p in files if p.suffix.lower() in {".dcm", ".dicom"}]
    if dcm:
        return dcm
    readable = []
    for p in files:
        try:
            dcmread(str(p), stop_before_pixels=True, force=True)
            readable.append(p)
        except Exception:
            continue
    return readable


def resolve(path: Path) -> tuple[list[Path], Path | None]:
    path = path.expanduser().resolve()
    if not path.exists():
        raise SystemExit(f"input not found: {path}")
    if path.is_file() and path.suffix.lower() == ".zip":
        tmp = Path(tempfile.mkdtemp(prefix="pacs-sim-"))
        with zipfile.ZipFile(path) as zf:
            zf.extractall(tmp)
        return collect(tmp), tmp
    return collect(path), None


def load(path: Path):
    ds = dcmread(str(path), force=True)
    if not getattr(ds, "SOPClassUID", None) or not getattr(ds, "SOPInstanceUID", None):
        raise ValueError(f"{path}: not a storable DICOM object")
    if not hasattr(ds, "file_meta") or ds.file_meta is None:
        ds.file_meta = FileMetaDataset()
    if not getattr(ds.file_meta, "TransferSyntaxUID", None):
        ds.file_meta.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta.MediaStorageSOPClassUID = ds.SOPClassUID
    ds.file_meta.MediaStorageSOPInstanceUID = ds.SOPInstanceUID
    return ds


def main() -> int:
    global QUIET
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("study", nargs="?", type=Path, help="study directory or .zip")
    ap.add_argument("--study", dest="study_opt", type=Path, help="study directory or .zip")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=11112)
    ap.add_argument("--called-ae", default="TELERAD",
                    help="must match the running SCP's AE title (see TELERAD_SCP_AE_TITLE)")
    ap.add_argument("--calling-ae", default="DEMOHOSP_PACS",
                    help="must be an active dicom_nodes row or the SCP refuses the association")
    ap.add_argument("--limit", type=int, default=0, help="send only the first N instances")
    ap.add_argument("--dry-run", action="store_true",
                    help="discover + associate + C-ECHO only; store nothing")
    ap.add_argument("--skip-echo", action="store_true")
    ap.add_argument("--progress-json", action="store_true",
                    help="emit NDJSON progress events on stdout; human output goes to stderr")
    ap.add_argument("--json-out", type=Path, help="write the full result as JSON")
    ap.add_argument("-v", "--verbose", action="store_true", help="log every instance")
    args = ap.parse_args()

    args.study = args.study_opt or args.study
    if args.study is None:
        ap.error("provide the study directory or .zip (positional or --study)")

    QUIET = bool(args.progress_json)
    started = time.time()
    result: dict = {
        "study": str(args.study), "host": args.host, "port": args.port,
        "called_ae": args.called_ae, "calling_ae": args.calling_ae, "dry_run": args.dry_run,
        "files_discovered": 0, "selected": 0,
        "association": "not-attempted", "c_echo": "skipped",
        "success": 0, "warning": 0, "failure": 0, "no_response": 0,
        "failures": [], "warnings_sample": [], "bytes_sent": 0, "elapsed_seconds": 0.0,
    }

    cleanup = None
    try:
        paths, cleanup = resolve(args.study)
        result["files_discovered"] = len(paths)
        if not paths:
            log("No DICOM files discovered.")
            emit(event="summary", summary="FAIL", detail="no DICOM files found")
            return 2
        if args.limit:
            paths = paths[: args.limit]
        result["selected"] = len(paths)
        log(f"Discovered {result['files_discovered']} instance(s); sending {len(paths)}")
        emit(event="start", files_discovered=result["files_discovered"],
             selected=result["selected"], study=str(args.study))

        datasets, sop_classes = [], set()
        unreadable = 0
        for p in paths:
            try:
                ds = load(p)
            except ValueError as exc:
                unreadable += 1
                result["failure"] += 1
                result["failures"].append({"file": p.name, "sop_instance_uid": None,
                                           "status": "unreadable", "verdict": "failure",
                                           "detail": str(exc)})
                continue
            datasets.append((p, ds))
            sop_classes.add(str(ds.SOPClassUID))

        ae = AE(ae_title=args.calling_ae)
        ae.add_requested_context(Verification)
        for cuid in sorted(sop_classes):
            ae.add_requested_context(cuid, ExplicitVRLittleEndian)
            ae.add_requested_context(cuid, ImplicitVRLittleEndian)

        log(f"Associating {args.calling_ae} -> {args.called_ae}@{args.host}:{args.port} ...")
        emit(event="associating", host=args.host, port=args.port,
             called_ae=args.called_ae, calling_ae=args.calling_ae)
        t0 = time.time()
        assoc = ae.associate(args.host, args.port, ae_title=args.called_ae)
        result["associate_seconds"] = round(time.time() - t0, 2)
        result["association"] = "established" if assoc.is_established else "rejected"
        emit(event="association", status=result["association"],
             seconds=result["associate_seconds"])
        if not assoc.is_established:
            log(f"ASSOCIATION REJECTED by {args.called_ae}@{args.host}:{args.port}")
            log("  Check the SCP is running and that --called-ae matches its AE title.")
            result["summary"] = "FAIL: association rejected"
            emit(event="summary", summary="FAIL", detail="association rejected",
                 association="rejected")
            return 3
        log(f"Association established ({result['associate_seconds']}s)")

        try:
            if not args.skip_echo:
                echo = assoc.send_c_echo()
                result["c_echo"] = classify(echo)
                result["c_echo_status"] = status_code(echo)
                log(f"C-ECHO: {result['c_echo']} ({result['c_echo_status']})")
                emit(event="echo", verdict=result["c_echo"], code=result["c_echo_status"])
                if result["c_echo"] != "success":
                    result["summary"] = "FAIL: C-ECHO not accepted"
                    emit(event="summary", summary="FAIL", detail="C-ECHO not accepted",
                         echo=result["c_echo"])
                    return 4

            if args.dry_run:
                result["summary"] = "DRY RUN: endpoint verified, nothing stored"
                emit(event="summary", summary="DRY RUN", association="established",
                     echo=result["c_echo"], selected=result["selected"])
                return 0

            log(f"Storing {len(datasets)} instance(s) ...")
            t0 = time.time()
            total = len(datasets)
            done = unreadable
            for path, ds in datasets:
                entry = None
                series_uid = str(getattr(ds, "SeriesInstanceUID", ""))
                try:
                    status = assoc.send_c_store(ds)
                except Exception as exc:
                    # The peer declined a presentation context for a SOP class it does not
                    # support; pynetdicom raises instead of returning a status. That is a
                    # per-instance failure — not a reason to abort the whole study.
                    verdict, code = "failure", "no-accepted-context"
                    entry = {"file": path.name,
                             "sop_instance_uid": str(getattr(ds, "SOPInstanceUID", "")),
                             "sop_class_uid": str(getattr(ds, "SOPClassUID", "")),
                             "series_instance_uid": series_uid,
                             "status": code, "verdict": verdict,
                             "detail": str(exc)[:200]}
                    result["failure"] += 1
                    result["failures"].append(entry)
                    log(f"  FAIL {path.name} — no accepted presentation context for "
                        f"SOP class {getattr(ds, 'SOPClassUID', '?')}")
                else:
                    verdict, code = classify(status), status_code(status)
                    if verdict == "success":
                        result["success"] += 1
                        result["bytes_sent"] += path.stat().st_size
                        if args.verbose:
                            log(f"  OK   {path.name} {code}")
                    else:
                        bucket = verdict if verdict in ("warning", "failure", "no-response") else "failure"
                        result[bucket] += 1
                        entry = {"file": path.name,
                                 "sop_instance_uid": str(getattr(ds, "SOPInstanceUID", "")),
                                 "sop_class_uid": str(getattr(ds, "SOPClassUID", "")),
                                 "series_instance_uid": series_uid,
                                 "status": code, "verdict": verdict}
                        if verdict == "warning":
                            if len(result["warnings_sample"]) < 25:
                                result["warnings_sample"].append(entry)
                            log(f"  WARN {path.name} {code}")
                        else:
                            result["failures"].append(entry)
                            log(f"  FAIL {path.name} {code}")
                done += 1
                emit(event="progress", verdict=verdict, file=path.name, code=code,
                     sop_instance_uid=(entry or {}).get("sop_instance_uid"),
                     sop_class_uid=(entry or {}).get("sop_class_uid"),
                     series_instance_uid=series_uid,
                     done=done, total=total + unreadable,
                     elapsed=round(time.time() - t0, 2),
                     bytes_sent=result["bytes_sent"])
            result["store_seconds"] = round(time.time() - t0, 2)
        finally:
            assoc.release()

        result["elapsed_seconds"] = round(time.time() - started, 2)
        attempted = result["success"] + result["warning"] + result["failure"] + result["no_response"]
        ok = result["failure"] == 0 and result["no_response"] == 0 and attempted > 0

        log("\n---------- PACS SEND SUMMARY ----------")
        log(f"  endpoint            : {args.called_ae}@{args.host}:{args.port} "
            f"(calling AE {args.calling_ae})")
        log(f"  association         : {result['association']} ({result.get('associate_seconds')}s)")
        log(f"  C-ECHO              : {result['c_echo']} {result.get('c_echo_status', '')}")
        log(f"  files discovered    : {result['files_discovered']}")
        log(f"  instances attempted : {attempted}")
        log(f"  success             : {result['success']}")
        log(f"  warnings            : {result['warning']}")
        log(f"  failures            : {result['failure']}")
        log(f"  no response         : {result['no_response']}")
        log(f"  bytes accepted      : {result['bytes_sent']:,}")
        log(f"  elapsed             : {result['elapsed_seconds']}s "
            f"(store {result.get('store_seconds', 0)}s)")
        if result["failures"]:
            log("  FAILED INSTANCES (file | sop_instance_uid | status):")
            for f in result["failures"][:20]:
                log(f"    {f['file']} | {f['sop_instance_uid']} | {f['status']}")
            if len(result["failures"]) > 20:
                log(f"    ... and {len(result['failures']) - 20} more (see --json-out)")
        if result["warning"]:
            log(f"  WARNINGS PRESENT — not a clean ingest; inspect the "
                f"{len(result['warnings_sample'])} sampled above and --json-out")
        log(f"  RESULT              : {'PASS (zero failures)' if ok else 'FAIL'}")
        log("--------------------------------------\n")

        result["summary"] = "PASS" if ok else "FAIL"
        emit(event="summary", **result)
        if args.json_out:
            args.json_out.write_text(json.dumps(result, indent=2))
            log(f"JSON result written to {args.json_out}")
        return 0 if ok and result["warning"] == 0 else 5
    finally:
        if cleanup and cleanup.exists():
            shutil.rmtree(cleanup, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
