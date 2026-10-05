---
name: telerad-acceptance
description: "Run and extend the TeleRad DICOM acceptance-test suite (PACS simulator, two-stage console, fixtures) on this VPS."
version: 1.3.0
author: Hermes Agent
license: MIT
platforms: [linux]
metadata:
  hermes:
    tags: [telerad, dicom, pacs, acceptance-testing, qa, c-store, verification, dsh]
    related_skills: [deepseek-harness, browse, hermes-agent]
---

# TeleRad acceptance testing

Use this skill whenever the human asks about **testing TeleRad**, the **PACS simulator**, the
**acceptance console**, sending a study to TeleRad, verifying an ingest, or continuing the
acceptance-test phases (P1–P4).

Deep reference: **`/home/hermes/telerad-acceptance/HANDOFF.md`** (paths, invariants, verified state
with evidence, the phase queue with acceptance criteria, tooling cheatsheet). This skill is the
operational short version so you can act immediately.

## The rule that matters

Stage 1 (DICOM transport: association, C-ECHO, C-STORE verdicts) **never** proves TeleRad accepted
a study. Stage 2 must ask TeleRad itself (its API, corroborated by the read-only role
`telerad_test_ro`) and must key every assertion to the **fixture's StudyInstanceUID** — never to
"a study appeared". Report the two stages separately, always.

Verdicts: `TEST PASSED` · `TEST PASSED WITH DECLINED OBJECTS` · `TEST FAILED` (name the stage). A
decline for a SOP class TeleRad does not support is amber, never red, and never a silent green.

## Two ways to hand this work between the agents

This machine already has a working Hermes ⇄ dsh bridge. Use it; do not invent a mechanism. Details
and caveats live in the `deepseek-harness` skill.

| Direction | Command | Use for |
|---|---|---|
| Hermes → dsh, one-shot | `dsh --profile headless "<task>"` | long or self-contained execution; prints only the final answer |
| Hermes → dsh, agent-to-agent (ACP) | `hermes --provider dsh-acp -m dsh chat -q "<task>"` | work that needs dsh to drive Hermes tools, or a full session on dsh |
| dsh → Hermes tools | via the bridge's tool forwarding (see `deepseek-harness`) | when dsh needs a Hermes-side capability |

Shared state makes the handoff cheap: the repo, the fixtures, the console, the read-only DB role and
the codecs are all on this box, so a task may start on one agent and finish on the other. Both
agents read **this same skill** (it is installed in the Hermes catalog *and* in dsh's
`~/.dsh/skills/`).

## What exists

