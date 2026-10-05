# TeleRad acceptance console

A browser UI over the PACS simulator. It answers one question — *did TeleRad accept the
study?* — without the operator reading logs, SQL or terminal output.

## The rule this console enforces

**Stage 1 (transport)** proves DICOM moved: association, C-ECHO, per-object C-STORE
verdicts. **Stage 2 (application)** asks TeleRad *itself* — via its REST API and a
read-only database role — whether the study exists, has the expected series and instance
counts, sits under the right hospital, is `READY`, can serve image pixels, **renders on
screen**, and reaches dispatch.

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
# viewer-render check (P1): headless Chrome + the app UI
export TELERAD_APP_URL=https://telerad.13.140.56.88.sslip.io
export BROWSERLESS_URL=http://127.0.0.1:3000
export BROWSERLESS_TOKEN=...      # from /opt/browserless/.env
uvicorn app:app --host 127.0.0.1 --port 8099
```

## Stage-2 check: `viewer-render`

`api-viewer` proves the pixels can be *fetched*. `viewer-render` proves the browser can
*draw* them. It opens `$TELERAD_APP_URL/studies/<id>` in headless Chrome (Browserless),
injects the full login object into `localStorage["telerad_auth"]` **before the app scripts
run**, and asserts:

* `#root` has at least one child (the bundle mounted),
* at least one `<canvas>` exists (a viewport was created),
* zero uncaught `pageerror` events.

It saves a PNG screenshot and links it from the check detail via
`GET /api/reports/<file>` (served from the reports directory, guarded against path
traversal). A structurally broken page (empty root / no canvas) is a **fail**; a page that
mounted but raised an uncaught JS error is also a **fail**; if Browserless cannot be
reached at all the check is a **warn** — never a silent pass. A screenshot that cannot be
written to disk is reported in the detail but does not, on its own, fail the render.

| Env var | Default | Meaning |
|---|---|---|
| `TELERAD_APP_URL` | `https://telerad.13.140.56.88.sslip.io` | React SPA origin for `/studies/:id` |
| `BROWSERLESS_URL` | `http://127.0.0.1:3000` | Browserless base URL |
| `BROWSERLESS_TOKEN` | *(empty)* | Browserless token; empty means the check warns |

## Prove the check (two-state selftest)

```bash
cd scripts/acceptance-console
/opt/dicom-tools/bin/python selftest_viewer_render.py            # newest READY study
/opt/dicom-tools/bin/python selftest_viewer_render.py --study-id <uuid>
```

It runs the check on the healthy deployment (**expect PASS**), moves
`tele2-frontend-1:/usr/share/nginx/html/assets` aside (**expect FAIL** — the broken-bundle
signal), then restores it in a `finally` block and re-checks (**expect PASS**). If the
assets cannot be restored it prints a loud `NEEDS ATTENTION` line and exits non-zero.
Evidence (per-step JSON + screenshots) goes to `selftest-evidence/`. Exit `0` only when
all three expectations held; `2` means a state could not run (e.g. docker unreachable).

## Read-only database role

Stage 2 corroborates through `telerad_test_ro`, which has SELECT on the study tables only
and no write privileges at all — deliberately not the application's credentials.

## What is not automated yet

Dispatch → claim → report → sign → delivery are linked in the UI for manual steps; the
automated versions are phase P2.
