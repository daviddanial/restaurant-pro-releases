import { describe, it, expect } from 'vitest'
import type { StudyListItem, StudyDetail, Role } from '../types'

describe('types smoke', () => {
  it('accepts study list item shape', () => {
    const item: StudyListItem = {
      id: 's1',
      tenantId: 't1',
      hospitalName: 'Demo',
      studyInstanceUid: '1.2.3',
      accessionNumber: 'A1',
      studyDescription: 'Chest CT',
      modality: 'CT',
      studyDate: '2024-01-01',
      priority: 'STAT',
      status: 'READY',
      assignedTo: null,
      instanceCount: 12,
      source: 'UPLOAD',
      patientName: 'DOE^JOHN',
      patientMrn: 'MRN1',
      readyAt: '2024-01-01T00:00:00Z',
      reportedAt: null,
      hasSignedReport: false,
      slaDueAt: null,
      slaBreachedAt: null,
      slaOverdue: false,
    }
    expect(item.priority).toBe('STAT')

    const detail: StudyDetail = { study: item, series: [], report: null }
    expect(detail.series).toEqual([])

    const roles: Role[] = ['ADMIN', 'HOSPITAL_USER', 'RADIOLOGIST']
    expect(roles).toHaveLength(3)
  })
})
