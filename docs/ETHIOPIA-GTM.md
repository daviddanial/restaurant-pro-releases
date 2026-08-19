# TeleRad — Ethiopia selling points

| Field | Value |
|-------|-------|
| **Status** | Draft — not requirements. Promote a wedge into [`REQUIREMENTS.md`](REQUIREMENTS.md) before building. |
| **Version** | 0.2 |
| **Date** | 2026-08-19 |

**Pitch:** Your CT does not wait for a radiologist to drive in. A signed report comes back the same night, on the hospital record, with a name and a timestamp — even when the link is bad.

**Buyer asks:** STAT turnaround at night? Do images leave Ethiopia, and who signs? Cost in ETB — hospital or patient?

**Do not sell:** worklist/viewer chrome, “modern RIS,” or AI. Those are table stakes. Real competitor is WhatsApp JPEGs, CDs, and one on-site radiologist.

---

## Wedges (one at a time)

| ID | Wedge | Sell | Notes |
|----|--------|------|--------|
| **SP-01** | Named local + diaspora roster | Readers and SLA, not “the cloud” | People/contracts first; legal locum model |
| **SP-02** | Resumable / edge ingest | Overnight upload on a bad link | `FR-EXT-001` Deferred (OD-EDGE) |
| **SP-03** | Telegram / WhatsApp + PDF | Report where doctors already are | Portal is the file; chat replaces JPEGs |
| **SP-04** | ETB per study | Hospital invoice; STAT extra | Chapa/patient-pay only after OD-BIL |
| **SP-05** | Data in Ethiopia | Local host + tenant isolation | Mostly ops; already have audit/assign |
| **SP-06** | Amharic at the counter | Tech + referring GP, not the viewer | After a site is live |
| **SP-07** | Trust demo (built) | Assign → claim → sign → PDF in 10 min | Use **their** DICOM |

**Not first:** Nurse role, MoH RFP, population health, hanging protocols, AI.

---

## First sale

Private Addis site that already has a **CT** and already outsources reads. One modality. Two-week pilot, two radiologists, written SLA (e.g. STAT 2h, routine next morning). Price in **ETB per study** (reads + platform, not a license). Success: they stop WhatsApping that CT.

Then: second private site → one regional public hospital with a champion → MoH later.

Talk to imaging head **and** owner/CFO (private). Do not improvise who is legally allowed to sign.

---

## Build order (after you refine this)

0. Pilot on what exists (SP-01 + SP-07)  
1. Hosting story (SP-05)  
2. Chat/PDF notify (SP-03)  
3. Hospital invoice (SP-04, no Chapa)  
4. Resumable ingest (SP-02)  
5. Amharic (SP-06)  
6. Patient-pay only if sites will not float reads (OD-BIL)

**Near-term rule:** serve one Addis CT site — reliable ingest, same-night signed report, ETB price. Viewer polish is maintenance.
