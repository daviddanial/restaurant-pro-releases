# TeleRad acceptance-testing — handoff brief for the in-VPS agents

**Purpose.** Phase P0 (the acceptance console + simulator + fixture pipeline) is complete and
verified. P1–P4 continue **on this VPS**, driven from mobile through Hermes, which may delegate
heavy execution to DeepSeek Harness (`dsh`). Nothing here needs the laptop.

Operator: Fayiz. Operator constraint: **never required to read logs, SQL or terminal output** to
know whether a test passed — the console must tell them.

---

## 1. Where everything lives

| Thing | Path |
|---|---|
| Test tooling (git) | `/home/hermes/telerad-repo` — branch **`test/pacs-simulator`** |
| Live app code | same repo, branch **`vps-integration`** @ `8c16b6a` — **do not modify** |
| Fixtures (single-use) | `/home/hermes/dicom-fixtures/<LETTER>/` + `reports/` |
| Acceptance console | `https://test.13.140.56.88.sslip.io` (basic auth, user `fayz`) |
| Console service | `telerad-acceptance.service` → uvicorn on `127.0.0.1:8099` |
| Console env | `/home/hermes/.telerad-acceptance.env` (0600) |
| Read-only DB role | `telerad_test_ro` (SELECT on study tables only) |
| Platform (compose) | project **`tele2`**, SCP `TELERAD` on `127.0.0.1:11112` |
| Console password | `/root/.acceptance-console-pw` |

The console runs **on the host**, not in a container, because it must reach `127.0.0.1` for the
SCP, the API and Postgres.

---

## 2. Invariants — never violate these

1. **Stage 1 never passes a test on its own.** Transport success (association, C-ECHO, C-STORE
   verdicts) is not proof of ingestion. Stage 2 must query TeleRad's own API/DB.
2. **Stage 2 assertions are keyed to the fixture's `StudyInstanceUID`**, never to "a study
   appeared". Otherwise a pre-existing study satisfies the checks and the test passes having sent
   nothing.
3. **Fixtures are single-use.** Each has its own Study/Series/SOP UIDs; once ingested, re-sending
   it is a *duplicate-SOP* test, not a clean run. The console shows `FRESH` / `ALREADY INGESTED` —
   prefer `FRESH`.
4. **Only verified de-identified fixtures, or manifest-marked synthetic ones, are selectable.**
   Never add a patient-identifiable study to the fixtures directory. `source/` holds the
   identifiable original and is excluded by policy.
5. **Do not modify application code** (`backend/`, `frontend/` of the app). Test tooling only, on
   `test/pacs-simulator`.
6. **Do not delete studies** without explicit human approval. `scripts/reset_study.sh` exists and
   works, but deleting is the human's call.
7. A decline for a SOP class TeleRad does not support is **not** a transport failure. It is an
   amber "passed with declined objects", never a red, and never silently a green.

---

## 3. Verified state at handoff (evidence, not claims)

* Fixture pipeline: de-identify → verify (gate) with a salt-recomputed UID mapping, exact match on
  1,199/1,199 SOP UIDs, 17/17 series, 0 private attributes remaining.
* Console: end-to-end run on fixture E produced **TEST PASSED WITH DECLINED OBJECTS**; 10 Stage-2
  checks executed; viewer pixel fetch verified (HTTP 200, DICM magic).
* Transport: C-ECHO `0x0000`; 1,193/1,199 stored; the 6 declines are `X-Ray Radiation Dose SR`
  (3) and `Enhanced SR` (3) — **TeleRad's SCP declares no presentation context for SR classes**
  (`DicomScpServer.commonStorageSopClasses()`). This is a product finding, not a test defect.
* Known product findings to preserve and re-verify, not to fix silently:
  F1 SCP drops SR objects · F2 portal upload records study modality `SR` where C-STORE records
  `CT` for identical data · F3 free-text `StudyDescription` retains the site abbreviation despite
  tag-level de-identification · F4 `BurnedInAnnotation` absent on 9 instances (needs a human eye)
  · F5 no TLS in the SCP.

---

## 4. Work queue

