import { useMemo } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import type { ShareCreateResult } from '../shareApi'

type SlipState = ShareCreateResult & { createdAt?: string }

export default function ShareSlipPage() {
  const location = useLocation()
  const navigate = useNavigate()
  const slip = location.state as SlipState | null

  const qrUrl = useMemo(() => {
    if (!slip?.url) return ''
    return `https://api.qrserver.com/v1/create-qr-code/?size=200x200&data=${encodeURIComponent(slip.url)}`
  }, [slip?.url])

  if (!slip?.url || !slip.accessCode) {
    return (
      <div className="page">
        <div className="panel">
          <p>No share slip data. Create a share first.</p>
          <Link to="/hospital">Back to hospital</Link>
        </div>
      </div>
    )
  }

  const expires = new Date(slip.expiresAt).toLocaleString()
  const asOf = slip.createdAt ? new Date(slip.createdAt).toLocaleString() : new Date().toLocaleString()

  return (
    <div className="page">
      <div className="no-print" style={{ display: 'flex', gap: '0.75rem', marginBottom: '1rem', flexWrap: 'wrap' }}>
        <button type="button" className="btn" onClick={() => window.print()}>
          Print slip
        </button>
        <button type="button" className="btn secondary" onClick={() => navigate(-1)}>
          Back
        </button>
        <Link className="btn secondary" to="/hospital">
          Hospital worklist
        </Link>
      </div>

      <div className="share-slip panel">
        <h1 style={{ marginTop: 0 }}>{slip.facilityName}</h1>
        <p style={{ fontSize: '1.1rem', marginBottom: '0.25rem' }}>Study imaging access</p>
        <p className="muted" style={{ marginTop: 0 }}>
          Shared study as of {asOf}
          {slip.modality || slip.studyDescription || slip.studyDate
            ? ` — ${[slip.modality, slip.studyDescription, slip.studyDate].filter(Boolean).join(' · ')}`
            : ''}
          {slip.priorCount != null && slip.priorCount > 0
            ? ` · includes ${slip.priorCount} frozen prior${slip.priorCount === 1 ? '' : 's'} for comparison`
            : ''}
        </p>
        {slip.patientLabel && (
          <p>
            <strong>Patient:</strong> {slip.patientLabel}
          </p>
        )}

        <div
          style={{
            display: 'flex',
            gap: '1.5rem',
            flexWrap: 'wrap',
            alignItems: 'flex-start',
            margin: '1.5rem 0',
          }}
        >
          {qrUrl && (
            <img src={qrUrl} alt="QR code for imaging link" width={200} height={200} style={{ border: '1px solid #ccc' }} />
          )}
          <div>
            <p style={{ marginBottom: '0.35rem' }}>
              <strong>Link</strong>
            </p>
            <p style={{ wordBreak: 'break-all', fontFamily: 'monospace', fontSize: '0.95rem' }}>{slip.url}</p>
            <p style={{ marginBottom: '0.35rem', marginTop: '1rem' }}>
              <strong>Access code</strong>
            </p>
            <p
              style={{
                fontFamily: 'monospace',
                fontSize: '1.75rem',
                letterSpacing: '0.2em',
                fontWeight: 700,
              }}
            >
              {slip.accessCode}
            </p>
            <p className="muted">Expires: {expires}</p>
          </div>
        </div>

        <p style={{ borderTop: '1px solid #ddd', paddingTop: '1rem' }}>
          Keep this slip private. Do not share the link and code publicly. This access does not include a diagnosis
          statement—ask your clinician for interpretation.
        </p>
      </div>

      <style>{`
        @media print {
          .no-print { display: none !important; }
          body { background: white; }
          .share-slip { box-shadow: none; border: none; }
        }
      `}</style>
    </div>
  )
}
