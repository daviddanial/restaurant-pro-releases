#!/usr/bin/env python3
"""Derive a synthetic "prior" study from an existing DICOM set, for comparison testing.

The copy keeps PatientID and PatientName so ingest links it to the same patient,
but gets fresh Study/Series/SOP/FrameOfReference UIDs and dates shifted into the
past, so the platform sees a genuinely separate earlier study.

Requires pydicom (pip install pydicom).

    ./make_prior_study.py CURRENT.zip -o PRIOR.zip --days-ago 90
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import zipfile
from datetime import datetime, timedelta
from pathlib import Path

try:
    import pydicom
    from pydicom.uid import generate_uid
except ImportError:
    sys.exit("pydicom is required: pip install pydicom")

# Every UID-valued tag we rewrite. Study/Series/SOP identity must change or ingest
# will treat the copy as a re-upload of the same study.
UID_TAGS = [
    "StudyInstanceUID",
    "SeriesInstanceUID",
    "SOPInstanceUID",
    "MediaStorageSOPInstanceUID",
    "FrameOfReferenceUID",
]

DATE_TAGS = [
    "StudyDate",
    "SeriesDate",
    "AcquisitionDate",
    "ContentDate",
    "InstanceCreationDate",
]


def shift_date(value: str, days: int) -> str:
    try:
        return (datetime.strptime(value, "%Y%m%d") - timedelta(days=days)).strftime("%Y%m%d")
    except ValueError:
        return value


def remap_uid(cache: dict[str, str], original: str) -> str:
    if original not in cache:
        cache[original] = generate_uid()
    return cache[original]


def rewrite_referenced_uids(dataset, cache: dict[str, str]) -> None:
    """Follow sequences and repoint any reference at a UID we already rewrote."""
    for element in dataset:
        if element.VR == "SQ":
            for item in element.value:
                rewrite_referenced_uids(item, cache)
        elif element.VR == "UI" and isinstance(element.value, str) and element.value in cache:
            element.value = cache[element.value]


def convert(path: Path, target: Path, cache: dict[str, str], args) -> None:
    ds = pydicom.dcmread(str(path))

    for tag in UID_TAGS:
        if tag in ds:
            ds.data_element(tag).value = remap_uid(cache, str(ds.data_element(tag).value))
    if ds.file_meta is not None and "MediaStorageSOPInstanceUID" in ds.file_meta:
        ds.file_meta.MediaStorageSOPInstanceUID = ds.SOPInstanceUID

    rewrite_referenced_uids(ds, cache)

    for tag in DATE_TAGS:
        if tag in ds and ds.data_element(tag).value:
            ds.data_element(tag).value = shift_date(str(ds.data_element(tag).value), args.days_ago)

    if args.accession:
        ds.AccessionNumber = args.accession
    if args.description_suffix and "StudyDescription" in ds:
        ds.StudyDescription = f"{ds.StudyDescription}{args.description_suffix}"

    ds.save_as(str(target), enforce_file_format=True)


def process_tree(src: Path, dest: Path, args) -> tuple[int, int, dict[str, str]]:
    cache: dict[str, str] = {}
    converted = 0
    skipped = 0
    for path in sorted(src.rglob("*")):
        if path.is_dir():
            continue
        target = dest / path.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            convert(path, target, cache, args)
            converted += 1
        except Exception:
            shutil.copy2(path, target)
            skipped += 1
    return converted, skipped, cache


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", help="DICOM directory or .zip to copy from")
    parser.add_argument("-o", "--output", required=True, help="output directory or .zip")
    parser.add_argument("--days-ago", type=int, default=90,
                        help="shift all dates back this many days (default: 90)")
    parser.add_argument("--accession", default=None, help="replacement AccessionNumber")
    parser.add_argument("--description-suffix", default=None,
                        help="text appended to StudyDescription, e.g. ' (PRIOR)'")
    args = parser.parse_args()

    source = Path(args.input)
    output = Path(args.output)
    if not source.exists():
        sys.exit(f"input not found: {source}")

    with tempfile.TemporaryDirectory() as tmp:
        tmp_path = Path(tmp)
        if source.is_file() and source.suffix.lower() == ".zip":
            extracted = tmp_path / "in"
            with zipfile.ZipFile(source) as zf:
                zf.extractall(extracted)
            src_root = extracted
        else:
            src_root = source

        if output.suffix.lower() == ".zip":
            staged = tmp_path / "out"
            staged.mkdir()
            converted, skipped, cache = process_tree(src_root, staged, args)
            with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zf:
                for path in sorted(staged.rglob("*")):
                    if path.is_file():
                        zf.write(path, path.relative_to(staged))
        else:
            output.mkdir(parents=True, exist_ok=True)
            converted, skipped, cache = process_tree(src_root, output, args)

    print(f"wrote {converted} files to {output} ({len(cache)} UIDs remapped, dates -{args.days_ago}d)")
    if skipped:
        print(f"copied {skipped} non-DICOM files unchanged")


if __name__ == "__main__":
    main()
