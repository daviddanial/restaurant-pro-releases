# Teleradiology Platform

Spring Boot + React teleradiology system: DICOM upload and PACS C-STORE ingest, radiologist worklist/viewer, signed report delivery to hospitals.

## Quick start (local)

```bash
# 1) Infrastructure
docker compose up -d postgres minio minio-init rabbitmq

# 2) Backend (Java 21 required — system Java 8/11 breaks spring-boot-maven-plugin)
./scripts/build-backend.sh
java -jar backend/target/telerad-backend-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local,api,worker \
  --server.port=18080 \
  --spring.datasource.url=jdbc:postgresql://localhost:5433/telerad \
  --spring.datasource.username=telerad \
  --spring.datasource.password=telerad \
  --spring.rabbitmq.host=localhost \
  --spring.rabbitmq.username=telerad \
  --spring.rabbitmq.password=telerad \
  --telerad.s3.endpoint=http://localhost:9000 \
  --telerad.s3.access-key=minioadmin \
  --telerad.s3.secret-key=minioadmin \
  --telerad.scp.enabled=false

# Optional DICOM SCP (separate process) — facility / modality receive path
./scripts/start-scp.sh
# equivalent: java -jar … --spring.profiles.active=scp --telerad.scp.enabled=true
#             --telerad.scp.port=11112 --telerad.scp.ae-title=TELERAD_SCP --server.port=18081

# 3) Frontend (proxies /api → :18080)
cd frontend && npm install && npm run dev
```

Or full stack with Docker images:

```bash
docker compose up --build
```

| Service | URL / port |
|---------|------------|
| UI | http://localhost:5173 |
| API | http://localhost:18080 |
| Swagger | http://localhost:18080/swagger-ui.html |
| Postgres | localhost:5433 |
| MinIO | http://localhost:9000 (console :9001) |
| RabbitMQ | http://localhost:15672 (`telerad`/`telerad`) |
| DICOM SCP | **11112**, AE **TELERAD_SCP** |

## Demo accounts

Password for all: `password`

| Role | Email |
|------|-------|
| Admin | admin@telerad.local |
| Hospital staff | staff@demohosp.local |
| Radiologist | rad@telerad.local |

## Sample DICOM

```bash
python3 scripts/generate_sample_dicom.py -o /tmp/sample.dcm
```

Upload via hospital portal (backup path) or:

```bash
TOKEN=$(curl -s -X POST http://localhost:18080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"staff@demohosp.local","password":"password"}' | jq -r .accessToken)
curl -X POST "http://localhost:18080/api/hospitals/11111111-1111-1111-1111-111111111111/studies/upload?priority=STAT" \
  -H "Authorization: Bearer $TOKEN" -F "files=@/tmp/sample.dcm"
```

## Modality / PACS simulator (preferred ingest path)

TeleRad listens as **SCP** (`TELERAD_SCP` / port **11112**) for lab/VPN. Production facilities should use the **edge gateway** (LAN C-STORE → HTTPS ingest) — see [`edge-gateway/README.md`](edge-gateway/README.md).

**Prereqs:** API+worker on `:18080`, infra up. Optional SCP in a second terminal:

```bash
./scripts/start-scp.sh
```

**Deps** (once): `pip3 install -r scripts/requirements-dicom.txt`

**Send like a modality** (Calling AE `DEMOHOSP_PACS` → Demo Hospital):

```bash
./scripts/modality_send.sh --sample                 # generate 1 CT + C-ECHO + C-STORE
./scripts/modality_send.sh /path/to/study_dir       # all .dcm under a folder
./scripts/modality_send.sh ANON.zip -v              # ZIP of DICOM
```

Same send with parameters set explicitly (host/IP only — not an `http://` URL):

```bash
./scripts/modality_send.sh --sample \
  --host 127.0.0.1 \
  --port 11112 \
  --called-ae TELERAD_SCP \
  --calling-ae DEMOHOSP_PACS
```

To exercise the secure facility path, point the simulator at the edge gateway (`TELERAD_EDGE` / `11113`) instead of TeleRad SCP.

Options: `--host`, `--port`, `--calling-ae`, `--called-ae`. Unknown Calling AEs are rejected (FR-PAC-002).

**Expect:** C-ECHO OK → C-STORE ok → study on radiologist worklist as `READY` with `source=CSTORE` → open in viewer.

### Working with real studies

```bash
# Strip patient/institution identifiers, keep all clinical content and pixel data
python3 scripts/anonymize_dicom.py IN.zip -o ANON.zip --name "TEST^PATIENT" --id ANON-0001

# Derive a dated-back copy with fresh UIDs, to exercise prior comparison (FR-VW-007)
python3 scripts/make_prior_study.py ANON.zip -o PRIOR.zip --days-ago 90 --accession ACC-PRIOR-001

# Delete a study and its series/instances/reports so the same set can be re-ingested
./scripts/reset_study.sh            # list
./scripts/reset_study.sh <study-id> # delete
```

Both scripts need `pydicom`.

## Core flow

1. Hospital staff uploads `.dcm` / ZIP (or PACS C-STOREs to `TELERAD_SCP` from allowlisted AE)
2. Study becomes READY → SLA timer starts → radiologists notified (email stub + webhook)
3. Hospital staff may assign a radiologist from **Dispatch & Triage**, or a radiologist claims the study from the worklist
4. Radiologist views frames (W/L, zoom, pan, rotate, invert, scroll); a prior study for the same patient is hung side by side with synchronized scrolling when one exists, then drafts/signs the report
5. Signed report delivered: portal PDF, stored FHIR JSON, outbound FHIR webhook + email stub
6. Admin reviews tenants, AE titles, audit, and notification outbox

Registered Calling AEs (seed): `DEMOHOSP_PACS`, `CITYMED_PACS`

## Layout

```
backend/     Spring Boot API + ingest worker + optional DICOM SCP
frontend/    React (Vite) hospital / radiologist / admin portals
scripts/     Sample DICOM, modality C-STORE simulator, anonymizer, prior builder, SCP start
docs/        Product requirements (source of truth)
PLAN.md      Architecture & milestones
docker-compose.yml
```

## Product requirements (source of truth)

Canonical scope, workflows, and granular functional requirements (with Done / Partial / Todo / Deferred status):

→ [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)

Ethiopia selling points and go-to-market (draft, not product commitments): [`docs/ETHIOPIA-GTM.md`](docs/ETHIOPIA-GTM.md)

Engineering architecture and delivery plan: [`PLAN.md`](PLAN.md)
