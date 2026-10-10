"""
TeleRad Edge Agent — Orthanc changes feed → queue → mTLS multipart ingest.

Orthanc (unmodified Docker) is the DICOM receiver. This agent:
  - polls Orthanc /changes (persists cursor)
  - queues StableStudy events
  - uploads instances to TeleRad ingest (mTLS or bearer-dev)
  - deletes from Orthanc after confirmed upload + safety period
  - heartbeats ops metrics (no PHI)
"""
from __future__ import annotations

import argparse
import json
import logging
import os
import random
import sqlite3
import ssl
import threading
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

import requests
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

LOG = logging.getLogger("edge-agent")


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class Audit:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

    def write(self, event: str, **fields):
        row = {"ts": utc_now(), "event": event, **fields}
        for banned in ("PatientName", "PatientID", "PatientBirthDate", "patientName", "patientId"):
            row.pop(banned, None)
        line = json.dumps(row, separators=(",", ":"))
        with self._lock:
            with self.path.open("a", encoding="utf-8") as f:
                f.write(line + "\n")


class QueueDb:
    def __init__(self, path: Path):
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        with self._connect() as conn:
            conn.executescript(
                """
                CREATE TABLE IF NOT EXISTS meta (
                  key TEXT PRIMARY KEY,
                  value TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS studies (
                  orthanc_id TEXT PRIMARY KEY,
                  study_uid TEXT,
                  queued_at REAL NOT NULL,
                  state TEXT NOT NULL,
                  last_error TEXT,
                  uploaded_at REAL,
                  delete_after REAL
                );
                CREATE TABLE IF NOT EXISTS rejected_senders (
                  ae TEXT NOT NULL,
                  ip TEXT NOT NULL,
                  last_seen REAL NOT NULL,
                  count INTEGER NOT NULL,
                  PRIMARY KEY (ae, ip)
                );
                """
            )

    def _connect(self):
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        return conn

    def get_meta(self, key: str, default: str | None = None) -> str | None:
        with self._lock, self._connect() as conn:
            row = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
            return row["value"] if row else default

    def set_meta(self, key: str, value: str):
        with self._lock, self._connect() as conn:
            conn.execute(
                "INSERT INTO meta(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value),
            )

    def queue_study(self, orthanc_id: str, study_uid: str | None):
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT INTO studies(orthanc_id, study_uid, queued_at, state)
                VALUES(?,?,?, 'queued')
                ON CONFLICT(orthanc_id) DO UPDATE SET
                  study_uid=COALESCE(excluded.study_uid, studies.study_uid),
                  state=CASE WHEN studies.state='uploaded' THEN studies.state ELSE 'queued' END,
                  last_error=NULL
                """,
                (orthanc_id, study_uid, time.time()),
            )

    def studies_by_state(self, *states: str) -> list[sqlite3.Row]:
        placeholders = ",".join("?" * len(states))
        with self._lock, self._connect() as conn:
            return conn.execute(
                f"SELECT * FROM studies WHERE state IN ({placeholders}) ORDER BY queued_at",
                states,
            ).fetchall()

    def set_study_state(self, orthanc_id: str, state: str, error: str | None = None, delete_after: float | None = None):
        with self._lock, self._connect() as conn:
            if state == "uploaded":
                conn.execute(
                    """
                    UPDATE studies SET state=?, last_error=NULL, uploaded_at=?, delete_after=COALESCE(?, delete_after)
                    WHERE orthanc_id=?
                    """,
                    (state, time.time(), delete_after, orthanc_id),
                )
            else:
                conn.execute(
                    "UPDATE studies SET state=?, last_error=? WHERE orthanc_id=?",
                    (state, error, orthanc_id),
                )

    def deletable(self) -> list[sqlite3.Row]:
        now = time.time()
        with self._lock, self._connect() as conn:
            return conn.execute(
                """
                SELECT * FROM studies
                WHERE state='uploaded' AND delete_after IS NOT NULL AND delete_after <= ?
                """,
                (now,),
            ).fetchall()

    def delete_study_row(self, orthanc_id: str):
        with self._lock, self._connect() as conn:
            conn.execute("DELETE FROM studies WHERE orthanc_id=?", (orthanc_id,))

    def oldest_queued_age_seconds(self) -> float | None:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT MIN(queued_at) AS q FROM studies WHERE state IN ('queued','failed','uploading')"
            ).fetchone()
            if not row or row["q"] is None:
                return None
            return max(0.0, time.time() - float(row["q"]))

    def queue_depth(self) -> int:
        with self._lock, self._connect() as conn:
            row = conn.execute(
                "SELECT COUNT(*) AS c FROM studies WHERE state IN ('queued','failed','uploading')"
            ).fetchone()
            return int(row["c"])

    def note_rejected(self, ae: str, ip: str):
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT INTO rejected_senders(ae, ip, last_seen, count) VALUES(?,?,?,1)
                ON CONFLICT(ae, ip) DO UPDATE SET last_seen=excluded.last_seen, count=rejected_senders.count+1
                """,
                (ae, ip, time.time()),
            )

    def rejected_snapshot(self, limit: int = 50) -> list[dict]:
        with self._lock, self._connect() as conn:
            rows = conn.execute(
                "SELECT ae, ip, count, last_seen FROM rejected_senders ORDER BY last_seen DESC LIMIT ?",
                (limit,),
            ).fetchall()
            return [
                {"ae": r["ae"], "ip": r["ip"], "count": r["count"], "lastSeen": r["last_seen"]}
                for r in rows
            ]


