import { useEffect, useId, useRef, useState } from 'react'
import { useExportJobPoller } from '../exportApi'
import {
  captureLayoutBlob,
  captureViewportBlob,
  downloadBlob,
  type ImageFormat,
} from '../viewerCapture'

type Props = {
  studyId: string
  patientLabel: string
  seriesInstanceUid: string | null
  sopInstanceUid: string | null
  disabled?: boolean
}

export function ViewerExportMenu({
  studyId,
  patientLabel,
  seriesInstanceUid,
  sopInstanceUid,
  disabled,
}: Props) {
  const [open, setOpen] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  const rootRef = useRef<HTMLDivElement | null>(null)
  const menuId = useId()
  const { job, busy, error, start, dismiss } = useExportJobPoller()

  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    window.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDoc)
      window.removeEventListener('keydown', onKey)
    }
  }, [open])

  const safeName = (patientLabel || 'study').replace(/[^\w.-]+/g, '_').slice(0, 40)

  async function exportRaster(kind: 'viewport' | 'layout', format: ImageFormat) {
    setLocalError(null)
    try {
      const blob =
        kind === 'viewport'
          ? await captureViewportBlob(format)
          : await captureLayoutBlob('png')
      const ext = format === 'jpeg' ? 'jpg' : 'png'
      downloadBlob(blob, `${safeName}-${kind}.${ext}`)
      setOpen(false)
    } catch (err) {
      setLocalError(err instanceof Error ? err.message : 'Export failed')
    }
  }

  async function exportDicom(scope: 'IMAGE' | 'SERIES' | 'STUDY') {
    setLocalError(null)
    if (scope === 'IMAGE' && !sopInstanceUid) {
      setLocalError('No active image to export')
      return
    }
    if (scope === 'SERIES' && !seriesInstanceUid) {
      setLocalError('No active series to export')
      return
    }
    setOpen(false)
    await start(
      studyId,
      {
        scope,
        seriesInstanceUid: scope === 'STUDY' ? null : seriesInstanceUid,
        sopInstanceUid: scope === 'IMAGE' ? sopInstanceUid : null,
      },
      `${safeName}-${scope.toLowerCase()}.zip`,
    )
  }

  const progress =
    job && job.totalObjects > 0
      ? Math.round((100 * job.completedObjects) / job.totalObjects)
      : busy
        ? 0
        : null

  return (
    <div className="viewer-export" ref={rootRef}>
      <button
        type="button"
        className="btn secondary"
        disabled={disabled || busy}
        aria-expanded={open}
        aria-controls={menuId}
        onClick={() => setOpen((v) => !v)}
      >
        Export
      </button>
      {open && (
        <div className="viewer-export-menu" id={menuId} role="menu">
          <div className="viewer-export-group">
            <div className="viewer-export-heading">Current viewport</div>
            <button type="button" role="menuitem" onClick={() => exportRaster('viewport', 'png')}>PNG</button>
            <button type="button" role="menuitem" onClick={() => exportRaster('viewport', 'jpeg')}>JPEG</button>
          </div>
          <div className="viewer-export-group">
            <div className="viewer-export-heading">Current layout</div>
            <button type="button" role="menuitem" onClick={() => exportRaster('layout', 'png')}>PNG</button>
          </div>
          <div className="viewer-export-group">
            <div className="viewer-export-heading">Current image</div>
            <button type="button" role="menuitem" disabled={!sopInstanceUid} onClick={() => exportDicom('IMAGE')}>DICOM</button>
          </div>
          <div className="viewer-export-group">
            <div className="viewer-export-heading">Series</div>
            <button type="button" role="menuitem" disabled={!seriesInstanceUid} onClick={() => exportDicom('SERIES')}>DICOM</button>
          </div>
          <div className="viewer-export-group">
            <div className="viewer-export-heading">Study</div>
            <button type="button" role="menuitem" onClick={() => exportDicom('STUDY')}>DICOM</button>
          </div>
        </div>
      )}
      {(busy || job || error || localError) && (
        <div className="viewer-export-progress" role="status">
          {localError && <span className="error">{localError}</span>}
          {error && <span className="error">{error}</span>}
          {busy && job && (
            <span>
              Exporting DICOM… {job.completedObjects}/{job.totalObjects || '…'}
              {progress != null ? ` (${progress}%)` : ''}
            </span>
          )}
          {!busy && job?.status === 'DONE' && <span>Download started</span>}
          {(error || localError || (!busy && job)) && (
            <button type="button" className="linkish" onClick={() => { dismiss(); setLocalError(null) }}>
              Dismiss
            </button>
          )}
        </div>
      )}
    </div>
  )
}
