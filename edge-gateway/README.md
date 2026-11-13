# TeleRad hospital server (Orthanc + agent)

Local-first images (Engineering KB note 13): the hospital server keeps the images, the cloud
keeps the worklist, reports and a copy of the images only while a remote read needs them.

```text
Modality ──C-STORE──► Orthanc (:11113, AE TELERAD_EDGE) ── keeps the image files
                         │ StableStudy
                         ▼
                      Agent ──index (headers only)──► TeleRad cloud: worklist shows the study
                         │  ◄──work: upload / confirm──  (button, rule, upload-all, priors, retention)
                         │  ──image files (mTLS)──────►  cloud copy for remote readers
                         │
   Browsers on the  ◄────┘ image service :8090 (HTTPS, image pass from the cloud)
   hospital network
```

- **Deployment, upgrade, checks and incident steps:** Engineering KB note 14 (deployment runbook).
- **Design:** Engineering KB note 13.

## Files

| File | What |
|---|---|
| `agent.py` | Orthanc changes → index → cloud work (upload, confirm) → local retention → heartbeat |
| `image_server.py` | Image service for the viewer (`/health`, `/dicom/studies/…/instances/…`, frames) |
| `edge_pass.py` | Checks the cloud's image pass (RS256 JWT: user, hospital server, studies, expiry) |
| `dicom_header.py` | Header-only copies for the index (reads only up to the pixel data) |
| `scripts/render_orthanc_config.py` | Writes `secrets/orthanc.json` (allowlist, agent password, emergency user) |
| `docker-compose.yml` | Orthanc (`orthancteam/orthanc`, with OHIF) + agent |
| `docker-compose.emergency.yml` | Publishes Orthanc's web UI on the LAN during an internet outage |
| `config.example.json` | Agent settings (copy to `secrets/config.json`) |
| `tests/` | `python3 -m unittest discover -s edge-gateway/tests` |

`secrets/` (config, passwords, TLS key) is git-ignored and never committed.

## Quick start (development)

```bash
cd edge-gateway
mkdir -p secrets/tls
python3 -c "import secrets;print(secrets.token_urlsafe(18))" > secrets/orthanc_agent_password
python3 scripts/render_orthanc_config.py allowlist.json secrets/orthanc.json \
  --agent-password-file secrets/orthanc_agent_password
cp config.example.json secrets/config.json   # edit: URLs, orthanc_password, lan_url
# Platform admin: Admin → Image storage → Add hospital server → enrolment token (24 h)
docker compose run --rm -v "$PWD/secrets:/cfg" agent \
  python3 /app/agent.py enroll --api https://HOST --mtls https://HOST:8443 --token 'enroll_…' --config /cfg/config.json
docker compose up -d --build
```

Modalities send to this machine on **port 11113**, AE **TELERAD_EDGE**, from an AE/IP listed in
`allowlist.json`.
