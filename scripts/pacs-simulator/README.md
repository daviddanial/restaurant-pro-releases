# TeleRad PACS simulator

A reusable command-line **PACS / modality sender** for testing the TeleRad ingest path:
association → C-ECHO → C-STORE, with a result you can put in a test report.

It is deliberately **not** an always-on service. A sender is an operator action: run it,
read the summary, keep the JSON. Turning it into a daemon would only add a moving part
that can fail silently between tests.

## Why not `scripts/modality_send.py`

That script is the quick smoke test and it is fine for "did anything arrive". This one is
the instrumented version used for **acceptance testing**, because it distinguishes things
the quick script collapses together:

| Concern | `modality_send.py` | this package |
|---|---|---|
| C-ECHO result | printed, not recorded | recorded separately from the transfer |
| Warnings (e.g. elements discarded) | counted as success | counted separately, **blocks a PASS** |
| Failed filenames / SOP UIDs | not recorded | listed, and in `--json-out` |
| Elapsed time / bytes accepted | no | yes, per phase |
| Staged send (`--limit`, `--dry-run`) | no | yes |

## Install

Dependencies are `pydicom` + `pynetdicom`, listed in `../requirements-dicom.txt`:

```bash
python3 -m pip install -r ../requirements-dicom.txt
```

`send-study.sh` installs them automatically if they are missing.

## Configure

```bash
cp config.env.example config.env   # then edit
```

Two settings cause almost every failed run, so check them first:

1. **Called AE must match the SCP's AE title.** The application default is `TELERAD_SCP`,
   but this deployment overrides it:
   ```bash
   docker inspect tele2-dicom-scp-1 \
     --format '{{range .Config.Env}}{{println .}}{{end}}' | grep AE_TITLE
   # TELERAD_SCP_AE_TITLE=TELERAD   → use --called-ae TELERAD
   ```
2. **Calling AE must be an allowlisted `dicom_nodes` row**, because the SCP maps the
   caller to a tenant:
   ```bash
   docker exec tele2-postgres-1 psql -U telerad -d telerad \
     -c "select ae_title, tenant_id, active from dicom_nodes"
   # DEMOHOSP_PACS → Demo General Hospital
   # CITYMED_PACS  → City Medical Center
   ```

The SCP port is bound to **loopback only** in the VPS deployment, so run the simulator on
the VPS (or tunnel `11112` over SSH).

## Use — staged, so a failure is cheap to diagnose

```bash
# 1. prove the endpoint answers and the association is accepted; store nothing
./send-study.sh --study /path/to/study.zip --dry-run

# 2. send a handful and confirm they land
./send-study.sh --study /path/to/study.zip --limit 20 -v

# 3. the real run
./send-study.sh --study /path/to/study.zip --json-out /tmp/send-result.json

# explicit flags override config.env
./send-study.sh --study /path/to/study.zip \
  --called-ae TELERAD --calling-ae DEMOHOSP_PACS --host 127.0.0.1 --port 11112
```

## Reading the result

DIMSE-C responses are classified per PS3.7 ch.9:

* **success** — `0x0000`
* **warning** — `0x0101`, `0x0103`, `0x0107`, `0x0111` (duplicate SOP instance),
  `0x0113`, `0x0115`, `0x0117`, `0x0121`, and `0xB000`–`0xBFFF`
* **failure** — anything else, plus *no response*

**A warning is not a pass.** `0x0103` ("elements discarded") or `0xA900` ("dataset does
not match SOP class") means the instance was *not* ingested faithfully. The summary lists
warnings, and the exit code is non-zero if any appear.

**Acceptance for a clean ingest:** association established, C-ECHO accepted, **zero
failures, zero warnings**, and the expected instance count visible in the platform.

Exit codes: `0` clean · `2` no DICOM found · `3` association rejected · `4` C-ECHO failed
· `5` failures or warnings present.

## Duplicate-ingest regression (separate test — run deliberately)

Sending the **same** fixture twice with identical Study/SOP Instance UIDs is a different
question from "does ingest work": it probes deduplication. Keep it out of the baseline.

```bash
./send-study.sh --study /path/to/study.zip --json-out /tmp/run1.json
./send-study.sh --study /path/to/study.zip --json-out /tmp/run2.json
```

Then read run 2: `0x0111` is the standard duplicate-SOP warning, and the platform's
study/instance counts tell you whether TeleRad deduplicates, replaces, warns, or creates
a second study. Record the answer rather than assuming one.
