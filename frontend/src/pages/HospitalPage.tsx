import { FormEvent, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import { ImageLocationBadge } from '../components/ImageLocationBadge'
import { PatientSharePanel } from '../components/PatientSharePanel'
import { requestCloudCopy } from '../imageSource'
import type { HospitalReportItem, StudyListItem, StudyStatus } from '../types'

const PROGRESS_STEPS = ['Received', 'Assigned', 'In Progress', 'Report Ready'] as const

function stepIndex(status: StudyStatus): number {
  switch (status) {
    case 'RECEIVED':
      return 0
    case 'READY':
      return 0
    case 'IN_PROGRESS':
      return 2
    case 'REPORTED':
    case 'DELIVERED':
      return 3
    default:
      return 0
  }
}

function slaTone(study: StudyListItem): 'ok' | 'warn' | 'breach' | 'done' {
  if (study.status === 'REPORTED' || study.status === 'DELIVERED') return 'done'
  if (study.slaOverdue) return 'breach'
  if (study.slaDueAt) {
    const mins = (new Date(study.slaDueAt).getTime() - Date.now()) / 60000
    if (mins >= 0 && mins <= 30) return 'warn'
  }
  return 'ok'
}

function slaCaption(study: StudyListItem): string {
  const tone = slaTone(study)
  if (tone === 'done') {
    const when = study.reportedAt ? new Date(study.reportedAt) : null
    return when ? `Delivered · ${formatWhen(when)}` : 'Delivered'
  }
  if (tone === 'breach') return 'SLA Breached'
  if (tone === 'warn' && study.slaDueAt) {
    return `Approaching SLA · ${formatWhen(new Date(study.slaDueAt))}`
  }
  if (study.slaDueAt) return `On track · ${formatWhen(new Date(study.slaDueAt))}`
  return 'On track'
}

function formatWhen(d: Date) {
  return d.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' })
}

function formatDateCreated(study: StudyListItem) {
  const raw = study.studyDate || study.readyAt
  if (!raw) return '—'
  const d = study.studyDate ? new Date(`${study.studyDate}T00:00:00`) : new Date(study.readyAt as string)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function displayPatientName(name: string | null) {
  if (!name) return 'Unknown'
  return name.replace(/\^/g, ', ').replace(/,\s*$/, '')
}

export function HospitalPage() {
  const { user } = useAuth()
  const hospitalId = user?.tenantId
  const fileInputRef = useRef<HTMLInputElement>(null)

  const [studies, setStudies] = useState<StudyListItem[]>([])
  const [reports, setReports] = useState<HospitalReportItem[]>([])
  const [files, setFiles] = useState<File[]>([])
  const [priority, setPriority] = useState('ROUTINE')
  const [modality, setModality] = useState('CT')
  const [bodyPart, setBodyPart] = useState('Chest')
  const [patientQuery, setPatientQuery] = useState('')
  const [indication, setIndication] = useState('')
  const [dragOver, setDragOver] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)
  const [sendingId, setSendingId] = useState<string | null>(null)

  async function load() {
    if (!hospitalId) return
    const [s, r] = await Promise.all([
      api<StudyListItem[]>(`/api/hospitals/${hospitalId}/studies`),
      api<HospitalReportItem[]>(`/api/hospitals/${hospitalId}/reports`),
    ])
    setStudies(s)
    setReports(r)
  }

  useEffect(() => {
    load().catch((err) => setError(err.message))
  }, [hospitalId])

  /** Ask the hospital server to upload a study (and its prior) for a remote read. */
  async function sendForRemoteReading(study: StudyListItem) {
    setSendingId(study.id)
    setError(null)
    try {
      const access = await requestCloudCopy(study.id)
      setStudies((prev) => prev.map((x) => (x.id === study.id ? { ...x, cloudStatus: access.cloudStatus } : x)))
      setMessage('Sent: the hospital server is uploading the study for remote reading.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not send the study')
    } finally {
      setSendingId(null)
    }
  }

  function addFiles(list: FileList | File[]) {
    const next = Array.from(list).filter((f) => {
      const n = f.name.toLowerCase()
      return n.endsWith('.dcm') || n.endsWith('.zip') || f.type.includes('dicom') || f.type.includes('zip')
    })
    if (next.length === 0) {
      setError('Please attach .dcm or ZIP files')
      return
    }
    setError(null)
    setFiles((prev) => {
      const names = new Set(prev.map((f) => f.name + f.size))
      return [...prev, ...next.filter((f) => !names.has(f.name + f.size))]
    })
  }

  async function onUpload(e: FormEvent) {
    e.preventDefault()
    if (!hospitalId || files.length === 0) return
    setUploading(true)
    setError(null)
    setMessage(null)
    try {
      const form = new FormData()
      files.forEach((f) => form.append('files', f))
      const result = await api<{ instancesStored: number; studyInstanceUids: string[]; errors: string[] }>(
        `/api/hospitals/${hospitalId}/studies/upload?priority=${priority}`,
        { method: 'POST', body: form },
      )
      const extra = result.errors?.length ? ` (${result.errors.length} skipped)` : ''
      setMessage(`Order submitted · ${result.instancesStored} instance(s) stored${extra}`)
      setFiles([])
      setIndication('')
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  const reportByStudy = useMemo(() => {
    const map = new Map<string, HospitalReportItem>()
    reports.forEach((r) => map.set(r.studyId, r))
    return map
  }, [reports])

  const filteredStudies = useMemo(() => {
    const q = patientQuery.trim().toLowerCase()
    if (!q) return studies
    return studies.filter((s) =>
      (s.patientName || '').toLowerCase().includes(q) ||
      (s.patientMrn || '').toLowerCase().includes(q),
    )
  }, [studies, patientQuery])

  if (!hospitalId) {
    return (
      <div className="page">
        <div className="error">No hospital tenant linked to this account.</div>
      </div>
    )
  }

  return (
    <div className="page hospital-page">
      <div className="page-header">
        <div>
          <h1>Hospital Staff Portal</h1>
          <p className="subtitle">{user?.tenantName || 'Hospital'} · Imaging Department</p>
        </div>
      </div>

      <section className="panel order-card">
        <h2>Create New Order</h2>
        <p className="order-lead">Attach the study and send it to the reading network.</p>
        {message && <div className="success">{message}</div>}
        {error && <div className="error">{error}</div>}

        <form onSubmit={onUpload}>
          <div className="field">
            <label>Patient</label>
            <div className="search-input">
              <svg viewBox="0 0 20 20" aria-hidden="true">
                <path fill="currentColor" d="M8.5 3a5.5 5.5 0 0 1 4.38 8.82l3.65 3.65-1.06 1.06-3.65-3.65A5.5 5.5 0 1 1 8.5 3Zm0 1.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z" />
              </svg>
              <input
                value={patientQuery}
                onChange={(e) => setPatientQuery(e.target.value)}
                placeholder="Search by name or MRN"
              />
            </div>
          </div>

          <div className="order-selects">
            <div className="field">
              <label>Modality</label>
              <select value={modality} onChange={(e) => setModality(e.target.value)}>
                <option>CT</option>
                <option>MR</option>
                <option>US</option>
                <option>CR</option>
                <option>DX</option>
                <option>XR</option>
              </select>
            </div>
            <div className="field">
              <label>Body part</label>
              <select value={bodyPart} onChange={(e) => setBodyPart(e.target.value)}>
                <option>Chest</option>
                <option>Head</option>
                <option>Abdomen</option>
                <option>Pelvis</option>
                <option>Spine</option>
                <option>Extremity</option>
              </select>
            </div>
            <div className="field">
              <label>Priority</label>
              <select value={priority} onChange={(e) => setPriority(e.target.value)}>
                <option value="ROUTINE">Regular</option>
                <option value="URGENT">Urgent</option>
                <option value="STAT">STAT</option>
              </select>
            </div>
          </div>

          <div className="order-split">
            <div
              className={`dropzone ${dragOver ? 'dragover' : ''} ${files.length ? 'has-files' : ''}`}
              onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
              onDragLeave={() => setDragOver(false)}
              onDrop={(e) => {
                e.preventDefault()
                setDragOver(false)
                if (e.dataTransfer.files?.length) addFiles(e.dataTransfer.files)
              }}
              onClick={() => fileInputRef.current?.click()}
            >
              <input
                ref={fileInputRef}
                type="file"
                multiple
                accept=".dcm,.zip,application/dicom,application/zip"
                hidden
                onChange={(e) => {
                  if (e.target.files?.length) addFiles(e.target.files)
                  e.target.value = ''
                }}
              />
              <svg className="dropzone-icon" viewBox="0 0 24 24" aria-hidden="true">
                <path fill="currentColor" d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6Zm4 18H6V4h7v5h5v11Z" />
              </svg>
              <p>
                Drag &amp; drop DICOM or ZIP files or{' '}
                <span className="dropzone-browse">browse your device</span>
              </p>
              {files.length > 0 && (
                <ul className="file-list" onClick={(e) => e.stopPropagation()}>
                  {files.map((f) => (
                    <li key={f.name + f.size}>
                      <div className="file-row">
                        <span className="file-name">{f.name}</span>
                        <button
                          type="button"
                          className="file-remove"
                          onClick={() => setFiles((prev) => prev.filter((x) => x !== f))}
                        >
                          Remove
                        </button>
                      </div>
                      <div className={`file-bar ${uploading ? 'active' : ''}`} />
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div className="field indication-field">
              <label>Clinical indication</label>
              <textarea
                value={indication}
                onChange={(e) => setIndication(e.target.value)}
                placeholder="e.g. Persistent cough, rule out pneumonia. Correlate with prior CXR if available."
              />
            </div>
          </div>

          <div className="order-actions">
            <button className="btn" disabled={uploading || files.length === 0} type="submit">
              {uploading ? 'Submitting…' : 'Submit Order'}
            </button>
          </div>
        </form>
      </section>

      <section className="panel table-card">
        <div className="table-head">
          <h2>Order History</h2>
          <span className="muted">{filteredStudies.length} order{filteredStudies.length === 1 ? '' : 's'}</span>
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Patient Name</th>
              <th>MRN</th>
              <th>Modality</th>
              <th>Date Created</th>
              <th>Progress</th>
              <th>Images</th>
              <th className="col-report">Report</th>
              <th>Share</th>
            </tr>
          </thead>
          <tbody>
            {filteredStudies.map((s) => {
              const report = reportByStudy.get(s.id)
              const filled = stepIndex(s.status)
              const tone = slaTone(s)
              const canShare = s.status !== 'RECEIVED' && s.instanceCount > 0
              return (
                <tr key={s.id} className={tone === 'breach' ? 'row-overdue' : undefined}>
                  <td className="patient-cell">{displayPatientName(s.patientName)}</td>
                  <td className="mono muted-cell">{s.patientMrn || '—'}</td>
                  <td className="muted-cell">{s.modality || '—'}</td>
                  <td className="date-cell">{formatDateCreated(s)}</td>
                  <td>
                    <div className={`progress-track tone-${tone}`}>
                      <div className="progress-bars">
                        {PROGRESS_STEPS.map((label, i) => (
                          <span
                            key={label}
                            className={`progress-seg ${i <= filled ? 'filled' : ''}`}
                            title={label}
                          />
                        ))}
                      </div>
                      <div className="progress-labels">
                        {PROGRESS_STEPS.map((label, i) => (
                          <span key={label} className={i <= filled ? 'on' : ''}>{label}</span>
                        ))}
                      </div>
                      <div className="progress-caption">{slaCaption(s)}</div>
                    </div>
                  </td>
                  <td>
                    <div className="image-location-cell">
                      <ImageLocationBadge study={s} />
                      {s.cloudStatus === 'EDGE_ONLY' && s.onHospitalServer && (
                        <button
                          type="button"
                          className="btn secondary btn-small"
                          disabled={sendingId === s.id}
                          onClick={() => void sendForRemoteReading(s)}
                        >
                          {sendingId === s.id ? 'Sending…' : 'Send for remote reading'}
                        </button>
                      )}
                    </div>
                  </td>
                  <td className="col-report">
                    {report ? (
                      <Link className="btn-report" to={`/hospital/reports/${s.id}`}>
                        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                          <path
                            d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                          <path
                            d="M14 2v4a2 2 0 0 0 2 2h4"
                            stroke="currentColor"
                            strokeWidth="2"
                            strokeLinecap="round"
                            strokeLinejoin="round"
                          />
                          <path d="M10 9H8M16 13H8M16 17H8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
                        </svg>
                        View Report
                      </Link>
                    ) : (
                      <span className="report-empty">—</span>
                    )}
                  </td>
                  <td>
                    {canShare ? (
                      <PatientSharePanel
                        studyId={s.id}
                        patientLabel={displayPatientName(s.patientName)}
                        compact
                      />
                    ) : (
                      <span className="muted">—</span>
                    )}
                  </td>
                </tr>
              )
            })}
            {filteredStudies.length === 0 && (
              <tr>
                <td colSpan={8} className="muted" style={{ padding: '1.75rem', textAlign: 'center' }}>
                  No orders yet.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </section>
    </div>
  )
}
