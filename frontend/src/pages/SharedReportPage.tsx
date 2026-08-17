import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, apiBlob } from '../api'
import { useAuth } from '../auth'
import type { StudyDetail } from '../types'

function displayPatientName(name: string | null) {
  if (!name) return 'Unknown patient'
  return name.replace(/\^/g, ' ').replace(/\s+/g, ' ').trim()
}

function hospitalInitials(name: string | null) {
  if (!name) return 'TR'
  const parts = name.split(/\s+/).filter((w) => !/^(the|of|and|general|hospital)$/i.test(w))
  const take = (parts.length >= 2 ? parts.slice(0, 2) : name.split(/\s+/).slice(0, 2))
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('')
  return take.slice(0, 2) || 'TR'
}

function formatStudyDate(studyDate: string | null, fallbackIso: string | null) {
  const d = studyDate
    ? new Date(`${studyDate}T00:00:00`)
    : fallbackIso
      ? new Date(fallbackIso)
      : null
  if (!d || Number.isNaN(d.getTime())) return null
  return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function findingsParagraphs(text: string | null): string[] {
  if (!text?.trim()) return ['No findings recorded.']
  return text.split(/\n+/).map((p) => p.trim()).filter(Boolean)
}

function impressionItems(text: string | null): string[] {
  if (!text?.trim()) return ['No impression recorded.']
  return text.split(/\n+/).map((p) => p.replace(/^\d+[.)]\s*/, '').trim()).filter(Boolean)
}

function seriesLabel(description: string | null, index: number) {
  const d = (description || '').toLowerCase()
  if (d.includes('axial')) return { title: 'AXIAL', caption: 'Axial view' }
  if (d.includes('coronal')) return { title: 'CORONAL', caption: 'Coronal view' }
  if (d.includes('sagittal')) return { title: 'SAGITTAL', caption: 'Sagittal' }
  if (description) return { title: description.toUpperCase().slice(0, 18), caption: description }
  return { title: `SERIES ${index + 1}`, caption: `Series ${index + 1}` }
}

export function SharedReportPage() {
  const { studyId } = useParams()
  const { user } = useAuth()
  const [detail, setDetail] = useState<StudyDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [thumbs, setThumbs] = useState<{ src: string; title: string; caption: string }[]>([])
  const [downloading, setDownloading] = useState(false)

  useEffect(() => {
    if (!studyId) return
    let cancelled = false
    const objectUrls: string[] = []

    async function load() {
      try {
        const data = await api<StudyDetail>(`/api/studies/${studyId}`)
        if (cancelled) return
        setDetail(data)

        const picks = data.series
          .map((s, idx) => ({ series: s, idx, inst: s.instances[0] }))
          .filter((x) => x.inst)
          .slice(0, 6)

        const loaded = await Promise.all(picks.map(async ({ series, idx, inst }) => {
          const label = seriesLabel(series.seriesDescription, idx)
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
        if (!cancelled) setThumbs(loaded)
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : 'Failed to load report')
      }
    }

    load()
    return () => {
      cancelled = true
      objectUrls.forEach((u) => URL.revokeObjectURL(u))
    }
  }, [studyId])

  const hospital = useMemo(
    () => detail?.study.hospitalName || user?.tenantName || 'Hospital',
    [detail, user],
  )

  async function downloadPdf() {
    const reportId = detail?.report?.id
    if (!reportId) return
    setDownloading(true)
    try {
      const blob = await apiBlob(`/api/reports/${reportId}/pdf`)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `report-${reportId}.pdf`
      a.click()
      URL.revokeObjectURL(url)
    } finally {
      setDownloading(false)
    }
  }

  if (error) {
    return (
      <div className="shared-report-page">
        <div className="shared-report">
          <div className="error">{error}</div>
          <Link to="/hospital">Back to hospital portal</Link>
        </div>
      </div>
    )
  }

  if (!detail) {
    return (
      <div className="shared-report-page">
        <div className="shared-report">
          <p className="muted">Loading report…</p>
        </div>
      </div>
    )
  }

  const study = detail.study
  const report = detail.report
  const dateLabel = formatStudyDate(study.studyDate, report?.signedAt || study.readyAt)
  const subtitle = [study.studyDescription || study.modality, dateLabel].filter(Boolean).join(' · ')

  return (
    <div className="shared-report-page">
      <article className="shared-report">
        <header className="shared-report-top">
          <div className="shared-hospital">
            <span className="shared-mark">{hospitalInitials(hospital)}</span>
            <strong>{hospital}</strong>
          </div>
          <span className="shared-kicker">Shared report</span>
        </header>

        <h1 className="shared-patient">{displayPatientName(study.patientName)}</h1>
        <p className="shared-subtitle">{subtitle || 'Radiology report'}</p>

        {report?.critical && <div className="badge stat" style={{ marginBottom: '1.25rem' }}>Critical finding</div>}

        <section className="shared-section">
          <h2>Findings</h2>
          {findingsParagraphs(report?.findings ?? null).map((p) => (
            <p key={p}>{p}</p>
          ))}
        </section>

        <section className="shared-section">
          <h2>Impression</h2>
          <ol>
            {impressionItems(report?.impression ?? null).map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ol>
        </section>

        <section className="shared-section">
          <h2>Images</h2>
          {thumbs.length === 0 ? (
            <p className="muted">No preview images available.</p>
          ) : (
            <div className="shared-thumbs">
              {thumbs.map((t) => (
                <figure key={t.caption + t.title}>
                  {t.src ? (
                    <img src={t.src} alt={t.caption} />
                  ) : (
                    <div className="shared-thumb-ph">{t.title}</div>
                  )}
                  <figcaption>{t.caption}</figcaption>
                </figure>
              ))}
            </div>
          )}
        </section>

        <aside className="shared-privacy">
          <svg viewBox="0 0 20 20" aria-hidden="true">
            <path fill="currentColor" d="M10 2a4 4 0 0 1 4 4v2h1a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2h1V6a4 4 0 0 1 4-4Zm0 1.5A2.5 2.5 0 0 0 7.5 6v2h5V6A2.5 2.5 0 0 0 10 3.5Z" />
          </svg>
          <p>
            This link is private to you. Please discuss these results with your doctor before making any
            decisions about your care.
          </p>
        </aside>

        {report?.hasPdf && (
          <button className="btn shared-download" type="button" disabled={downloading} onClick={downloadPdf}>
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path fill="currentColor" d="M10 3v8.2L7.4 8.6 6 10l4 4 4-4-1.4-1.4L11 11.2V3H9Zm-5 12v2h10v-2H5Z" />
            </svg>
            {downloading ? 'Preparing PDF…' : 'Download PDF'}
          </button>
        )}

        <p className="shared-back">
          <Link to="/hospital">Back to hospital portal</Link>
        </p>
      </article>
    </div>
  )
}
