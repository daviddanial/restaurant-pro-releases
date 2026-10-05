#!/usr/bin/env python3
"""
Modality / PACS simulator: C-ECHO then C-STORE DICOM files to TeleRad SCP.

Mimics an imaging device (or hospital gateway) sending a finished study after scan.
Default Calling AE is the seed allowlisted node DEMOHOSP_PACS → Demo Hospital tenant.

Examples:
  python3 scripts/modality_send.py --sample
  python3 scripts/modality_send.py /path/to/study_dir
  python3 scripts/modality_send.py study.zip --host 127.0.0.1 --port 11112
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

try:
    from pydicom import dcmread
    from pydicom.dataset import FileMetaDataset
    from pydicom.uid import ExplicitVRLittleEndian, ImplicitVRLittleEndian
except ImportError as e:  # pragma: no cover
    print("Missing dependency: pydicom. Install with:", file=sys.stderr)
    print("  pip3 install -r scripts/requirements-dicom.txt", file=sys.stderr)
    raise SystemExit(1) from e

try:
    from pynetdicom import AE
    from pynetdicom.sop_class import Verification
except ImportError as e:  # pragma: no cover
    print("Missing dependency: pynetdicom. Install with:", file=sys.stderr)
    print("  pip3 install -r scripts/requirements-dicom.txt", file=sys.stderr)
    raise SystemExit(1) from e


REPO_ROOT = Path(__file__).resolve().parent.parent


def collect_dicom_paths(root: Path) -> list[Path]:
    if root.is_file():
        return [root]
    files = sorted(p for p in root.rglob("*") if p.is_file() and not p.name.startswith("."))
    dcm = [p for p in files if p.suffix.lower() in {".dcm", ".dicom"}]
    if dcm:
        return dcm
    readable: list[Path] = []
    for p in files:
        try:
            dcmread(str(p), stop_before_pixels=True, force=True)
            readable.append(p)
        except Exception:
            continue
    return readable


def resolve_inputs(path: Path | None, sample: bool) -> tuple[list[Path], Path | None]:
    """Return (dicom paths, optional temp dir to delete)."""
    if sample:
        tmp = Path(tempfile.mkdtemp(prefix="telerad-sample-"))
        out = tmp / "sample-modality.dcm"
        gen = REPO_ROOT / "scripts" / "generate_sample_dicom.py"
        subprocess.check_call(
            [
                sys.executable,
                str(gen),
                "-o",
                str(out),
                "--patient",
                "SIMULATOR^MODALITY",
                "--modality",
                "CT",
            ],
        )
        return [out], tmp

    if path is None:
        raise SystemExit("Provide a path or use --sample")

    source = path.expanduser().resolve()
    if not source.exists():
        raise FileNotFoundError(source)

    if source.is_file() and source.suffix.lower() == ".zip":
        tmp = Path(tempfile.mkdtemp(prefix="telerad-modality-"))
        with zipfile.ZipFile(source, "r") as zf:
            zf.extractall(tmp)
        return collect_dicom_paths(tmp), tmp

    return collect_dicom_paths(source), None


def load_dataset(path: Path):
    ds = dcmread(str(path), force=True)
    if not getattr(ds, "SOPClassUID", None):
        raise ValueError(f"{path}: missing SOP Class UID")
    if not getattr(ds, "SOPInstanceUID", None):
        raise ValueError(f"{path}: missing SOP Instance UID")
    if not hasattr(ds, "file_meta") or ds.file_meta is None:
        ds.file_meta = FileMetaDataset()
    if not getattr(ds.file_meta, "TransferSyntaxUID", None):
        ds.file_meta.TransferSyntaxUID = ExplicitVRLittleEndian
    ds.file_meta.MediaStorageSOPClassUID = ds.SOPClassUID
    ds.file_meta.MediaStorageSOPInstanceUID = ds.SOPInstanceUID
    return ds


def status_ok(status) -> bool:
    if status is None:
        return False
    return int(getattr(status, "Status", status)) == 0x0000


def main() -> int:
    parser = argparse.ArgumentParser(description="Send DICOM to TeleRad SCP like a modality/PACS")
    parser.add_argument("path", nargs="?", type=Path, help="File, directory, or ZIP of DICOM")
    parser.add_argument("--sample", action="store_true", help="Generate one sample CT and send it")
    parser.add_argument("--host", default="127.0.0.1", help="TeleRad SCP host or IP (default 127.0.0.1)")
    parser.add_argument("--port", type=int, default=11112, help="TeleRad SCP port (default 11112)")
    parser.add_argument("--called-ae", default="TELERAD_SCP", help="Called AE title")
    parser.add_argument(
        "--calling-ae",
        default="DEMOHOSP_PACS",
        help="Calling AE title (must be an active dicom_nodes row)",
    )
    parser.add_argument("--skip-echo", action="store_true", help="Skip C-ECHO verification")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args()

    cleanup: Path | None = None
    try:
        paths, cleanup = resolve_inputs(args.path, args.sample)
        if not paths:
            print("No DICOM files found to send.", file=sys.stderr)
            return 2

        datasets = []
        sop_classes: set[str] = set()
        for p in paths:
            ds = load_dataset(p)
            datasets.append((p, ds))
            sop_classes.add(str(ds.SOPClassUID))

        ae = AE(ae_title=args.calling_ae)
        ae.add_requested_context(Verification)
        for cuid in sorted(sop_classes):
            ae.add_requested_context(cuid, ExplicitVRLittleEndian)
            ae.add_requested_context(cuid, ImplicitVRLittleEndian)

        if args.verbose:
            print(f"Associating {args.calling_ae} → {args.called_ae}@{args.host}:{args.port}")
            print(f"Instances: {len(datasets)}")

        assoc = ae.associate(args.host, args.port, ae_title=args.called_ae)
        if not assoc.is_established:
            print(
                f"Association rejected/failed to {args.called_ae}@{args.host}:{args.port}. "
                "Is the SCP running? (see README — DICOM SCP / modality simulator)",
                file=sys.stderr,
            )
            return 3

        try:
            if not args.skip_echo:
                echo = assoc.send_c_echo()
                if not status_ok(echo):
                    print(f"C-ECHO failed: {echo}", file=sys.stderr)
                    return 4
                print("C-ECHO OK")

            ok = 0
            failed = 0
            for path, ds in datasets:
                status = assoc.send_c_store(ds)
                if status_ok(status):
                    ok += 1
                    if args.verbose:
                        print(f"  C-STORE OK {path.name} sop={ds.SOPInstanceUID}")
                else:
                    failed += 1
                    code = getattr(status, "Status", status)
                    print(f"  C-STORE FAIL {path.name} status={code}", file=sys.stderr)
            print(f"C-STORE done: {ok} ok, {failed} failed (of {len(datasets)})")
            if ok > 0:
                print("Study should appear on the radiologist worklist as READY (source=CSTORE).")
            return 0 if failed == 0 and ok > 0 else 5
        finally:
            assoc.release()
    finally:
        if cleanup and cleanup.exists():
            shutil.rmtree(cleanup, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
