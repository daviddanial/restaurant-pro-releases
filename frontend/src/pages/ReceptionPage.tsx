import { FormEvent, useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api'
import { useAuth } from '../auth'
import {
  cancelOrder,
  createOrder,
  examLabel,
  findPatient,
  listModalities,
  listProcedures,
  LATERALITY_LABEL,
  linkStudy,
  listOrders,
  NewOrder,
  ORDER_STATUS_LABEL,
  ORDER_STEPS,
  orderAction,
  OrderAction,
  orderActions,
  orderDetail,
  orderSettings,
  orderStepIndex,
  reconcileList,
  rejectSuggestion,
  releaseStudy,
} from '../ordersApi'
import type {
  FacilityOrderSettings,
  ImagingOrder,
  Laterality,
  ModalityDto,
  ProcedureDto,
  OrderEventDto,
  ReconcileItem,
  StudyPriority,
  TenantDto,
} from '../types'


const localDay = (d = new Date()) => {
  const off = d.getTimezoneOffset() * 60000
  return new Date(d.getTime() - off).toISOString().slice(0, 10)
}

const errText = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback)

const time = (iso: string | null) => (iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '')

const emptyForm = (modality = ''): NewOrder => ({
  patientMrn: '',
  patientName: '',
  birthDate: null,
  sex: null,
  modality,
  procedureId: null,
  procedureDescription: null,
  laterality: 'NOT_APPLICABLE',
  additionalInstructions: '',
  priority: 'ROUTINE',
  referringPhysician: '',
  clinicalInfo: '',
})

