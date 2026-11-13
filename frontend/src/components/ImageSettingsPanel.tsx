import { FormEvent, useEffect, useState } from 'react'
import { api } from '../api'
import type { TenantDto } from '../types'

type UploadPolicy = 'ALL' | 'ON_DEMAND'
type EdgeStorageMode = 'ARCHIVE' | 'CACHE'

interface ImageSettings {
  uploadPolicy: UploadPolicy
  autoSendRules: string | null
  priorsToSend: number
  cloudRetentionDays: number | null
  edgeStorageMode: EdgeStorageMode
  edgeLocalRetentionDays: number | null
}

interface GatewayDto {
  id: string
  tenantId: string
  name: string
  location: string | null
  active: boolean
  enrolled: boolean
  lastHeartbeatAt: string | null
  lastSuccessfulUploadAt: string | null
  lanUrl: string | null
}

interface CreatedGateway {
  gateway: GatewayDto
  enrollmentToken: string
  enrollmentExpiresAt: string
}

const RULES_EXAMPLE = '[{"modality":"CT","fromHour":18,"toHour":8},{"priority":"STAT"}]'

const toDays = (v: string): number | null => (v.trim() === '' ? null : Number(v))

/** Per-hospital image storage (KB note 13), set by us when the hospital is onboarded. */
export function ImageSettingsPanel({ tenants }: { tenants: TenantDto[] }) {
  const [tenantId, setTenantId] = useState('')
  const [form, setForm] = useState<ImageSettings | null>(null)
  const [cloudDays, setCloudDays] = useState('')
  const [localDays, setLocalDays] = useState('')
  const [rules, setRules] = useState('')
  const [gateways, setGateways] = useState<GatewayDto[]>([])
  const [gwName, setGwName] = useState('')
  const [gwLocation, setGwLocation] = useState('')
  const [created, setCreated] = useState<CreatedGateway | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!tenantId && tenants[0]) setTenantId(tenants[0].id)
  }, [tenants, tenantId])

  useEffect(() => {
    if (!tenantId) return
    setMessage(null)
    setError(null)
    setCreated(null)
    Promise.all([
      api<ImageSettings>(`/api/admin/tenants/${tenantId}/image-settings`),
      api<GatewayDto[]>(`/api/admin/gateways?tenantId=${tenantId}`),
    ])
      .then(([s, g]) => {
        setForm(s)
        setCloudDays(s.cloudRetentionDays == null ? '' : String(s.cloudRetentionDays))
        setLocalDays(s.edgeLocalRetentionDays == null ? '' : String(s.edgeLocalRetentionDays))
        setRules(s.autoSendRules ?? '')
        setGateways(g)
      })
      .catch((e) => setError(e instanceof Error ? e.message : 'Failed to load'))
  }, [tenantId])

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!form) return
    setError(null)
    setMessage(null)
    try {
      const saved = await api<ImageSettings>(`/api/admin/tenants/${tenantId}/image-settings`, {
        method: 'PUT',
        body: JSON.stringify({
          ...form,
          autoSendRules: rules.trim() || null,
          cloudRetentionDays: toDays(cloudDays),
          edgeLocalRetentionDays: toDays(localDays),
        }),
      })
      setForm(saved)
      setRules(saved.autoSendRules ?? '')
      setMessage('Saved. The hospital server picks up the change within a minute.')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Save failed')
    }
  }

  async function addGateway(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const res = await api<CreatedGateway>('/api/admin/gateways', {
        method: 'POST',
        body: JSON.stringify({ tenantId, name: gwName, location: gwLocation || null, gatewayRole: 'PRIMARY' }),
      })
      setCreated(res)
      setGwName('')
      setGwLocation('')
      setGateways(await api<GatewayDto[]>(`/api/admin/gateways?tenantId=${tenantId}`))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add the hospital server')
    }
  }

  const archiveWithRetention = form?.edgeStorageMode === 'ARCHIVE' && localDays.trim() !== ''

  return (
    <div className="panel">
      <h2>Image storage</h2>
      <p className="muted">Where each hospital&apos;s images are kept, and when they go to the cloud.</p>
      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}
      <div className="field">
        <label htmlFor="img-tenant">Hospital</label>
        <select id="img-tenant" value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
          {tenants.map((t) => <option key={t.id} value={t.id}>{t.code} · {t.name}</option>)}
        </select>
      </div>

      {form && (
        <form className="image-settings" onSubmit={save}>
          <div className="field">
            <label htmlFor="img-upload">Send to the cloud</label>
            <select
              id="img-upload"
              value={form.uploadPolicy}
              onChange={(e) => setForm({ ...form, uploadPolicy: e.target.value as UploadPolicy })}
            >
              <option value="ALL">Every study</option>
              <option value="ON_DEMAND">Only when sent for remote reading, or matching a rule</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="img-rules">Automatic send rules (JSON, optional)</label>
            <textarea
              id="img-rules"
              rows={2}
              placeholder={RULES_EXAMPLE}
              value={rules}
              disabled={form.uploadPolicy === 'ALL'}
              onChange={(e) => setRules(e.target.value)}
            />
            <span className="muted hint">
              Any rule may match; all fields of a rule must match. Hours are local time, e.g. 18 to 8 is 6 pm to 8 am.
            </span>
          </div>
          <div className="field">
            <label htmlFor="img-priors">Prior studies sent with a study</label>
            <input
              id="img-priors"
              type="number"
              min={0}
              max={5}
              value={form.priorsToSend}
              onChange={(e) => setForm({ ...form, priorsToSend: Number(e.target.value) })}
            />
          </div>
          <div className="field">
            <label htmlFor="img-cloud-days">Keep the cloud copy for (days after the report is signed)</label>
            <input
              id="img-cloud-days"
              type="number"
              min={0}
              placeholder="Empty: keep"
              value={cloudDays}
              onChange={(e) => setCloudDays(e.target.value)}
            />
            <span className="muted hint">Removed only after the hospital server confirms it still has every image.</span>
          </div>
          <div className="field">
            <label htmlFor="img-mode">Long-term archive</label>
            <select
              id="img-mode"
              value={form.edgeStorageMode}
              onChange={(e) => setForm({ ...form, edgeStorageMode: e.target.value as EdgeStorageMode })}
            >
              <option value="ARCHIVE">Our hospital server</option>
              <option value="CACHE">The hospital&apos;s own PACS (our server is a cache)</option>
            </select>
          </div>
          <div className="field">
            <label htmlFor="img-local-days">Hospital server keeps studies for (days)</label>
            <input
              id="img-local-days"
              type="number"
              min={0}
              placeholder="Empty: keep for good"
              value={localDays}
              onChange={(e) => setLocalDays(e.target.value)}
            />
            {archiveWithRetention && (
              <span className="error hint">
                The hospital server is the archive: studies older than this are deleted for good unless they are in the
                cloud or the hospital&apos;s PACS.
              </span>
            )}
          </div>
          <button className="btn" type="submit">Save image settings</button>
        </form>
      )}

      <h3>Hospital servers</h3>
      <table className="table">
        <thead><tr><th>Name</th><th>Image service address</th><th>Enrolled</th><th>Last heartbeat</th></tr></thead>
        <tbody>
          {gateways.map((g) => (
            <tr key={g.id}>
              <td>{g.name}{g.location ? <span className="muted"> · {g.location}</span> : null}{!g.active && ' (disabled)'}</td>
              <td className="mono">{g.lanUrl || '—'}</td>
              <td>{g.enrolled ? 'yes' : 'no'}</td>
              <td>{g.lastHeartbeatAt ? new Date(g.lastHeartbeatAt).toLocaleString() : '—'}</td>
            </tr>
          ))}
          {gateways.length === 0 && <tr><td colSpan={4} className="muted">No hospital server yet.</td></tr>}
        </tbody>
      </table>
      <form className="row" onSubmit={addGateway}>
        <input placeholder="Server name" value={gwName} onChange={(e) => setGwName(e.target.value)} required />
        <input placeholder="Location (e.g. Radiology server room)" value={gwLocation} onChange={(e) => setGwLocation(e.target.value)} />
        <button className="btn" type="submit">Add hospital server</button>
      </form>
      {created && (
        <div className="success">
          Enrolment token for <strong>{created.gateway.name}</strong> (shown once, valid until{' '}
          {new Date(created.enrollmentExpiresAt).toLocaleString()}):
          <pre className="mono enroll-token">{created.enrollmentToken}</pre>
        </div>
      )}
    </div>
  )
}
