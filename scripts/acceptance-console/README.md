# TeleRad acceptance console

A browser UI over the PACS simulator. It answers one question — *did TeleRad accept the
study?* — without the operator reading logs, SQL or terminal output.

## The rule this console enforces

**Stage 1 (transport)** proves DICOM moved: association, C-ECHO, per-object C-STORE
verdicts. **Stage 2 (application)** asks TeleRad *itself* — via its REST API and a
read-only database role — whether the study exists, has the expected series and instance
counts, sits under the right hospital, is `READY`, can serve image pixels, **renders on
screen**, and reaches dispatch. **Stage 3 (clinical workflow)** closes the loop:
assign → claim → report → sign → deliver, ending with the hospital able to download the
signed PDF.

A successful C-STORE is never presented as proof of ingestion. Stage 2 is keyed to the
**fixture's StudyInstanceUID**, never to "a new study appeared" — otherwise a
pre-existing study would satisfy the checks and the test would pass having sent nothing.
Stage 3 is likewise resolved by the fixture's StudyInstanceUID against the hospital's own
study list.

Verdicts: `TEST PASSED`, `TEST PASSED WITH DECLINED OBJECTS` (usable study, some objects
refused by TeleRad), `TEST FAILED` (a hard check failed; the failing stage is named). A
failed stage-3 step names the workflow step; a completed one says the loop closed.

## Run

```bash
export TELERAD_API=http://127.0.0.1:18080
export TELERAD_ADMIN_EMAIL=admin@telerad.local
export TELERAD_ADMIN_PASSWORD=password
# stage-3 accounts (P2): dispatch + clinical actor + hospital read-back
export TELERAD_RADIOLOGIST_EMAIL=rad@telerad.local
export TELERAD_RADIOLOGIST_PASSWORD=password
export TELERAD_HOSPITAL_USER_EMAIL=staff@demohosp.local
export TELERAD_HOSPITAL_USER_PASSWORD=password
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

## Stage-3 checks: the clinical workflow (P2)

`workflow.py` (pure module, no FastAPI imports) automates what used to be a manual list.
It first resolves the caller's study by StudyInstanceUID against
`/api/hospitals/{hospital_id}/studies` (returning no checks when the study is absent —
stage 2 already reported the miss), then runs six checks:

| id | action (actor) | pass condition |
|---|---|---|
| `wf-assign` | hospital user assigns the study to the radiologist | status `IN_PROGRESS` and `assignedTo` == the radiologist's own `userId` |
| `wf-claim` | the radiologist claims it | claim HTTP 200 and the study is `IN_PROGRESS` assigned to them |
| `wf-report` | the radiologist saves a synthetic draft | a report id comes back with status `DRAFT` |
| `wf-sign` | the radiologist signs it | status `SIGNED`, `hasPdf` true, `signedAt` set |
| `wf-delivered` | hospital side | study status `DELIVERED` **and** the report is listed by `/api/hospitals/{id}/reports` |
| `wf-pdf` | hospital side | `GET /api/reports/{reportId}/pdf` → HTTP 200, `%PDF-` magic, size > 0 |

The report text is deliberately synthetic and says so — it must never be mistaken for a
clinical record. The downloaded PDF is saved to `out_dir` as `delivered-<reportId>.pdf`,
and the check detail ends with a link through the console's existing
`GET /api/reports/<file>` route, so the operator can open the artifact.

**Account model.** Three seed accounts (password `password`): `rad@telerad.local`
(RADIOLOGIST — assign target *and* claim/report/sign actor; its `userId` is read from its
own login, never hardcoded), `staff@demohosp.local` (HOSPITAL_USER of Demo General
Hospital — dispatch and the delivery/PDF read-back), and `admin@telerad.local` (ADMIN,
used only as a fallback login). Point the console at different tenants purely through the
env vars below.

**Honesty note.** The study detail JSON is `{study, series, report}` and does **not**
expose the `claimed_at` column — it exists on the entity but in no response DTO.
`wf-claim` therefore verifies the observable claim transition (the 200 plus the
`IN_PROGRESS`/assigned state) and says so in the detail, rather than asserting a field the
API never returns.

| Env var | Default | Meaning |
|---|---|---|
| `TELERAD_RADIOLOGIST_EMAIL` | `rad@telerad.local` | Radiologist account for claim/report/sign |
| `TELERAD_RADIOLOGIST_PASSWORD` | `password` | its password |
| `TELERAD_HOSPITAL_USER_EMAIL` | `staff@demohosp.local` | Hospital user for dispatch and read-back |
| `TELERAD_HOSPITAL_USER_PASSWORD` | `password` | its password |

Run it once by hand (picks the smallest `READY`, unassigned, report-free study — it
**mutates** that study to `DELIVERED`):

```bash
cd scripts/acceptance-console
/opt/dicom-tools/bin/python workflow.py            # or --study-uid <StudyInstanceUID>
```

In the console this runs automatically as part of a full run (not the 20-image quick
check, and never when the study was not found), streamed as `stage: 3` check events.

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

Negative-path coverage (wrong/unknown AE, corrupt or non-DICOM objects, interrupted
transfers, duplicate SOP UIDs, unsupported SOP classes) is phase P3; portability to a
real hospital PACS is phase P4.
