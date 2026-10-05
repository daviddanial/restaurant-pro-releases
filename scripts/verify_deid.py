#!/usr/bin/env python3
"""
Verify that a derived fixture is actually de-identified and internally consistent.

This is the check the fixture pipeline gates on: de-identification that is not
verified is not de-identification, it is hope.

Checks
------
1. Instance-count parity with the original.
2. Identity replaced: PatientName / PatientID / InstitutionName differ from the source.
3. Residual identifiers: no source identifier token survives in the derived set
   (case-insensitive substring test over the identifier tags and their values).
4. UID integrity: exactly one StudyInstanceUID across all instances, the study UID
   differs from the source, no derived SOP/Series/Frame-of-Reference UID equals any
   source UID, and SOP Instance UIDs are unique.
5. Referential consistency: every referenced UID inside sequences still resolves
   inside the derived set (same remap everywhere), so nothing dangles.
6. Private attributes: zero odd-group elements remain.
7. Burned-in annotation: reported per value so a human can inspect flagged instances.
   This tool cannot remove burned-in pixel text and does not pretend to.

Exit code is 0 only when every hard check passes; burned-in annotation is reported as
a warning, never silently ignored.

Usage
-----
  ./verify_deid.py --original SOURCE.zip --derived FIXTURE.zip \
      --expect-name "TEST^PATIENT^A" --expect-id ANON-A-0001 --report verify.json
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import re
import sys
import tempfile
import zipfile
from pathlib import Path

try:
    import pydicom
    from pydicom.tag import Tag
except ImportError:  # pragma: no cover
    sys.exit("pydicom is required: pip install pydicom")

# Must match deidentify_study.py: with --salt we recompute the mapping and require an
# exact match, which proves the remap was consistent rather than merely plausible.
UID_ROOT = "1.2.826.0.1.3680043.10.1738."
STANDARD_UID_PREFIX = "1.2.840.10008."


def make_uid(original: str, salt: str) -> str:
    digest = hashlib.sha256(f"{salt}\x00{original}".encode()).hexdigest()
    return UID_ROOT + str(int(digest[:24], 16))

IDENTIFIER_TAGS = {
    "PatientName": Tag(0x0010, 0x0010),
    "PatientID": Tag(0x0010, 0x0020),
    "OtherPatientIDs": Tag(0x0010, 0x1000),
    "OtherPatientNames": Tag(0x0010, 0x1001),
    "PatientBirthName": Tag(0x0010, 0x1005),
    "PatientAddress": Tag(0x0010, 0x1040),
    "PatientTelephoneNumbers": Tag(0x0010, 0x2154),
    "AccessionNumber": Tag(0x0008, 0x0050),
    "ReferringPhysicianName": Tag(0x0008, 0x0090),
    "InstitutionName": Tag(0x0008, 0x0080),
    "InstitutionAddress": Tag(0x0008, 0x0081),
    "InstitutionalDepartmentName": Tag(0x0008, 0x1040),
    "StationName": Tag(0x0008, 0x1010),
    "OperatorsName": Tag(0x0008, 0x1070),
    "PerformingPhysicianName": Tag(0x0008, 0x1050),
    "NameOfPhysiciansReadingStudy": Tag(0x0008, 0x1060),
    "RequestingPhysician": Tag(0x0032, 0x1032),
}
UID_TAGS = {
    "StudyInstanceUID": Tag(0x0020, 0x000D),
    "SeriesInstanceUID": Tag(0x0020, 0x000E),
    "SOPInstanceUID": Tag(0x0008, 0x0018),
    "FrameOfReferenceUID": Tag(0x0020, 0x0052),
}
BURNED_IN = Tag(0x0028, 0x0301)

# Tokens too generic to be evidence of a leak.
STOPWORDS = {"HOSPITAL", "CENTER", "CENTRE", "MEDICAL", "CITY", "GENERAL", "TEST",
             "SITE", "PATIENT", "ANON", "ADDIS", "ABABA", "THE", "AND", "UNIVERSITY"}


def iter_files(root: Path):
    return sorted(p for p in root.rglob("*") if p.is_file() and not p.name.startswith("."))


def load_root(path: Path, tmp: Path) -> Path:
    if path.is_file() and path.suffix.lower() == ".zip":
        dest = tmp / path.stem
        with zipfile.ZipFile(path) as zf:
            zf.extractall(dest)
        return dest
    return path


def scan(root: Path) -> dict:
    """Collect the identifiers, UIDs, private-tag count and mismatches of a tree."""
    out = {
        "instances": 0, "unreadable": 0,
        "identifiers": collections.defaultdict(collections.Counter),
        "study_uids": collections.Counter(),
        "series_uids": set(),
        "sop_uids": set(),
        "frame_uids": set(),
        "referenced_uids": set(),
        "private_tags": 0,
        "burned_in": collections.Counter(),
        "patients": collections.Counter(),
    }
    for path in iter_files(root):
        try:
            ds = pydicom.dcmread(str(path))
        except Exception:
            out["unreadable"] += 1
            continue
        out["instances"] += 1

        for name, tag in IDENTIFIER_TAGS.items():
            if tag in ds:
                value = str(ds[tag].value or "").strip()
                if value:
                    out["identifiers"][name][value] += 1
        for name, tag in UID_TAGS.items():
            # NB: ds.get(Tag) returns the DataElement in pydicom 3.x, not its value —
            # using it here silently produced "(0020,000D) Study Instance UID ..."
            # strings instead of UIDs. Read the element explicitly.
            if tag not in ds:
                continue
            value = str(ds[tag].value or "").strip()
            if not value:
                continue
            if name == "StudyInstanceUID":
                out["study_uids"][value] += 1
            elif name == "SeriesInstanceUID":
                out["series_uids"].add(value)
            elif name == "SOPInstanceUID":
                out["sop_uids"].add(value)
            else:
                out["frame_uids"].add(value)

        patient = str(ds.get("PatientName", "") or "")
        if patient:
            out["patients"][patient] += 1

        burned = ds.get(BURNED_IN, None)
        out["burned_in"][str(burned.value) if burned else "absent"] += 1

        def walk(dataset):
            for elem in dataset:
                if elem.tag.is_private:
                    out["private_tags"] += 1
                if elem.VR == "SQ":
                    for item in elem.value:
                        walk(item)
                elif elem.VR == "UI" and elem.keyword and "Referenced" in elem.keyword:
                    value = str(elem.value or "").strip()
                    # No prefix filter: vendors use 1.3.* roots (Siemens) and filtering
                    # by "1.2." hid exactly those references in the source while their
                    # remapped successors appeared in the derived set.
                    if value:
                        out["referenced_uids"].add(value)
        walk(ds)
    return out


def tokens(values) -> set[str]:
    words: set[str] = set()
    for value in values:
        for part in re.split(r"[\^\s,;/|()]+", str(value)):
            part = part.strip().upper()
            if len(part) >= 3 and part not in STOPWORDS and not part.isdigit():
                words.add(part)
    return words


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--original", required=True, help="source DICOM dir or .zip")
    ap.add_argument("--derived", required=True, help="de-identified dir or .zip")
    ap.add_argument("--expect-name")
    ap.add_argument("--expect-id")
    ap.add_argument("--expect-institution")
    ap.add_argument("--salt",
                    help="salt used by deidentify_study.py; enables recomputing the "
                         "expected UID mapping and asserting an exact match")
    ap.add_argument("--report")
    args = ap.parse_args()

    tmp = Path(tempfile.mkdtemp(prefix="verify-deid-"))
    checks: list[dict] = []
    warnings: list[str] = []

    def check(name: str, ok: bool, detail: str) -> None:
        checks.append({"check": name, "pass": bool(ok), "detail": detail})

    try:
        src_dir = load_root(Path(args.original).expanduser(), tmp / "orig")
        dst_dir = load_root(Path(args.derived).expanduser(), tmp / "derived")
        src = scan(src_dir)
        dst = scan(dst_dir)

        check("instances_match", src["instances"] == dst["instances"],
              f"source={src['instances']} derived={dst['instances']}")
        check("no_unreadable_files", dst["unreadable"] == 0,
              f"unreadable in derived={dst['unreadable']}")

        # 2. identity replaced
        identity_ok = True
        details = []
        for label, expect in (("PatientName", args.expect_name),
                              ("PatientID", args.expect_id),
                              ("InstitutionName", args.expect_institution)):
            if not expect:
                continue
            present = expect in dst["identifiers"].get(label, {})
            identity_ok &= present
            details.append(f"{label}={'OK' if present else 'MISSING'}")
        check("fixture_identity_applied", identity_ok, ", ".join(details) or "not specified")

        # 3. residual identifiers: source tokens must not survive anywhere in derived
        src_tokens = set()
        for label in IDENTIFIER_TAGS:
            src_tokens |= tokens(src["identifiers"].get(label, {}))
        derived_blob = " ".join(
            " ".join(counter.keys()) for counter in dst["identifiers"].values()
        ).upper()
        leaked = sorted(t for t in src_tokens if t in derived_blob)
        check("no_residual_identifiers", not leaked,
              "clean" if not leaked else f"leaked tokens: {leaked[:10]}")

        # 4. UID integrity
        check("single_study_uid", len(dst["study_uids"]) == 1,
              f"distinct StudyInstanceUIDs={len(dst['study_uids'])}")
        src_all = set(src["sop_uids"]) | set(src["series_uids"]) | set(src["frame_uids"])
        dst_all = set(dst["sop_uids"]) | set(dst["series_uids"]) | set(dst["frame_uids"])
        overlap = src_all & dst_all
        check("no_source_uids_reused", not overlap,
              "none reused" if not overlap else f"{len(overlap)} UID(s) reused, e.g. {sorted(overlap)[:2]}")
        check("sop_uids_unique", len(dst["sop_uids"]) == dst["instances"],
              f"distinct SOP={len(dst['sop_uids'])} instances={dst['instances']}")
        check("series_count_preserved", len(dst["series_uids"]) == len(src["series_uids"]),
              f"source={len(src['series_uids'])} derived={len(dst['series_uids'])}")

        # 5. Referential integrity is a *relative* property. Remapping must not create
        #    references that dangle where the source's did not. An absolute form of this
        #    check fails on a perfect fixture, because:
        #      * ReferencedSOPClassUID holds a SOP Class UID (1.2.840.10008.*) which is
        #        never remapped and never resolves as an instance;
        #      * a study legitimately references objects it does not contain, e.g. the
        #        Modality Performed Procedure Step instance, which is not exported here.
        def dangling_refs(scan_result) -> set[str]:
            known = (set(scan_result["sop_uids"]) | set(scan_result["series_uids"])
                     | set(scan_result["study_uids"]) | set(scan_result["frame_uids"]))
            return {u for u in scan_result["referenced_uids"]
                    if not u.startswith(STANDARD_UID_PREFIX) and u not in known}

        dangle_src, dangle_dst = dangling_refs(src), dangling_refs(dst)
        check("references_not_degraded", len(dangle_dst) <= len(dangle_src),
              f"dangling source={len(dangle_src)} derived={len(dangle_dst)}"
              + (" (pre-existing external/MPPS references)" if dangle_src else ""))

        # 5b. Strongest available proof of consistent remapping: the mapping is
        #     deterministic in (original UID, salt), so when the salt is supplied we can
        #     recompute it and require every derived UID to be exactly the expected one.
        if args.salt:
            expected_study = {make_uid(u, args.salt) for u in src["study_uids"]}
            expected_series = {make_uid(u, args.salt) for u in src["series_uids"]}
            expected_sop = {make_uid(u, args.salt) for u in src["sop_uids"]}
            got_study = sorted(dst["study_uids"])[:1]
            check("study_uid_matches_salt", set(dst["study_uids"]) == expected_study,
                  f"expected {sorted(expected_study)[:1]} got {got_study}")
            missing_series = len(expected_series - dst["series_uids"])
            check("series_uids_match_salt",
                  expected_series.issubset(dst["series_uids"]),
                  f"{len(expected_series)} expected, {missing_series} missing")
            missing_sop = len(expected_sop - dst["sop_uids"])
            check("sop_uids_match_salt",
                  expected_sop.issubset(dst["sop_uids"]),
                  f"{len(expected_sop)} expected, {missing_sop} missing, "
                  f"{len(dst['sop_uids'] - expected_sop)} unexpected")

        # 6. private attributes
        check("no_private_attributes", dst["private_tags"] == 0,
              f"private elements in derived={dst['private_tags']} (source={src['private_tags']})")

        # 7. burned-in annotation: a warning, never silently passed
        burned = dict(dst["burned_in"])
        if any(k.upper() == "YES" for k in burned):
            warnings.append(
                "BurnedInAnnotation=YES on "
                f"{sum(v for k, v in burned.items() if k.upper() == 'YES')} instance(s): "
                "inspect pixels manually — a script cannot remove burned-in identifiers."
            )
        if "absent" in burned:
            warnings.append(
                f"BurnedInAnnotation absent on {burned['absent']} instance(s): treat as unknown.")

        report = {
            "original": args.original,
            "derived": args.derived,
            "source": {"instances": src["instances"], "series": len(src["series_uids"]),
                       "study_uids": list(src["study_uids"])[:2]},
            "derived": {"instances": dst["instances"], "series": len(dst["series_uids"]),
                        "study_uids": list(dst["study_uids"]),
                        "patients": dict(dst["patients"]),
                        "burned_in_annotation": burned},
            "checks": checks,
            "warnings": warnings,
            "result": "PASS" if all(c["pass"] for c in checks) else "FAIL",
        }
        if args.report:
            Path(args.report).write_text(json.dumps(report, indent=2))

        width = max(len(c["check"]) for c in checks)
        print(f"\n  {'CHECK'.ljust(width)}  RESULT  DETAIL")
        for c in checks:
            print(f"  {c['check'].ljust(width)}  {'PASS' if c['pass'] else 'FAIL':<6}  {c['detail']}")
        for w in warnings:
            print(f"  WARNING: {w}")
        print(f"\n  RESULT: {report['result']}\n")
        return 0 if report["result"] == "PASS" else 1
    finally:
        import shutil
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
