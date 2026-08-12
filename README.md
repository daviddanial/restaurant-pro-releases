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

# Optional DICOM SCP (separate process)
java -jar backend/target/telerad-backend-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=scp \
  --server.port=18081 \
  --spring.datasource.url=jdbc:postgresql://localhost:5433/telerad \
  --spring.datasource.username=telerad \
  --spring.datasource.password=telerad \
  --spring.rabbitmq.host=localhost \
  --spring.rabbitmq.username=telerad \
  --spring.rabbitmq.password=telerad \
  --telerad.s3.endpoint=http://localhost:9000 \
  --telerad.s3.access-key=minioadmin \
  --telerad.s3.secret-key=minioadmin \
  --telerad.scp.enabled=true \
  --telerad.scp.port=11112 \
  --telerad.scp.ae-title=TELERAD_SCP

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

Upload via hospital portal or:

```bash
TOKEN=$(curl -s -X POST http://localhost:18080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"staff@demohosp.local","password":"password"}' | jq -r .accessToken)
curl -X POST "http://localhost:18080/api/hospitals/11111111-1111-1111-1111-111111111111/studies/upload?priority=STAT" \
  -H "Authorization: Bearer $TOKEN" -F "files=@/tmp/sample.dcm"
```

## Core flow

1. Hospital staff uploads `.dcm` / ZIP (or PACS C-STOREs to `TELERAD_SCP` from allowlisted AE)
2. Study becomes READY → SLA timer starts → radiologists notified (email stub + webhook)
3. Radiologist claims study, views frames (W/L, zoom, pan, invert, scroll), drafts/signs report
4. Signed report delivered: portal PDF, stored FHIR JSON, outbound FHIR webhook + email stub
5. Admin reviews tenants, AE titles, audit, and notification outbox

Registered Calling AEs (seed): `DEMOHOSP_PACS`, `CITYMED_PACS`

## Layout

```
backend/     Spring Boot API + ingest worker + optional DICOM SCP
frontend/    React (Vite) hospital / radiologist / admin portals
scripts/     Sample DICOM generator
docs/        Product requirements (source of truth)
PLAN.md      Architecture & milestones
docker-compose.yml
```

## Product requirements (source of truth)

Canonical scope, workflows, and granular functional requirements (with Done / Partial / Todo / Deferred status):

→ [`docs/REQUIREMENTS.md`](docs/REQUIREMENTS.md)

Engineering architecture and delivery plan: [`PLAN.md`](PLAN.md)
