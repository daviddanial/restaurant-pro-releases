#!/usr/bin/env python3
"""Generate a small synthetic JPEG-LS lossless DICOM study (ZIP) for reproducing
the black-viewport issue: "Viewer: main viewport renders black for JPEG-LS studies".

Creation matches the failing study: modality MR, MONOCHROME2, 16-bit, Transfer
Syntax 1.2.840.10008.1.2.4.80 (JPEG-LS lossless), encapsulated pixel data.

Requirements:
    pip install pydicom numpy gdcm

NOTE: if the GDCM JPEG-LS encoder is unavailable, the study is written
UNCOMPRESSED (TS 1.2.840.10008.1.2.1) and a warning is printed — that zip will
NOT reproduce the JPEG-LS bug (it still works for general viewer smoke tests).

Usage:
    ./make_jpegls_study.py -o jpegls-study.zip --series 2 --instances 6 --with-prior

    # After generating, upload through the Hospital page or:
    curl -s -X POST -H "X-Telerad-Service-Token: dev-service-token" \
      -F "files=@jpegls-study.zip" \
      "http://localhost:18080/api/hospitals/11111111-1111-1111-1111-111111111111/studies/upload"
"""
from __future__ import annotations

import argparse
import sys
import tempfile
import zipfile
from datetime import datetime, timedelta
from pathlib import Path

try:
    import numpy as np
    import pydicom
    from pydicom.dataset import FileDataset, FileMetaDataset
    from pydicom.uid import ExplicitVRLittleEndian, generate_uid, JPEGLSLossless
except ImportError:
    sys.exit("pydicom + numpy required: pip install pydicom numpy gdcm")


def phantom(rows: int = 256, cols: int = 256) -> np.ndarray:
    """Simple MR-like 16-bit phantom: gradient + a few ellipses."""
    y, x = np.mgrid[0:rows, 0:cols]
    yy = (y - rows / 2) / (rows / 2)
    xx = (x - cols / 2) / (cols / 2)
    img = 400 + 1200 * (1 - (xx**2 + yy**2)).clip(0, 1)  # radial gradient 400..1600
    for cx, cy, rx, ry, amp in [(0.25, -0.2, 0.22, 0.18, 2600), (-0.3, 0.25, 0.16, 0.2, 2000)]:
        mask = ((xx - cx) / rx) ** 2 + ((yy - cy) / ry) ** 2 <= 1
        img[mask] = amp
    return img.astype(np.uint16)


