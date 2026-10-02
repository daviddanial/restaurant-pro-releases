import { useEffect, useMemo, useState } from 'react'
import type { StudyListItem } from '../types'
import { collectLayoutTiles, type LayoutTile } from '../viewerCapture'

export type PrintOptions = {
  patientInfo: boolean
  orientationHud: boolean
  measurements: boolean
  pageSize: 'A4' | 'Letter'
  orientation: 'portrait' | 'landscape'
}

type Props = {
  open: boolean
  onClose: () => void
  study: StudyListItem
}

function defaultOptions(): PrintOptions {
  return {
    patientInfo: true,
    orientationHud: true,
    measurements: true,
    pageSize: 'A4',
    orientation: 'landscape',
  }
}

function dataUrlFromCanvas(canvas: HTMLCanvasElement): string {
  return canvas.toDataURL('image/png')
}

export function PrintPreviewModal({ open, onClose, study }: Props) {
  const [opts, setOpts] = useState<PrintOptions>(defaultOptions)
  const [tiles, setTiles] = useState<Array<LayoutTile & { url: string }>>([])

  useEffect(() => {
    if (!open) return
    setOpts(defaultOptions())
    try {
      const collected = collectLayoutTiles()
      setTiles(collected.map((t) => ({ ...t, url: dataUrlFromCanvas(t.canvas) })))
    } catch {
      setTiles([])
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])

  const gridClass = useMemo(() => {
    if (tiles.length <= 1) return 'print-grid-1'
    if (tiles.length === 2) return 'print-grid-2'
    return 'print-grid-4'
  }, [tiles.length])

  if (!open) return null

  function printNow() {
    window.print()
  }

  return (
    <div className="print-overlay" role="dialog" aria-modal="true" aria-labelledby="print-preview-title">
      <div className="print-dialog no-print">
        <header className="print-dialog-head">
          <h2 id="print-preview-title">Print preview</h2>
          <button type="button" className="btn secondary" onClick={onClose}>Cancel</button>
        </header>

        <div className="print-options">
          <label>
            <input
              type="checkbox"
              checked={opts.patientInfo}
              onChange={(e) => setOpts((o) => ({ ...o, patientInfo: e.target.checked }))}
            />
            Patient / study information
          </label>
          <label>
            <input
              type="checkbox"
              checked={opts.orientationHud}
              onChange={(e) => setOpts((o) => ({ ...o, orientationHud: e.target.checked }))}
            />
            Orientation / HUD markers
          </label>
          <label>
            <input
              type="checkbox"
              checked={opts.measurements}
              onChange={(e) => setOpts((o) => ({ ...o, measurements: e.target.checked }))}
            />
            Measurements (as rendered)
          </label>
          <label>
            Page size
            <select
              value={opts.pageSize}
              onChange={(e) => setOpts((o) => ({ ...o, pageSize: e.target.value as PrintOptions['pageSize'] }))}
            >
              <option value="A4">A4</option>
              <option value="Letter">Letter</option>
            </select>
          </label>
          <label>
            Orientation
            <select
              value={opts.orientation}
              onChange={(e) => setOpts((o) => ({ ...o, orientation: e.target.value as PrintOptions['orientation'] }))}
            >
              <option value="portrait">Portrait</option>
              <option value="landscape">Landscape</option>
            </select>
          </label>
        </div>

        <div className="print-dialog-actions">
          <button type="button" className="btn" onClick={printNow} disabled={tiles.length === 0}>
            Print
          </button>
        </div>
      </div>

      <div
        className={`print-sheet page-${opts.pageSize.toLowerCase()} orient-${opts.orientation}`}
        data-measurements={opts.measurements ? '1' : '0'}
      >
        {opts.patientInfo && (
          <div className="print-header">
            <strong>{study.patientName || 'Unknown patient'}</strong>
            <span>MRN {study.patientMrn || '—'}</span>
            <span>{study.patientSex || '—'}</span>
            <span>DOB {study.patientBirthDate || '—'}</span>
            <span>{study.modality || '—'}</span>
            <span>{study.studyDate || '—'}</span>
            <span>Acc {study.accessionNumber || '—'}</span>
            <span>{study.hospitalName || '—'}</span>
            {study.studyDescription && <span>{study.studyDescription}</span>}
          </div>
        )}
        <div className={`print-grid ${gridClass}`}>
          {tiles.length === 0 ? (
            <p className="muted">No viewports to print.</p>
          ) : (
            tiles.map((t) => (
              <figure key={t.label + t.url.slice(-12)} className="print-tile">
                {opts.orientationHud && <figcaption>{t.label}</figcaption>}
                <img src={t.url} alt={t.label} />
              </figure>
            ))
          )}
        </div>
      </div>
    </div>
  )
}
