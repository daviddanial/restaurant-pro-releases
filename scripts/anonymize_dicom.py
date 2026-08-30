#!/usr/bin/env python3
"""Replace patient and institution identifiers in a DICOM study before test ingest.

Clinical content is left alone: UIDs, study/series descriptions, acquisition
parameters, dates other than birth date, and the encoded pixel data are all
preserved, so the anonymized copy still exercises the real rendering path.

Requires pydicom (pip install pydicom).

    ./anonymize_dicom.py IN.zip -o OUT.zip --name "TEST^PATIENT" --id ANON-001
    ./anonymize_dicom.py /path/to/dicom/dir -o /path/to/out/dir
"""
from __future__ import annotations

import argparse
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

try:
    import pydicom
except ImportError:
    sys.exit("pydicom is required: pip install pydicom")

BIRTH_DATE_TAG = (0x0010, 0x0030)

# Direct identifiers that would defeat the purpose if left behind. Emptied
# rather than replaced, since nothing downstream reads them.
CLEARED_TAGS = [
    (0x0008, 0x0081),  # InstitutionAddress
    (0x0008, 0x0090),  # ReferringPhysicianName
    (0x0008, 0x0092),  # ReferringPhysicianAddress
    (0x0008, 0x0094),  # ReferringPhysicianTelephoneNumbers
    (0x0008, 0x1040),  # InstitutionalDepartmentName
    (0x0008, 0x1048),  # PhysiciansOfRecord
    (0x0008, 0x1050),  # PerformingPhysicianName
    (0x0008, 0x1060),  # NameOfPhysiciansReadingStudy
    (0x0008, 0x1070),  # OperatorsName
    (0x0010, 0x0021),  # IssuerOfPatientID
    (0x0010, 0x1000),  # OtherPatientIDs
    (0x0010, 0x1001),  # OtherPatientNames
    (0x0010, 0x1005),  # PatientBirthName
    (0x0010, 0x1040),  # PatientAddress
    (0x0010, 0x1060),  # PatientMotherBirthName
    (0x0010, 0x2154),  # PatientTelephoneNumbers
    (0x0010, 0x2160),  # EthnicGroup
    (0x0032, 0x1032),  # RequestingPhysician
    (0x0038, 0x0300),  # CurrentPatientLocation
    (0x0038, 0x0400),  # PatientInstitutionResidence
]


def anonymize(ds, name: str, patient_id: str, institution: str, birth_date: str | None) -> None:
    ds.PatientName = name
    ds.PatientID = patient_id
    ds.InstitutionName = institution

    if birth_date is not None:
        ds.PatientBirthDate = birth_date
    elif BIRTH_DATE_TAG in ds and ds[BIRTH_DATE_TAG].value:
        # Keep the birth year so age stays clinically meaningful, drop the day.
        original = str(ds[BIRTH_DATE_TAG].value)
        ds.PatientBirthDate = original[:4] + "0101" if len(original) >= 4 else ""

    for tag in CLEARED_TAGS:
        if tag in ds:
            ds[tag].value = ""


def process_tree(src: Path, dest: Path, args) -> tuple[int, int]:
    converted = 0
    skipped = 0
    for path in sorted(src.rglob("*")):
        if path.is_dir():
            continue
        target = dest / path.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            ds = pydicom.dcmread(str(path))
        except Exception:
            shutil.copy2(path, target)
            skipped += 1
            continue
        anonymize(ds, args.name, args.id, args.institution, args.birth_date)
        ds.save_as(str(target), enforce_file_format=True)
        converted += 1
    return converted, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input", help="DICOM directory or .zip")
    parser.add_argument("-o", "--output", required=True, help="output directory or .zip")
    parser.add_argument("--name", default="TEST^PATIENT", help="replacement PatientName")
    parser.add_argument("--id", default="ANON-0001", help="replacement PatientID")
    parser.add_argument("--institution", default="TEST SITE", help="replacement InstitutionName")
    parser.add_argument("--birth-date", default=None,
                        help="replacement PatientBirthDate as YYYYMMDD (default: keep year, set to Jan 1)")
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
            converted, skipped = process_tree(src_root, staged, args)
            with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zf:
                for path in sorted(staged.rglob("*")):
                    if path.is_file():
                        zf.write(path, path.relative_to(staged))
        else:
            output.mkdir(parents=True, exist_ok=True)
            converted, skipped = process_tree(src_root, output, args)

    print(f"anonymized {converted} DICOM files -> {output}")
    if skipped:
        print(f"copied {skipped} non-DICOM files unchanged")


if __name__ == "__main__":
    main()
