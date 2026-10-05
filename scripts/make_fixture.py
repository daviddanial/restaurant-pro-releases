#!/usr/bin/env python3
"""
Create, verify and register a fresh acceptance fixture — one command per study.

Why this exists
---------------
Fixtures are **single-use**. Every fixture gets its own Study/Series/SOP UIDs, so once
it has been ingested the same fixture can no longer produce a clean acceptance run —
re-sending it becomes a duplicate-SOP test, which is a *different* test. Preparing more
studies is therefore routine work, and routine work deserves a command rather than an
ad-hoc derivation with the right flags remembered by hand.

What it does
------------
1. Builds the source for this fixture: the full controlled source study, or (with
   ``--subset N``) a stride-sampled slice of N instances that still spans many series —
   useful for quick smoke checks that would otherwise burn a whole fixture.
2. De-identifies it with a fresh salt, giving independent UIDs.
3. Verifies the result against its own source (the gate) and writes both reports.
4. Optionally writes a manifest marking a fixture generated to contain no patient data
   at all (synthetic studies), so the console can offer it without a de-identification
   report it does not need.

Usage
-----
  ./make_fixture.py --letter F                        # full study, ~1,199 instances
  ./make_fixture.py --letter S --subset 24            # smoke fixture, 24 instances
  ./make_fixture.py --letter G --date-shift -6000
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path


def stride_sample(files: list[Path], n: int) -> list[Path]:
    """Pick n files spread evenly across the list so a subset still spans many series."""
    if n >= len(files):
        return files
    step = len(files) / n
    return [files[int(i * step)] for i in range(n)]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--letter", required=True, help="fixture key, e.g. F")
    ap.add_argument("--fixtures", default="/home/hermes/dicom-fixtures")
    ap.add_argument("--source", default=None,
                    help="source directory or .zip (default: <fixtures>/work/original)")
    ap.add_argument("--salt", default=None, help="UID remap salt (default: fixture-<letter>)")
    ap.add_argument("--subset", type=int, default=0, help="use only N instances (0 = all)")
    ap.add_argument("--date-shift", type=int, default=0, help="shift dates by N days")
    ap.add_argument("--name", default=None)
    ap.add_argument("--id", default=None)
    ap.add_argument("--institution", default=None)
    ap.add_argument("--python", default=sys.executable, help="interpreter with pydicom")
    ap.add_argument("--repo", default="/home/hermes/telerad-repo")
    args = ap.parse_args()

    fix = Path(args.fixtures)
    letter = args.letter.upper()
    salt = args.salt or f"fixture-{letter.lower()}"
    name = args.name or f"TEST^PATIENT^{letter}"
    pid = args.id or f"ANON-{letter}-0001"
    inst = args.institution or f"TEST SITE {letter}"
    source = Path(args.source) if args.source else fix / "work" / "original"
    if not source.exists():
        return print(f"source not found: {source}") or 1

    outdir = fix / letter
    outdir.mkdir(parents=True, exist_ok=True)
    outzip = outdir / f"{letter.lower()}-{args.subset or 'full'}.zip"
    reports = fix / "reports"
    reports.mkdir(exist_ok=True)

    deid = Path(args.repo) / "scripts" / "deidentify_study.py"
    verify = Path(args.repo) / "scripts" / "verify_deid.py"

    tmp = Path(tempfile.mkdtemp(prefix=f"fixture-{letter}-"))
    try:
        # ---- 1. decide the source for this fixture -------------------------
        src_for_deid = source
        if args.subset:
            files = sorted(p for p in source.rglob("*") if p.is_file())
            picked = stride_sample(files, args.subset)
            subset_dir = tmp / "subset"
            subset_dir.mkdir(parents=True)
            for f in picked:
                shutil.copy2(f, subset_dir / f.name)
            src_for_deid = subset_dir
            print(f"  subset source: {len(picked)} instance(s) stride-sampled from the source")

        # ---- 2. de-identify -------------------------------------------------
        cmd = [args.python, str(deid), str(src_for_deid), "-o", str(outzip),
               "--copy-id", letter, "--salt", salt, "--name", name, "--id", pid,
               "--institution", inst, "--date-shift-days", str(args.date_shift),
               "--report", str(reports / f"{letter}-deid.json")]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print(r.stdout[-800:]); print(r.stderr[-800:]); return 1
        info = json.loads((reports / f"{letter}-deid.json").read_text())

        # ---- 3. verify against its own source (the gate) --------------------
        cmd = [args.python, str(verify), "--original", str(src_for_deid),
               "--derived", str(outzip), "--salt", salt,
               "--expect-name", name, "--expect-id", pid, "--expect-institution", inst,
               "--report", str(reports / f"{letter}-verify.json")]
        r = subprocess.run(cmd, capture_output=True, text=True)
        gate = json.loads((reports / f"{letter}-verify.json").read_text())["result"] if \
            (reports / f"{letter}-verify.json").exists() else "FAIL"

        print(f"\n  fixture {letter}: {info['instances_converted']} instances, "
              f"{info['series_count']} series, {outzip.stat().st_size/1e6:.0f} MB")
        print(f"  study UID  : {info['study_instance_uid_new']}")
        print(f"  salt       : {salt}")
        print(f"  gate       : {gate}")
        if gate != "PASS":
            print("\n  verification failed — this fixture must NOT be sent:")
            print("\n".join(l for l in r.stdout.splitlines() if "FAIL" in l or "WARNING" in l))
            return 1
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
