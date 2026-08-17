import { describe, it, expect } from 'vitest'
import {
  REPORT_TEMPLATES,
  applyTemplate,
  fillPlaceholders,
  templatesForType,
  uniqueSorted,
} from '../reportTemplates'

describe('report templates', () => {
  it('cascades global templates by type', () => {
    expect(templatesForType('Global').every((t) => t.type === 'Global')).toBe(true)
    expect(templatesForType('All')).toHaveLength(REPORT_TEMPLATES.length)
  })

  it('fills known order fields and leaves clinical blanks', () => {
    const filled = fillPlaceholders('EXAM: {MODALITY} {BODY_PART}\nLungs: {LUNG_FINDINGS}', {
      MODALITY: 'CT',
      BODY_PART: 'Chest',
    })
    expect(filled).toContain('EXAM: CT Chest')
    expect(filled).toContain('{LUNG_FINDINGS}')
  })

  it('splits PE protocol into findings and impression with order values', () => {
    const tpl = REPORT_TEMPLATES.find((t) => t.id === 'tpl-ct-chest-pe')
    expect(tpl).toBeTruthy()
    const result = applyTemplate(tpl!, {
      modality: 'CT',
      patientName: 'DOE^JANE',
      radiologistName: 'Dr. A. Osei',
      studyDate: '2026-08-04',
    })
    expect(result.findings).toContain('EXAM: CT Chest WITH IV CONTRAST')
    expect(result.findings).toContain('None available')
    expect(result.impression).toContain('No evidence of acute pulmonary embolism')
    expect(result.impression).toContain('Dr. A. Osei')
    expect(result.impression).toContain('{IMPRESSION_ADDENDUM}')
  })

  it('lists unique modalities', () => {
    expect(uniqueSorted(['US', 'CT', 'CT'])).toEqual(['CT', 'US'])
  })
})
