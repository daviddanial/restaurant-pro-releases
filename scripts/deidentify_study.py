#!/usr/bin/env python3
"""
De-identify a DICOM study for test ingest, with internally consistent UID remapping.

Why this exists next to ``anonymize_dicom.py``
---------------------------------------------
``anonymize_dicom.py`` deliberately *preserves* UIDs, which is right for showing the
real rendering path. For **split test fixtures** (portal copy A / PACS copy B) we need
different Study/Series/SOP/Frame-of-Reference UIDs per copy, so the two ingest paths
stay genuinely independent and duplicate-handling behaviour cannot contaminate either.

What it does
------------
* Replaces/clears the direct-identifier attributes of the DICOM PS3.15 Basic
  Application Level Confidentiality Profile (not exhaustive — see KNOWN LIMITS).
* Remaps every non-standard UID **consistently**: the same original UID always maps to
  the same new UID, across all instances of the input, so references inside sequences
  stay intact. Standard ``1.2.840.10008.*`` UIDs (SOP classes, transfer syntaxes) are
  preserved.
* Shifts ``DA``/``DT`` values by a fixed number of days so clinical intervals survive,
  while ``PatientBirthDate`` keeps only its year (age stays meaningful).
* Removes odd-group (private) attributes.

The mapping is deterministic in (original UID, salt), so a fixture can be rebuilt
byte-for-byte from the same input and salt.

Usage
-----
  ./deidentify_study.py IN.zip -o OUT.zip --copy-id A --salt portal-a \
      --name "TEST^PATIENT^A" --id ANON-A-0001 --institution "TEST SITE A" \
      --date-shift-days -1000 --report OUT.report.json

KNOWN LIMITS (checked by verify_deid.py, not fixed here)
--------------------------------------------------------
* Burned-in pixel annotation cannot be removed safely by a script; the report records
  ``BurnedInAnnotation`` so a human can inspect the flagged instances.
* No attempt is made to preserve study/series descriptions or acquisition parameters
  beyond leaving them untouched: they are clinical content, not identifiers.
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import shutil
import sys
import tempfile
import zipfile
from datetime import datetime, timedelta, timezone
from pathlib import Path

try:
    import pydicom
    from pydicom.tag import Tag
except ImportError:  # pragma: no cover
    sys.exit("pydicom is required: pip install pydicom")

# Non-standard UID root reserved for this tool's derived fixtures.
# Derived UIDs must NOT collide with the originals or with real-world UIDs.
UID_ROOT = "1.2.826.0.1.3680043.10.1738."
# Every DICOM-registered UID lives under this arc (SOP classes, transfer syntaxes,
# well-known instances). Those must survive verbatim or the images become unreadable.
STANDARD_UID_PREFIX = "1.2.840.10008."
# Never remap these file-meta UIDs: they describe the writer, not the patient.
FILE_META_PRESERVE = {"TransferSyntaxUID", "ImplementationClassUID",
                      "ImplementationVersionName", "MediaStorageSOPClassUID"}

BIRTH_DATE = Tag(0x0010, 0x0030)

# Replace with the supplied fixture identity. Values are argparse dest names.
REPLACED_TAGS = {
    Tag(0x0010, 0x0010): "name",          # PatientName   <- --name
    Tag(0x0010, 0x0020): "id",            # PatientID     <- --id
    Tag(0x0008, 0x0080): "institution",   # InstitutionName <- --institution
}

# Blanked outright: nothing downstream reads them, and they are direct identifiers.
CLEARED_TAGS = [
    Tag(0x0008, 0x0081),  # InstitutionAddress
    Tag(0x0008, 0x0090),  # ReferringPhysicianName
    Tag(0x0008, 0x0092),  # ReferringPhysicianAddress
    Tag(0x0008, 0x0094),  # ReferringPhysicianTelephoneNumbers
    Tag(0x0008, 0x1010),  # StationName
    Tag(0x0008, 0x1040),  # InstitutionalDepartmentName
    Tag(0x0008, 0x1048),  # PhysiciansOfRecord
    Tag(0x0008, 0x1050),  # PerformingPhysicianName
    Tag(0x0008, 0x1060),  # NameOfPhysiciansReadingStudy
    Tag(0x0008, 0x1070),  # OperatorsName
    Tag(0x0008, 0x0084),  # InstitutionCodeSequence
    Tag(0x0008, 0x0050),  # AccessionNumber  (identifies the order at the source site)
    Tag(0x0010, 0x0021),  # IssuerOfPatientID
    Tag(0x0010, 0x1000),  # OtherPatientIDs
    Tag(0x0010, 0x1001),  # OtherPatientNames
    Tag(0x0010, 0x1005),  # PatientBirthName
    Tag(0x0010, 0x1040),  # PatientAddress
    Tag(0x0010, 0x1060),  # PatientMotherBirthName
    Tag(0x0010, 0x2154),  # PatientTelephoneNumbers
    Tag(0x0010, 0x2160),  # EthnicGroup
    Tag(0x0010, 0x4000),  # PatientComments  (free text: often identifying)
    Tag(0x0032, 0x1032),  # RequestingPhysician
    Tag(0x0038, 0x0300),  # CurrentPatientLocation
    Tag(0x0038, 0x0400),  # PatientInstitutionResidence
    Tag(0x0040, 0x0241),  # PerformedProcedureStepID (site-local identifier)
    Tag(0x0040, 0x0253),  # PerformedProcedureStepDescription
    Tag(0x0040, 0x0275),  # RequestAttributesSequence
    Tag(0x0008, 0x1032),  # ProcedureCodeSequence (site coding)
]

BURNED_IN = Tag(0x0028, 0x0301)


def make_uid(original: str, salt: str) -> str:
    """Deterministic, collision-resistant UID under UID_ROOT for a given original+salt."""
    digest = hashlib.sha256(f"{salt}\x00{original}".encode()).hexdigest()
    return UID_ROOT + str(int(digest[:24], 16))


class Stats:
    def __init__(self, salt: str) -> None:
        self.salt = salt
        self.uid_map: dict[str, str] = {}
        self.private_removed = 0
        self.dates_shifted = 0
        self.instances = 0
        self.series_uids: set[str] = set()
        self.study_uid: str | None = None
        self.burned_in: collections.Counter = collections.Counter()
        self.missing_sop_class = 0

    def map_uid(self, value: str) -> str:
        if value not in self.uid_map:
            self.uid_map[value] = make_uid(value, self.salt)
        return self.uid_map[value]


def shift_date(value: str, days: int) -> str:
    if not value or days == 0:
        return value
    try:
        d = datetime.strptime(value.strip(), "%Y%m%d")
    except ValueError:
        return value
    return (d + timedelta(days=days)).strftime("%Y%m%d")


def shift_datetime(value: str, days: int) -> str:
    if not value or days == 0:
        return value
    raw = value.strip()
    for fmt, out in (("%Y%m%d%H%M%S.%f", "%Y%m%d%H%M%S.%f"),
                     ("%Y%m%d%H%M%S", "%Y%m%d%H%M%S"),
                     ("%Y%m%d%H%M", "%Y%m%d%H%M"),
                     ("%Y%m%d", "%Y%m%d")):
        try:
            d = datetime.strptime(raw, fmt)
        except ValueError:
            continue
        shifted = d + timedelta(days=days)
        return shifted.strftime(out) if "%f" not in out else shifted.strftime(out)[:len(out) - 3]
    return value


def scrub_dataset(ds, args, stats: Stats, top_level: bool = False) -> None:
    """Recursively de-identify one dataset in place."""
    to_delete = []
    for elem in ds:
        tag = elem.tag

        if tag.is_private:
            to_delete.append(tag)
            stats.private_removed += 1
            continue

        if elem.VR == "SQ":
            for item in elem.value:
                scrub_dataset(item, args, stats)
            continue

        if tag in REPLACED_TAGS:
            setattr(ds, elem.keyword, getattr(args, REPLACED_TAGS[tag]))
            continue

        if tag in CLEARED_TAGS:
            elem.value = ""
            continue

        if tag == BIRTH_DATE:
            original = str(elem.value or "")
            elem.value = (original[:4] + "0101") if len(original) >= 4 else ""
            continue

        if elem.VR == "UI":
            value = str(elem.value or "")
            # Keep DICOM-registered UIDs; remap everything else consistently.
            if value and not value.startswith(STANDARD_UID_PREFIX):
                elem.value = stats.map_uid(value)
            continue

        if elem.VR == "DA" and args.date_shift_days:
            elem.value = shift_date(str(elem.value or ""), args.date_shift_days)
            if elem.value:
                stats.dates_shifted += 1
        elif elem.VR == "DT" and args.date_shift_days:
            elem.value = shift_datetime(str(elem.value or ""), args.date_shift_days)
            if elem.value:
                stats.dates_shifted += 1

    for tag in to_delete:
        del ds[tag]

    if top_level:
        sop_class = getattr(ds, "SOPClassUID", None)
        if not sop_class:
            stats.missing_sop_class += 1
        burned = ds.get(BURNED_IN, None)
        stats.burned_in[str(burned.value) if burned else "absent"] += 1
        if ds.get("StudyInstanceUID"):
            stats.study_uid = str(ds.StudyInstanceUID)
        if ds.get("SeriesInstanceUID"):
            stats.series_uids.add(str(ds.SeriesInstanceUID))


def process_file(path: Path, dest: Path, args, stats: Stats) -> bool:
    try:
        ds = pydicom.dcmread(str(path))
    except Exception:
        return False

    scrub_dataset(ds, args, stats, top_level=True)

    # File meta must describe the *new* instance or PACS rejects the object.
    if ds.file_meta is None:
        from pydicom.dataset import FileMetaDataset
        ds.file_meta = FileMetaDataset()
    if getattr(ds, "SOPInstanceUID", None):
        ds.file_meta.MediaStorageSOPInstanceUID = ds.SOPInstanceUID
    if getattr(ds, "SOPClassUID", None):
        ds.file_meta.MediaStorageSOPClassUID = ds.SOPClassUID

    dest.parent.mkdir(parents=True, exist_ok=True)
    ds.save_as(str(dest), enforce_file_format=True)
    return True


def iter_input(root: Path):
    return sorted(p for p in root.rglob("*") if p.is_file() and not p.name.startswith("."))


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help="DICOM directory or .zip (never modified)")
    ap.add_argument("-o", "--output", required=True, help="output directory or .zip")
    ap.add_argument("--copy-id", default="A", help="fixture label recorded in the report")
    ap.add_argument("--salt", required=True, help="UID remap salt: same salt -> same UIDs")
    ap.add_argument("--name", default="TEST^PATIENT")
    ap.add_argument("--id", default="ANON-0001")
    ap.add_argument("--institution", default="TEST SITE")
    ap.add_argument("--date-shift-days", type=int, default=0,
                    help="shift DA/DT by N days (0 = leave dates as-is)")
    ap.add_argument("--report", help="write a JSON report to this path")
    args = ap.parse_args()

    source = Path(args.input).expanduser()
    output = Path(args.output).expanduser()
    if not source.exists():
        sys.exit(f"input not found: {source}")

    stats = Stats(args.salt)
    tmp = Path(tempfile.mkdtemp(prefix="deid-"))
    try:
        if source.is_file() and source.suffix.lower() == ".zip":
            extract_dir = tmp / "in"
            with zipfile.ZipFile(source) as zf:
                zf.extractall(extract_dir)
            src_root = extract_dir
        else:
            src_root = source

        out_dir = tmp / "out"
        converted = skipped = 0
        for path in iter_input(src_root):
            rel = path.relative_to(src_root)
            if process_file(path, out_dir / rel, args, stats):
                converted += 1
                stats.instances += 1
            else:
                # Not a DICOM object (or unreadable): copy through so the fixture stays
                # a faithful mirror, and say so in the report.
                dest = out_dir / rel
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(path, dest)
                skipped += 1

        output.parent.mkdir(parents=True, exist_ok=True)
        if output.suffix.lower() == ".zip":
            with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zf:
                for path in iter_input(out_dir):
                    zf.write(path, path.relative_to(out_dir))
        else:
            if output.exists():
                shutil.rmtree(output)
            shutil.copytree(out_dir, output)

        report = {
            "copy_id": args.copy_id,
            "salt": args.salt,
            "input": str(source),
            "output": str(output),
            "instances_converted": converted,
            "files_copied_through": skipped,
            "series_count": len(stats.series_uids),
            "uid_pairs_remapped": len(stats.uid_map),
            "private_attributes_removed": stats.private_removed,
            "dates_shifted": stats.dates_shifted,
            "date_shift_days": args.date_shift_days,
            "burned_in_annotation": dict(stats.burned_in),
            "instances_without_sop_class": stats.missing_sop_class,
            "identity": {"PatientName": args.name, "PatientID": args.id,
                         "InstitutionName": args.institution},
            "study_instance_uid_new": stats.study_uid,
            "exported_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }
        if args.report:
            Path(args.report).write_text(json.dumps(report, indent=2))
        print(json.dumps(report, indent=2))
        return 0
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
