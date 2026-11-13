"""
TeleRad Edge Agent — the hospital server (KB note 13: local-first images).

Orthanc (unmodified Docker) receives DICOM from the modalities and keeps the image files.
This agent:
  - polls Orthanc /changes and, for each stable study, sends the cloud a header-only index
    (no pixel data), so the worklist shows the study
  - polls the cloud for work: studies to upload (button, automatic rule, upload-all policy,
    priors) and studies to confirm before the cloud removes its copy
  - uploads image files over mTLS when asked; queues and retries when the internet is down
  - applies the facility's local retention (cache mode) and reports what it removed
  - serves images to browsers on the hospital network, checking the cloud's image pass
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

from dicom_header import header_from_stream
from edge_pass import load_public_key

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
    """Local state per study.

    ``index_state``: pending → indexed | failed (the cloud has the rows).
    ``state`` (upload): local (not asked) → queued → uploading → uploaded | failed.
    """

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
            cols = {r["name"] for r in conn.execute("PRAGMA table_info(studies)")}
            # Added for local-first images; rows from the previous agent keep their upload state.
            if "received_at" not in cols:
                conn.execute("ALTER TABLE studies ADD COLUMN received_at REAL")
                conn.execute("UPDATE studies SET received_at = queued_at")
            if "index_state" not in cols:
                conn.execute("ALTER TABLE studies ADD COLUMN index_state TEXT")
            if "index_error" not in cols:
                conn.execute("ALTER TABLE studies ADD COLUMN index_error TEXT")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_studies_uid ON studies(study_uid)")

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

    def note_stable(self, orthanc_id: str, study_uid: str | None):
        """A study became stable (new, or new images arrived): index it (again)."""
        now = time.time()
        with self._lock, self._connect() as conn:
            conn.execute(
                """
                INSERT INTO studies(orthanc_id, study_uid, queued_at, received_at, state, index_state)
                VALUES(?,?,?,?, 'local', 'pending')
                ON CONFLICT(orthanc_id) DO UPDATE SET
                  study_uid=COALESCE(excluded.study_uid, studies.study_uid),
                  index_state='pending', index_error=NULL
                """,
                (orthanc_id, study_uid, now, now),
            )

    def by_index_state(self, *states: str) -> list[sqlite3.Row]:
        ph = ",".join("?" * len(states))
        with self._lock, self._connect() as conn:
            return conn.execute(
                f"SELECT * FROM studies WHERE index_state IN ({ph}) ORDER BY received_at", states
            ).fetchall()

    def set_index_state(self, orthanc_id: str, state: str, error: str | None = None):
        with self._lock, self._connect() as conn:
            conn.execute("UPDATE studies SET index_state=?, index_error=? WHERE orthanc_id=?",
                         (state, error, orthanc_id))

    def by_state(self, *states: str) -> list[sqlite3.Row]:
        ph = ",".join("?" * len(states))
        with self._lock, self._connect() as conn:
            return conn.execute(
                f"SELECT * FROM studies WHERE state IN ({ph}) ORDER BY queued_at", states
            ).fetchall()

    def find_by_uid(self, study_uid: str) -> sqlite3.Row | None:
        with self._lock, self._connect() as conn:
            return conn.execute("SELECT * FROM studies WHERE study_uid=?", (study_uid,)).fetchone()

    def ensure_row(self, orthanc_id: str, study_uid: str):
        now = time.time()
        with self._lock, self._connect() as conn:
            conn.execute(
                """INSERT INTO studies(orthanc_id, study_uid, queued_at, received_at, state, index_state)
                   VALUES(?,?,?,?, 'local', 'indexed') ON CONFLICT(orthanc_id) DO NOTHING""",
                (orthanc_id, study_uid, now, now),
            )

    def request_upload(self, orthanc_id: str) -> bool:
        """Queue an upload the cloud asked for; False when one is already queued or running."""
        with self._lock, self._connect() as conn:
            cur = conn.execute(
                """UPDATE studies SET state='queued', queued_at=?, last_error=NULL
                   WHERE orthanc_id=? AND state NOT IN ('queued','uploading','failed')""",
                (time.time(), orthanc_id),
            )
            return cur.rowcount > 0

    def set_study_state(self, orthanc_id: str, state: str, error: str | None = None):
        with self._lock, self._connect() as conn:
            if state == "uploaded":
                conn.execute(
                    "UPDATE studies SET state=?, last_error=NULL, uploaded_at=? WHERE orthanc_id=?",
                    (state, time.time(), orthanc_id),
                )
            else:
                conn.execute(
                    "UPDATE studies SET state=?, last_error=? WHERE orthanc_id=?",
                    (state, error, orthanc_id),
                )

    def retention_candidates(self, older_than: float) -> list[sqlite3.Row]:
        """Studies received before ``older_than`` with nothing pending (no upload, index done)."""
        with self._lock, self._connect() as conn:
            return conn.execute(
                """
                SELECT * FROM studies
                WHERE received_at < ?
                  AND state NOT IN ('queued','uploading','failed')
                  AND COALESCE(index_state,'indexed') = 'indexed'
                ORDER BY received_at LIMIT 200
                """,
                (older_than,),
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
                """SELECT COUNT(*) AS c FROM studies
                   WHERE state IN ('queued','failed','uploading') OR index_state IN ('pending','failed')"""
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
        kwargs.setdefault("timeout", 60)
        return requests.get(self.base + path, auth=self.auth, **kwargs)

    def post(self, path: str, **kwargs):
        kwargs.setdefault("timeout", 60)
        return requests.post(self.base + path, auth=self.auth, **kwargs)

    def delete(self, path: str, **kwargs):
        kwargs.setdefault("timeout", 60)
        return requests.delete(self.base + path, auth=self.auth, **kwargs)

    def changes(self, since: int, limit: int = 100) -> dict:
        r = self.get("/changes", params={"since": since, "limit": limit})
        r.raise_for_status()
        return r.json()

    def study(self, orthanc_id: str) -> dict:
        r = self.get(f"/studies/{orthanc_id}")
        r.raise_for_status()
        return r.json()

    def study_instance_ids(self, orthanc_id: str) -> list[str]:
        r = self.get(f"/studies/{orthanc_id}/instances")
        r.raise_for_status()
        return [i["ID"] for i in r.json()]

    def find_study(self, study_uid: str) -> str | None:
        r = self.post("/tools/lookup", data=study_uid)
        r.raise_for_status()
        ids = [x["ID"] for x in r.json() if x.get("Type") == "Study"]
        return ids[0] if ids else None

    def instance_file(self, instance_id: str) -> bytes:
        r = self.get(f"/instances/{instance_id}/file")
        r.raise_for_status()
        return r.content

    def instance_header(self, instance_id: str) -> bytes:
        """Header-only copy (no pixel data) of an instance, reading as little of the file as possible."""
        with self.get(f"/instances/{instance_id}/file", stream=True) as r:
            r.raise_for_status()
            chunks = r.iter_content(64 * 1024)
            return header_from_stream(chunks, read_rest=lambda: b"".join(chunks))

    def system(self) -> dict:
        r = self.get("/system", timeout=10)
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
        self.batch_size = int(cfg.get("upload_batch_size", 20))
        self.index_batch_size = int(cfg.get("index_batch_size", 100))
        self.work_poll_seconds = float(cfg.get("work_poll_seconds", 10))
        self.failed_retry_seconds = float(cfg.get("failed_retry_seconds", 60))
        self.api_base = cfg["telerad_api_url"].rstrip("/")
        self.mtls_base = (cfg.get("telerad_mtls_url") or self.api_base).rstrip("/")
        self.token = cfg.get("gateway_access_token")
        self.gateway_id = str(cfg.get("gateway_id") or "")
        self.lan_url = (cfg.get("lan_url") or "").strip() or None
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
        self._last_work_ok: float | None = None
        self._tls_inspection: bool = False
        self._critical: list[dict] = []
        self._config_lock = threading.Lock()
        self._edge_config: dict = self._load_cached_config()
        self._pass_key = None
        self._pass_key_pem = None
        self._refresh_pass_key()
        self._orthanc_ok = False
        self._orthanc_checked = 0.0
        self.image_server = None

    # ------------------------------------------------------------------ cloud settings

    def _config_cache_path(self) -> Path:
        return self.root / "edge-config.json"

    def _load_cached_config(self) -> dict:
        try:
            return json.loads(self._config_cache_path().read_text())
        except Exception:
            return {}

    def _store_config(self, config: dict):
        with self._config_lock:
            changed = config != self._edge_config
            self._edge_config = config
        if changed:
            tmp = self._config_cache_path().with_suffix(".tmp")
            tmp.write_text(json.dumps(config, indent=2))
            tmp.replace(self._config_cache_path())
            self.audit.write("config_updated", upload_policy=config.get("uploadPolicy"),
                             storage_mode=config.get("storageMode"),
                             local_retention_days=config.get("localRetentionDays"))
            LOG.info("Facility settings: upload=%s storage=%s local retention=%s days",
                     config.get("uploadPolicy"), config.get("storageMode"), config.get("localRetentionDays"))
        self._refresh_pass_key()

    def edge_config(self) -> dict:
        with self._config_lock:
            return dict(self._edge_config)

    def _refresh_pass_key(self):
        pem = self.edge_config().get("passPublicKeyPem")
        if pem and pem != self._pass_key_pem:
            try:
                self._pass_key = load_public_key(pem)
                self._pass_key_pem = pem
            except Exception as e:
                LOG.error("Invalid image-pass key from cloud: %s", e)

    def pass_public_key(self):
        return self._pass_key

    def web_origins(self) -> list[str]:
        origins = list(self.edge_config().get("webOrigins") or [])
        origins.extend(self.cfg.get("extra_web_origins") or [])
        return origins

    def orthanc_ok(self) -> bool:
        if time.time() - self._orthanc_checked > 10:
            try:
                self.orthanc.system()
                self._orthanc_ok = True
            except Exception:
                self._orthanc_ok = False
            self._orthanc_checked = time.time()
        return self._orthanc_ok

    # ------------------------------------------------------------------ cloud connection

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

    # ------------------------------------------------------------------ Orthanc changes

    def poll_changes(self):
        since = int(self.db.get_meta("orthanc_since", "0") or "0")
        try:
            data = self.orthanc.changes(since=since, limit=100)
        except Exception as e:
            LOG.warning("Orthanc changes failed: %s", e)
            return
        last = data.get("Last", since)
        for ch in data.get("Changes", []):
            if ch.get("ChangeType") == "StableStudy":
                oid = ch.get("ID")
                study_uid = None
                try:
                    meta = self.orthanc.study(oid)
                    study_uid = (meta.get("MainDicomTags") or {}).get("StudyInstanceUID")
                except Exception:
                    pass
                self.db.note_stable(oid, study_uid)
                self.audit.write("received", orthanc_id=oid, study_uid=study_uid)
                LOG.info("Stable study %s uid=%s", oid, study_uid)
        if last != since:
            self.db.set_meta("orthanc_since", str(last))

    # ------------------------------------------------------------------ index

    def index_study(self, orthanc_id: str):
        """Send the cloud a header-only copy of every instance; the files stay here."""
        try:
            instance_ids = self.orthanc.study_instance_ids(orthanc_id)
        except requests.HTTPError as e:
            if e.response is not None and e.response.status_code == 404:
                self.db.delete_study_row(orthanc_id)  # deleted from Orthanc before we got to it
                return
            raise
        if not instance_ids:
            self.db.set_index_state(orthanc_id, "failed", "no instances")
            return
        session = self._session()
        url = f"{self._auth_base()}/api/gateway/index"
        for i in range(0, len(instance_ids), self.index_batch_size):
            batch = instance_ids[i: i + self.index_batch_size]
            files = []
            for iid in batch:
                files.append(("files", (f"{iid}.dcm", self.orthanc.instance_header(iid), "application/dicom")))
            complete = i + self.index_batch_size >= len(instance_ids)
            resp = self._post_cloud(session, url, orthanc_id, files=files,
                                    data={"studyComplete": "true" if complete else "false"}, timeout=120)
            if resp is None:
                self.db.set_index_state(orthanc_id, "failed", "transient")
                raise RuntimeError("index upload failed (transient)")
            payload = resp.json()
            failed = int(payload.get("failed") or 0)
            if failed:
                self.db.set_index_state(orthanc_id, "failed", f"{failed} instance(s) failed")
                self.audit.write("index_failed", orthanc_id=orthanc_id, failed=failed)
                return
        self.db.set_index_state(orthanc_id, "indexed")
        self.audit.write("indexed", orthanc_id=orthanc_id, instances=len(instance_ids))
        LOG.info("Indexed study %s (%d instances)", orthanc_id, len(instance_ids))

    def _post_cloud(self, session, url, orthanc_id, **kwargs):
        """POST to the cloud; None on a transient failure (retried later), raise on auth failure."""
        try:
            resp = session.post(url, **kwargs)
        except ssl.SSLError as e:
            self._tls_inspection = True
            self.audit.write("tls_error", orthanc_id=orthanc_id, error=str(e))
            self._critical.append({"type": "tls_inspection", "error": str(e), "ts": utc_now()})
            return None
        except requests.RequestException as e:
            LOG.warning("Cloud unreachable: %s", e)
            return None
        if resp.status_code == 403:
            self.audit.write("upload_forbidden", orthanc_id=orthanc_id)
            raise RuntimeError("Gateway unauthorized/disabled")
        if resp.status_code >= 500 or resp.status_code == 429:
            return None
        if resp.status_code >= 400:
            raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:300]}")
        return resp

    # ------------------------------------------------------------------ upload (cloud copy)

    def upload_study(self, orthanc_id: str, known_uid: str | None = None):
        self.db.set_study_state(orthanc_id, "uploading")
        try:
            meta = self.orthanc.study(orthanc_id)
        except requests.HTTPError as e:
            if e.response is not None and e.response.status_code == 404:
                # Gone from Orthanc (deleted by hand or by retention): tell the cloud.
                self.db.delete_study_row(orthanc_id)
                if known_uid:
                    self.report_evicted([known_uid])
                return
            self.db.set_study_state(orthanc_id, "failed", str(e))
            return
        except Exception as e:
            self.db.set_study_state(orthanc_id, "failed", str(e))
            return
        study_uid = (meta.get("MainDicomTags") or {}).get("StudyInstanceUID")
        instance_ids = self.orthanc.study_instance_ids(orthanc_id)
        if not instance_ids:
            self.db.set_study_state(orthanc_id, "failed", "no instances")
            return

        session = self._session()
        url = f"{self._auth_base()}/api/gateway/ingest"
        for i in range(0, len(instance_ids), self.batch_size):
            batch_ids = instance_ids[i: i + self.batch_size]
            files = []
            for iid in batch_ids:
                files.append(("files", (f"{iid}.dcm", self.orthanc.instance_file(iid), "application/dicom")))
            complete = i + self.batch_size >= len(instance_ids)
            try:
                resp = self._post_cloud(session, url, orthanc_id, files=files,
                                        data={"studyComplete": "true" if complete else "false"}, timeout=300)
            except RuntimeError as e:
                self.db.set_study_state(orthanc_id, "failed", str(e)[:500])
                raise
            if resp is None:
                self.db.set_study_state(orthanc_id, "failed", "transient")
                raise RuntimeError("upload failed (transient); retrying later")
            payload = resp.json()
            for r in payload.get("results", []):
                if r.get("status") not in ("stored", "duplicate"):
                    self.audit.write("upload_failed", orthanc_id=orthanc_id, sop_uid=r.get("sopInstanceUid"),
                                     reason=r.get("reason"), failure_class=r.get("failureClass"))
            failed = int(payload.get("failed") or 0)
            if failed:
                self.db.set_study_state(orthanc_id, "failed", f"{failed} instance(s) failed")
                return

        self.db.set_study_state(orthanc_id, "uploaded")
        self._last_upload_ok = time.time()
        self.audit.write("study_uploaded", orthanc_id=orthanc_id, study_uid=study_uid, instances=len(instance_ids))
        LOG.info("Uploaded study %s (%d instances) uid=%s", orthanc_id, len(instance_ids), study_uid)

    # ------------------------------------------------------------------ work from the cloud

    def poll_work(self):
        session = self._session()
        try:
            resp = session.get(f"{self._auth_base()}/api/gateway/work", timeout=30)
        except requests.RequestException as e:
            LOG.debug("Work poll failed: %s", e)
            return
        if resp.status_code >= 400:
            LOG.warning("Work poll HTTP %s", resp.status_code)
            return
        work = resp.json()
        self._last_work_ok = time.time()
        if isinstance(work.get("config"), dict):
            self._store_config(work["config"])
        missing: list[str] = []
        for up in work.get("uploads") or []:
            uid = up.get("studyInstanceUid")
            if not uid:
                continue
            oid = self._orthanc_id_for(uid)
            if oid is None:
                missing.append(uid)
                continue
            if self.db.request_upload(oid):
                self.audit.write("upload_requested", orthanc_id=oid, study_uid=uid)
                LOG.info("Cloud asked for study %s", uid)
        results = []
        for v in work.get("verify") or []:
            uid = v.get("studyInstanceUid")
            if not uid:
                continue
            oid = self._orthanc_id_for(uid)
            count = 0
            if oid is not None:
                try:
                    count = len(self.orthanc.study_instance_ids(oid))
                except Exception as e:
                    LOG.warning("Count failed for %s: %s", uid, e)
                    continue  # unknown, not zero: answer next time
            results.append({"studyInstanceUid": uid, "instanceCount": count})
        if results:
            r = session.post(f"{self._auth_base()}/api/gateway/verify", json={"results": results}, timeout=60)
            if r.status_code < 400:
                for res in results:
                    self.audit.write("hold_confirmed", study_uid=res["studyInstanceUid"],
                                     instances=res["instanceCount"])
        if missing:
            self.report_evicted(missing)

    def _orthanc_id_for(self, study_uid: str) -> str | None:
        row = self.db.find_by_uid(study_uid)
        if row:
            return row["orthanc_id"]
        try:
            oid = self.orthanc.find_study(study_uid)
        except Exception as e:
            LOG.warning("Orthanc lookup failed: %s", e)
            return None
        if oid:
            self.db.ensure_row(oid, study_uid)
        return oid

    def report_evicted(self, study_uids: list[str]):
        try:
            session = self._session()
            r = session.post(f"{self._auth_base()}/api/gateway/evicted",
                             json={"studyInstanceUids": study_uids}, timeout=60)
            if r.status_code < 400:
                for uid in study_uids:
                    self.audit.write("reported_not_held", study_uid=uid)
        except requests.RequestException as e:
            LOG.warning("Could not report removed studies: %s", e)

    # ------------------------------------------------------------------ local retention

    def apply_local_retention(self):
        days = self.edge_config().get("localRetentionDays")
        if days is None:
            return  # keep for good (archive)
        cutoff = time.time() - float(days) * 86400
        removed: list[str] = []
        for row in self.db.retention_candidates(cutoff):
            oid = row["orthanc_id"]
            try:
                self.orthanc.delete_study(oid)
                self.db.delete_study_row(oid)
                self.audit.write("deleted_orthanc", orthanc_id=oid, study_uid=row["study_uid"], reason="retention")
                if self.image_server:
                    self.image_server.index.forget_study(row["study_uid"])
                if row["study_uid"]:
                    removed.append(row["study_uid"])
            except Exception as e:
                LOG.warning("Orthanc delete failed %s: %s", oid, e)
        if removed:
            LOG.info("Local retention removed %d studies", len(removed))
            self.report_evicted(removed)

    # ------------------------------------------------------------------ loops

    def main_worker(self):
        backoff = 2.0
        last_retention = 0.0
        last_work = 0.0
        last_failed_retry = 0.0
        while not self._stop.is_set():
            try:
                self.poll_changes()
                # Failed items are retried once a minute, not on every pass.
                retry_failed = time.time() - last_failed_retry >= self.failed_retry_seconds
                if retry_failed:
                    last_failed_retry = time.time()
                for row in self.db.by_index_state(*(("pending", "failed") if retry_failed else ("pending",))):
                    self.index_study(row["orthanc_id"])
                if time.time() - last_work >= self.work_poll_seconds:
                    last_work = time.time()
                    self.poll_work()
                for row in self.db.by_state(*(("queued", "failed") if retry_failed else ("queued",))):
                    self.upload_study(row["orthanc_id"], row["study_uid"])
                if time.time() - last_retention >= 3600:
                    last_retention = time.time()
                    self.apply_local_retention()
                backoff = 2.0
                self._stop.wait(2)
            except Exception as e:
                LOG.warning("Worker: %s (retry in %.0fs)", e, backoff)
                self.audit.write("system_error", error=str(e)[:500])
                if "unauthorized" in str(e).lower():
                    self._critical.append({"type": "system_error", "error": str(e), "ts": utc_now()})
                self._stop.wait(min(backoff, 300))
                backoff = min(backoff * 2, 300)

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
                self.audit.write("heartbeat_error", error=str(e)[:300])
            self._stop.wait(self.heartbeat_seconds + jitter)

    def send_heartbeat(self, immediate_critical: bool = False):
        allowlist = []
        try:
            allowlist = self.load_allowlist()
        except Exception as e:
            self.audit.write("allowlist_invalid", error=str(e))
            self._critical.append({"type": "allowlist_invalid", "error": str(e), "ts": utc_now()})

        # Disk where Orthanc keeps the images (mounted read-only into the agent), else the agent's.
        disk_path = Path(self.cfg.get("orthanc_storage_dir", "/orthanc-storage"))
        if not disk_path.exists():
            disk_path = self.root
        usage = os.statvfs(disk_path)
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
            "gatewayId": self.gateway_id,
            "lanUrl": self.lan_url,
            "disk": {
                "freeBytes": free_bytes,
                "totalBytes": total_bytes,
                "freeRatio": round(free_ratio, 4),
                "watermarkLow": free_ratio < 0.15,
            },
            "queueDepth": self.db.queue_depth(),
            "oldestQueuedStudyAgeSeconds": self.db.oldest_queued_age_seconds(),
            "orthancHealthy": self.orthanc_ok(),
            "agentHealthy": True,
            "imageServiceRunning": self.image_server is not None,
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
        url = f"{self.mtls_base if self.cert_path.exists() else self.api_base}/api/gateway/heartbeat"
        resp = session.post(url, json=payload, timeout=30)
        if resp.status_code < 400:
            self._critical.clear()
            self._tls_inspection = False
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

    def start_image_server(self):
        if not self.cfg.get("lan_image_service", True):
            return
        from image_server import ImageServer

        self.image_server = ImageServer(
            self,
            host=self.cfg.get("lan_bind", "0.0.0.0"),
            port=int(self.cfg.get("lan_port", 8090)),
            tls_cert=self.cfg.get("lan_tls_cert"),
            tls_key=self.cfg.get("lan_tls_key"),
        )
        self.image_server.start()

    def run(self):
        self.audit.write("start", orthanc=self.orthanc.base, api=self.api_base)
        try:
            self.load_allowlist()
            self.audit.write("allowlist_loaded", path=str(self.allowlist_path))
        except Exception as e:
            LOG.error("Allowlist validation failed: %s", e)
            raise
        self.start_image_server()
        threading.Thread(target=self.main_worker, name="worker", daemon=True).start()
        threading.Thread(target=self.heartbeat_worker, name="heartbeat", daemon=True).start()
        LOG.info("Edge agent running (Orthanc=%s, image service=%s)", self.orthanc.base, self.lan_url)
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
    cfg.setdefault("upload_batch_size", 20)
    cfg.setdefault("heartbeat_seconds", 300)
    cfg.setdefault("work_poll_seconds", 10)
    cfg.setdefault("lan_port", 8090)
    cfg.setdefault("allowlist_file", str(cfg_path.parent / "allowlist.json"))
    cfg["data_dir"] = str(data_dir)
    cfg_path.write_text(json.dumps(cfg, indent=2) + "\n")
    print(f"Enrolled gateway {data['gatewayId']} — wrote {cfg_path}")
    print(f"Client cert: {cert_path}")
    print("Next: set lan_url (and lan_tls_cert / lan_tls_key) in the config — see the runbook.")
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

    p_run = sub.add_parser("run", help="Run Orthanc poller, cloud work, uploads, image service, heartbeat")
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
