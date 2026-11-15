import { FormEvent, useEffect, useState } from 'react'
import { api } from '../api'
import type { ReadyMode, TenantDto } from '../types'

interface OrderSettings {
  ordersEnabled: boolean
  readyMode: ReadyMode
  scanStableSeconds: number
  accessionPrefix: string | null
  effectivePrefix: string
}

/** Per-hospital orders and scanner workflow (KB note 15), set by us when the hospital is onboarded. */
export function OrderSettingsPanel({ tenants }: { tenants: TenantDto[] }) {
  const [tenantId, setTenantId] = useState('')
  const [form, setForm] = useState<OrderSettings | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!tenantId && tenants[0]) setTenantId(tenants[0].id)
  }, [tenants, tenantId])

  useEffect(() => {
    if (!tenantId) return
    setMessage(null)
    setError(null)
    api<OrderSettings>(`/api/admin/tenants/${tenantId}/order-settings`)
      .then(setForm)
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load'))
  }, [tenantId])

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!form) return
    setError(null)
    setMessage(null)
    try {
      const saved = await api<OrderSettings>(`/api/admin/tenants/${tenantId}/order-settings`, {
        method: 'PUT',
        body: JSON.stringify({ ...form, accessionPrefix: form.accessionPrefix?.trim() || null }),
      })
      setForm(saved)
      setMessage('Saved.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    }
  }

  return (
    <div className="panel">
      <h2>Orders</h2>
      <p className="muted">
        Reception creates orders and prints a slip; images are linked to the order by accession number and patient ID.
      </p>
      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}
      <div className="field">
        <label htmlFor="ord-tenant">Hospital</label>
        <select id="ord-tenant" value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
          {tenants.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.name}</option>)}
        </select>
      </div>
      {form && (
        <form className="image-settings" onSubmit={save}>
          <label className="check-line">
            <input
              type="checkbox"
              checked={form.ordersEnabled}
              onChange={(e) => setForm({ ...form, ordersEnabled: e.target.checked })}
            />
            Use orders at this hospital
          </label>
          <span className="muted hint">
            When on, studies go to the radiologists only once they are linked to an order (or released by a person).
          </span>
          <div className="field">
            <label htmlFor="ord-mode">Ready to read</label>
            <select
              id="ord-mode"
              value={form.readyMode}
              onChange={(e) => setForm({ ...form, readyMode: e.target.value as ReadyMode })}
            >
              <option value="AUTO">Automatically, when the images stop arriving</option>
              <option value="WAIT_FOR_MRT">Only after the MRT clicks “Scan complete”</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="ord-stable">Scan finished after (seconds without a new image)</label>
            <input
              id="ord-stable"
              type="number"
              min={30}
              max={3600}
              value={form.scanStableSeconds}
              onChange={(e) => setForm({ ...form, scanStableSeconds: Number(e.target.value) })}
            />
          </div>
          <div className="field">
            <label htmlFor="ord-prefix">Accession prefix (1–5 letters or digits)</label>
            <input
              id="ord-prefix"
              maxLength={5}
              placeholder={form.effectivePrefix}
              value={form.accessionPrefix ?? ''}
              onChange={(e) => setForm({ ...form, accessionPrefix: e.target.value })}
            />
            <span className="muted hint">Accession numbers look like {form.effectivePrefix}-2611130001 (fits the scanner’s 16 characters).</span>
          </div>
          <button className="btn" type="submit">Save</button>
        </form>
      )}
    </div>
  )
}
