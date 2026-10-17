#!/usr/bin/env python3
"""Generate synthetic (no real patient data) CT / MR / CR studies and ingest them
through the hospital upload API, the same path a facility uses.

Usage: PYTHONPATH=loadtest/pylib python3 -I loadtest/gen_ingest.py --modality CT --count 20
Writes loadtest/studies.json (appends) with study UIDs and encodings.
"""
from __future__ import annotations

import argparse
import datetime as dt
import io
import json
import shutil
import sys
import time
import urllib.request
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE / "pylib"))

import jpeg_ls  # noqa: E402
import numpy as np  # noqa: E402
from pydicom.dataset import Dataset, FileMetaDataset  # noqa: E402
from pydicom.encaps import encapsulate  # noqa: E402
from pydicom.uid import ExplicitVRLittleEndian, JPEGLSLossless  # noqa: E402

API = "http://127.0.0.1:38471"
FACILITY_A = "11111111-1111-1111-1111-111111111111"
STAFF_EMAIL = "lt_uploader@telerad.local"
CREDS_FILE = HERE / "creds.json"

SPEC = {
    # modality: (images, rows, cols, sop_class, bits_stored)
    "CT": (520, 512, 512, "1.2.840.10008.5.1.4.1.1.2", 12),
    "MR": (320, 512, 512, "1.2.840.10008.5.1.4.1.1.4", 12),
    "CR": (2, 3000, 2500, "1.2.840.10008.5.1.4.1.1.1", 12),
}


def uid() -> str:
    return "2.25." + str(uuid.uuid4().int)


def phantom(rows: int, cols: int, z: float, rng: np.random.Generator, modality: str) -> np.ndarray:
    """Body ellipse + organs that change with z, plus noise, so compression ratios are realistic."""
    y, x = np.ogrid[-1:1:rows * 1j, -1:1:cols * 1j]
    img = np.zeros((rows, cols), np.float32)
    body = (x / 0.85) ** 2 + (y / 0.65) ** 2 < 1
    if modality == "CT":
        img[:] = 24  # air (HU -1000 with intercept -1024)
        img[body] = 1064  # soft tissue ~ +40 HU
        lung = (((x + 0.38) / 0.28) ** 2 + (y / (0.4 + 0.1 * np.sin(z))) ** 2 < 1) | \
               (((x - 0.38) / 0.28) ** 2 + (y / (0.4 + 0.1 * np.cos(z))) ** 2 < 1)
        img[lung & body] = 200
        spine = (x / 0.08) ** 2 + ((y - 0.45) / 0.08) ** 2 < 1
        img[spine] = 1900
        noise = 18
    else:  # MR / CR
        img[:] = 5
        img[body] = 900
        organ = ((x - 0.2 * np.sin(z)) / 0.3) ** 2 + (y / 0.25) ** 2 < 1
        img[organ] = 1500
        noise = 25 if modality == "MR" else 12
    img += rng.normal(0, noise, img.shape).astype(np.float32)
    return np.clip(img, 0, 4095).astype(np.uint16)