class OrthancClient:
    def __init__(self, base: str, user: str, password: str):
        self.base = base.rstrip("/")
        self.auth = (user, password) if user else None

    def get(self, path: str, **kwargs):
        return requests.get(self.base + path, auth=self.auth, timeout=60, **kwargs)

    def delete(self, path: str, **kwargs):
        return requests.delete(self.base + path, auth=self.auth, timeout=60, **kwargs)

    def changes(self, since: int, limit: int = 100) -> dict:
        r = self.get("/changes", params={"since": since, "limit": limit})
        r.raise_for_status()
        return r.json()

    def study(self, orthanc_id: str) -> dict:
        r = self.get(f"/studies/{orthanc_id}")
        r.raise_for_status()
        return r.json()

    def instance_file(self, instance_id: str) -> bytes:
        r = self.get(f"/instances/{instance_id}/file")
        r.raise_for_status()
        return r.content

    def system(self) -> dict:
        r = self.get("/system")
        r.raise_for_status()
        return r.json()

    def delete_study(self, orthanc_id: str):
        r = self.delete(f"/studies/{orthanc_id}")
        if r.status_code not in (200, 404):
            r.raise_for_status()


class EdgeAgent:
    def __init__(self, cfg: dict, cfg_path: Path):
        self.cfg = cfg
        self.cfg_path = cfg_path
        self.root = Path(cfg.get("data_dir", "./data")).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.db = QueueDb(self.root / "queue.sqlite3")
        self.audit = Audit(self.root / "audit.jsonl")
        self.safety = float(cfg.get("delete_safety_seconds", 86400))
        self.batch_size = int(cfg.get("upload_batch_size", 20))
        self.api_base = cfg["telerad_api_url"].rstrip("/")
        self.mtls_base = (cfg.get("telerad_mtls_url") or self.api_base).rstrip("/")
        self.token = cfg.get("gateway_access_token")
        self.cert_path = Path(cfg.get("client_cert", self.root / "client.crt"))
        self.key_path = Path(cfg.get("client_key", self.root / "client.key"))
        self.ca_path = Path(cfg.get("ca_cert", self.root / "ca.crt"))
        self.orthanc = OrthancClient(
            cfg.get("orthanc_url", "http://orthanc:8042"),
            cfg.get("orthanc_user", "agent"),
            cfg.get("orthanc_password", "agent"),
        )
        self.allowlist_path = Path(cfg.get("allowlist_file", "allowlist.json"))
        self.heartbeat_seconds = float(cfg.get("heartbeat_seconds", 300))
        self._stop = threading.Event()
        self._last_upload_ok: float | None = None
        self._tls_inspection: bool = False
        self._critical: list[dict] = []

    def _auth_base(self) -> str:
        if self.cert_path.exists() and self.key_path.exists():
            return self.mtls_base
        return self.api_base

    def _session(self) -> requests.Session:
        s = requests.Session()
        if self.cert_path.exists() and self.key_path.exists():
            s.cert = (str(self.cert_path), str(self.key_path))
            if self.ca_path.exists():
                s.verify = str(self.ca_path)
            else:
                s.verify = False
        elif self.token:
            s.headers["Authorization"] = f"Bearer {self.token}"
        else:
            raise RuntimeError("No client cert or bearer token configured")
        return s

    def poll_changes(self):
        since = int(self.db.get_meta("orthanc_since", "0") or "0")
        try:
            data = self.orthanc.changes(since=since, limit=100)
        except Exception as e:
            LOG.warning("Orthanc changes failed: %s", e)
            return
        last = data.get("Last", since)
        for ch in data.get("Changes", []):
            ctype = ch.get("ChangeType")
            if ctype == "StableStudy":
                oid = ch.get("ID")
                study_uid = None
                try:
                    meta = self.orthanc.study(oid)
                    study_uid = (meta.get("MainDicomTags") or {}).get("StudyInstanceUID")
                except Exception:
                    pass
                self.db.queue_study(oid, study_uid)
                self.audit.write("queued", orthanc_id=oid, study_uid=study_uid)
                LOG.info("Queued StableStudy %s uid=%s", oid, study_uid)
        if last != since:
            self.db.set_meta("orthanc_since", str(last))

    def upload_worker(self):
        backoff = 2.0
        while not self._stop.is_set():
            try:
                self.poll_changes()
                for row in self.db.studies_by_state("queued", "failed"):
                    self.upload_study(row["orthanc_id"])
                    backoff = 2.0
                self.cleanup_orthanc()
                self._stop.wait(5)
            except Exception as e:
                LOG.exception("Upload loop error: %s", e)
                self.audit.write("system_error", error=str(e))
                self._critical.append({"type": "system_error", "error": str(e), "ts": utc_now()})
                self._stop.wait(min(backoff, 300))
                backoff = min(backoff * 2, 300)

    def upload_study(self, orthanc_id: str):
        self.db.set_study_state(orthanc_id, "uploading")
        try:
            meta = self.orthanc.study(orthanc_id)
        except Exception as e:
            self.db.set_study_state(orthanc_id, "failed", str(e))
            return
        study_uid = (meta.get("MainDicomTags") or {}).get("StudyInstanceUID")
        instance_ids: list[str] = []
        for series_id in meta.get("Series", []):
            sr = self.orthanc.get(f"/series/{series_id}")
            sr.raise_for_status()
            instance_ids.extend(sr.json().get("Instances") or [])

        if not instance_ids:
            self.db.set_study_state(orthanc_id, "failed", "no instances")
            return

        session = self._session()
        url = f"{self._auth_base()}/api/gateway/ingest"
        for i in range(0, len(instance_ids), self.batch_size):
            batch_ids = instance_ids[i : i + self.batch_size]
            files = []
            blobs = []
            try:
                for iid in batch_ids:
                    blob = self.orthanc.instance_file(iid)
                    blobs.append(blob)
                    files.append(("files", (f"{iid}.dcm", blob, "application/dicom")))
                complete = i + self.batch_size >= len(instance_ids)
                resp = session.post(
                    url,
                    files=files,
                    data={"studyComplete": "true" if complete else "false"},
                    timeout=180,
                )
            except ssl.SSLError as e:
                self._tls_inspection = True
                self.db.set_study_state(orthanc_id, "failed", f"ssl: {e}")
                self.audit.write("tls_error", orthanc_id=orthanc_id, error=str(e))
                self._critical.append({"type": "tls_inspection", "error": str(e), "ts": utc_now()})
                return
            except Exception as e:
                self.db.set_study_state(orthanc_id, "failed", str(e))
                raise

            if resp.status_code == 403:
                self.audit.write("upload_forbidden", orthanc_id=orthanc_id)
                self.db.set_study_state(orthanc_id, "failed", "forbidden")
                raise RuntimeError("Gateway unauthorized/disabled")

            if resp.status_code >= 500 or resp.status_code == 429:
                self.db.set_study_state(orthanc_id, "failed", f"http {resp.status_code}")
                raise RuntimeError(f"Transient HTTP {resp.status_code}")

            if resp.status_code >= 400:
                self.db.set_study_state(orthanc_id, "failed", resp.text[:500])
                self.audit.write("upload_rejected", orthanc_id=orthanc_id, detail=resp.text[:500])
                return

            payload = resp.json()
            for r in payload.get("results", []):
                st = r.get("status")
                if st in ("stored", "duplicate"):
                    self.audit.write(
                        "uploaded",
                        orthanc_id=orthanc_id,
                        study_uid=r.get("studyInstanceUid") or study_uid,
                        sop_uid=r.get("sopInstanceUid"),
                        status=st,
                        request_id=payload.get("requestId"),
                    )
                else:
                    self.audit.write(
                        "upload_failed",
                        orthanc_id=orthanc_id,
                        sop_uid=r.get("sopInstanceUid"),
                        reason=r.get("reason"),
                        failure_class=r.get("failureClass"),
                    )
            failed = int(payload.get("failed") or 0)
            if failed:
                self.db.set_study_state(orthanc_id, "failed", f"{failed} instance(s) failed")
                return

        delete_after = time.time() + self.safety
        self.db.set_study_state(orthanc_id, "uploaded", delete_after=delete_after)
        self._last_upload_ok = time.time()
        self.audit.write("study_uploaded", orthanc_id=orthanc_id, study_uid=study_uid, delete_after=delete_after)
        LOG.info("Study uploaded orthanc_id=%s study_uid=%s", orthanc_id, study_uid)

    def cleanup_orthanc(self):
        for row in self.db.deletable():
            oid = row["orthanc_id"]
            try:
                self.orthanc.delete_study(oid)
                self.db.delete_study_row(oid)
                self.audit.write("deleted_orthanc", orthanc_id=oid, study_uid=row["study_uid"])
            except Exception as e:
                LOG.warning("Orthanc delete failed %s: %s", oid, e)

    def load_allowlist(self) -> list[dict]:
        if not self.allowlist_path.exists():
            return []
        data = json.loads(self.allowlist_path.read_text())
        if isinstance(data, dict) and "senders" in data:
            senders = data["senders"]
        elif isinstance(data, list):
            senders = data
        else:
            senders = []
        # validate
        out = []
        for s in senders:
            ae = str(s.get("ae", "")).strip()
            ip = str(s.get("ip", "")).strip()
            if not ae or not ip:
                raise ValueError(f"Invalid allowlist entry: {s}")
            out.append({"ae": ae, "ip": ip})
        return out

    def heartbeat_worker(self):
        while not self._stop.is_set():
            jitter = random.uniform(0, min(30.0, self.heartbeat_seconds * 0.1))
            try:
                self.send_heartbeat(immediate_critical=True)
            except Exception as e:
                LOG.warning("Heartbeat failed (uploads continue): %s", e)
                self.audit.write("heartbeat_error", error=str(e))
            self._stop.wait(self.heartbeat_seconds + jitter)

    def send_heartbeat(self, immediate_critical: bool = False):
        allowlist = []
        try:
            allowlist = self.load_allowlist()
        except Exception as e:
            self.audit.write("allowlist_invalid", error=str(e))
            self._critical.append({"type": "allowlist_invalid", "error": str(e), "ts": utc_now()})

        orthanc_ok = False
        try:
            self.orthanc.system()
            orthanc_ok = True
        except Exception:
            orthanc_ok = False

        usage = os.statvfs(self.root)
        free_bytes = usage.f_bavail * usage.f_frsize
        total_bytes = usage.f_blocks * usage.f_frsize
        free_ratio = free_bytes / total_bytes if total_bytes else 0

        cert_not_after = None
        days_left = None
        if self.cert_path.exists():
            cert = x509.load_pem_x509_certificate(self.cert_path.read_bytes())
            cert_not_after = cert.not_valid_after_utc.isoformat()
            days_left = (cert.not_valid_after_utc - datetime.now(timezone.utc)).total_seconds() / 86400

        payload = {
            "gatewayId": self.cfg.get("gateway_id"),
            "disk": {
                "freeBytes": free_bytes,
                "totalBytes": total_bytes,
                "freeRatio": round(free_ratio, 4),
                "watermarkLow": free_ratio < 0.15,
            },
            "queueDepth": self.db.queue_depth(),
            "oldestQueuedStudyAgeSeconds": self.db.oldest_queued_age_seconds(),
            "orthancHealthy": orthanc_ok,
            "agentHealthy": True,
            "certNotAfter": cert_not_after,
            "certDaysRemaining": days_left,
            "tlsInspectionSuspected": self._tls_inspection,
            "lastSuccessfulUploadAt": (
                datetime.fromtimestamp(self._last_upload_ok, tz=timezone.utc).isoformat()
                if self._last_upload_ok
                else None
            ),
            "allowlist": allowlist,
            "rejectedSenders": self.db.rejected_snapshot(),
            "critical": list(self._critical) if immediate_critical else [],
        }
        session = self._session()
        url = f"{self.mtls_base}/api/gateway/heartbeat"
        resp = session.post(url, json=payload, timeout=30)
        if resp.status_code < 400:
            self._critical.clear()
            self._tls_inspection = False
            # renew hint
            if days_left is not None and days_left <= 30:
                try:
                    self.try_renew()
                except Exception as e:
                    LOG.warning("Auto-renew failed: %s", e)
        else:
            raise RuntimeError(f"heartbeat HTTP {resp.status_code}: {resp.text[:200]}")

    def try_renew(self):
        if not self.cert_path.exists() or not self.key_path.exists():
            return
        cert = x509.load_pem_x509_certificate(self.cert_path.read_bytes())
        age_days = (datetime.now(timezone.utc) - cert.not_valid_before_utc).total_seconds() / 86400
        days_left = (cert.not_valid_after_utc - datetime.now(timezone.utc)).total_seconds() / 86400
        if age_days < 60 and days_left > 30:
            return
        key, csr_pem = generate_key_and_csr(str(self.cfg.get("gateway_id", "gateway")))
        session = self._session()
        resp = session.post(
            f"{self._auth_base()}/api/gateway/renew",
            json={"csrPem": csr_pem},
            timeout=60,
        )
        if resp.status_code >= 400:
            raise RuntimeError(resp.text[:300])
        data = resp.json()
        self.key_path.write_bytes(key)
        self.cert_path.write_text(data["certificatePem"])
        if data.get("caCertificatePem"):
            self.ca_path.write_text(data["caCertificatePem"])
        self.audit.write("cert_renewed", serial=data.get("serialHex"))
        LOG.info("Certificate renewed serial=%s", data.get("serialHex"))

    def run(self):
        self.audit.write("start", orthanc=self.orthanc.base, api=self.api_base)
        try:
            self.load_allowlist()
            self.audit.write("allowlist_loaded", path=str(self.allowlist_path))
        except Exception as e:
            LOG.error("Allowlist validation failed: %s", e)
            raise
        threading.Thread(target=self.upload_worker, name="upload", daemon=True).start()
        threading.Thread(target=self.heartbeat_worker, name="heartbeat", daemon=True).start()
        LOG.info("Edge agent running (Orthanc=%s)", self.orthanc.base)
        while not self._stop.is_set():
            self._stop.wait(3600)


