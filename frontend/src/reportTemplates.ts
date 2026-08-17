export type TemplateType = 'Global' | 'Radiologist-Specific'

export interface ReportTemplate {
  id: string
  name: string
  type: TemplateType
  modality: string
  bodyPart: string
  updated: string
  body: string
  impression: string
}

export const TEMPLATE_TYPES = ['All', 'Radiologist-Specific', 'Global'] as const

export const REPORT_TEMPLATES: ReportTemplate[] = [
  {
    id: 'tpl-ct-chest-pe',
    name: 'CT Chest — PE Protocol',
    type: 'Global',
    modality: 'CT',
    bodyPart: 'Chest',
    updated: 'Updated Jun 12, 2026',
    body: `EXAM: {MODALITY} {BODY_PART} WITH IV CONTRAST

COMPARISON: {PRIOR_STUDY_DATE}

TECHNIQUE: Helical acquisition of the {BODY_PART} was performed following administration of {CONTRAST_VOLUME} of intravenous contrast. Axial, coronal, and sagittal reconstructions were reviewed.

FINDINGS:
Vasculature: No filling defect within the pulmonary arterial tree to the segmental level.
Lungs: {LUNG_FINDINGS}
Pleura: No pneumothorax. {PLEURAL_FINDINGS}
Mediastinum: No pathologically enlarged lymph nodes.`,
    impression: `1. No evidence of acute pulmonary embolism.
2. {IMPRESSION_ADDENDUM}

Reported by {RADIOLOGIST_NAME} on {REPORT_DATE}.`,
  },
  {
    id: 'tpl-ct-chest-onc',
    name: 'CT Chest — Oncologic Follow-up',
    type: 'Radiologist-Specific',
    modality: 'CT',
    bodyPart: 'Chest',
    updated: 'Updated Jul 30, 2026',
    body: `EXAM: {MODALITY} {BODY_PART} WITH IV CONTRAST — ONCOLOGIC FOLLOW-UP

CLINICAL HISTORY: {CLINICAL_INDICATION}

COMPARISON: {PRIOR_STUDY_DATE}

FINDINGS:
Target lesions measured per RECIST 1.1: {TARGET_LESIONS}
Non-target lesions: {NON_TARGET_LESIONS}
No new sites of disease within the {BODY_PART}.`,
    impression: `1. {RESPONSE_CATEGORY} compared with {PRIOR_STUDY_DATE}.
2. Recommend follow-up {MODALITY} in {FOLLOWUP_INTERVAL}.`,
  },
  {
    id: 'tpl-ct-abd',
    name: 'CT Abdomen/Pelvis — Routine',
    type: 'Global',
    modality: 'CT',
    bodyPart: 'Abdomen',
    updated: 'Updated May 04, 2026',
    body: `EXAM: {MODALITY} {BODY_PART} WITH CONTRAST

FINDINGS:
Liver, spleen, pancreas, adrenals: Unremarkable.
Bowel: {BOWEL_FINDINGS}
Vasculature: Patent.`,
    impression: `1. {IMPRESSION_ADDENDUM}`,
  },
  {
    id: 'tpl-mr-brain',
    name: 'MR Brain — Stroke Protocol',
    type: 'Global',
    modality: 'MR',
    bodyPart: 'Head',
    updated: 'Updated Apr 21, 2026',
    body: `EXAM: {MODALITY} {BODY_PART} WITHOUT CONTRAST

TECHNIQUE: Multiplanar, multisequence imaging including DWI/ADC.

FINDINGS:
Diffusion: {DIFFUSION_FINDINGS}
Parenchyma: {PARENCHYMAL_FINDINGS}`,
    impression: `1. {IMPRESSION_ADDENDUM}`,
  },
  {
    id: 'tpl-dx-chest',
    name: 'DX Chest — 2 View',
    type: 'Global',
    modality: 'DX',
    bodyPart: 'Chest',
    updated: 'Updated Mar 09, 2026',
    body: `EXAM: {MODALITY} {BODY_PART}, TWO VIEWS

FINDINGS:
Lungs are clear. Heart size within normal limits. No pleural effusion or pneumothorax.`,
    impression: `1. {IMPRESSION_ADDENDUM}`,
  },
  {
    id: 'tpl-us-abd',
    name: 'US Abdomen — Complete',
    type: 'Radiologist-Specific',
    modality: 'US',
    bodyPart: 'Abdomen',
    updated: 'Updated Feb 14, 2026',
    body: `EXAM: {MODALITY} {BODY_PART}, COMPLETE

FINDINGS:
Gallbladder: {GALLBLADDER_FINDINGS}
Kidneys: {RENAL_FINDINGS}`,
    impression: `1. {IMPRESSION_ADDENDUM}`,
  },
]

export interface OrderFields {
  modality?: string | null
  bodyPart?: string | null
  patientName?: string | null
  mrn?: string | null
  accession?: string | null
  hospital?: string | null
  studyDate?: string | null
  studyDescription?: string | null
  radiologistName?: string | null
}

export function uniqueSorted(values: string[]) {
  return [...new Set(values)].sort()
}

export function templatesForType(type: string) {
  if (!type || type === 'All') return REPORT_TEMPLATES
  return REPORT_TEMPLATES.filter((t) => t.type === type)
}

export function fillPlaceholders(text: string, fields: Record<string, string>) {
  return text.replace(/\{([A-Z_]+)\}/g, (token, key: string) => (
    Object.prototype.hasOwnProperty.call(fields, key) ? fields[key] : token
  ))
}

export function orderFieldMap(order: OrderFields, template: ReportTemplate): Record<string, string> {
  const studyDate = order.studyDate
    ? formatDate(order.studyDate)
    : 'Not recorded'
  return {
    MODALITY: order.modality || template.modality,
    BODY_PART: template.bodyPart,
    PATIENT_NAME: displayName(order.patientName),
    MRN: order.mrn || '—',
    ACCESSION: order.accession || '—',
    HOSPITAL: order.hospital || '—',
    STUDY_DATE: studyDate,
    PRIOR_STUDY_DATE: 'None available',
    CLINICAL_INDICATION: order.studyDescription || 'Not provided',
    RADIOLOGIST_NAME: order.radiologistName || 'Radiologist',
    REPORT_DATE: formatDate(new Date().toISOString().slice(0, 10)),
    CONTRAST_VOLUME: '—',
  }
}

export function applyTemplate(template: ReportTemplate, order: OrderFields) {
  const fields = orderFieldMap(order, template)
  return {
    findings: fillPlaceholders(template.body, fields),
    impression: fillPlaceholders(template.impression, fields),
  }
}

export function templatePreviewBody(template: ReportTemplate) {
  return `${template.body}\n\nIMPRESSION:\n${template.impression}`
}

function displayName(name: string | null | undefined) {
  if (!name) return 'Unknown'
  return name.replace(/\^/g, ' ').replace(/\s+/g, ' ').trim()
}

function formatDate(raw: string) {
  const d = raw.includes('T') ? new Date(raw) : new Date(`${raw}T00:00:00`)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
}
