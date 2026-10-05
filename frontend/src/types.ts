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

export type StudyStatus = 'ORDERED' | 'RECEIVED' | 'READY' | 'IN_PROGRESS' | 'REPORTED' | 'DELIVERED'
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
  suggestedRadiologistId: string | null
  externalOrderId: string | null
  referringPhysician: string | null
  feedbackMessage: string | null
  feedbackAt: string | null
}

export interface RadiologistOption {
  id: string
  fullName: string
  pendingCount: number
  available: boolean
  availabilityOverride: boolean | null
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
