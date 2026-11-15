import { api } from './api'
import type {
  FacilityOrderSettings,
  ImagingOrder,
  OrderDetail,
  OrderPatient,
  Laterality,
  ModalityDto,
  OrderStatus,
  ProcedureDto,
  ReconcileItem,
  StudyPriority,
} from './types'

/** Orders and scanner workflow (KB note 15). Admins pass a tenantId; hospital staff use their own. */
const q = (tenantId?: string | null, extra: Record<string, string> = {}) => {
  const p = new URLSearchParams(extra)
  if (tenantId) p.set('tenantId', tenantId)
  const s = p.toString()
  return s ? `?${s}` : ''
}

export interface NewOrder {
  tenantId?: string | null
  patientMrn: string | null
  patientName: string
  birthDate: string | null
  sex: string | null
  modality: string
  procedureId: string | null
  procedureDescription: string | null
  laterality: Laterality
  additionalInstructions: string | null
  priority: StudyPriority
  referringPhysician: string | null
  clinicalInfo: string | null
  scheduledDate?: string | null
}

export const listProcedures = () => api<ProcedureDto[]>('/api/procedures')
export const listModalities = () => api<ModalityDto[]>('/api/procedures/modalities')
export const adminProcedures = () => api<ProcedureDto[]>('/api/admin/procedures')
export interface ProcedureChange {
  modality?: string
  name?: string
  active?: boolean
  sortOrder?: number
}
export const createProcedure = (body: ProcedureChange) =>
  api<ProcedureDto>('/api/admin/procedures', { method: 'POST', body: JSON.stringify(body) })
export const updateProcedure = (id: string, body: ProcedureChange) =>
  api<ProcedureDto>(`/api/admin/procedures/${id}`, { method: 'PUT', body: JSON.stringify(body) })

export const orderSettings = (tenantId?: string | null) =>
  api<FacilityOrderSettings>(`/api/orders/settings${q(tenantId)}`)

export const listOrders = (tenantId: string | null, day: string) =>
  api<ImagingOrder[]>(`/api/orders${q(tenantId, { day })}`)

export const createOrder = (body: NewOrder) =>
  api<ImagingOrder>('/api/orders', { method: 'POST', body: JSON.stringify(body) })

export const orderDetail = (id: string) => api<OrderDetail>(`/api/orders/${id}`)

export const findPatient = (tenantId: string | null, mrn: string) =>
  api<OrderPatient | undefined>(`/api/orders/patient${q(tenantId, { mrn })}`)

export type OrderAction = 'arrived' | 'start-scan' | 'scan-complete' | 'no-show'

export const orderAction = (id: string, action: OrderAction) =>
  api<ImagingOrder>(`/api/orders/${id}/${action}`, { method: 'POST' })

export const cancelOrder = (id: string, reason: string) =>
  api<ImagingOrder>(`/api/orders/${id}/cancel`, { method: 'POST', body: JSON.stringify({ reason }) })

export const reconcileList = (tenantId: string | null) =>
  api<ReconcileItem[]>(`/api/orders/reconcile${q(tenantId)}`)

export const linkStudy = (studyId: string, orderId: string) =>
  api<ImagingOrder>(`/api/orders/reconcile/${studyId}/link`, { method: 'POST', body: JSON.stringify({ orderId }) })

export const rejectSuggestion = (studyId: string) =>
  api<void>(`/api/orders/reconcile/${studyId}/reject`, { method: 'POST' })

export const releaseStudy = (studyId: string, reason: string) =>
  api<void>(`/api/orders/reconcile/${studyId}/release`, { method: 'POST', body: JSON.stringify({ reason }) })

export const LATERALITY_LABEL: Record<Laterality, string> = {
  NOT_APPLICABLE: 'Not applicable',
  LEFT: 'Left',
  RIGHT: 'Right',
  BILATERAL: 'Bilateral',
}

/** "X-ray knee AP (Left)": the procedure with the side, when there is one. */
export function examLabel(o: { modality: string; procedureDescription: string | null; laterality: Laterality }) {
  const side = o.laterality && o.laterality !== 'NOT_APPLICABLE' ? ` (${LATERALITY_LABEL[o.laterality]})` : ''
  return `${o.modality}${o.procedureDescription ? ` · ${o.procedureDescription}` : ''}${side}`
}

/** Reception-facing words for each status (note 15 §6). */
export const ORDER_STATUS_LABEL: Record<OrderStatus, string> = {
  SCHEDULED: 'Scheduled',
  ARRIVED: 'Arrived',
  SCANNING: 'Scanning',
  SCANNED: 'Scanned',
  READY: 'Ready to read',
  REPORTING: 'Reporting',
  SIGNED: 'Signed',
  DELIVERED: 'Delivered',
  CANCELLED: 'Cancelled',
  NO_SHOW: 'No-show',
}

/** The steps shown as a progress line on the reception screen. */
export const ORDER_STEPS: OrderStatus[] = [
  'SCHEDULED',
  'ARRIVED',
  'SCANNING',
  'SCANNED',
  'READY',
  'REPORTING',
  'SIGNED',
  'DELIVERED',
]

export const orderStepIndex = (s: OrderStatus) => ORDER_STEPS.indexOf(s)

/** Which buttons reception / the MRT see. Late clicks are allowed by the server but not offered. */
export function orderActions(o: ImagingOrder, readyMode: 'AUTO' | 'WAIT_FOR_MRT') {
  const i = orderStepIndex(o.status)
  const closed = o.status === 'CANCELLED' || o.status === 'NO_SHOW'
  return {
    arrived: !closed && o.status === 'SCHEDULED',
    startScan: !closed && i >= 0 && i < orderStepIndex('SCANNING'),
    scanComplete:
      !closed &&
      !o.mrtConfirmedAt &&
      (i < orderStepIndex('SCANNED') || (o.status === 'SCANNED' && readyMode === 'WAIT_FOR_MRT')),
    cancel: !closed && !o.studyId && i < orderStepIndex('SCANNING'),
    noShow: !closed && !o.studyId && i < orderStepIndex('SCANNING'),
  }
}
