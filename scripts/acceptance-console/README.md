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

A separate **negative suite** (P3, below) covers the cases where TeleRad is *supposed* to
refuse something. Each negative case is judged against **its own expected outcome**, so an
expected rejection is a PASS and is never misreported as a failure.

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

## Negative suite (P3) — a clean rejection is a PASS

`negative_suite.py` (pure module, no FastAPI imports) runs nine cases where the platform is
**supposed** to refuse. Each result is `{id, label, expected, observed, status, detail,
plain}` and the verdict is always against that case's **own expectation**, never the
happy-path one. Every assertion is behavioural: the simulator's per-object C-STORE verdicts
plus TeleRad's read-only database keyed to the fixture's StudyInstanceUID and the platform's
total study count. Nothing is inferred from "a study appeared".

| case id | how it runs | expected outcome | status when correct |
|---|---|---|---|
| `neg-non-dicom` | fixture `bad-non-dicom` | payload refused; no study created; clear message | pass (green) |
| `neg-zero-byte` | fixture `bad-zero-byte` | nothing stored; no study created | pass (green) |
| `neg-truncated` | fixture `bad-truncated` | clear error; store-or-refuse both acceptable (`either`) | pass if refused, **warn** if silently stored |
| `neg-partial` | fixture `bad-one-of-six` | 5 good stored, corrupt one reported, not fatal; study usable | pass (green) |
| `neg-wrong-called-ae` | simulator `--called-ae WRONG_AE --limit 2` on a spent fixture | association refused cleanly; nothing stored | pass (green) |
| `neg-unknown-calling-ae` | simulator `--calling-ae GHOST_PACS --limit 2` on a spent fixture | association forms, every C-STORE refused `NotAuthorized` (`0x0124`); nothing stored | pass (green) |
| `neg-duplicate` | re-send a spent fixture (same SOP UIDs) | no second study; duplicate absorbed cleanly | pass (green) |
| `neg-interrupted` | kill the sender mid-transfer | platform consistent, no crash, reported as interrupted | pass; **warn** if the kill missed and the send completed |
| `neg-unsupported-sop` | generated CT+SR fixture | SR objects declined (no accepted presentation context); CT stored; study usable | **warn (amber, finding F1)** |

A `warn` is deliberately not a silent green: `neg-truncated` stored silently (no error was
surfaced) and `neg-unsupported-sop` is the known F1 limitation, so both are amber. Junk that
gets stored when it should not is red. Connection, duplicate and interrupted cases reuse an
**already-ingested** fixture (discovered from the read-only DB) so a first ingest cannot
pollute the before/after counts.

### Endpoints

| method | path | purpose |
|---|---|---|
| `GET` | `/api/negative/cases` | list the case matrix with each expected outcome (sends nothing) |
| `POST` | `/api/negative/run` | run one case: `{"case_id": "neg-duplicate"}` → one result |
| `POST` | `/api/negative/run-all` | run the whole suite sequentially → `{results, summary}` |

The console panel **6 · Negative suite (P3)** lists every case with its expected outcome and
renders each result green/amber/red with the observed text, so the operator never has to read
a log to know whether an expected rejection behaved correctly. Runs are serialised with an
async lock because each case measures the study count before and after.

### Run it directly (no console restart needed)

```bash
cd scripts/acceptance-console
/opt/dicom-tools/bin/python negative_suite.py                 # all nine cases, JSON on stdout
/opt/dicom-tools/bin/python negative_suite.py --case neg-duplicate
/opt/dicom-tools/bin/python negative_suite.py --list
```

It loads `/home/hermes/.telerad-acceptance.env` itself (a plain `KEY=VALUE` parser — never
`source`, which the unquoted `TELERAD_HOSPITAL_NAME` would break), so it never prints a
password. Raw evidence (simulator JSON + before/after DB snapshots) lands in
`negative-evidence/` (gitignored).

### SR material generator (`neg-unsupported-sop`)

`scripts/make_sr_fixture.py` derives a small fixture containing a few CT images plus ≥1
unsupported SR object (X-Ray Radiation Dose SR and/or Enhanced SR) from the already
de-identified fixture `F/f-full.zip`, preserving the SR SOP classes and assigning fresh
Study/Series/SOP UIDs. The output root is a parameter:

```bash
# host-side: into the standard fixture tree (Hermes runs this)
/opt/dicom-tools/bin/python scripts/make_sr_fixture.py \
    --out-root /home/hermes/dicom-fixtures

# sandboxed: repo-local proof
/opt/dicom-tools/bin/python scripts/make_sr_fixture.py \
    --out-root scripts/acceptance-console/negative-fixtures
```

It writes `<out-root>/sr-unsupported/{sr-unsupported.zip,manifest.json}`; the manifest marks
it synthetic (`partial_ingest`, 3 CT stored, 2 SR declined) so the console can select it
without a new de-identification gate. The case resolves the fixture from the repo-local dir
first, then `<fixtures_dir>/V/sr-unsupported/`, and warns with this exact command if it is
absent.

## Read-only database role

Stage 2 corroborates through `telerad_test_ro`, which has SELECT on the study tables only
and no write privileges at all — deliberately not the application's credentials.

## Portability (P4) — point the suite at another site by configuration alone

The suite is site-agnostic. A new site edits **one profile file** and changes nothing else;
the console's `portal_urls` (and every other site value) are derived from that profile rather
than baked into `app.py`.

| Artifact | What it is |
|---|---|
| `profile.example.env` | The single profile surface: API/app URLs, PACS host/port/AE titles, DB, accounts, Browserless, paths. Every variable is documented inline. Copy it and edit. |
| `RUNBOOK.md` | Step-by-step for a site operator: prerequisites, required reachability, start the console, create + verify a fixture, connection test, full run, how to read verdicts, common failure meanings. |
| `MIGRATION.md` | The checklist of what **changes** (hostnames/ports/AE titles/node rows/credentials/TLS/browserless) vs what **must not** (two-stage principle, UID-keyed Stage-2, single-use fixtures, no app-code edits, decline ≠ fail, never a silent green). |
| `../portability/mock_scp.py` | A throwaway pynetdicom storage SCP that enforces the called AE and the allowed calling AE, so a site can validate its config end-to-end **before** the real PACS. Not a PACS, never for a clinical decision. |
| `../portability/portability_check.sh` | Runs the Stage-1 check (association + C-ECHO dry run, then an optional limited send) against whichever endpoint the profile names. The command is identical for the mock and the real endpoint — only `--profile` differs. |

```bash
cd /home/hermes/telerad-repo
# validate config against the mock first
/opt/dicom-tools/bin/python scripts/portability/mock_scp.py --port 11113 \
    --ae-title MOCKPACS --allow-calling-ae DEMOHOSP_PACS --max-stores 5
scripts/portability/portability_check.sh --profile scripts/portability/mock.env
# then the real endpoint: connection only, stores nothing (never spend a fresh fixture)
scripts/portability/portability_check.sh --profile /home/hermes/.telerad-acceptance.env --dry-run-only
```

Site values in the profile examples are **documented defaults** for the demo deployment, not
hidden hardcodes; active paths read them from the profile.

## What is not automated yet

Negative-path coverage (wrong/unknown AE, corrupt or non-DICOM objects, interrupted
transfers, duplicate SOP UIDs, unsupported SOP classes) is the P3 suite above.