def build_instance(modality, study, series_uid, idx, arr, compressed):
    n, rows, cols, sop_class, bits = SPEC[modality]
    sop_uid = uid()
    meta = FileMetaDataset()
    meta.MediaStorageSOPClassUID = sop_class
    meta.MediaStorageSOPInstanceUID = sop_uid
    meta.TransferSyntaxUID = JPEGLSLossless if compressed else ExplicitVRLittleEndian
    meta.ImplementationClassUID = "2.25.1"
    ds = Dataset()
    ds.file_meta = meta
    ds.is_little_endian, ds.is_implicit_VR = True, False
    ds.SOPClassUID, ds.SOPInstanceUID = sop_class, sop_uid
    ds.StudyInstanceUID, ds.SeriesInstanceUID = study["uid"], series_uid
    ds.PatientName = study["patient_name"]
    ds.PatientID = study["patient_id"]
    ds.PatientBirthDate = "19700101"
    ds.PatientSex = "O"
    ds.StudyDate = ds.SeriesDate = ds.ContentDate = dt.date.today().strftime("%Y%m%d")
    ds.StudyTime = "120000"
    ds.AccessionNumber = study["accession"]
    ds.StudyDescription = study["description"]
    ds.SeriesDescription = f"LOADTEST {modality} {'JPEGLS' if compressed else 'RAW'}"
    ds.Modality = modality
    ds.SeriesNumber = 1
    ds.InstanceNumber = idx + 1
    ds.Rows, ds.Columns = rows, cols
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    ds.BitsAllocated, ds.BitsStored, ds.HighBit, ds.PixelRepresentation = 16, bits, bits - 1, 0
    spacing = 0.7 if modality != "CR" else 0.14
    ds.PixelSpacing = [spacing, spacing]
    if modality != "CR":
        ds.ImageOrientationPatient = [1, 0, 0, 0, 1, 0]
        ds.ImagePositionPatient = [-rows * spacing / 2, -cols * spacing / 2, -idx * 1.0]
        ds.SliceLocation = -idx * 1.0
        ds.SliceThickness = 1.0
    if modality == "CT":
        ds.RescaleIntercept, ds.RescaleSlope = -1024, 1
        ds.WindowCenter, ds.WindowWidth = 40, 400
    else:
        ds.WindowCenter, ds.WindowWidth = 1000, 2000
    if compressed:
        ds.PixelData = encapsulate([bytes(jpeg_ls.encode(arr))])
        ds["PixelData"].is_undefined_length = True
        ds["PixelData"].VR = "OB"
    else:
        ds.PixelData = arr.tobytes()
    buf = io.BytesIO()
    ds.save_as(buf, write_like_original=False)
    return buf.getvalue()


def http_json(method, path, body=None, token=None, headers=None):
    h = {"Content-Type": "application/json", **(headers or {})}
    if token:
        h["Authorization"] = "Bearer " + token
    req = urllib.request.Request(API + path, data=body, method=method, headers=h)
    with urllib.request.urlopen(req, timeout=600) as r:
        return json.loads(r.read() or b"{}")


def upload_batch(token, files):
    boundary = uuid.uuid4().hex
    out = io.BytesIO()
    for name, data in files:
        out.write(f"--{boundary}\r\nContent-Disposition: form-data; name=\"files\"; filename=\"{name}\"\r\n"
                  f"Content-Type: application/dicom\r\n\r\n".encode())
        out.write(data)
        out.write(b"\r\n")
    out.write(f"--{boundary}--\r\n".encode())
    return http_json("POST", f"/api/hospitals/{FACILITY_A}/studies/upload?priority=ROUTINE", out.getvalue(),
                     token, {"Content-Type": f"multipart/form-data; boundary={boundary}"})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--modality", required=True, choices=SPEC)
    ap.add_argument("--count", type=int, default=20)
    ap.add_argument("--start", type=int, default=1)
    ap.add_argument("--batch", type=int, default=50)
    args = ap.parse_args()

    pw = json.loads(CREDS_FILE.read_text())[STAFF_EMAIL]
    token = http_json("POST", "/api/auth/login", json.dumps({"email": STAFF_EMAIL, "password": pw}).encode())["accessToken"]
    index_file = HERE / "studies.json"
    index = json.loads(index_file.read_text()) if index_file.exists() else []
    n, rows, cols, *_ = SPEC[args.modality]

    for k in range(args.start, args.start + args.count):
        compressed = k % 2 == 0  # alternate raw / JPEG-LS
        study = {
            "uid": uid(), "modality": args.modality, "compressed": compressed, "images": n,
            "patient_name": f"LOADTEST^{args.modality}{k:02d}", "patient_id": f"LT-{args.modality}-{k:02d}",
            "accession": f"LT{args.modality}{k:02d}", "description": f"LOADTEST {args.modality} {k:02d}",
        }
        series_uid = uid()
        rng = np.random.default_rng(k)
        t0, total_bytes, batch = time.time(), 0, []
        for i in range(n):
            arr = phantom(rows, cols, i / 25.0, rng, args.modality)
            data = build_instance(args.modality, study, series_uid, i, arr, compressed)
            total_bytes += len(data)
            batch.append((f"{i:04d}.dcm", data))
            if len(batch) == args.batch or i == n - 1:
                upload_batch(token, batch)
                batch = []
        study["bytes"] = total_bytes
        index.append(study)
        index_file.write_text(json.dumps(index, indent=1))
        print(f"{study['accession']} {'JPEG-LS' if compressed else 'raw'} {n} img {total_bytes/1e6:.0f} MB "
              f"in {time.time()-t0:.0f}s", flush=True)


if __name__ == "__main__":
    main()
