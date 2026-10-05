# MIGRATION — what changes at a new site, and what must never change

This is the checklist for pointing the acceptance suite at **another TeleRad deployment or
another hospital PACS**. The acceptance criterion for P4 is exactly this: same tools, two
endpoints, a configuration-only difference.

The one file a site edits is the profile (`profile.example.env` → e.g.
`/home/hermes/.telerad-acceptance.env`). The simulator also reads
`scripts/pacs-simulator/config.env` for standalone use; its `PACS_*` names are the same.

---

## A · What CHANGES (configuration only)

Tick every line that is true for the new site. None of these require touching code.

- [ ] **Hostnames / ports** — `TELERAD_API`, `TELERAD_APP_URL`, `PACS_HOST`, `PACS_PORT`,
      `DB_HOST`, `DB_PORT`, `BROWSERLESS_URL`
- [ ] **AE titles** — `PACS_CALLED_AE` must equal the receiving SCP's configured AE title;
      `PACS_CALLING_AE` is the caller the site presents
- [ ] **Node rows / tenant mapping** — `PACS_CALLING_AE` must be an **active `dicom_nodes`
      row**; `TELERAD_HOSPITAL_ID` / `TELERAD_HOSPITAL_NAME` must be the tenant that node
      maps to (otherwise association is refused, or the study lands at the wrong hospital)
- [ ] **Credentials** — `TELERAD_ADMIN_*`, `TELERAD_RADIOLOGIST_*`,
      `TELERAD_HOSPITAL_USER_*`, `DB_USER`/`DB_PASSWORD`, `BROWSERLESS_TOKEN`
- [ ] **TLS** — the portal/API origin must present a certificate the console host trusts.
      There is deliberately **no "skip verification" switch**: install the site CA in the
      host trust store instead of disabling checks. The scanned SCP itself speaks plain
      DICOM in the demo (finding F5) — if the site adds DICOM TLS, that is a new capability,
      not a config toggle, and must be reported, not assumed
- [ ] **Browserless** — `BROWSERLESS_URL` / `BROWSERLESS_TOKEN`. If the site has none, the
      viewer-render check **warns**; that is not a pass
- [ ] **Paths** — `FIXTURES_DIR`, `SENDER`, `PYTHON`, `TELERAD_ACCEPTANCE_ENV`
- [ ] **Fixture choice for the connection test** — `TELERAD_CONNECTION_FIXTURE` (dry-run
      only; nothing is stored)
- [ ] **Site profile loaded the same way** — console `EnvironmentFile=`, or
      `set -a; . profile.env; set +a` before the standalone tools

Verify a change landed with, e.g.:

```bash
scripts/portability/portability_check.sh --profile <site>.env --dry-run-only
```

## B · What MUST NOT change

These are invariants, not preferences. If a migration "needs" one of them, the migration is
wrong — report it instead.

- [ ] **The two-stage principle.** Stage 1 (association / C-ECHO / C-STORE verdicts) never
      proves ingestion on its own. Stage 2 must ask TeleRad itself (API + read-only DB).
- [ ] **Stage-2 assertions stay keyed to the fixture's `StudyInstanceUID`.** Never assert
      "a study appeared": a pre-existing study would satisfy it and the test would pass
      having sent nothing.
- [ ] **Fixtures stay single-use.** Fresh UIDs per fixture; a spent fixture re-sent is a
      duplicate-SOP test, not a clean run.
- [ ] **No app-code edits.** `backend/` and `frontend/` are never modified; test tooling
      lives on `test/pacs-simulator`. Portability is achieved by config, not by patching the
      product.
- [ ] **"Decline ≠ fail".** An unsupported SOP class refused by the peer is amber
      (`TEST PASSED WITH DECLINED OBJECTS`), never red.
- [ ] **Never a silent green.** Anything that cannot run (Browserless missing, DB password
      absent, config incomplete) must warn or fail loudly and say so — never be counted as
      a pass.
- [ ] **No patient-identifiable data** in the fixtures directory; only verified
      de-identified or manifest-marked synthetic fixtures are selectable.
- [ ] **No study deletion** without explicit human approval.

## C · Order of operations at the new site

1. Copy `profile.example.env` and fill in section A.
2. Prove it against `scripts/portability/mock_scp.py` (same check, mock endpoint).
3. Prove it against the real endpoint with `--dry-run-only` (C-ECHO; stores nothing).
4. Start the console against the profile.
5. Create a fresh fixture (`make_fixture.py`) and run the full acceptance test.
6. Record the verdict and any declines; leave the findings (F1–F5) intact — do not fix them
   silently.

## D · After the migration, prove nothing in code changed

```bash
cd /home/hermes/telerad-repo
git status --short                 # only test-tooling files / the profile
git diff vps-integration -- backend/ frontend/   # must be empty
```

The profile examples carry documented defaults for the demo host; those defaults are not
hidden hardcodes. Any *active* path that needs a site value must read it from this profile —
if you find one that does not, that is a P4 bug: fix the tooling, not the site.
