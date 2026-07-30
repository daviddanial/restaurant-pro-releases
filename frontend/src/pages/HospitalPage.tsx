import { FormEvent, useEffect, useState } from 'react'
import { api, apiBlob } from '../api'
import { useAuth } from '../auth'
import type { HospitalReportItem, StudyListItem } from '../types'

export function HospitalPage() {
  const { user } = useAuth()
  const hospitalId = user?.tenantId
  const [studies, setStudies] = useState<StudyListItem[]>([])
  const [reports, setReports] = useState<HospitalReportItem[]>([])
  const [files, setFiles] = useState<FileList | null>(null)
  const [priority, setPriority] = useState('ROUTINE')
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [uploading, setUploading] = useState(false)

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

  async function onUpload(e: FormEvent) {
    e.preventDefault()
    if (!hospitalId || !files || files.length === 0) return
    setUploading(true)
    setError(null)
    setMessage(null)
    try {
      const form = new FormData()
      Array.from(files).forEach((f) => form.append('files', f))
      const result = await api<{ instancesStored: number; studyInstanceUids: string[]; errors: string[] }>(
        `/api/hospitals/${hospitalId}/studies/upload?priority=${priority}`,
        { method: 'POST', body: form },
      )
      setMessage(`Stored ${result.instancesStored} instance(s). Studies: ${(result.studyInstanceUids || []).join(', ') || 'n/a'}`)
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed')
    } finally {
      setUploading(false)
    }
  }

  async function downloadPdf(reportId: string) {
    const blob = await apiBlob(`/api/reports/${reportId}/pdf`)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `report-${reportId}.pdf`
    a.click()
    URL.revokeObjectURL(url)
  }

  if (!hospitalId) {
    return <div className="page"><div className="error">No hospital tenant linked to this account.</div></div>
  }

  return (
    <div className="page stack">
      <div className="panel">
        <h2>Upload DICOM studies</h2>
        <p className="muted">Upload .dcm files or a ZIP of a series. Ingest parses tags and places studies on the radiologist worklist.</p>
        {message && <div className="success">{message}</div>}
        {error && <div className="error">{error}</div>}
        <form onSubmit={onUpload} className="stack">
          <div className="field">
            <label>DICOM files / ZIP</label>
            <input type="file" multiple accept=".dcm,.zip,application/dicom,application/zip" onChange={(e) => setFiles(e.target.files)} />
          </div>
          <div className="field">
            <label>Priority</label>
            <select value={priority} onChange={(e) => setPriority(e.target.value)}>
              <option value="ROUTINE">ROUTINE</option>
              <option value="URGENT">URGENT</option>
              <option value="STAT">STAT</option>
            </select>
          </div>
          <button className="btn" disabled={uploading || !files?.length} type="submit">
            {uploading ? 'Uploading…' : 'Upload & ingest'}
          </button>
        </form>
      </div>

      <div className="panel">
        <h2>Hospital studies</h2>
        <table className="table">
          <thead>
            <tr>
              <th>Patient</th>
              <th>Modality</th>
              <th>Accession</th>
              <th>Status</th>
              <th>Priority</th>
              <th>Source</th>
              <th>Instances</th>
            </tr>
          </thead>
          <tbody>
            {studies.map((s) => (
              <tr key={s.id}>
                <td>{s.patientName || s.patientMrn || '—'}</td>
                <td>{s.modality}</td>
                <td>{s.accessionNumber || '—'}</td>
                <td><span className="badge">{s.status}</span></td>
                <td>{s.priority}</td>
                <td>{s.source}</td>
                <td>{s.instanceCount}</td>
              </tr>
            ))}
            {studies.length === 0 && <tr><td colSpan={7} className="muted">No studies yet.</td></tr>}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h2>Signed report inbox</h2>
        <table className="table">
          <thead>
            <tr>
              <th>Patient</th>
              <th>Modality</th>
              <th>Signed</th>
              <th>Impression</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {reports.map((r) => (
              <tr key={r.reportId}>
                <td>{r.patientName || '—'}{r.critical ? ' · CRITICAL' : ''}</td>
                <td>{r.modality}</td>
                <td>{r.signedAt ? new Date(r.signedAt).toLocaleString() : '—'}</td>
                <td>{r.impression}</td>
                <td><button className="btn secondary" type="button" onClick={() => downloadPdf(r.reportId)}>PDF</button></td>
              </tr>
            ))}
            {reports.length === 0 && <tr><td colSpan={5} className="muted">No signed reports yet.</td></tr>}
          </tbody>
        </table>
      </div>
    </div>
  )
}
