import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, apiBlob } from '../api'
import { useAuth } from '../auth'
import type { RadiologistOption, SeriesDto, StudyDetail, StudyListItem, StudyPriority, StudyStatus } from '../types'

type QueueTab = 'pending' | 'assigned'

type PreviewThumb = { src: string; title: string; caption: string }

export function DispatchPage() {
  const { user } = useAuth()
  const [studies, setStudies] = useState<StudyListItem[]>([])
  const [radiologists, setRadiologists] = useState<RadiologistOption[]>([])
  const [tab, setTab] = useState<QueueTab>('pending')
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [assigningId, setAssigningId] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  async function loadStudies() {
    if (user?.role === 'ADMIN') {
      const tenants = await api<{ id: string }[]>('/api/admin/tenants')
      const lists = await Promise.all(
        tenants.map((t) => api<StudyListItem[]>(`/api/hospitals/${t.id}/studies`)),
      )
      return lists.flat()
    }
    if (!user?.tenantId) throw new Error('No hospital tenant linked to this account.')
    return api<StudyListItem[]>(`/api/hospitals/${user.tenantId}/studies`)
  }

  async function load() {
    const studyResult = loadStudies().then((studyData) => {
      setStudies(studyData)
    })
    const radResult = api<RadiologistOption[]>('/api/radiologists').then((rads) => {
      setRadiologists(rads)
    })
    const results = await Promise.allSettled([studyResult, radResult])
    const firstError = results.find((r) => r.status === 'rejected') as PromiseRejectedResult | undefined
    if (firstError) {
      const reason = firstError.reason
      setError(reason instanceof Error ? reason.message : 'Failed to load dispatch queue')
    } else {
      setError(null)
    }
  }

  useEffect(() => {
    load()
  }, [user?.tenantId, user?.role])

  const pending = studies.filter((s) => s.status === 'READY' && !s.assignedTo)
  const assigned = studies.filter((s) => s.status === 'IN_PROGRESS')
  const rows = tab === 'pending' ? pending : assigned
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return rows
    return rows.filter((s) =>
      [s.accessionNumber, s.patientName, s.patientMrn, s.hospitalName, s.modality]
        .filter(Boolean)
        .some((v) => v!.toLowerCase().includes(q)),
    )
  }, [rows, query])

  const selected = studies.find((s) => s.id === selectedId) ?? null
  const statUnassigned = pending.filter((s) => s.priority === 'STAT').length
  const closeDrawer = useCallback(() => setSelectedId(null), [])

  async function assign(studyId: string, radiologistId: string) {
    if (!radiologistId) return
    setAssigningId(studyId)
    setError(null)
    try {
      const updated = await api<StudyListItem>(`/api/studies/${studyId}/assign`, {
        method: 'POST',
        body: JSON.stringify({ radiologistId }),
      })
      setStudies((prev) => prev.map((s) => (s.id === updated.id ? updated : s)))
      const rads = await api<RadiologistOption[]>('/api/radiologists')
      setRadiologists(rads)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Assign failed')
    } finally {
      setAssigningId(null)
    }
  }

  return (
    <div className="page dispatch-page">
      <div className="page-header">
        <div>
          <h1>Dispatch &amp; Triage</h1>
          <p className="subtitle">
            {user?.role === 'ADMIN'
              ? 'Coordinator queue · all sites'
              : `${user?.tenantName || 'Hospital'} · assign studies to a radiologist`}
          </p>
        </div>
        {statUnassigned > 0 && (
          <span className="stat-unassigned">
            <span>{statUnassigned}</span>
            STAT unassigned
          </span>
        )}
      </div>

      <div className="dispatch-controls">
        <div className="dispatch-search">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path fill="currentColor" d="M8.5 3a5.5 5.5 0 0 1 4.38 8.82l3.65 3.65-1.06 1.06-3.65-3.65A5.5 5.5 0 1 1 8.5 3Zm0 1.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z" />
          </svg>
          <input
            type="search"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search accession, patient, or site"
            aria-label="Search cases"
          />
        </div>
        <div className="dispatch-tabs">
          <button type="button" className={tab === 'pending' ? 'on' : undefined} onClick={() => setTab('pending')}>
            Pending Assignment
          </button>
          <button type="button" className={tab === 'assigned' ? 'on' : undefined} onClick={() => setTab('assigned')}>
            Assigned
          </button>
        </div>
      </div>

      {error && <div className="error">{error}</div>}

      <div className="panel table-card">
        <table className="table">
          <thead>
            <tr>
              <th>Priority</th>
              <th>Accession</th>
              <th>Site / Hospital</th>
              <th>Modality</th>
              <th>Status</th>
              <th className="col-action">Action</th>
            </tr>
          </thead>
          <tbody>
            {filtered.map((s) => (
              <tr
                key={s.id}
                className={selectedId === s.id ? 'selected' : undefined}
                onClick={() => setSelectedId(s.id)}
              >
                <td><PriorityBadge priority={s.priority} /></td>
                <td className="mono muted-cell">{s.accessionNumber || '—'}</td>
                <td>
                  <div className="patient-cell">{s.hospitalName || '—'}</div>
                  <div className="muted-cell" style={{ fontSize: 12 }}>{displayPatientName(s.patientName)}</div>
                </td>
                <td className="muted-cell">{s.modality || '—'}</td>
                <td><StatusBadge status={s.status} /></td>
                <td className="col-action" onClick={(e) => e.stopPropagation()}>
                  <label className="assign-select">
                    <span className="sr-only">Assign radiologist</span>
                    <select
                      value={s.assignedTo || ''}
                      disabled={assigningId === s.id || (s.status !== 'READY' && s.status !== 'IN_PROGRESS')}
                      onChange={(e) => assign(s.id, e.target.value)}
                    >
                      <option value="">Assign Radiologist</option>
                      {radiologists.map((r) => (
                        <option key={r.id} value={r.id}>
                          {r.fullName} — {r.pendingCount} pending
                        </option>
                      ))}
                    </select>
                  </label>
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <tr>
                <td colSpan={6} className="muted" style={{ padding: '2rem', textAlign: 'center' }}>
                  {tab === 'pending' ? 'No studies waiting for assignment.' : 'No assigned studies.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {selected && (
        <CaseDetailDrawer
          study={selected}
          radiologists={radiologists}
          assigning={assigningId === selected.id}
          onAssign={(radiologistId) => assign(selected.id, radiologistId)}
          onClose={closeDrawer}
        />
      )}
    </div>
  )
}

function CaseDetailDrawer({
  study,
  radiologists,
  assigning,
  onAssign,
  onClose,
}: {
  study: StudyListItem
  radiologists: RadiologistOption[]
  assigning: boolean
  onAssign: (radiologistId: string) => void
  onClose: () => void
}) {
  const [thumbs, setThumbs] = useState<PreviewThumb[]>([])
  const [previewState, setPreviewState] = useState<'loading' | 'ready' | 'empty'>('loading')

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [onClose])

  useEffect(() => {
    let cancelled = false
    const objectUrls: string[] = []
    setPreviewState('loading')
    setThumbs([])

    async function loadPreview() {
      try {
        const data = await api<StudyDetail>(`/api/studies/${study.id}`)
        if (cancelled) return
        const picks = data.series
          .map((s, idx) => ({ series: s, idx, inst: s.instances[0] }))
          .filter((x) => x.inst)
          .slice(0, 6)
        if (picks.length === 0) {
          setPreviewState('empty')
          return
        }
        const loaded = await Promise.all(picks.map(async ({ series, idx, inst }) => {
          const label = seriesLabel(series, idx)
          try {
            const blob = await apiBlob(
              `/api/dicom/studies/${data.study.studyInstanceUid}/series/${series.seriesInstanceUid}/instances/${inst.sopInstanceUid}/frames/1`,
            )
            const src = URL.createObjectURL(blob)
            objectUrls.push(src)
            return { src, ...label }
          } catch {
            return { src: '', ...label }
          }
        }))
        if (!cancelled) {
          setThumbs(loaded)
          setPreviewState('ready')
        }
      } catch {
        if (!cancelled) setPreviewState('empty')
      }
    }

    loadPreview()
    return () => {
      cancelled = true
      objectUrls.forEach((u) => URL.revokeObjectURL(u))
    }
  }, [study.id])

  const ageSex = [ageFromBirthDate(study.patientBirthDate), formatSex(study.patientSex)]
    .filter(Boolean)
    .join(' · ') || '—'

  return (
    <>
      <div className="dispatch-drawer-overlay" onClick={onClose} />
      <aside
        className="dispatch-drawer"
        role="dialog"
        aria-modal="true"
        aria-label={`Case detail for ${displayPatientName(study.patientName)}`}
      >
        <header className="dispatch-drawer-header">
          <div className="dispatch-drawer-heading">
            <div className="dispatch-drawer-meta">
              <PriorityBadge priority={study.priority} />
              <span className="mono muted-cell">{study.accessionNumber || '—'}</span>
            </div>
            <h2>{displayPatientName(study.patientName)}</h2>
          </div>
          <button type="button" className="dispatch-drawer-close" onClick={onClose} aria-label="Close panel">
            <svg viewBox="0 0 16 16" aria-hidden="true">
              <path fill="currentColor" d="M3.22 3.22a.75.75 0 0 1 1.06 0L8 6.94l3.72-3.72a.75.75 0 1 1 1.06 1.06L9.06 8l3.72 3.72a.75.75 0 1 1-1.06 1.06L8 9.06l-3.72 3.72a.75.75 0 0 1-1.06-1.06L6.94 8 3.22 4.28a.75.75 0 0 1 0-1.06Z" />
            </svg>
          </button>
        </header>

        <DrawerSection title="Demographics">
          <dl className="dispatch-demo">
            <div>
              <dt>Age / Sex</dt>
              <dd>{ageSex}</dd>
            </div>
            <div>
              <dt>Modality</dt>
              <dd>{study.modality || '—'}</dd>
            </div>
            <div className="span-2">
              <dt>Site</dt>
              <dd>{study.hospitalName || '—'}</dd>
            </div>
            {study.patientMrn && (
              <div className="span-2">
                <dt>MRN</dt>
                <dd className="mono">{study.patientMrn}</dd>
              </div>
            )}
          </dl>
        </DrawerSection>

        <DrawerSection title="Clinical Notes">
          <p className="dispatch-notes">
            {study.studyDescription?.trim() || 'No clinical notes on this order.'}
          </p>
        </DrawerSection>

        <DrawerSection title="Assignment">
          <label className="assign-select drawer-assign">
            <span className="sr-only">Assign radiologist</span>
            <select
              value={study.assignedTo || ''}
              disabled={assigning || (study.status !== 'READY' && study.status !== 'IN_PROGRESS')}
              onChange={(e) => onAssign(e.target.value)}
            >
              <option value="">Assign Radiologist</option>
              {radiologists.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.fullName} — {r.pendingCount} pending
                </option>
              ))}
            </select>
          </label>
        </DrawerSection>

        <DrawerSection title="Study Preview">
          {previewState === 'loading' && <p className="muted">Loading series…</p>}
          {previewState === 'empty' && <p className="muted">No series available yet.</p>}
          {previewState === 'ready' && (
            <div className="dispatch-preview">
              {thumbs.map((t, i) => (
                <figure key={`${t.title}-${i}`} className="dispatch-preview-item">
                  {t.src ? <img src={t.src} alt={t.caption} /> : <div className="dispatch-preview-empty" />}
                  <figcaption>{t.title}</figcaption>
                </figure>
              ))}
            </div>
          )}
        </DrawerSection>
      </aside>
    </>
  )
}

function DrawerSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="dispatch-drawer-section">
      <h3>{title}</h3>
      {children}
    </section>
  )
}

function displayPatientName(name: string | null) {
  if (!name) return 'Unknown'
  return name.replace(/\^/g, ' ').replace(/\s+/g, ' ').trim()
}

function formatSex(sex: string | null | undefined) {
  if (!sex) return null
  const s = sex.trim().toUpperCase()
  if (s === 'M' || s === 'MALE') return 'M'
  if (s === 'F' || s === 'FEMALE') return 'F'
  if (s === 'O' || s === 'OTHER') return 'O'
  return s.slice(0, 1)
}

function ageFromBirthDate(iso: string | null | undefined) {
  if (!iso) return null
  const d = new Date(`${iso.slice(0, 10)}T00:00:00`)
  if (Number.isNaN(d.getTime())) return null
  const today = new Date()
  let age = today.getFullYear() - d.getFullYear()
  const m = today.getMonth() - d.getMonth()
  if (m < 0 || (m === 0 && today.getDate() < d.getDate())) age -= 1
  if (age < 0 || age > 130) return null
  return String(age)
}

function seriesLabel(series: SeriesDto, index: number) {
  const description = series.seriesDescription
  const d = (description || '').toLowerCase()
  if (d.includes('axial')) return { title: 'AXIAL', caption: 'Axial view' }
  if (d.includes('coronal')) return { title: 'CORONAL', caption: 'Coronal view' }
  if (d.includes('sagittal')) return { title: 'SAGITTAL', caption: 'Sagittal' }
  if (description) return { title: description.toUpperCase().slice(0, 18), caption: description }
  return { title: `SERIES ${index + 1}`, caption: `Series ${index + 1}` }
}

function PriorityBadge({ priority }: { priority: StudyPriority }) {
  if (priority === 'STAT') return <span className="pri-stat">STAT</span>
  return <span className="pri-regular">{priority === 'URGENT' ? 'Urgent' : 'Regular'}</span>
}

function StatusBadge({ status }: { status: StudyStatus }) {
  if (status === 'READY') return <span className="status-pill ready">Ready</span>
  if (status === 'IN_PROGRESS') return <span className="status-pill progress">In Progress</span>
  return <span className="status-pill">{status}</span>
}
