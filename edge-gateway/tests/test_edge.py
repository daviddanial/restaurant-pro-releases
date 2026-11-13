"""Unit tests for the hospital-server agent. Run: python3 -m unittest discover -s edge-gateway/tests"""
from __future__ import annotations

import base64
import io
import json
import socket
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import pydicom  # noqa: E402
from cryptography.hazmat.primitives import hashes, serialization  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import padding, rsa  # noqa: E402
from pydicom.dataset import Dataset, FileMetaDataset  # noqa: E402
from pydicom.filewriter import dcmwrite  # noqa: E402
from pydicom.uid import ExplicitVRLittleEndian, generate_uid  # noqa: E402

from agent import QueueDb  # noqa: E402
from dicom_header import header_from_stream, header_only  # noqa: E402
from edge_pass import PassError, load_public_key, verify_pass  # noqa: E402

KEY = rsa.generate_private_key(public_exponent=65537, key_size=2048)
PUB_PEM = KEY.public_key().public_bytes(
    serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
GW = "6f1c7a8e-0000-4000-8000-000000000001"


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_pass(claims: dict, key=KEY, alg="RS256") -> str:
    head = b64(json.dumps({"alg": alg, "typ": "JWT"}).encode())
    body = b64(json.dumps(claims).encode())
    sig = key.sign(f"{head}.{body}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{head}.{body}.{b64(sig)}"


def claims(**over):
    c = {"iss": "telerad", "aud": GW, "sub": "user-1", "studies": ["1.2.3"],
         "exp": int(time.time()) + 600, "jti": "j1"}
    c.update(over)
    return c


def make_dicom(study="1.2.3", series="1.2.3.4", sop=None, pixels=b"\x01\x02" * 64, icon=False) -> bytes:
    meta = FileMetaDataset()
    meta.MediaStorageSOPClassUID = "1.2.840.10008.5.1.4.1.1.2"
    meta.MediaStorageSOPInstanceUID = sop or generate_uid()
    meta.TransferSyntaxUID = ExplicitVRLittleEndian
    ds = Dataset()
    ds.file_meta = meta
    ds.SOPClassUID = meta.MediaStorageSOPClassUID
    ds.SOPInstanceUID = meta.MediaStorageSOPInstanceUID
    ds.StudyInstanceUID = study
    ds.SeriesInstanceUID = series
    ds.PatientID = "SYN-1"
    ds.Modality = "CT"
    ds.Rows = 8
    ds.Columns = 8
    ds.BitsAllocated = 16
    ds.BitsStored = 16
    ds.HighBit = 15
    ds.PixelRepresentation = 0
    ds.SamplesPerPixel = 1
    ds.PhotometricInterpretation = "MONOCHROME2"
    if icon:
        item = Dataset()
        item.Rows = 2
        item.Columns = 2
        item.BitsAllocated = 8
        item.PixelData = b"\x00" * 4
        ds.IconImageSequence = [item]
    ds.PixelData = pixels
    out = io.BytesIO()
    dcmwrite(out, ds, write_like_original=False)
    return out.getvalue()


class PassTests(unittest.TestCase):
    key = load_public_key(PUB_PEM)

    def test_valid_pass(self):
        c = verify_pass("" + make_pass(claims()), self.key, GW, "1.2.3")
        self.assertEqual(c["sub"], "user-1")

    def test_rejections(self):
        cases = [
            (make_pass(claims(exp=int(time.time()) - 120)), 401),
            (make_pass(claims(aud="other-gateway")), 403),
            (make_pass(claims(studies=["9.9"])), 403),
            (make_pass(claims(iss="someone")), 401),
            (make_pass(claims(), key=rsa.generate_private_key(public_exponent=65537, key_size=2048)), 401),
            (make_pass(claims(), alg="HS256"), 401),
            ("not.a.pass", 401),
            ("", 401),
        ]
        for token, status in cases:
            with self.assertRaises(PassError) as ctx:
                verify_pass(token, self.key, GW, "1.2.3")
            self.assertEqual(ctx.exception.status, status, token[:20])

    def test_tampered_claims(self):
        head, body, sig = make_pass(claims()).split(".")
        forged = b64(json.dumps(claims(studies=["1.2.3", "4.5.6"])).encode())
        with self.assertRaises(PassError):
            verify_pass(f"{head}.{forged}.{sig}", self.key, GW, "4.5.6")

    def test_no_key_yet(self):
        with self.assertRaises(PassError) as ctx:
            verify_pass(make_pass(claims()), None, GW, "1.2.3")
        self.assertEqual(ctx.exception.status, 503)


class HeaderTests(unittest.TestCase):
    def test_strips_pixel_data_and_keeps_tags(self):
        data = make_dicom(pixels=b"\x07" * 200_000)
        hdr = header_only(data)
        self.assertLess(len(hdr), 2000)
        ds = pydicom.dcmread(io.BytesIO(hdr))
        self.assertNotIn("PixelData", ds)
        self.assertEqual(ds.StudyInstanceUID, "1.2.3")
        self.assertEqual(ds.Rows, 8)
        self.assertEqual(ds.file_meta.TransferSyntaxUID, ExplicitVRLittleEndian)

    def test_stream_stops_at_pixel_data(self):
        data = make_dicom(pixels=b"\x07" * 1_000_000)
        read = []

        def chunks():
            for i in range(0, len(data), 4096):
                read.append(i)
                yield data[i:i + 4096]

        hdr = header_from_stream(chunks())
        self.assertLess(len(read), 3, "should stop after the first chunks")
        self.assertEqual(pydicom.dcmread(io.BytesIO(hdr)).SeriesInstanceUID, "1.2.3.4")

    def test_icon_sequence_falls_back_to_whole_object(self):
        data = make_dicom(icon=True)
        gen = (data[i:i + 64] for i in range(0, len(data), 64))
        hdr = header_from_stream(gen, read_rest=lambda: b"".join(gen))
        ds = pydicom.dcmread(io.BytesIO(hdr))
        self.assertIn("IconImageSequence", ds)
        self.assertNotIn("PixelData", ds)
        self.assertEqual(ds.Modality, "CT")


class QueueTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = QueueDb(Path(self.tmp.name) / "q.sqlite3")

    def tearDown(self):
        self.tmp.cleanup()

    def test_study_lifecycle(self):
        self.db.note_stable("o1", "1.2.3")
        self.assertEqual([r["orthanc_id"] for r in self.db.by_index_state("pending")], ["o1"])
        self.db.set_index_state("o1", "indexed")
        self.assertTrue(self.db.request_upload("o1"))
        self.assertFalse(self.db.request_upload("o1"), "already queued")
        self.assertEqual(self.db.queue_depth(), 1)
        self.db.set_study_state("o1", "uploaded")
        self.assertEqual(self.db.queue_depth(), 0)
        # new images arrive later: indexed again, upload state kept
        self.db.note_stable("o1", "1.2.3")
        row = self.db.find_by_uid("1.2.3")
        self.assertEqual((row["index_state"], row["state"]), ("pending", "uploaded"))

    def test_retention_never_removes_pending_work(self):
        for oid, uid in (("a", "1"), ("b", "2"), ("c", "3")):
            self.db.note_stable(oid, uid)
            self.db.set_index_state(oid, "indexed")
        self.db.request_upload("b")              # upload pending
        self.db.set_index_state("c", "pending")  # index pending
        later = time.time() + 10
        self.assertEqual([r["orthanc_id"] for r in self.db.retention_candidates(later)], ["a"])
        self.assertEqual(self.db.retention_candidates(time.time() - 3600), [])

    def test_upgrades_a_previous_agent_database(self):
        import sqlite3
        path = Path(self.tmp.name) / "old.sqlite3"
        conn = sqlite3.connect(path)
        conn.executescript("""
            CREATE TABLE studies (orthanc_id TEXT PRIMARY KEY, study_uid TEXT, queued_at REAL NOT NULL,
              state TEXT NOT NULL, last_error TEXT, uploaded_at REAL, delete_after REAL);
            INSERT INTO studies VALUES ('x','9.9',100.0,'uploaded',NULL,101.0,NULL);
        """)
        conn.commit()
        conn.close()
        db = QueueDb(path)
        row = db.find_by_uid("9.9")
        self.assertEqual(row["received_at"], 100.0)
        self.assertEqual(row["state"], "uploaded")


class FakeResponse:
    def __init__(self, status=200, body=b"", headers=None, json_body=None):
        self.status_code = status
        self._body = body if json_body is None else json.dumps(json_body).encode()
        self.headers = headers or {}

    def json(self):
        return json.loads(self._body)

    @property
    def content(self):
        return self._body

    def iter_content(self, n):
        for i in range(0, len(self._body), n):
            yield self._body[i:i + n]

    def raise_for_status(self):
        if self.status_code >= 400:
            raise RuntimeError(self.status_code)

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeOrthanc:
    def __init__(self, files: dict[str, tuple[str, str, bytes]]):
        self.files = files  # sop -> (study, series, bytes)
        self.calls = []

    def post(self, path, data=None, **kw):
        self.calls.append(path)
        if path == "/tools/lookup" and data in self.files:
            return FakeResponse(json_body=[{"ID": "oid-" + data, "Type": "Instance"}])
        return FakeResponse(json_body=[])

    def get(self, path, **kw):
        self.calls.append(path)
        sop = path.split("/")[2].removeprefix("oid-") if path.startswith("/instances/") else None
        if sop not in self.files:
            return FakeResponse(404)
        study, series, data = self.files[sop]
        if path.endswith("/study"):
            return FakeResponse(json_body={"MainDicomTags": {"StudyInstanceUID": study}})
        if path.endswith("/series"):
            return FakeResponse(json_body={"MainDicomTags": {"SeriesInstanceUID": series}})
        if path.endswith("/file"):
            return FakeResponse(body=data, headers={"Content-Length": str(len(data))})
        if path.endswith("/rendered"):
            return FakeResponse(body=b"\xff\xd8jpeg", headers={"Content-Type": "image/jpeg"})
        return FakeResponse(404)


class FakeAgent:
    def __init__(self, orthanc):
        self.orthanc = orthanc
        self.gateway_id = GW
        self.audit = type("A", (), {"rows": [], "write": lambda self, e, **f: self.rows.append((e, f))})()
        self._key = load_public_key(PUB_PEM)

    def pass_public_key(self):
        return self._key

    def web_origins(self):
        return ["http://localhost:*", "https://telerad.example.et"]

    def orthanc_ok(self):
        return True


class ImageServerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from image_server import ImageServer
        cls.dicom = make_dicom(study="1.2.3", series="1.2.3.4", sop="1.2.3.4.5")
        cls.orthanc = FakeOrthanc({"1.2.3.4.5": ("1.2.3", "1.2.3.4", cls.dicom),
                                   "7.7.7": ("7.7", "7.7.1", b"other")})
        cls.agent = FakeAgent(cls.orthanc)
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        cls.port = s.getsockname()[1]
        s.close()
        cls.server = ImageServer(cls.agent, "127.0.0.1", cls.port, None, None)
        cls.server.start()
        cls.base = f"http://127.0.0.1:{cls.port}"

    @classmethod
    def tearDownClass(cls):
        cls.server.stop()

    def req(self, path, method="GET", headers=None):
        r = urllib.request.Request(self.base + path, method=method, headers=headers or {})
        try:
            with urllib.request.urlopen(r, timeout=5) as resp:
                return resp.status, dict(resp.headers), resp.read()
        except urllib.error.HTTPError as e:
            return e.code, dict(e.headers), e.read()

    def test_health_is_open(self):
        status, _, body = self.req("/health")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["gatewayId"], GW)

    def test_serves_the_file_with_a_valid_pass(self):
        path = "/dicom/studies/1.2.3/series/1.2.3.4/instances/1.2.3.4.5"
        status, headers, body = self.req(path, headers={
            "Authorization": "Bearer " + make_pass(claims()), "Origin": "http://localhost:5173"})
        self.assertEqual(status, 200)
        self.assertEqual(body, self.dicom)
        self.assertEqual(headers["Access-Control-Allow-Origin"], "http://localhost:5173")
        self.assertEqual(headers["Content-Type"], "application/dicom")
        self.assertIn(("image_access", {"user": "user-1", "study_uid": "1.2.3", "jti": "j1"}), self.agent.audit.rows)

    def test_refuses_without_or_with_a_wrong_pass(self):
        path = "/dicom/studies/1.2.3/series/1.2.3.4/instances/1.2.3.4.5"
        self.assertEqual(self.req(path)[0], 401)
        other = make_pass(claims(studies=["7.7"]))
        self.assertEqual(self.req(path, headers={"Authorization": "Bearer " + other})[0], 403)

    def test_pass_for_one_study_cannot_read_another_studys_image(self):
        # The pass covers study 7.7, but SOP 1.2.3.4.5 belongs to study 1.2.3.
        token = make_pass(claims(studies=["7.7"]))
        status, _, _ = self.req("/dicom/studies/7.7/series/7.7.1/instances/1.2.3.4.5",
                                headers={"Authorization": "Bearer " + token})
        self.assertEqual(status, 404)

    def test_unknown_image(self):
        status, _, body = self.req("/dicom/studies/1.2.3/series/1.2.3.4/instances/9.9.9",
                                   headers={"Authorization": "Bearer " + make_pass(claims())})
        self.assertEqual((status, body), (404, b"IMAGE_NOT_ON_HOSPITAL_SERVER"))

    def test_preview_frame(self):
        status, headers, body = self.req("/dicom/studies/1.2.3/series/1.2.3.4/instances/1.2.3.4.5/frames/1?maxSize=256",
                                         headers={"Authorization": "Bearer " + make_pass(claims())})
        self.assertEqual(status, 200)
        self.assertEqual(headers["Content-Type"], "image/jpeg")

    def test_preflight_for_a_public_site_on_the_lan(self):
        status, headers, _ = self.req("/dicom/studies/1.2.3/series/1.2.3.4/instances/1.2.3.4.5", method="OPTIONS",
                                      headers={"Origin": "https://telerad.example.et",
                                               "Access-Control-Request-Method": "GET",
                                               "Access-Control-Request-Headers": "authorization, x-evil",
                                               "Access-Control-Request-Private-Network": "true"})
        self.assertEqual(status, 204)
        self.assertEqual(headers["Access-Control-Allow-Origin"], "https://telerad.example.et")
        self.assertEqual(headers["Access-Control-Allow-Headers"], "authorization")
        self.assertEqual(headers["Access-Control-Allow-Private-Network"], "true")

    def test_preflight_from_an_unknown_site_is_refused(self):
        status, headers, _ = self.req("/health", method="OPTIONS", headers={"Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        self.assertNotIn("Access-Control-Allow-Origin", headers)


if __name__ == "__main__":
    unittest.main()
