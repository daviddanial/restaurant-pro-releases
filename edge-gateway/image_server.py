"""Image service on the hospital network (KB note 13 §4).

Browsers of doctors inside the hospital load images from here instead of the cloud. Each request
carries an image pass the cloud signed for that user and study; this server checks it and streams
the file from Orthanc. Same paths as the cloud WADO endpoints, so the viewer only swaps the base URL:

  GET /health
  GET /dicom/studies/{study}/series/{series}/instances/{sop}             DICOM file
  GET /dicom/studies/{study}/series/{series}/instances/{sop}/frames/{n}  JPEG preview (?maxSize=)
"""
from __future__ import annotations

import fnmatch
import json
import logging
import os
import re
import ssl
import threading
import time
from collections import OrderedDict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit

from edge_pass import PassError, verify_pass

LOG = logging.getLogger("edge-images")

_UID = r"[0-9.]{1,64}"
_INSTANCE = re.compile(rf"^/dicom/studies/({_UID})/series/({_UID})/instances/({_UID})$")
_FRAME = re.compile(rf"^/dicom/studies/({_UID})/series/({_UID})/instances/({_UID})/frames/(\d{{1,5}})$")
_ALLOWED_REQUEST_HEADERS = {"authorization", "accept", "if-none-match", "cache-control", "pragma"}
_CHUNK = 256 * 1024


class InstanceIndex:
    """SOP Instance UID → (Orthanc id, study UID, series UID), looked up in Orthanc and cached."""

    def __init__(self, orthanc, max_entries: int = 200_000):
        self.orthanc = orthanc
        self.max_entries = max_entries
        self._cache: OrderedDict[str, tuple[str, str, str]] = OrderedDict()
        self._lock = threading.Lock()

    def find(self, sop_uid: str) -> tuple[str, str, str] | None:
        with self._lock:
            hit = self._cache.get(sop_uid)
            if hit:
                self._cache.move_to_end(sop_uid)
                return hit
        r = self.orthanc.post("/tools/lookup", data=sop_uid)
        r.raise_for_status()
        ids = [x["ID"] for x in r.json() if x.get("Type") == "Instance"]
        if not ids:
            return None
        oid = ids[0]
        r = self.orthanc.get(f"/instances/{oid}/study")
        r.raise_for_status()
        study_uid = (r.json().get("MainDicomTags") or {}).get("StudyInstanceUID")
        r = self.orthanc.get(f"/instances/{oid}/series")
        r.raise_for_status()
        series_uid = (r.json().get("MainDicomTags") or {}).get("SeriesInstanceUID")
        entry = (oid, study_uid, series_uid)
        with self._lock:
            self._cache[sop_uid] = entry
            while len(self._cache) > self.max_entries:
                self._cache.popitem(last=False)
        return entry

    def forget_study(self, study_uid: str):
        with self._lock:
            for k in [k for k, v in self._cache.items() if v[1] == study_uid]:
                del self._cache[k]


class ImageServer:
    def __init__(self, agent, host: str, port: int, tls_cert: str | None, tls_key: str | None):
        self.agent = agent
        self.host = host
        self.port = port
        self.tls_cert = tls_cert
        self.tls_key = tls_key
        self.index = InstanceIndex(agent.orthanc)
        self._seen: OrderedDict[tuple[str, str], None] = OrderedDict()
        self._seen_lock = threading.Lock()
        self._httpd: ThreadingHTTPServer | None = None
        self._ctx: ssl.SSLContext | None = None
        self._cert_mtime = 0.0

    # ------------------------------------------------------------------ lifecycle

    def start(self):
        handler = _make_handler(self)
        httpd = ThreadingHTTPServer((self.host, self.port), handler)
        httpd.daemon_threads = True
        if self.tls_cert and self.tls_key:
            self._ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            self._ctx.minimum_version = ssl.TLSVersion.TLSv1_2
            self._load_cert()
            httpd.socket = self._ctx.wrap_socket(httpd.socket, server_side=True)
            threading.Thread(target=self._watch_cert, name="tls-reload", daemon=True).start()
        self._httpd = httpd
        threading.Thread(target=httpd.serve_forever, name="image-server", daemon=True).start()
        LOG.info("Image service listening on %s://%s:%s", "https" if self._ctx else "http", self.host, self.port)

    def stop(self):
        if self._httpd:
            self._httpd.shutdown()
            self._httpd.server_close()

    def _load_cert(self):
        self._ctx.load_cert_chain(self.tls_cert, self.tls_key)
        self._cert_mtime = os.path.getmtime(self.tls_cert)

    def _watch_cert(self):
        # Renewed certificates (e.g. Let's Encrypt every 60–90 days) apply to new connections.
        while True:
            time.sleep(60)
            try:
                if os.path.getmtime(self.tls_cert) != self._cert_mtime:
                    self._load_cert()
                    LOG.info("Reloaded image service certificate")
            except Exception as e:
                LOG.warning("Certificate reload failed: %s", e)

    # ------------------------------------------------------------------ policy

    def origin_allowed(self, origin: str | None) -> bool:
        if not origin:
            return False
        return any(fnmatch.fnmatchcase(origin, pattern) for pattern in self.agent.web_origins())

    def note_access(self, claims: dict, study_uid: str):
        key = (str(claims.get("jti")), study_uid)
        with self._seen_lock:
            if key in self._seen:
                return
            self._seen[key] = None
            while len(self._seen) > 10_000:
                self._seen.popitem(last=False)
        self.agent.audit.write("image_access", user=claims.get("sub"), study_uid=study_uid, jti=claims.get("jti"))


