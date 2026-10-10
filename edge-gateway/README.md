# TeleRad Edge (Orthanc + Agent)

Facility path (locked OD-EDGE / FR-EDGE):

```text
Modality ──C-STORE──► Orthanc (:11113 / AE TELERAD_EDGE)
                         │ StableStudy (changes feed)
                         ▼
                      Edge agent (queue, retry, audit)
                         │ mTLS HTTPS multipart
                         ▼
                   TeleRad ingest → MinIO + worker → READY
```

Orthanc HTTP/UI is **internal only** (compose network) and password-protected.  
Only the **DICOM port** is published to the LAN.

## Facility deployment (Ubuntu + Docker)

On the **hospital edge device** (not the TeleRad VPS). Need Docker installed. Copy or clone this `edge-gateway/` folder onto the machine.

```bash
cd edge-gateway

# 1) Allow only your modalities (AE Title + IP)
#    edit allowlist.json, then:
python3 scripts/render_orthanc_config.py allowlist.json orthanc/orthanc.json

# 2) One-time enroll (outbound internet to TeleRad required)
#    Platform admin creates a gateway in TeleRad → gives you enrollmentToken (24h)
mkdir -p secrets
pip3 install -r requirements.txt
python3 agent.py enroll \
  --api https://YOUR-TELERAD-HOST \
  --mtls https://YOUR-TELERAD-HOST:8443 \
  --token 'enroll_…' \
  --config secrets/config.json

# 3) Run Orthanc + agent
docker compose up -d --build
```

Then point modalities at this machine: **port 11113**, AE **TELERAD_EDGE**.  
Do not commit `secrets/`. Set `TELERAD_GATEWAY_BEARER_DEV=false` on the TeleRad VPS for pilot/production.

## Allowlist (K3)

Edit `allowlist.json` (AE + IP). Render Orthanc config:

```bash
python3 edge-gateway/scripts/render_orthanc_config.py \
  edge-gateway/allowlist.json \
  edge-gateway/orthanc/orthanc.json
```

Restart Orthanc after changes. Heartbeat reports the allowlist read-only to TeleRad.

## Enroll (one-time token → client cert)

```bash
pip3 install -r edge-gateway/requirements.txt

# Platform admin: POST /api/admin/gateways → enrollmentToken (24h)

python3 edge-gateway/agent.py enroll \
  --api http://127.0.0.1:38471 \
  --mtls https://127.0.0.1:38473 \
  --token 'enroll_…' \
  --config edge-gateway/secrets/config.json
```

Copy certs into the agent data volume (or enroll inside the container). For local-dev, enroll may also return a Bearer token when `TELERAD_GATEWAY_BEARER_DEV=true`.

## Run with Docker Compose

```bash
mkdir -p edge-gateway/secrets
# place secrets/config.json (from enroll) — never commit secrets

docker compose -f edge-gateway/docker-compose.yml up -d --build
```

## Simulator → Orthanc

```bash
./scripts/modality_send.sh --sample \
  --host 127.0.0.1 \
  --port 11113 \
  --called-ae TELERAD_EDGE \
  --calling-ae DEMOHOSP_PACS
```

After Orthanc `StableAge` (~30s), the agent uploads. Local audit: agent volume `audit.jsonl` (UIDs only).  
Delete from Orthanc after confirmed upload + `delete_safety_seconds` (default 24h).

## Ops

- Heartbeat every 5 minutes (mTLS, no PHI); TeleRad alerts after 15 minutes missed.
- Cert auto-renew from day 60; alerts at 21 / 7 days remaining (via heartbeat fields).
- Heartbeat failures never block uploads.