def generate_key_and_csr(common_name: str) -> tuple[bytes, str]:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    csr = (
        x509.CertificateSigningRequestBuilder()
        .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, common_name[:64])]))
        .sign(key, hashes.SHA256())
    )
    key_pem = key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.TraditionalOpenSSL,
        encryption_algorithm=serialization.NoEncryption(),
    )
    csr_pem = csr.public_bytes(serialization.Encoding.PEM).decode("utf-8")
    return key_pem, csr_pem


def enroll(cfg_path: Path, api_base: str, enrollment_token: str, mtls_url: str | None) -> None:
    key_pem, csr_pem = generate_key_and_csr(f"enroll-{uuid.uuid4()}")
    url = api_base.rstrip("/") + "/api/gateway/enroll"
    resp = requests.post(
        url,
        json={"enrollmentToken": enrollment_token, "csrPem": csr_pem},
        timeout=60,
    )
    resp.raise_for_status()
    data = resp.json()
    cfg: dict = {}
    if cfg_path.exists():
        cfg = json.loads(cfg_path.read_text())
    data_dir = Path(cfg.get("data_dir", cfg_path.parent / "data"))
    data_dir.mkdir(parents=True, exist_ok=True)
    cert_path = data_dir / "client.crt"
    key_path = data_dir / "client.key"
    ca_path = data_dir / "ca.crt"
    key_path.write_bytes(key_pem)
    cert_path.write_text(data["certificatePem"])
    ca_path.write_text(data["caCertificatePem"])
    key_path.chmod(0o600)

    cfg["telerad_api_url"] = api_base.rstrip("/")
    cfg["telerad_mtls_url"] = (mtls_url or api_base).rstrip("/")
    cfg["gateway_id"] = data["gatewayId"]
    cfg["tenant_id"] = data["tenantId"]
    cfg["client_cert"] = str(cert_path)
    cfg["client_key"] = str(key_path)
    cfg["ca_cert"] = str(ca_path)
    if data.get("accessToken"):
        cfg["gateway_access_token"] = data["accessToken"]
    cfg.setdefault("orthanc_url", "http://orthanc:8042")
    cfg.setdefault("orthanc_user", "agent")
    cfg.setdefault("orthanc_password", "agent")
    cfg.setdefault("delete_safety_seconds", 86400)
    cfg.setdefault("upload_batch_size", 20)
    cfg.setdefault("heartbeat_seconds", 300)
    cfg.setdefault("allowlist_file", str(cfg_path.parent / "allowlist.json"))
    cfg["data_dir"] = str(data_dir)
    cfg_path.write_text(json.dumps(cfg, indent=2) + "\n")
    print(f"Enrolled gateway {data['gatewayId']} — wrote {cfg_path}")
    print(f"Client cert: {cert_path}")
    if data.get("accessToken"):
        print("Bearer access token stored for local-dev only; pilot uses mTLS.")


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    parser = argparse.ArgumentParser(description="TeleRad edge agent (Orthanc)")
    sub = parser.add_subparsers(dest="cmd", required=True)

    p_enroll = sub.add_parser("enroll", help="Exchange enrollment token + CSR for client cert")
    p_enroll.add_argument("--api", required=True, help="TeleRad API base (HTTP), e.g. http://127.0.0.1:38471")
    p_enroll.add_argument("--mtls", default=None, help="mTLS base URL, e.g. https://127.0.0.1:38473")
    p_enroll.add_argument("--token", required=True, help="One-time enrollment token")
    p_enroll.add_argument("--config", type=Path, default=Path("edge-gateway/config.json"))

    p_run = sub.add_parser("run", help="Run Orthanc poller + upload + heartbeat")
    p_run.add_argument("--config", type=Path, default=Path("edge-gateway/config.json"))

    args = parser.parse_args()
    if args.cmd == "enroll":
        enroll(args.config, args.api, args.token, args.mtls)
        return 0

    cfg = json.loads(args.config.read_text())
    EdgeAgent(cfg, args.config).run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
