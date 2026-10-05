# RUNBOOK — stand up the acceptance suite at a new site

Audience: the site operator who has the TeleRad deployment in front of them and wants a
verdict. This is the ordered list. It assumes **no code changes** — everything below is
configuration and commands (see `MIGRATION.md` for what changes vs what must not).

Two rules before you start:

* The suite is **not** a proof that a study was ingested unless Stage 2 says so. Transport
  success alone is never a pass.
* Fixtures are **single-use**. Never re-send a fixture that is already ingested; the console
  shows `FRESH` / `ALREADY INGESTED` and a spent fixture proves nothing.

---

## 0 · Prerequisites on the console host

| Need | Check |
|---|---|
| Python with pydicom + pynetdicom | `/opt/dicom-tools/bin/python -c 'import pydicom, pynetdicom'` |
| The test-tooling repo | branch `test/pacs-simulator` checked out at `scripts/` |
| A writable fixtures directory | e.g. `/home/hermes/dicom-fixtures` |
| Curl (for the API steps) | or drive everything from the console UI |
| An account on the TeleRad API | admin, radiologist and hospital-user logins |

## 1 · Reachability from the console host

All three paths must work **from the host that runs the console**, not from your laptop:

| Path | Default | Prove it |
|---|---|---|
| DICOM SCP | `127.0.0.1:11112` | `mc`/`echoscu`, or step 3 below (dry-run) |
| TeleRad API | `http://127.0.0.1:18080` | `curl -s $TELERAD_API/health` (or any known route) |
| PostgreSQL (read-only) | `127.0.0.1:5433` | the console's Stage-2 checks; `telerad_test_ro` logs in |
| Browserless (optional) | `http://127.0.0.1:3000` | only for the viewer-render check; if it is down that check **warns**, it does not silently pass |

If the SCP is bound to loopback (the demo deployment is), the console must run on the same
host or reach it over an SSH tunnel.

## 2 · Create the site profile (the one file to edit)

```bash
cd /home/hermes/telerad-repo/scripts/acceptance-console
cp profile.example.env /home/hermes/.telerad-acceptance.env
$EDITOR /home/hermes/.telerad-acceptance.env
```

Every variable is documented in `profile.example.env`. The ones that usually change:
`TELERAD_API`, `TELERAD_APP_URL`, `PACS_HOST/PACS_PORT/PACS_CALLED_AE/PACS_CALLING_AE`,
`DB_*`, the three account pairs, `TELERAD_HOSPITAL_ID/NAME`, `FIXTURES_DIR`,
`BROWSERLESS_URL/TOKEN`.

The simulator's `PACS_*` values are the **same names** as `scripts/pacs-simulator/config.env`,
so a site that only uses the simulator can edit `scripts/pacs-simulator/config.env` instead.
Real environment variables always win over the file.

## 3 · Validate the configuration against the mock BEFORE the real PACS

This proves host/port/AE wiring without touching (or spending) anything on the real endpoint.

```bash
cd /home/hermes/telerad-repo
# terminal A — a throwaway storage SCP on a private port
/opt/dicom-tools/bin/python scripts/portability/mock_scp.py \
    --port 11113 --ae-title MOCKPACS --allow-calling-ae DEMOHOSP_PACS \
    --store-dir /tmp/mock-scp --max-stores 5 --expect-echo 2 --expect-stores 5

# terminal B — the SAME check you will run against the real endpoint
scripts/portability/portability_check.sh --profile scripts/portability/mock.env
#   -> dry run (assoc + C-ECHO) : PASS
#   -> limited send             : PASS
```

`mock_scp.py` enforces the called AE and the allowed calling AE, so a typo in either value
fails here instead of at the hospital.

## 4 · Point at the real endpoint: connection test (no store)

```bash
cd /home/hermes/telerad-repo
scripts/portability/portability_check.sh \
    --profile /home/hermes/.telerad-acceptance.env --dry-run-only
```

`--dry-run-only` associates and sends C-ECHO and **stores nothing** — never spend a fresh
fixture on a real PACS just to test the wiring. Expect:

```
Association established (...s)
C-ECHO: success (0x0000)
```

Press the same button in the console with **Test connection** (`POST /api/test-connection`).

## 5 · Start the console