def write_instance(path: Path, frames: np.ndarray, study_uid: str, series_uid: str,
                   sop_uid: str, modality: str, patient_id: str, patient_name: str,
                   study_date: str, series_no: int, instance_no: int,
                   jpegls: bool) -> bool:
    rows, cols = frames.shape
    file_meta = FileMetaDataset()
    file_meta.MediaStorageSOPClassUID = pydicom.uid.MRImageStorage
    file_meta.MediaStorageSOPInstanceUID = sop_uid
    file_meta.TransferSyntaxUID = ExplicitVRLittleEndian  # intermediate; gdcm transcodes to JPEG-LS
    file_meta.ImplementationClassUID = pydicom.uid.PYDICOM_IMPLEMENTATION_UID

    ds = FileDataset(str(path), {}, file_meta=file_meta, preamble=b"\x00" * 128)
    ds.SOPClassUID = pydicom.uid.MRImageStorage
    ds.SOPInstanceUID = sop_uid
    ds.StudyInstanceUID = study_uid
    ds.SeriesInstanceUID = series_uid
    ds.Modality = modality
    ds.PatientID = patient_id
    ds.PatientName = patient_name
    ds.PatientBirthDate = "19800101"
    ds.PatientSex = "F"
    ds.StudyDate = study_date
    ds.SeriesDate = study_date
    ds.ContentDate = study_date
    ds.StudyTime = "090000"
    ds.SeriesNumber = series_no
    ds.InstanceNumber = instance_no
    ds.Rows = rows
    ds.Columns = cols
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.BitsAllocated = 16
    ds.BitsStored = 16
    ds.HighBit = 15
    ds.PixelRepresentation = 0
    ds.NumberOfFrames = 1
    ds.SeriesDescription = f"Synthetic MR series {series_no}"
    ds.StudyDescription = "Synthetic JPEG-LS repro study"
    ds.PixelData = frames.tobytes()  # 16-bit MONOCHROME2 pixel data

    # 1) write uncompressed, 2) transcode to JPEG-LS via GDCM (fully optional)
    tmp = path.with_suffix(".uncompressed.dcm")
    pydicom.dcmwrite(tmp, ds, enforce_file_format=True)
    if jpegls:
        try:
            import gdcm  # type: ignore
            r = gdcm.ImageReader()
            r.SetFileName(str(tmp))
            if not r.Read():
                raise RuntimeError("gdcm read failed")
            ch = gdcm.ImageChangeTransferSyntax()
            ch.SetInput(r.GetImage())
            ch.SetTransferSyntax(gdcm.TransferSyntax.JPEGLSLossless)
            out = ch.GetOutput()
            if out is None:
                raise RuntimeError("gdcm JPEG-LS encode returned None")
            w = gdcm.ImageWriter()
            w.SetFileName(str(path))
            w.SetImage(out)
            if not w.Write():
                raise RuntimeError("gdcm write failed")
            tmp.unlink(missing_ok=True)
            return True
        except Exception as e:
            print(f"  ! GDCM JPEG-LS encode failed ({e}); writing UNCOMPRESSED instead")
            tmp.replace(path)
            return False
    tmp.replace(path)
    return False


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("-o", "--output", default="jpegls-study.zip")
    ap.add_argument("--series", type=int, default=2)
    ap.add_argument("--instances", type=int, default=6)
    ap.add_argument("--patient", default="JPEGLS^TEST")
    ap.add_argument("--patient-id", default="JPEGLS-0001")
    ap.add_argument("--with-prior", action="store_true", help="also emit an earlier study (prior comparison test)")
    args = ap.parse_args()

    out = Path(args.output)
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        if not args.with_prior:
            studies = [("current", datetime.now())]
        else:
            studies = [("prior", datetime.now() - timedelta(days=90)), ("current", datetime.now())]

        all_files: list[Path] = []
        jpegls_used = False
        for _label, when in studies:
            study_uid = generate_uid()
            for s in range(1, args.series + 1):
                series_uid = generate_uid()
                for i in range(1, args.instances + 1):
                    fname = td / f"{_label}-series{s:02d}-inst{i:02d}.dcm"
                    sop_uid = generate_uid()
                    ok = write_instance(
                        fname, phantom(), study_uid, series_uid, sop_uid,
                        "MR", args.patient_id, args.patient, when.strftime("%Y%m%d"),
                        s, i, jpegls=True,
                    )
                    jpegls_used = jpegls_used or ok
                    all_files.append(fname)

        with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
            for f in all_files:
                z.write(f, arcname=f.name)

    print(f"Wrote {out} ({len(all_files)} instances, {args.series} series"
          + (", prior study included" if args.with_prior else "") + ")")
    if not jpegls_used:
        print("WARNING: written UNCOMPRESSED — install gdcm (pip install gdcm)")
        print("         to produce the JPEG-LS transfer syntax that reproduces the bug")
    else:
        print("Transfer syntax: JPEG-LS Lossless (1.2.840.10008.1.2.4.80)")
    print("\nUpload:")
    print(f'  curl -s -X POST -H "X-Telerad-Service-Token: dev-service-token" \\')
    print(f'    -F "files=@{out.name}" \\')
    print(f'    "http://localhost:18080/api/hospitals/11111111-1111-1111-1111-111111111111/studies/upload"')


if __name__ == "__main__":
    main()