def _make_handler(server: ImageServer):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        server_version = "TeleRadEdge"
        sys_version = ""

        def log_message(self, fmt, *args):  # keep request lines (UIDs) out of the logs
            pass

        # -------------------------------------------------------------- CORS

        def _cors(self):
            origin = self.headers.get("Origin")
            if server.origin_allowed(origin):
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Vary", "Origin")

        def do_OPTIONS(self):
            origin = self.headers.get("Origin")
            if not server.origin_allowed(origin):
                self._plain(403, "origin not allowed")
                return
            self.send_response(204)
            self._cors()
            self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
            requested = self.headers.get("Access-Control-Request-Headers", "")
            allowed = [h.strip() for h in requested.split(",")
                       if h.strip() and h.strip().lower() in _ALLOWED_REQUEST_HEADERS]
            if allowed:
                self.send_header("Access-Control-Allow-Headers", ", ".join(allowed))
            # Chrome: a public site calling a private (LAN) address must be allowed explicitly.
            if self.headers.get("Access-Control-Request-Private-Network") == "true":
                self.send_header("Access-Control-Allow-Private-Network", "true")
            self.send_header("Access-Control-Max-Age", "7200")
            self.send_header("Content-Length", "0")
            self.end_headers()

        # -------------------------------------------------------------- routes

        def do_GET(self):
            try:
                self._route()
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as e:  # never leak details
                LOG.warning("Image request failed: %s", e)
                try:
                    self._plain(502, "image service error")
                except Exception:
                    pass

        def _route(self):
            parts = urlsplit(self.path)
            path = unquote(parts.path)
            if path == "/health":
                self._json(200, {"ok": True, "gatewayId": server.agent.gateway_id,
                                 "orthanc": server.agent.orthanc_ok()})
                return
            m = _INSTANCE.match(path)
            if m:
                self._instance(*m.groups())
                return
            m = _FRAME.match(path)
            if m:
                qs = parse_qs(parts.query)
                size = None
                if qs.get("maxSize"):
                    try:
                        size = max(64, min(int(qs["maxSize"][0]), 4096))
                    except ValueError:
                        size = None
                self._frame(*m.groups()[:3], int(m.group(4)), size)
                return
            self._plain(404, "not found")

        def _authorise(self, study_uid: str) -> dict | None:
            auth = self.headers.get("Authorization", "")
            token = auth[7:].strip() if auth.lower().startswith("bearer ") else ""
            try:
                claims = verify_pass(token, server.agent.pass_public_key(), server.agent.gateway_id, study_uid)
            except PassError as e:
                self._plain(e.status, str(e))
                return None
            server.note_access(claims, study_uid)
            return claims

        def _locate(self, study_uid: str, series_uid: str, sop_uid: str) -> str | None:
            found = server.index.find(sop_uid)
            if not found or found[1] != study_uid or (series_uid and found[2] != series_uid):
                self._plain(404, "IMAGE_NOT_ON_HOSPITAL_SERVER")
                return None
            return found[0]

        def _instance(self, study_uid, series_uid, sop_uid):
            if self._authorise(study_uid) is None:
                return
            oid = self._locate(study_uid, series_uid, sop_uid)
            if oid is None:
                return
            etag = f'"{sop_uid}"'
            if etag in (self.headers.get("If-None-Match") or ""):
                self.send_response(304)
                self._cors()
                self.send_header("ETag", etag)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            with server.agent.orthanc.get(f"/instances/{oid}/file", stream=True) as r:
                if r.status_code == 404:
                    server.index.forget_study(study_uid)
                    self._plain(404, "IMAGE_NOT_ON_HOSPITAL_SERVER")
                    return
                r.raise_for_status()
                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", "application/dicom")
                self.send_header("Cache-Control", "private, max-age=86400")
                self.send_header("ETag", etag)
                length = r.headers.get("Content-Length")
                if length:
                    self.send_header("Content-Length", length)
                    self.end_headers()
                    for chunk in r.iter_content(_CHUNK):
                        self.wfile.write(chunk)
                else:
                    body = r.content
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

        def _frame(self, study_uid, series_uid, sop_uid, frame: int, size: int | None):
            if self._authorise(study_uid) is None:
                return
            oid = self._locate(study_uid, series_uid, sop_uid)
            if oid is None:
                return
            params = {}
            if size:
                params = {"width": size, "height": size}
            r = server.agent.orthanc.get(f"/instances/{oid}/frames/{max(0, frame - 1)}/rendered",
                                         params=params, headers={"Accept": "image/jpeg"})
            if r.status_code >= 400:
                self._plain(422 if r.status_code < 500 else 502, "cannot render frame")
                return
            body = r.content
            self.send_response(200)
            self._cors()
            self.send_header("Content-Type", r.headers.get("Content-Type", "image/jpeg"))
            self.send_header("Cache-Control", "private, max-age=86400")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        # -------------------------------------------------------------- helpers

        def _json(self, status: int, payload: dict):
            body = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self._cors()
            self.send_header("Content-Type", "application/json")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _plain(self, status: int, message: str):
            body = message.encode("utf-8")
            self.send_response(status)
            self._cors()
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return Handler
