import { useEffect, useState } from 'react'
import { useAuth } from '../auth'
import { cloudHasImages, fetchImageAccess, requestCloudCopy, type ImageAccess } from '../imageSource'
import type { StudyListItem } from '../types'

const POLL_MS = 5000

/**
 * Shown instead of the viewer when the study's images are only on a hospital server this
 * browser cannot reach (KB note 13): offers to send them to the cloud and follows the upload.
 */
export function ImagesNotHere({ study, access: initial, onBack, onAvailable }: {
  study: StudyListItem
  access: ImageAccess | null
  onBack: () => void
  onAvailable: () => void
}) {
  const { user } = useAuth()
  const [access, setAccess] = useState<ImageAccess | null>(initial)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const status = access?.cloudStatus ?? study.cloudStatus
  const onHospitalServer = access?.onHospitalServer ?? study.onHospitalServer
  const uploading = status === 'REQUESTED'

  // Follow the upload until the cloud has every image, then open the viewer.
  useEffect(() => {
    if (!uploading) return
    const timer = setInterval(() => {
      fetchImageAccess(study.id)
        .then((next) => {
          setAccess(next)
          if (cloudHasImages(next.cloudStatus)) onAvailable()
        })
        .catch(() => { /* keep polling */ })
    }, POLL_MS)
    return () => clearInterval(timer)
  }, [uploading, study.id, onAvailable])

  async function send() {
    setBusy(true)
    setError(null)
    try {
      const next = await requestCloudCopy(study.id)
      setAccess(next)
      if (cloudHasImages(next.cloudStatus)) onAvailable()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed')
    } finally {
      setBusy(false)
    }
  }

  const hospital = study.hospitalName || 'the hospital'
  const sendLabel = user?.role === 'HOSPITAL_USER' ? 'Send for remote reading' : 'Request images from the hospital'
  const total = access?.totalInstances ?? study.instanceCount
  const done = access?.cloudInstances ?? 0

  return (
    <div className="page images-not-here">
      <div className="panel">
        <p className="muted">
          {study.patientName || 'Unknown patient'} · {study.modality || '—'} · {study.studyDescription || ''}
        </p>
        {!onHospitalServer ? (
          <>
            <h2>The images are no longer stored</h2>
            <p>
              {hospital}&apos;s server removed this study after its retention period, and there is no cloud
              copy. The facility&apos;s own PACS may still have it.
            </p>
          </>
        ) : uploading ? (
          <>
            <h2>Sending images to the cloud…</h2>
            <p>
              The server at {hospital} is uploading this study. It opens here by itself when the upload is done.
            </p>
            <div className="upload-progress" role="progressbar" aria-valuemin={0} aria-valuemax={total} aria-valuenow={done}>
              <div style={{ width: `${total ? Math.min(100, (done / total) * 100) : 0}%` }} />
            </div>
            <p className="muted">
              {done} of {total} images in the cloud. If the hospital&apos;s internet is down, the upload
              starts when the connection is back.
            </p>
          </>
        ) : (
          <>
            <h2>The images are at {hospital}</h2>
            <p>
              They are stored on the hospital&apos;s server and this computer cannot reach it, which usually means
              you are outside the hospital network.
            </p>
            <p>
              Inside the hospital, open the study again and it loads from the hospital server. To read it from
              here, the hospital server needs to send it to the cloud first.
            </p>
            <button type="button" className="btn" disabled={busy} onClick={() => void send()}>
              {busy ? 'Sending…' : sendLabel}
            </button>
          </>
        )}
        {error && <div className="error">{error}</div>}
        <p>
          <button type="button" className="btn secondary" onClick={onBack}>← Back</button>
        </p>
      </div>
    </div>
  )
}