* Console (the operator's UI): **https://test.13.140.56.88.sslip.io**, service
  `telerad-acceptance`, code in `scripts/acceptance-console/`, host-side because it needs
  `127.0.0.1` for the SCP, API and Postgres.
* Simulator: `scripts/pacs-simulator/send-study.py` (C-ECHO + C-STORE; per-instance verdicts;
  `--dry-run`, `--limit`, `--progress-json`, `--json-out`).
* Fixtures: `/home/hermes/dicom-fixtures`. **Single-use** — check `FRESH` vs `ALREADY INGESTED` in
  the console before sending. New one: `make_fixture.py --letter <L>` (`--subset N` for smoke).
* De-identification gate: `scripts/verify_deid.py` must PASS before a fixture is selectable.
* TeleRad SCP: AE `TELERAD` on `127.0.0.1:11112`; allowlisted callers `DEMOHOSP_PACS` (Demo
  General Hospital), `CITYMED_PACS`.
* Transfer-syntax tooling: `dcmcjpeg` (+eb/+el), `dcmcjpls`, `dcmcrle`, `gdcmconv` (JPEG-LS works;
  **JPEG 2000 silently falls back to uncompressed — never claim it works**), and DCMTK
  `storescu`/`echoscu` as an independent sender for cross-checks.

## Running a test for the human

1. Find a **FRESH** fixture; generate one if none.
2. `curl -X POST localhost:8099/api/run -H 'Content-Type: application/json' -d '{"fixture_id":"<id>"}'`
   then read `curl -N localhost:8099/api/stream/<run_id>` — or drive it in the console.
3. Report **in plain language**: what was sent, what TeleRad confirmed, what was declined, the
   verdict — with the evidence line, not a log summary.

## Pitfalls (learned the hard way during P0)

* **Suspect the expectation before the platform.** Three of four failures during P0 were bugs in
  the *test tooling*, not TeleRad. Say which you concluded, and why.
* **A series whose instances were all declined does not exist** in TeleRad, by design — so the
  expected series count is `sent − series declined in full`, not the raw sent count.
* **Declined ≠ failed.** A refusal for an unsupported SOP class is a deliberate peer decision.
* **A spent fixture cannot prove anything.** Re-sending one is a duplicate-SOP test.
* **Never send `dicom-fixtures/source/`** (the identifiable original) and never add identifiable
  data to the fixtures directory; only verified de-identified or manifest-marked synthetic
  fixtures are selectable.
* **Never modify application code** (`backend/`, `frontend/`). Test tooling only, branch
  `test/pacs-simulator`; the deployed product is `vps-integration`.
* **Never delete studies** without explicit human approval.

## Phase queue

P1 **done** — `viewer-render` check + two-state selftest · P2 **done** — stage-3 workflow automation
(`workflow.py`: assign→claim→report→sign→deliver; runs on every full console run) · P3 **done** —
negative suite (`negative_suite.py`: nine refusal cases judged against their own expected outcome;
console panel 6; `make_sr_fixture.py` for the unsupported-SR fixture) · P4 **done** — portability pack
(`profile.example.env`, `RUNBOOK.md`, `MIGRATION.md`, `scripts/portability/mock_scp.py` +
`portability_check.sh`). Acceptance criteria: the handoff brief, section 4.

## Findings → developer cards (F1–F5, P3)

Six findings (F1–F5 and the truncated-object case P3) were turned into **self-contained developer
cards** for people who do NOT have this suite: `/home/hermes/telerad-acceptance/findings-cards/`
(master doc + `cards/` per finding + `toolkit/` scripts + zip). Each card is independently
reproducible with DCMTK, pydicom, curl and openssl only — every quoted command/output was executed
on 2026-10-05 before delivery.

Reusable repro toolkit (copies in that folder):
* `toolkit/make_synth_study.py` — synthetic study; flags `--with-sr`, `--site-abbrev`, `--burned-in`.
* `toolkit/truncate_dicom.py` — `--keep-pct` / `--drop-tail` / `--cut-before-pixels`.

Lessons worth keeping:
* **DCMTK `storescu` refuses mid-element-truncated files client-side** ("I/O suspension or premature
  end of stream") — the reproducible *silently stored* case is the **boundary cut**
  (`--cut-before-pixels`): accepted by C-STORE *and* by the portal upload; study goes READY, frame
  render HTTP 500, nothing flagged. Mid-element cuts are refused by the upload path too
  (`errors: ["<file>: null"]`).
* The de-identification tool's KNOWN LIMITS leave study/series descriptions untouched — free-text
  site abbreviations survive (F3); the de-id report carries `burned_in_annotation` counts (F4).
* Root-cause refs used in the cards: `DicomScpServer.java` :145–162 (SOP class list) :118–122
  (registration) :96–104 (parse-only validation) :44–58 (no TLS); `IngestWorker.java` :117–118
  (study modality last-write-wins) :132–147 (no pixel validation); `UploadService.java` :92–115;
  `TeleradProperties.java` :32–36; `verify_deid.py` :84/:115.
* Quote values containing spaces in `.telerad-acceptance.env` (unquoted values break `source`).

## Prove the negative suite (P3)

`negative_suite.py` runs the nine refusal cases (`neg-non-dicom`, `neg-zero-byte`,
`neg-truncated`, `neg-partial`, `neg-wrong-called-ae`, `neg-unknown-calling-ae`, `neg-duplicate`,
`neg-interrupted`, `neg-unsupported-sop`) and judges each against its **own** expected outcome —
a clean rejection is a PASS, an unsupported-SOP decline is amber (finding F1), junk stored is red.
Every assertion is behavioural: simulator C-STORE verdicts + the read-only DB keyed to the
fixture's StudyInstanceUID and the total study count. Run it directly (no service restart):

    cd /home/hermes/telerad-repo/scripts/acceptance-console
    /opt/dicom-tools/bin/python negative_suite.py            # all nine, JSON on stdout
    /opt/dicom-tools/bin/python negative_suite.py --list

The console exposes `GET /api/negative/cases`, `POST /api/negative/run` and
`POST /api/negative/run-all`, driving panel **6 · Negative suite (P3)**. The SR case needs its
fixture generated once, host-side:

    /opt/dicom-tools/bin/python scripts/make_sr_fixture.py \
        --out-root /home/hermes/dicom-fixtures

(derives a small de-identified CT+SR fixture from the spent-but-readable `F/f-full.zip`; the case
warns with this command if the fixture is missing).

## Prove the viewer-render check (P1)

Stage-2 `viewer-render` drives headless Chrome (Browserless) to `/studies/<id>` with the full login
object injected into `localStorage["telerad_auth"]` before app scripts run; passes on `#root` children
+ a canvas + zero uncaught JS errors, saves a screenshot served via `/api/reports/<file>`, and warns
(never silent-passes) when Browserless is unreachable. Prove it in both states — run on the HOST; the
broken-state step needs docker, which a sandboxed dsh cannot reach:

    cd /home/hermes/telerad-repo/scripts/acceptance-console
    /opt/dicom-tools/bin/python selftest_viewer_render.py

healthy → PASS, frontend assets moved aside → FAIL, restored → PASS; exit 0 = all three expectations
held. Evidence lands in `selftest-evidence/` (gitignored).
