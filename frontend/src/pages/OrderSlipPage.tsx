import JsBarcode from 'jsbarcode'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { LATERALITY_LABEL, ORDER_STATUS_LABEL, orderDetail } from '../ordersApi'
import type { ImagingOrder } from '../types'

/** One Code 128 barcode, drawn in the browser (no patient data leaves the page). */
function Barcode({ value, label }: { value: string; label: string }) {
  const ref = useRef<SVGSVGElement>(null)
  useEffect(() => {
    if (!ref.current) return
    JsBarcode(ref.current, value, {
      format: 'CODE128',
      width: 2,
      height: 64,
      margin: 12,
      displayValue: true,
      fontSize: 16,
      font: 'monospace',
      background: '#ffffff',
      lineColor: '#000000',
    })
  }, [value])
  return (
    <figure className="slip-barcode">
      <figcaption>{label}</figcaption>
      <svg ref={ref} role="img" aria-label={`${label}: ${value}`} data-value={value} />
    </figure>
  )
}

const fmtDate = (d: string | null) => (d ? new Date(`${d}T00:00:00`).toLocaleDateString() : '—')

/**
 * Order slip (KB note 15 §3): reception prints it, the patient brings it to the MRT, and the MRT
 * scans the two barcodes into the scanner's Accession Number and Patient ID fields.
 */
export function OrderSlipPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const [order, setOrder] = useState<ImagingOrder | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    orderDetail(id)
      .then((d) => setOrder(d.order))
      .catch((e) => setError(e instanceof Error ? e.message : 'Order not found'))
  }, [id])

  if (error) {
    return (
      <div className="page">
        <div className="error">{error}</div>
        <Link to="/reception">Back to reception</Link>
      </div>
    )
  }
  if (!order) return <div className="page"><p className="muted">Loading…</p></div>

  const p = order.patient
  return (
    <div className="page share-slip-print order-slip-page">
      <div className="no-print slip-toolbar">
        <button type="button" className="btn" onClick={() => window.print()}>Print slip</button>
        <button type="button" className="btn secondary" onClick={() => navigate(-1)}>Back</button>
        <Link className="btn secondary" to="/reception">Reception</Link>
      </div>

      <article className="share-slip panel order-slip">
        <header className="slip-head">
          <div>
            <h1>{order.hospitalName}</h1>
            <p>Imaging order · {fmtDate(order.scheduledDate)}</p>
          </div>
          {order.priority !== 'ROUTINE' && <div className={`slip-priority pri-${order.priority.toLowerCase()}`}>{order.priority}</div>}
        </header>

        <section className="slip-patient">
          <div className="slip-name">{p?.name}</div>
          <div>
            Patient ID <strong className="mono">{p?.mrn}</strong> · Born {fmtDate(p?.birthDate ?? null)} · Sex {p?.sex ?? '—'}
          </div>
          <div className="slip-exam">
            Exam <strong>{order.modality}</strong>
            {order.procedureDescription ? <> · <strong>{order.procedureDescription}</strong></> : ''}
            {order.laterality !== 'NOT_APPLICABLE' && (
              <span className="slip-side">{LATERALITY_LABEL[order.laterality].toUpperCase()}</span>
            )}
          </div>
          {order.referringPhysician && <div>Referred by {order.referringPhysician}</div>}
          {order.additionalInstructions && (
            <div className="slip-instr">
              <strong>Instructions:</strong> {order.additionalInstructions}
            </div>
          )}
          {order.clinicalInfo && <div className="slip-info">Clinical information: {order.clinicalInfo}</div>}
        </section>

        <section className="slip-barcodes">
          <Barcode label="1 · Accession Number" value={order.accessionNumber} />
          {p?.mrn && <Barcode label="2 · Patient ID" value={p.mrn} />}
        </section>

        <footer className="slip-mrt">
          <strong>For the MRT at the scanner:</strong> click the <em>Accession Number</em> field and scan barcode 1,
          then click the <em>Patient ID</em> field and scan barcode 2. The name is optional: TeleRad uses the name
          on this slip.
          <div className="no-print muted">Status now: {ORDER_STATUS_LABEL[order.status]}</div>
        </footer>
      </article>
    </div>
  )
}
