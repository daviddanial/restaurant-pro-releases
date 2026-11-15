export type Role = 'ADMIN' | 'HOSPITAL_USER' | 'RADIOLOGIST'

export interface AuthUser {
  accessToken: string
  userId: string
  email: string
  fullName: string
  role: Role
  tenantId: string | null
  tenantCode: string | null
  tenantName: string | null
}

export type StudyStatus = 'RECEIVED' | 'READY' | 'IN_PROGRESS' | 'REPORTED' | 'DELIVERED'
export type StudyPriority = 'STAT' | 'URGENT' | 'ROUTINE'

export interface StudyListItem {
  id: string
  tenantId: string
  hospitalName: string | null
  studyInstanceUid: string
  accessionNumber: string | null
  studyDescription: string | null
  modality: string | null
  studyDate: string | null
  priority: StudyPriority
  status: StudyStatus
  assignedTo: string | null
  instanceCount: number
  source: string
  patientName: string | null
  patientMrn: string | null
  patientSex: string | null
  patientBirthDate: string | null
  readyAt: string | null
  reportedAt: string | null
  hasSignedReport: boolean
  slaDueAt: string | null
  slaBreachedAt: string | null
  slaOverdue: boolean
  /** Where the image files are: in the cloud, only on the hospital server, or on their way. */
  cloudStatus: CloudStatus
  /** The hospital server holds the image files. */
  onHospitalServer: boolean
  /** Link to an order (KB note 15); null when the facility does not use orders. */
  matchState?: MatchState | null
  matchNote?: string | null
  /** Linked, but the name typed at the scanner differs (the order's name is shown). */
  nameDiffers?: boolean
  scannerPatientName?: string | null
  orderStatus?: OrderStatus | null
  /** New images arrived after the report was signed. */
  imagesAfterReportAt?: string | null
}

export type MatchState = 'LINKED' | 'NEEDS_CHECK' | 'SUGGESTED' | 'UNMATCHED' | 'RELEASED'

export type OrderStatus =
  | 'SCHEDULED'
  | 'ARRIVED'
  | 'SCANNING'
  | 'SCANNED'
  | 'READY'
  | 'REPORTING'
  | 'SIGNED'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'NO_SHOW'

export type ReadyMode = 'AUTO' | 'WAIT_FOR_MRT'

/** One entry of the central procedure list (KB note 15 §9). */
export interface ProcedureDto {
  id: string
  modality: string
  name: string
  active: boolean
  sortOrder: number
}

export interface ModalityDto {
  code: string
  label: string
  procedures: number
}

/** Side of the body, chosen on the order. */
export type Laterality = 'NOT_APPLICABLE' | 'LEFT' | 'RIGHT' | 'BILATERAL'

export interface OrderPatient {
  id: string
  mrn: string
  name: string | null
  birthDate: string | null
  sex: string | null
}

export interface ImagingOrder {
  id: string
  tenantId: string
  hospitalName: string | null
  accessionNumber: string
  patient: OrderPatient | null
  modality: string
  procedureId: string | null
  procedureDescription: string | null
  laterality: Laterality
  additionalInstructions: string | null
  priority: StudyPriority
  referringPhysician: string | null
  clinicalInfo: string | null
  source: 'MANUAL' | 'HL7' | 'FHIR'
  status: OrderStatus
  scheduledDate: string
  studyId: string | null
  imageCount: number
  arrivedAt: string | null
  scanningAt: string | null
  scannedAt: string | null
  readyAt: string | null
  mrtConfirmedAt: string | null
  cancelledAt: string | null
  cancelReason: string | null
  createdAt: string
  waitingForMrt: boolean
  nameDiffers: boolean
  scannerPatientName: string | null
  imagesAfterReport: boolean
}

export interface OrderEventDto {
  at: string
  kind: string
  detail: string | null
  userId: string | null
}

export interface OrderDetail {
  order: ImagingOrder
  history: OrderEventDto[]
}

export interface ReconcileItem {
  studyId: string
  studyInstanceUid: string
  matchState: MatchState
  matchNote: string | null
  scannerAccession: string | null
  scannerPatientId: string | null
  scannerPatientName: string | null
  scannerBirthDate: string | null
  modality: string | null
  studyDescription: string | null
  studyDate: string | null
  imageCount: number
  receivedAt: string
  suggested: ImagingOrder | null
  candidates: ImagingOrder[]
}

export interface FacilityOrderSettings {
  tenantId: string
  hospitalName: string
  ordersEnabled: boolean
  readyMode: ReadyMode
  scanStableSeconds: number
}

export type CloudStatus = 'AVAILABLE' | 'EDGE_ONLY' | 'REQUESTED' | 'PENDING_DELETE'

export interface RadiologistOption {
  id: string
  fullName: string
  pendingCount: number
}

export interface InstanceDto {
  id: string
  sopInstanceUid: string
  instanceNumber: number | null
  rows: number | null
  columns: number | null
  numberOfFrames: number
  cineRate?: number | null
  frameTime?: number | null
  imagePositionPatient?: number[] | null
  imageOrientationPatient?: number[] | null
  transferSyntaxUid?: string | null
}

export interface SeriesDto {
  id: string
  seriesInstanceUid: string
  modality: string | null
  seriesNumber: number | null
  seriesDescription: string | null
  instanceCount: number
  instances: InstanceDto[]
}

export interface ReportSummary {
  id: string
  status: 'DRAFT' | 'SIGNED'
  findings: string | null
  impression: string | null
  critical: boolean
  signedAt: string | null
  hasPdf: boolean
}

export interface StudyDetail {
  study: StudyListItem
  series: SeriesDto[]
  report: ReportSummary | null
}

export interface HospitalReportItem {
  reportId: string
  studyId: string
  studyUid: string
  accession: string | null
  patientName: string | null
  modality: string | null
  signedAt: string | null
  critical: boolean
  impression: string | null
}

export interface TenantDto {
  id: string
  code: string
  name: string
  active: boolean
}

export interface DicomNodeDto {
  id: string
  tenantId: string
  aeTitle: string
  host: string | null
  port: number | null
  direction: string
  allowedIp: string | null
  active: boolean
}

export interface UserDto {
  id: string
  email: string
  fullName: string
  role: Role
  tenantId: string | null
  active: boolean
}

export interface AuditEvent {
  id: string
  tenantId: string | null
  userId: string | null
  action: string
  entityType: string | null
  entityId: string | null
  detail: string | null
  createdAt: string
}

export interface NotificationEventDto {
  id: string
  eventType: string
  studyId: string | null
  tenantId: string | null
  channel: string
  recipient: string | null
  status: string
  errorMessage: string | null
  createdAt: string
}
