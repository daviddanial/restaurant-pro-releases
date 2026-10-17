#!/usr/bin/env python3
"""Concurrent-ingest check: two synthetic CT studies of one new synthetic patient, each uploaded as
8 batches sent at the same time (16 parallel uploads). Before the fix this raised duplicate-key errors.

Usage: PYTHONPATH=loadtest/pylib python3 -I loadtest/ingest_race.py
Prints the upload results; check the DB / backend log afterwards (see report)."""
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE / "pylib"))

import numpy as np  # noqa: E402
import gen_ingest as g  # noqa: E402

IMAGES, BATCHES = 48, 8


def main():
    pw = json.loads(g.CREDS_FILE.read_text())[g.STAFF_EMAIL]
    token = g.http_json("POST", "/api/auth/login", json.dumps({"email": g.STAFF_EMAIL, "password": pw}).encode())["accessToken"]
    stamp = time.strftime("%H%M%S")
    batches = []
    for k, compressed in ((1, False), (2, True)):
        study = {"uid": g.uid(), "patient_name": "LOADTEST^RACE", "patient_id": f"LT-RACE-{stamp}",
                 "accession": f"LTRACE{stamp}{k}", "description": f"LOADTEST RACE {k}"}
        series_uid, rng = g.uid(), np.random.default_rng(k)
        files = [(f"{i:04d}.dcm", g.build_instance("CT", study, series_uid, i,
                                                  g.phantom(512, 512, i / 25.0, rng, "CT"), compressed))
                 for i in range(IMAGES)]
        per = IMAGES // BATCHES
        batches += [files[b * per:(b + 1) * per] for b in range(BATCHES)]
        print("study", study["accession"], study["uid"], "JPEG-LS" if compressed else "raw")
    t0 = time.time()
    with ThreadPoolExecutor(len(batches)) as pool:
        results = list(pool.map(lambda b: g.upload_batch(token, b), batches))
    print(f"{len(batches)} concurrent batches in {time.time() - t0:.1f}s")
    print("stored per batch:", [r.get("instancesStored") for r in results])
    print("errors:", [e for r in results for e in r.get("errors", [])])
    print("patient id:", f"LT-RACE-{stamp}")


if __name__ == "__main__":
    main()
