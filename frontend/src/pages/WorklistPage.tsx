import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import type { StudyListItem, StudyPriority, StudyStatus } from '../types'

const AVAILABILITY = ['Online', 'On-call', 'Offline'] as const
type Availability = (typeof AVAILABILITY)[number]

export function WorklistPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [items, setItems] = useState<StudyListItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [priority, setPriority] = useState('')
  const [site, setSite] = useState('')
  const [availability, setAvailability] = useState<Availability>('Online')
  const [claimingId, setClaimingId] = useState<string | null>(null)

  async function load() {
    try {
      const data = await api<StudyListItem[]>('/api/worklist')
      setItems(data)
      setError(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load worklist')
    }
  }

  useEffect(() => {
    load()
  }, [])

  const sites = useMemo(() => {
    const names = items.map((s) => s.hospitalName).filter((n): n is string => Boolean(n))
    return [...new Set(names)].sort()
  }, [items])

  const filtered = useMemo(() => {
    return items.filter((s) => {
      if (priority === 'STAT' && s.priority !== 'STAT') return false
      if (priority === 'REGULAR' && s.priority === 'STAT') return false
      if (site && s.hospitalName !== site) return false
      return true
    })
  }, [items, priority, site])

  const claimedCount = items.filter((s) => s.status === 'IN_PROGRESS').length
  const queueCount = items.length || 1
  const progressPct = Math.round((claimedCount / queueCount) * 100)

  async function onClaim(study: StudyListItem) {
    const mine = study.assignedTo === user?.userId
    if (study.status === 'IN_PROGRESS' && mine) {
      navigate(`/studies/${study.id}`)
      return
    }
    setClaimingId(study.id)
    setError(null)
    try {
      await api(`/api/studies/${study.id}/claim`, { method: 'POST' })
      navigate(`/studies/${study.id}`)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Claim failed')
      setClaimingId(null)
    }
  }

  return (
    <div className="page worklist-page">
      <div className="page-header worklist-header">
        <div>
          <h1>My Worklist</h1>
          <p className="subtitle">
            {user?.fullName || 'Radiologist'}
            {user?.role === 'RADIOLOGIST' ? ' · Radiologist' : ''}
          </p>
        </div>
        <div className="worklist-meta">
          <div className="worklist-progress">
            <p>
              {claimedCount}/{items.length} studies claimed
            </p>
            <div className="worklist-progress-track" aria-hidden="true">
              <span style={{ width: `${items.length ? progressPct : 0}%` }} />
            </div>
          </div>
          <div className="avail-toggle" role="radiogroup" aria-label="Availability">
            {AVAILABILITY.map((opt) => (
              <button
                key={opt}
                type="button"
                role="radio"
                aria-checked={availability === opt}
                className={availability === opt ? 'on' : undefined}
                onClick={() => setAvailability(opt)}
              >
                <span className={`avail-dot ${opt === 'Online' ? 'online' : opt === 'On-call' ? 'oncall' : 'offline'}`} />
                {opt}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="worklist-filters">
        <label className="filter-select">
          <span className="sr-only">Priority</span>
          <select value={priority} onChange={(e) => setPriority(e.target.value)}>
            <option value="">All priorities</option>
            <option value="STAT">STAT</option>
            <option value="REGULAR">Regular</option>
          </select>
        </label>
        <label className="filter-select">
          <span className="sr-only">Site</span>
          <select value={site} onChange={(e) => setSite(e.target.value)}>
            <option value="">All sites</option>
            {sites.map((name) => (
              <option key={name} value={name}>{name}</option>
            ))}
          </select>
        </label>
        <label className="filter-select">
          <span className="sr-only">Subspecialty</span>
          <select defaultValue="">
            <option value="">All subspecialties</option>
          </select>
        </label>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="panel table-card">
        <table className="table">
          <thead>
            <tr>
              <th>Priority</th>
              <th>Patient Name</th>
              <th>MRN</th>
              <th>Modality</th>
              <th>Referring Site</th>
              <th>Status</th>
              <th>Assign Date</th>
              <th className="col-action">Action</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((s) => {
              const claimable = canClaim(s, user?.userId)
              return (
                <tr key={s.id} className={s.slaOverdue ? 'row-overdue' : undefined}>
                  <td><PriorityBadge priority={s.priority} /></td>
                  <td className="patient-cell">{displayPatientName(s.patientName)}</td>
                  <td className="mono muted-cell">{s.patientMrn || '—'}</td>
                  <td className="muted-cell">{s.modality || '—'}</td>
                  <td className="muted-cell">{s.hospitalName || '—'}</td>
                  <td><StatusBadge status={s.status} /></td>
                  <td className="date-cell">{formatAssignDate(s.readyAt)}</td>
                  <td className="col-action">
                    <div className="claim-cell">
                      <button
                        type="button"
                        className="btn-claim"
                        disabled={!claimable || claimingId === s.id}
                        onClick={() => onClaim(s)}
                      >
                        {claimingId === s.id ? 'Opening…' : 'Claim'}
                      </button>
                    </div>
                  </td>
                </tr>
              )
            })}
            {filtered.length === 0 && (
              <tr>
                <td colSpan={8} className="muted" style={{ padding: '2rem', textAlign: 'center' }}>
                  No studies in worklist.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function canClaim(study: StudyListItem, userId: string | undefined) {
  if (study.status === 'READY') return true
  if (study.status === 'IN_PROGRESS' && study.assignedTo && userId && study.assignedTo === userId) return true
  return false
}

function displayPatientName(name: string | null) {
  if (!name) return 'Unknown'
  return name.replace(/\^/g, ' ').replace(/\s+/g, ' ').trim()
}

function formatAssignDate(iso: string | null) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const mon = d.toLocaleString('en-US', { month: 'short' })
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${mon} ${d.getDate()} · ${hh}:${mm}`
}

function PriorityBadge({ priority }: { priority: StudyPriority }) {
  if (priority === 'STAT') return <span className="pri-stat">STAT</span>
  return <span className="pri-regular">{priority === 'URGENT' ? 'Urgent' : 'Regular'}</span>
}

function StatusBadge({ status }: { status: StudyStatus }) {
  if (status === 'READY') return <span className="status-pill ready">Ready</span>
  if (status === 'IN_PROGRESS') return <span className="status-pill progress">In Progress</span>
  if (status === 'REPORTED' || status === 'DELIVERED') return <span className="status-pill done">Report Ready</span>
  return <span className="status-pill">{status}</span>
}
