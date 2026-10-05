#!/usr/bin/env python3
"""
TeleRad PACS/modality simulator: send a study with C-ECHO + C-STORE and report properly.

This is the *test-instrumented* sibling of ``scripts/modality_send.py``. Same protocol
path, but it treats the exchange as a measurement rather than a one-liner:

* C-ECHO result is reported **separately** from the transfer, because Verification
  (PS3.4 A.4) only proves the endpoint answers — not that it can ingest anything.
* C-STORE responses are classified by DIMSE-C status semantics (PS3.7 ch.9):
  ``success`` (0x0000), ``warning`` (0x0101/0x0103/0x0107/0x0111/0x0113/0x0115/
  0x0117/0x0121 and 0xB000-0xBFFF), or ``failure`` (everything else, plus no response).
  A warning is **not** a pass: a dataset that "does not match the SOP class" or whose
  elements were discarded was not ingested faithfully, so warnings are counted,
  listed, and surfaced in the exit code.
* Every failure records the filename, SOP Instance UID and status code, so a rejected
  instance can be identified — not just counted.

Acceptance criterion for a clean ingest: **zero failures**, association established,
C-ECHO accepted. Warnings are reported and investigated separately.

Usage
-----
  # staged: prove the endpoint first, then send everything
  ./send-study.py /path/to/study.zip --host 127.0.0.1 --port 11112 \\
      --called-ae TELERAD --calling-ae DEMOHOSP_PACS --dry-run
  ./send-study.py /path/to/study.zip --host 127.0.0.1 --port 11112 \\
      --called-ae TELERAD --calling-ae DEMOHOSP_PACS --limit 20
  ./send-study.py /path/to/study.zip --host 127.0.0.1 --port 11112 \\
      --called-ae TELERAD --calling-ae DEMOHOSP_PACS --json-out result.json

Duplicate-ingest regression (run deliberately, not as part of the baseline):
  send the SAME fixture twice and read the second run's warning/failure codes.
  0x0111 is the standard "duplicate SOP instance" warning.
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


def classify(status) -> str:
    """Map a DIMSE-C response to success / warning / failure / no-response."""
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
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("study", nargs="?", type=Path,
                    help="study directory or .zip (positional form)")
    ap.add_argument("--study", dest="study_opt", type=Path,
                    help="study directory or .zip (flag form, as used in the README)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=11112)
    ap.add_argument("--called-ae", default="TELERAD",
                    help="must match the running SCP's AE title (check TELERAD_SCP_AE_TITLE)")
    ap.add_argument("--calling-ae", default="DEMOHOSP_PACS",
                    help="must be an active dicom_nodes row or the SCP refuses the association")
    ap.add_argument("--limit", type=int, default=0, help="send only the first N instances (0 = all)")
    ap.add_argument("--dry-run", action="store_true",
                    help="discover + associate + C-ECHO only; store nothing")
    ap.add_argument("--skip-echo", action="store_true")
    ap.add_argument("--json-out", type=Path, help="write the full result as JSON")
    ap.add_argument("-v", "--verbose", action="store_true", help="log every instance")
    args = ap.parse_args()

    args.study = args.study_opt or args.study
    if args.study is None:
        ap.error("provide the study directory or .zip (positional or --study)")

    started = time.time()
    result: dict = {
        "study": str(args.study),
        "host": args.host, "port": args.port,
        "called_ae": args.called_ae, "calling_ae": args.calling_ae,
        "dry_run": args.dry_run,
        "files_discovered": 0, "selected": 0,
        "association": "not-attempted", "c_echo": "skipped",
        "success": 0, "warning": 0, "failure": 0, "no_response": 0,
        "failures": [], "warnings_sample": [],
        "bytes_sent": 0, "elapsed_seconds": 0.0,
    }

    cleanup = None
    try:
        paths, cleanup = resolve(args.study)
        result["files_discovered"] = len(paths)
        if not paths:
            print("No DICOM files discovered.", file=sys.stderr)
            result["summary"] = "FAIL: no DICOM files found"
            return 2
        if args.limit:
            paths = paths[: args.limit]
        result["selected"] = len(paths)
        print(f"Discovered {result['files_discovered']} instance(s); sending {len(paths)}")

        datasets, sop_classes = [], set()
        for p in paths:
            try:
                ds = load(p)
            except ValueError as exc:
                result["failure"] += 1
                result["failures"].append({"file": p.name, "sop_instance_uid": None,
                                           "status": "unreadable", "detail": str(exc)})
                continue
            datasets.append((p, ds))
            sop_classes.add(str(ds.SOPClassUID))

        ae = AE(ae_title=args.calling_ae)
        ae.add_requested_context(Verification)
        for cuid in sorted(sop_classes):
            ae.add_requested_context(cuid, ExplicitVRLittleEndian)
            ae.add_requested_context(cuid, ImplicitVRLittleEndian)

        print(f"Associating {args.calling_ae} -> {args.called_ae}@{args.host}:{args.port} ...")
        t0 = time.time()
        assoc = ae.associate(args.host, args.port, ae_title=args.called_ae)
        result["associate_seconds"] = round(time.time() - t0, 2)
        result["association"] = "established" if assoc.is_established else "rejected"
        if not assoc.is_established:
            print(f"ASSOCIATION REJECTED by {args.called_ae}@{args.host}:{args.port}", file=sys.stderr)
            print("  Check the SCP is running and that --called-ae matches its AE title.", file=sys.stderr)
            result["summary"] = "FAIL: association rejected"
            return 3
        print(f"Association established ({result['associate_seconds']}s)")

        try:
            if not args.skip_echo:
                echo = assoc.send_c_echo()
                result["c_echo"] = classify(echo)
                result["c_echo_status"] = status_code(echo)
                print(f"C-ECHO: {result['c_echo']} ({result['c_echo_status']})")
                if result["c_echo"] != "success":
                    result["summary"] = "FAIL: C-ECHO not accepted"
                    return 4

            if args.dry_run:
                result["summary"] = "DRY RUN: endpoint verified, nothing stored"
                return 0

            print(f"Storing {len(datasets)} instance(s) ...")
            t0 = time.time()
            for path, ds in datasets:
                status = assoc.send_c_store(ds)
                verdict = classify(status)
                code = status_code(status)
                if verdict == "success":
                    result["success"] += 1
                    result["bytes_sent"] += path.stat().st_size
                    if args.verbose:
                        print(f"  OK   {path.name} {code}")
                else:
                    result[verdict if verdict in ("warning", "failure", "no-response")
                           else "failure"] += 1
                    entry = {"file": path.name,
                             "sop_instance_uid": str(getattr(ds, "SOPInstanceUID", "")),
                             "status": code, "verdict": verdict}
                    if verdict == "warning":
                        if len(result["warnings_sample"]) < 25:
                            result["warnings_sample"].append(entry)
                        print(f"  WARN {path.name} {code}", file=sys.stderr)
                    else:
                        result["failures"].append(entry)
                        print(f"  FAIL {path.name} {code}", file=sys.stderr)
            result["store_seconds"] = round(time.time() - t0, 2)
        finally:
            assoc.release()

        result["elapsed_seconds"] = round(time.time() - started, 2)
        attempted = result["success"] + result["warning"] + result["failure"] + result["no_response"]
        ok = result["failure"] == 0 and result["no_response"] == 0 and attempted > 0

        print("\n---------- PACS SEND SUMMARY ----------")
        print(f"  endpoint            : {args.called_ae}@{args.host}:{args.port} "
              f"(calling AE {args.calling_ae})")
        print(f"  association         : {result['association']} ({result.get('associate_seconds')}s)")
        print(f"  C-ECHO              : {result['c_echo']} {result.get('c_echo_status', '')}")
        print(f"  files discovered    : {result['files_discovered']}")
        print(f"  instances attempted : {attempted}")
        print(f"  success             : {result['success']}")
        print(f"  warnings            : {result['warning']}")
        print(f"  failures            : {result['failure']}")
        print(f"  no response         : {result['no_response']}")
        print(f"  bytes accepted      : {result['bytes_sent']:,}")
        print(f"  elapsed             : {result['elapsed_seconds']}s "
              f"(store {result.get('store_seconds', 0)}s)")
        if result["failures"]:
            print("  FAILED INSTANCES (file | sop_instance_uid | status):")
            for f in result["failures"][:20]:
                print(f"    {f['file']} | {f['sop_instance_uid']} | {f['status']}")
            if len(result["failures"]) > 20:
                print(f"    ... and {len(result['failures']) - 20} more (see --json-out)")
        if result["warning"]:
            print(f"  WARNINGS PRESENT — not a clean ingest; inspect the {len(result['warnings_sample'])}"
                  f" sampled above and the full list via --json-out")
        print(f"  RESULT              : {'PASS (zero failures)' if ok else 'FAIL'}")
        print("--------------------------------------\n")

        result["summary"] = "PASS" if ok else "FAIL"
        if args.json_out:
            args.json_out.write_text(json.dumps(result, indent=2))
            print(f"JSON result written to {args.json_out}")
        # 0 only for a clean ingest: warnings deliberately block a PASS.
        return 0 if ok and result["warning"] == 0 else 5
    finally:
        if cleanup and cleanup.exists():
            shutil.rmtree(cleanup, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