/** Reception and MRT screen: orders, patient slips, and studies waiting for a check (KB note 15). */
export function ReceptionPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN'
  const [tenants, setTenants] = useState<TenantDto[]>([])
  const [tenantId, setTenantId] = useState<string | null>(isAdmin ? null : user?.tenantId ?? null)
  const [settings, setSettings] = useState<FacilityOrderSettings | null>(null)
  const [day, setDay] = useState(localDay())
  const [orders, setOrders] = useState<ImagingOrder[]>([])
  const [checks, setChecks] = useState<ReconcileItem[]>([])
  const [form, setForm] = useState<NewOrder>(emptyForm())
  const [known, setKnown] = useState<string | null>(null)
  const [created, setCreated] = useState<ImagingOrder | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [history, setHistory] = useState<Record<string, OrderEventDto[]>>({})
  const [modalities, setModalities] = useState<ModalityDto[]>([])
  const [procedures, setProcedures] = useState<ProcedureDto[]>([])
  const [cancelling, setCancelling] = useState<string | null>(null)
  const [cancelReason, setCancelReason] = useState('')

  // The central procedure list; the form offers only the chosen modality's procedures.
  useEffect(() => {
    Promise.all([listModalities(), listProcedures()])
      .then(([m, p]) => {
        setModalities(m)
        setProcedures(p)
        setForm((f) => (f.modality ? f : { ...f, modality: m[0]?.code ?? '' }))
      })
      .catch((e) => setError(errText(e, 'Could not load the procedure list')))
  }, [])

  useEffect(() => {
    if (!isAdmin) return
    api<TenantDto[]>('/api/admin/tenants')
      .then((t) => {
        setTenants(t)
        setTenantId((cur) => cur ?? t[0]?.id ?? null)
      })
      .catch((e) => setError(errText(e, 'Could not load hospitals')))
  }, [isAdmin])

  const refresh = useCallback(async () => {
    if (isAdmin && !tenantId) return
    try {
      const s = await orderSettings(tenantId)
      setSettings(s)
      if (!s.ordersEnabled) return
      const [o, c] = await Promise.all([listOrders(tenantId, day), reconcileList(tenantId)])
      setOrders(o)
      setChecks(c)
    } catch (e) {
      setError(errText(e, 'Could not load orders'))
    }
  }, [tenantId, day, isAdmin])

  useEffect(() => {
    void refresh()
    const t = window.setInterval(() => void refresh(), 10000)
    return () => window.clearInterval(t)
  }, [refresh])

  async function lookupPatient() {
    const mrn = (form.patientMrn ?? '').trim()
    setKnown(null)
    if (!mrn) return
    try {
      const p = await findPatient(tenantId, mrn)
      if (p) {
        setForm((f) => ({ ...f, patientName: p.name ?? f.patientName, birthDate: p.birthDate, sex: p.sex }))
        setKnown(`Known patient: ${p.name ?? ''}${p.birthDate ? `, born ${p.birthDate}` : ''}`)
      }
    } catch {
      /* lookup is a convenience */
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setMessage(null)
    setBusy('create')
    try {
      const o = await createOrder({
        ...form,
        tenantId,
        patientMrn: form.patientMrn?.trim() || null,
        procedureDescription: null,
        additionalInstructions: form.additionalInstructions?.trim() || null,
        referringPhysician: form.referringPhysician?.trim() || null,
        clinicalInfo: form.clinicalInfo?.trim() || null,
        scheduledDate: day,
      })
      setCreated(o)
      setForm(emptyForm(form.modality))
      setKnown(null)
      await refresh()
    } catch (err) {
      setError(errText(err, 'Could not create the order'))
    } finally {
      setBusy(null)
    }
  }

  async function act(o: ImagingOrder, action: OrderAction) {
    setError(null)
    setMessage(null)
    setBusy(`${o.id}:${action}`)
    try {
      await orderAction(o.id, action)
      await refresh()
      if (history[o.id]) await toggleHistory(o.id, true)
    } catch (err) {
      setError(errText(err, 'Action failed'))
    } finally {
      setBusy(null)
    }
  }

  async function doCancel(o: ImagingOrder) {
    setBusy(`${o.id}:cancel`)
    try {
      await cancelOrder(o.id, cancelReason.trim())
      setCancelling(null)
      setCancelReason('')
      await refresh()
    } catch (err) {
      setError(errText(err, 'Could not cancel'))
    } finally {
      setBusy(null)
    }
  }

  async function toggleHistory(id: string, reload = false) {
    if (history[id] && !reload) {
      setHistory(({ [id]: _, ...rest }) => rest)
      return
    }
    try {
      const d = await orderDetail(id)
      setHistory((h) => ({ ...h, [id]: d.history }))
    } catch (err) {
      setError(errText(err, 'Could not load the history'))
    }
  }

  if (isAdmin && tenants.length === 0 && !error) {
    return <div className="page"><p className="muted">Loading…</p></div>
  }

  return (
    <div className="page reception-page">
      <div className="page-head">
        <div>
          <h1>Reception</h1>
          <p className="muted">
            Orders for {settings?.hospitalName ?? 'this hospital'}. Print the slip; the MRT scans its two barcodes at
            the scanner.
          </p>
        </div>
        {isAdmin && (
          <div className="reception-head-controls">
            <select aria-label="Hospital" value={tenantId ?? ''} onChange={(e) => setTenantId(e.target.value)}>
              {tenants.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}

      {settings && !settings.ordersEnabled && (
        <div className="panel">
          <p>Orders are not switched on for this hospital. A platform admin can switch them on in Admin → Orders.</p>
        </div>
      )}

      {settings?.ordersEnabled && (
        <>
          {checks.length > 0 && (
            <NeedsCheck
              items={checks}
              onDone={async (msg) => {
                setMessage(msg)
                await refresh()
              }}
              onError={setError}
            />
          )}

          <section className="panel">
            <h2>New order</h2>
            {created && (
              <div className="success order-created">
                Order <strong className="mono">{created.accessionNumber}</strong> created for{' '}
                {created.patient?.name}.{' '}
                <Link className="btn btn-small" to={`/reception/orders/${created.id}/slip`}>Print slip</Link>
              </div>
            )}
            <form className="order-form" onSubmit={submit}>
              <div className="field">
                <label htmlFor="o-mrn">Patient ID (MRN / card no.)</label>
                <input
                  id="o-mrn"
                  value={form.patientMrn ?? ''}
                  placeholder="Empty: a new ID is given"
                  onChange={(e) => setForm({ ...form, patientMrn: e.target.value })}
                  onBlur={() => void lookupPatient()}
                />
                {known && <span className="muted hint">{known}</span>}
              </div>
              <div className="field">
                <label htmlFor="o-name">Patient name</label>
                <input
                  id="o-name"
                  required
                  value={form.patientName}
                  onChange={(e) => setForm({ ...form, patientName: e.target.value })}
                />
              </div>
              <div className="field">
                <label htmlFor="o-dob">Birth date</label>
                <input
                  id="o-dob"
                  type="date"
                  value={form.birthDate ?? ''}
                  onChange={(e) => setForm({ ...form, birthDate: e.target.value || null })}
                />
              </div>
              <div className="field">
                <label htmlFor="o-sex">Sex</label>
                <select id="o-sex" value={form.sex ?? ''} onChange={(e) => setForm({ ...form, sex: e.target.value || null })}>
                  <option value="">—</option>
                  <option value="F">F</option>
                  <option value="M">M</option>
                  <option value="O">O</option>
                </select>
              </div>
              <div className="field">
                <label htmlFor="o-mod">Modality</label>
                <select
                  id="o-mod"
                  required
                  value={form.modality}
                  onChange={(e) => setForm({ ...form, modality: e.target.value, procedureId: null })}
                >
                  {modalities.map((m) => (
                    <option key={m.code} value={m.code}>{m.label} ({m.code})</option>
                  ))}
                </select>
              </div>
              <div className="field field-proc">
                <label htmlFor="o-proc">Procedure</label>
                <select
                  id="o-proc"
                  required
                  value={form.procedureId ?? ''}
                  onChange={(e) => setForm({ ...form, procedureId: e.target.value || null })}
                >
                  <option value="">Choose a procedure…</option>
                  {procedures
                    .filter((p) => p.modality === form.modality)
                    .map((p) => (
                      <option key={p.id} value={p.id}>{p.name}</option>
                    ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="o-side">Laterality</label>
                <select
                  id="o-side"
                  value={form.laterality}
                  onChange={(e) => setForm({ ...form, laterality: e.target.value as Laterality })}
                >
                  {(Object.keys(LATERALITY_LABEL) as Laterality[]).map((l) => (
                    <option key={l} value={l}>{LATERALITY_LABEL[l]}</option>
                  ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="o-pri">Priority</label>
                <select
                  id="o-pri"
                  value={form.priority}
                  onChange={(e) => setForm({ ...form, priority: e.target.value as StudyPriority })}
                >
                  <option value="ROUTINE">Routine</option>
                  <option value="URGENT">Urgent</option>
                  <option value="STAT">STAT</option>
                </select>
              </div>
              <div className="field">
                <label htmlFor="o-ref">Referring physician</label>
                <input
                  id="o-ref"
                  value={form.referringPhysician ?? ''}
                  onChange={(e) => setForm({ ...form, referringPhysician: e.target.value })}
                />
              </div>
              <div className="field field-wide">
                <label htmlFor="o-info">Clinical information (from the paper)</label>
                <textarea
                  id="o-info"
                  rows={2}
                  value={form.clinicalInfo ?? ''}
                  onChange={(e) => setForm({ ...form, clinicalInfo: e.target.value })}
                />
              </div>
              <div className="field field-wide">
                <label htmlFor="o-instr">Additional instructions (for the MRT)</label>
                <textarea
                  id="o-instr"
                  rows={2}
                  maxLength={2000}
                  placeholder="e.g. Patient on wheelchair; compare with previous X-ray"
                  value={form.additionalInstructions ?? ''}
                  onChange={(e) => setForm({ ...form, additionalInstructions: e.target.value })}
                />
              </div>
              <div className="order-actions">
                <button className="btn" type="submit" disabled={busy === 'create'}>
                  {busy === 'create' ? 'Creating…' : 'Create order'}
                </button>
              </div>
            </form>
          </section>

          <section className="panel table-card">
            <div className="table-head">
              <h2>Orders</h2>
              <div className="reception-head-controls">
                <span className="muted">{orders.length} order{orders.length === 1 ? '' : 's'}</span>
                <input type="date" aria-label="Day" value={day} onChange={(e) => setDay(e.target.value)} />
              </div>
            </div>
            <table className="table orders-table">
              <thead>
                <tr>
                  <th>Accession</th>
                  <th>Patient</th>
                  <th>Exam</th>
                  <th>Status</th>
                  <th>Images</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {orders.map((o) => {
                  const a = orderActions(o, settings.readyMode)
                  const step = orderStepIndex(o.status)
                  const closed = o.status === 'CANCELLED' || o.status === 'NO_SHOW'
                  return [
                    <tr key={o.id} className={closed ? 'row-closed' : undefined}>
                      <td className="mono nowrap">{o.accessionNumber}</td>
                      <td>
                        <div className="patient-cell">{o.patient?.name}</div>
                        <div className="mono muted">{o.patient?.mrn}</div>
                        {o.nameDiffers && (
                          <span className="flag-warn" title={`Name typed at the scanner: ${o.scannerPatientName ?? ''}`}>
                            Name differs at scanner
                          </span>
                        )}
                      </td>
                      <td>
                        <div>{examLabel(o)}</div>
                        {o.additionalInstructions && <div className="order-instr">⚠ {o.additionalInstructions}</div>}
                        {o.priority !== 'ROUTINE' && <span className={`badge badge-${o.priority.toLowerCase()}`}>{o.priority}</span>}
                      </td>
                      <td>
                        <span className={`order-status status-${o.status.toLowerCase()}`}>{ORDER_STATUS_LABEL[o.status]}</span>
                        {o.waitingForMrt && <div className="flag-warn">Waiting for MRT confirmation</div>}
                        {o.imagesAfterReport && <div className="flag-warn">New images after the report</div>}
                        {!closed && (
                          <div className="order-steps" aria-hidden="true">
                            {ORDER_STEPS.map((s, i) => (
                              <span key={s} className={i <= step ? 'on' : ''} title={ORDER_STATUS_LABEL[s]} />
                            ))}
                          </div>
                        )}
                        {o.cancelReason && <div className="muted">{o.cancelReason}</div>}
                      </td>
                      <td>{o.imageCount > 0 ? o.imageCount : '—'}</td>
                      <td>
                        <div className="order-row-actions">
                          <Link className="btn secondary btn-small" to={`/reception/orders/${o.id}/slip`}>Slip</Link>
                          {a.arrived && (
                            <button type="button" className="btn btn-small" disabled={!!busy} onClick={() => void act(o, 'arrived')}>
                              Patient arrived
                            </button>
                          )}
                          {a.startScan && (
                            <button type="button" className="btn secondary btn-small" disabled={!!busy} onClick={() => void act(o, 'start-scan')}>
                              Start scan
                            </button>
                          )}
                          {a.scanComplete && (
                            <button type="button" className="btn secondary btn-small" disabled={!!busy} onClick={() => void act(o, 'scan-complete')}>
                              Scan complete
                            </button>
                          )}
                          {a.noShow && (
                            <button type="button" className="btn secondary btn-small" disabled={!!busy} onClick={() => void act(o, 'no-show')}>
                              No-show
                            </button>
                          )}
                          {a.cancel && (
                            <button type="button" className="btn secondary btn-small" disabled={!!busy} onClick={() => setCancelling(o.id)}>
                              Cancel
                            </button>
                          )}
                          <button type="button" className="btn-link" onClick={() => void toggleHistory(o.id)}>
                            {history[o.id] ? 'Hide history' : 'History'}
                          </button>
                        </div>
                        {cancelling === o.id && (
                          <div className="inline-form">
                            <input
                              aria-label="Cancel reason"
                              placeholder="Reason"
                              value={cancelReason}
                              onChange={(e) => setCancelReason(e.target.value)}
                            />
                            <button type="button" className="btn btn-small" onClick={() => void doCancel(o)}>
                              Cancel order
                            </button>
                            <button type="button" className="btn-link" onClick={() => setCancelling(null)}>Keep</button>
                          </div>
                        )}
                      </td>
                    </tr>,
                    history[o.id] && (
                      <tr key={`${o.id}-h`} className="history-row">
                        <td colSpan={6}>
                          <ol className="order-history">
                            {history[o.id].map((e, i) => (
                              <li key={i}>
                                <span className="mono muted">{time(e.at)}</span> <strong>{e.kind.replace(/_/g, ' ').toLowerCase()}</strong>
                                {e.detail ? ` — ${e.detail}` : ''}
                              </li>
                            ))}
                          </ol>
                        </td>
                      </tr>
                    ),
                  ]
                })}
                {orders.length === 0 && (
                  <tr>
                    <td colSpan={6} className="muted" style={{ padding: '1.5rem', textAlign: 'center' }}>
                      No orders for this day.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </section>
        </>
      )}
    </div>
  )
}

const MATCH_TITLE: Record<string, string> = {
  NEEDS_CHECK: 'Needs check',
  SUGGESTED: 'Suggested match',
  UNMATCHED: 'No matching order',
}

/** Studies that do not clearly belong to an order wait here for a person (note 15 §4). */
function NeedsCheck({
  items,
  onDone,
  onError,
}: {
  items: ReconcileItem[]
  onDone: (msg: string) => Promise<void>
  onError: (msg: string) => void
}) {
  const [choice, setChoice] = useState<Record<string, string>>({})
  const [reason, setReason] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)

  const run = async (id: string, fn: () => Promise<unknown>, msg: string) => {
    setBusy(id)
    try {
      await fn()
      await onDone(msg)
    } catch (e) {
      onError(errText(e, 'Action failed'))
    } finally {
      setBusy(null)
    }
  }

  return (
    <section className="panel needs-check">
      <h2>Needs check <span className="count-pill">{items.length}</span></h2>
      <p className="muted">
        These images do not clearly belong to an order, so radiologists do not see them yet. Compare and decide.
      </p>
      {items.map((it) => {
        const target = it.suggested ?? null
        const picked = choice[it.studyId] ?? target?.id ?? ''
        return (
          <article key={it.studyId} className="check-card">
            <header>
              <strong>{MATCH_TITLE[it.matchState] ?? it.matchState}</strong>
              <span className="muted"> · {it.imageCount} image{it.imageCount === 1 ? '' : 's'} · {it.modality}</span>
              {it.matchNote && <div className="check-note">{it.matchNote}</div>}
            </header>
            <div className="check-compare">
              <dl>
                <dt>At the scanner</dt>
                <dd>Accession: <span className="mono">{it.scannerAccession || '—'}</span></dd>
                <dd>Patient ID: <span className="mono">{it.scannerPatientId || '—'}</span></dd>
                <dd>Name: {it.scannerPatientName ? it.scannerPatientName.replace(/\^/g, ' ') : '—'}</dd>
                <dd>Study: {it.studyDescription || '—'} {it.studyDate ?? ''}</dd>
              </dl>
              <dl>
                <dt>{target ? 'Suggested order' : 'Order'}</dt>
                {target ? (
                  <>
                    <dd>Accession: <span className="mono">{target.accessionNumber}</span></dd>
                    <dd>Patient ID: <span className="mono">{target.patient?.mrn}</span></dd>
                    <dd>Name: {target.patient?.name}</dd>
                    <dd>Exam: {examLabel(target)} · {target.scheduledDate}</dd>
                  </>
                ) : (
                  <dd>
                    <select
                      aria-label="Order to link"
                      value={picked}
                      onChange={(e) => setChoice({ ...choice, [it.studyId]: e.target.value })}
                    >
                      <option value="">Choose an open order…</option>
                      {it.candidates.map((c) => (
                        <option key={c.id} value={c.id}>
                          {c.accessionNumber} · {c.patient?.name} · {c.patient?.mrn} · {c.modality} · {c.scheduledDate}
                        </option>
                      ))}
                    </select>
                  </dd>
                )}
              </dl>
            </div>
            <div className="order-row-actions">
              <button
                type="button"
                className="btn btn-small"
                disabled={!picked || busy === it.studyId}
                onClick={() => void run(it.studyId, () => linkStudy(it.studyId, picked), 'Linked: the order’s patient details are used.')}
              >
                Link to this order
              </button>
              {target && (
                <button
                  type="button"
                  className="btn secondary btn-small"
                  disabled={busy === it.studyId}
                  onClick={() => void run(it.studyId, () => rejectSuggestion(it.studyId), 'Marked as not this patient.')}
                >
                  Not this patient
                </button>
              )}
              <input
                aria-label="Release reason"
                placeholder="Reason to read without an order"
                value={reason[it.studyId] ?? ''}
                onChange={(e) => setReason({ ...reason, [it.studyId]: e.target.value })}
              />
              <button
                type="button"
                className="btn secondary btn-small"
                disabled={!(reason[it.studyId] ?? '').trim() || busy === it.studyId}
                onClick={() =>
                  void run(it.studyId, () => releaseStudy(it.studyId, reason[it.studyId] ?? ''), 'Released for reading without an order.')
                }
              >
                Read without order
              </button>
            </div>
          </article>
        )
      })}
    </section>
  )
}