```bash
cd /home/hermes/telerad-repo/scripts/acceptance-console
set -a; . /home/hermes/.telerad-acceptance.env; set +a
/opt/dicom-tools/bin/python -m uvicorn app:app --host 127.0.0.1 --port 8099
```

In the demo deployment this is the `telerad-acceptance.service` unit with
`EnvironmentFile=/home/hermes/.telerad-acceptance.env`; the site's own supervisor should do
the same. Open the console in a browser (the demo puts basic auth in front of it). If you
run it under a different profile, point `TELERAD_ACCEPTANCE_ENV` at that file so the
standalone tools agree.

## 6 · Create and verify a fixture

`make_fixture.py` de-identifies, verifies (the gate) and registers in one command. A small
subset smoke fixture is enough to shake out configuration; use a full study for acceptance.

```bash
cd /home/hermes/telerad-repo
# smoke: 24 instances across many series
/opt/dicom-tools/bin/python scripts/make_fixture.py --letter S --subset 24
# full acceptance study (~1,199 instances)
/opt/dicom-tools/bin/python scripts/make_fixture.py --letter F
```

The verifier is the gate: a fixture that fails de-identification is never selectable. To
re-run the gate by hand:

```bash
/opt/dicom-tools/bin/python scripts/verify_deid.py \
    --original <source> --derived <derived> --salt <salt> --report /tmp/deid.json
```

Never add a patient-identifiable study to the fixtures directory.

## 7 · Run the acceptance test

From the console UI: pick a `FRESH` fixture and press **Run**. By API:

```bash
curl -s -X POST localhost:8099/api/run -H 'Content-Type: application/json' \
     -d '{"fixture_id":"f-full"}'
# -> {"run_id":"...","fixture":{...}}
curl -N localhost:8099/api/stream/<run_id>     # SSE: stage 1, stage 2, stage 3, verdict
```

Add `"limit": 20` for a quick smoke that sends the first 20 instances and **skips** Stage 2/3
(so it never mutates a study). A full run walks all three stages and ends with a verdict.

## 8 · How to read the verdict

| Verdict | Meaning |
|---|---|
| `TEST PASSED` | Every Stage-2 check passed, keyed to this fixture's StudyInstanceUID |
| `TEST PASSED WITH DECLINED OBJECTS` | The study is usable; some objects were refused by TeleRad for a SOP class it does not support. **Amber**, not red — but a named finding, never a silent green |
| `TEST FAILED` | A hard check failed; the failing stage (1/2/3) is named in the check |

Read it on the console's result panel; you should not need logs or SQL to know the outcome.
The `plain` line on each check says what it observed in words.

## 9 · Common failure meanings

| Symptom | First thing to check |
|---|---|
| `ASSOCIATION REJECTED` | `PACS_CALLED_AE` must equal the SCP's AE title; wrong called AE → reject |
| Association forms, every store `NotAuthorized (0x0124)` | `PACS_CALLING_AE` is not an active `dicom_nodes` row (tenant mapping) |
| Connection refused / timeout | wrong `PACS_HOST`/`PACS_PORT`, firewall, or the SCP is bound elsewhere |
| C-ECHO ok, store fails | the SCP has no presentation context for that SOP class — expected for SR |
| Stage 2 finds no study | the suite is asking the wrong API/DB, or the study UID is spent; check `TELERAD_API` and `DB_*` |
| Stage 2 passes but the wrong hospital | `TELERAD_HOSPITAL_ID` is the tenant the DICOM node maps to; verify the run's hospital |
| viewer-render warns | Browserless unreachable or `BROWSERLESS_TOKEN` empty — a warning is deliberate, not a pass |
| Fixture shows `ALREADY INGESTED` | it is spent; generate a new fixture (step 6) — re-sending is a duplicate-SOP test |
| Login failures in Stage 3 | the three account env vars; the radiologist must match `TELERAD_RADIOLOGIST_EMAIL` |

## 10 · What not to do

* Do not edit `backend/` or `frontend/`. If a run fails, suspect the configuration (or the
  suite) before the platform, and say which you concluded.
* Do not delete studies without explicit human approval.
* Do not "fix" a decline into a pass. A decline is a peer decision and is reported amber.
* Do not re-send a spent fixture and call it acceptance.

The mock (`scripts/portability/mock_scp.py`) is a validation target only. It is not a PACS;
never use it for a clinical decision, and delete its store directory when done.
