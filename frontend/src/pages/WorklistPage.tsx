import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import type { StudyListItem } from '../types'

export function WorklistPage() {
  const [items, setItems] = useState<StudyListItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [modality, setModality] = useState('')
  const [priority, setPriority] = useState('')

  async function load() {
    try {
      const params = new URLSearchParams()
      if (modality) params.set('modality', modality)
      if (priority) params.set('priority', priority)
      const data = await api<StudyListItem[]>(`/api/worklist?${params}`)
      setItems(data)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load worklist')
    }
  }

  useEffect(() => {
    load()
  }, [modality, priority])

  async function claim(id: string) {
    await api(`/api/studies/${id}/claim`, { method: 'POST' })
    await load()
  }

  return (
    <div className="page">
      <div className="panel">
        <div className="row" style={{ justifyContent: 'space-between' }}>
          <h2 style={{ margin: 0 }}>Radiologist worklist</h2>
          <div className="row">
            <select value={modality} onChange={(e) => setModality(e.target.value)}>
              <option value="">All modalities</option>
              <option>CT</option>
              <option>MR</option>
              <option>CR</option>
              <option>DX</option>
              <option>US</option>
            </select>
            <select value={priority} onChange={(e) => setPriority(e.target.value)}>
              <option value="">All priorities</option>
              <option value="STAT">STAT</option>
              <option value="URGENT">URGENT</option>
              <option value="ROUTINE">ROUTINE</option>
            </select>
            <button className="btn secondary" type="button" onClick={load}>Refresh</button>
          </div>
        </div>
        {error && <div className="error" style={{ marginTop: '0.8rem' }}>{error}</div>}
        <table className="table" style={{ marginTop: '1rem' }}>
          <thead>
            <tr>
              <th>Priority</th>
              <th>Patient</th>
              <th>Hospital</th>
              <th>Modality</th>
              <th>Accession</th>
              <th>Status</th>
              <th>SLA</th>
              <th>Instances</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {items.map((s) => (
              <tr key={s.id} className={s.slaOverdue ? 'row-overdue' : ''}>
                <td><span className={`badge ${s.priority === 'STAT' ? 'stat' : ''}`}>{s.priority}</span></td>
                <td>
                  <div>{s.patientName || 'Unknown'}</div>
                  <div className="muted" style={{ fontSize: '0.8rem' }}>{s.patientMrn}</div>
                </td>
                <td>{s.hospitalName}</td>
                <td>{s.modality}</td>
                <td>{s.accessionNumber || '—'}</td>
                <td><span className={`badge ${statusClass(s.status)}`}>{s.status}</span></td>
                <td>
                  {s.slaDueAt ? (
                    <span className={`badge ${s.slaOverdue ? 'stat' : ''}`}>
                      {s.slaOverdue ? 'OVERDUE' : formatSla(s.slaDueAt)}
                    </span>
                  ) : '—'}
                </td>
                <td>{s.instanceCount}</td>
                <td className="row">
                  {(s.status === 'READY' || s.status === 'IN_PROGRESS') && (
                    <button className="btn secondary" type="button" onClick={() => claim(s.id)}>Claim</button>
                  )}
                  <Link className="btn" to={`/studies/${s.id}`}>Open</Link>
                </td>
              </tr>
            ))}
            {items.length === 0 && (
              <tr><td colSpan={9} className="muted">No studies in worklist.</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function formatSla(iso: string) {
  const due = new Date(iso).getTime()
  const mins = Math.round((due - Date.now()) / 60000)
  if (mins >= 120) return `${Math.round(mins / 60)}h left`
  if (mins >= 0) return `${mins}m left`
  return `${Math.abs(mins)}m late`
}

function statusClass(status: string) {
  if (status === 'READY') return 'ready'
  if (status === 'IN_PROGRESS') return 'progress'
  if (status === 'REPORTED' || status === 'DELIVERED') return 'done'
  return ''
}