Each phase: implement, verify with evidence, commit to `test/pacs-simulator`, and report in plain
language. Do not start a phase without finishing the previous one's verification.

### P1 — viewer/render verification in the UI
* Goal: the console proves the viewer *renders*, not just that pixels are fetchable.
* The API-level pixel check already exists (`api-viewer`). Extend to a headless render: load the
  study route with a token, assert `#root` has children, `canvas` count > 0, no `pageerror`, and
  capture a screenshot.
* Reuse the `browserless` container (`127.0.0.1:3000`, token in its env).
* Acceptance: the checklist contains a viewer-render item that fails when the frontend bundle is
  broken and passes on a healthy build. Prove it by testing both states.

### P2 — automated workflow: dispatch → claim → report → sign → deliver
* Automate what is now a manual list: assign the study to a radiologist, claim it, create a report,
  sign it, and retrieve the delivered PDF from the hospital side.
* API endpoints exist (`/api/studies/{id}/assign|claim|report|report/sign`, `/api/reports/{id}/pdf`,
  `/api/studies/{id}/comply|feedback` — note *comply* is state-guarded: it needs a report first).
* Acceptance: one console run ends with a checklist item *delivered*, not *ready to read*.

### P3 — negative suite
* Cases: wrong called AE, unknown calling AE, unknown hospital, corrupt/truncated instance,
  zero-byte file, non-DICOM file, interrupted transfer, duplicate send (same SOP UID),
  unsupported SOP class.
* **"Rejects cleanly with a clear message" is a PASS** for these.
* Acceptance: each case appears in the console with its expected outcome recorded, so an expected
  rejection is not misreported as a failure.

### P4 — portability pack for a real hospital PACS
* Deliver: a config-only profile (host/port/called AE/calling AE/node row/TLS), a runbook, and the
  migration checklist of what changes vs what must not.
* Acceptance: the suite can be pointed at a different endpoint by editing configuration alone.

---

## 5. Tooling cheatsheet

```bash
PY=/opt/dicom-tools/bin/python
R=/home/hermes/telerad-repo

# a fresh, single-use, verified fixture (full study, or a small subset)
$PY $R/scripts/make_fixture.py --letter J                 # ~1,199 instances
$PY $R/scripts/make_fixture.py --letter T --subset 24     # smoke fixture

# de-identification gate on its own
$PY $R/scripts/verify_deid.py --original SRC --derived OUT --salt SALT --report rep.json

# send a study the way a hospital PACS would
$PY $R/scripts/pacs-simulator/send-study.py FIXTURE.zip --called-ae TELERAD \
    --calling-ae DEMOHOSP_PACS --dry-run          # connection only
#  (add --progress-json for NDJSON events; --json-out for the full result)

# the console, as the browser drives it
curl -s localhost:8099/api/config                       # fixtures, defaults, freshness
curl -s -X POST localhost:8099/api/test-connection -H 'Content-Type: application/json' \
     -d '{"host":"127.0.0.1","port":11112,"called_ae":"TELERAD","calling_ae":"DEMOHOSP_PACS"}'
curl -s -X POST localhost:8099/api/run -H 'Content-Type: application/json' \
     -d '{"fixture_id":"f-full"}'
curl -N localhost:8099/api/stream/<run_id>              # SSE: progress + Stage-2 checks + verdict

# codecs for transfer-syntax studies (installed): dcmcjpeg +eb|+el, dcmcjpls, dcmcrle,
# gdcmconv (JPEG-LS ok; JPEG2000 falls back to uncompressed — do not claim it works),
# DCMTK storescu/echoscu as an independent sender for cross-checks.
```

---

## 6. Working rules

* Commit test-tooling changes to `test/pacs-simulator` and push. App code stays on
  `vps-integration`; regressing the product is `git checkout vps-integration` then rebuild.
* Delegate long or parallel execution to `dsh` (`dsh --profile headless "<task>"`); the
  `deepseek-harness` skill documents it.
* When a verification fails, treat the **expectation** as suspect before the platform: three of the
  four failures during P0 were bugs in the test tooling, not in TeleRad. Report which one you
  concluded and why.
* Report to the human in plain language, with the evidence line that proves it.
