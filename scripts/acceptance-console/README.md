# TeleRad acceptance console

A browser UI over the PACS simulator. It answers one question — *did TeleRad accept the
study?* — without the operator reading logs, SQL or terminal output.

## The rule this console enforces

**Stage 1 (transport)** proves DICOM moved: association, C-ECHO, per-object C-STORE
verdicts. **Stage 2 (application)** asks TeleRad *itself* — via its REST API and a
read-only database role — whether the study exists, has the expected series and instance
counts, sits under the right hospital, is `READY`, can serve image pixels, and reaches
dispatch.

A successful C-STORE is never presented as proof of ingestion. Stage 2 is keyed to the
**fixture's StudyInstanceUID**, never to "a new study appeared" — otherwise a
pre-existing study would satisfy the checks and the test would pass having sent nothing.

Verdicts: `TEST PASSED`, `TEST PASSED WITH DECLINED OBJECTS` (usable study, some objects
refused by TeleRad), `TEST FAILED` (a hard check failed; the failing stage is named).

## Run

```bash
export TELERAD_API=http://127.0.0.1:18080
export TELERAD_ADMIN_EMAIL=admin@telerad.local
export TELERAD_ADMIN_PASSWORD=password
export FIXTURES_DIR=/home/hermes/dicom-fixtures
export SENDER=/home/hermes/telerad-repo/scripts/pacs-simulator/send-study.py
export PYTHON=/opt/dicom-tools/bin/python
export DB_HOST=127.0.0.1 DB_PORT=5433 DB_NAME=telerad
export DB_USER=telerad_test_ro DB_PASSWORD=...
uvicorn app:app --host 127.0.0.1 --port 8099
```

## Read-only database role

Stage 2 corroborates through `telerad_test_ro`, which has SELECT on the study tables only
and no write privileges at all — deliberately not the application's credentials.

## What is not automated yet

Dispatch → claim → report → sign → delivery are linked in the UI for manual steps; the
automated versions are phase P2.
