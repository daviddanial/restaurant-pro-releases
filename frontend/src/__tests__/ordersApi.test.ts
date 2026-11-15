import { describe, expect, it } from 'vitest'
import { examLabel, orderActions, orderStepIndex } from '../ordersApi'
import type { ImagingOrder, OrderStatus } from '../types'

const order = (status: OrderStatus, extra: Partial<ImagingOrder> = {}): ImagingOrder => ({
  id: 'o1',
  tenantId: 't1',
  hospitalName: 'City Medical Center',
  accessionNumber: 'CMC-2611130001',
  patient: { id: 'p1', mrn: 'MRN-1', name: 'Kebede Abebe', birthDate: null, sex: 'F' },
  modality: 'CT',
  procedureId: null,
  procedureDescription: null,
  laterality: 'NOT_APPLICABLE',
  additionalInstructions: null,
  priority: 'ROUTINE',
  referringPhysician: null,
  clinicalInfo: null,
  source: 'MANUAL',
  status,
  scheduledDate: '2026-11-13',
  studyId: null,
  imageCount: 0,
  arrivedAt: null,
  scanningAt: null,
  scannedAt: null,
  readyAt: null,
  mrtConfirmedAt: null,
  cancelledAt: null,
  cancelReason: null,
  createdAt: '2026-11-13T07:00:00Z',
  waitingForMrt: false,
  nameDiffers: false,
  scannerPatientName: null,
  imagesAfterReport: false,
  ...extra,
})

describe('order buttons (KB note 15)', () => {
  it('offers arrival, cancel and no-show before the scan', () => {
    const a = orderActions(order('SCHEDULED'), 'AUTO')
    expect(a).toMatchObject({ arrived: true, startScan: true, scanComplete: true, cancel: true, noShow: true })
  })

  it('offers no cancel once images are linked', () => {
    const a = orderActions(order('SCANNING', { studyId: 's1' }), 'AUTO')
    expect(a.cancel).toBe(false)
    expect(a.noShow).toBe(false)
    expect(a.startScan).toBe(false)
    expect(a.scanComplete).toBe(true)
  })

  it('does not offer a late Scan complete in automatic mode', () => {
    expect(orderActions(order('SCANNED', { studyId: 's1' }), 'AUTO').scanComplete).toBe(false)
    expect(orderActions(order('READY', { studyId: 's1' }), 'WAIT_FOR_MRT').scanComplete).toBe(false)
  })

  it('asks the MRT to confirm a scanned study when the hospital waits for it', () => {
    expect(orderActions(order('SCANNED', { studyId: 's1' }), 'WAIT_FOR_MRT').scanComplete).toBe(true)
    expect(orderActions(order('SCANNED', { studyId: 's1', mrtConfirmedAt: 'x' }), 'WAIT_FOR_MRT').scanComplete).toBe(false)
  })

  it('offers nothing on a closed order', () => {
    expect(Object.values(orderActions(order('CANCELLED'), 'AUTO')).some(Boolean)).toBe(false)
    expect(orderStepIndex('NO_SHOW')).toBe(-1)
  })

  it('adds the side to the exam name only when there is one', () => {
    expect(examLabel({ modality: 'DX', procedureDescription: 'X-ray knee AP', laterality: 'LEFT' })).toBe('DX · X-ray knee AP (Left)')
    expect(examLabel({ modality: 'DX', procedureDescription: 'X-ray chest PA', laterality: 'NOT_APPLICABLE' })).toBe('DX · X-ray chest PA')
    expect(examLabel({ modality: 'US', procedureDescription: null, laterality: 'BILATERAL' })).toBe('US (Bilateral)')
  })
})
