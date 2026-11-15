import { FormEvent, useEffect, useState } from 'react'
import { api } from '../api'
import { ImageSettingsPanel } from '../components/ImageSettingsPanel'
import { OrderSettingsPanel } from '../components/OrderSettingsPanel'
import { ProceduresPanel } from '../components/ProceduresPanel'
import type { AuditEvent, DicomNodeDto, NotificationEventDto, TenantDto, UserDto } from '../types'

export function AdminPage() {
  const [tenants, setTenants] = useState<TenantDto[]>([])
  const [nodes, setNodes] = useState<DicomNodeDto[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [audit, setAudit] = useState<AuditEvent[]>([])
  const [notifications, setNotifications] = useState<NotificationEventDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)

  const [tenantCode, setTenantCode] = useState('')
  const [tenantName, setTenantName] = useState('')
  const [aeTitle, setAeTitle] = useState('')
  const [aeTenantId, setAeTenantId] = useState('')
  const [userEmail, setUserEmail] = useState('')
  const [userName, setUserName] = useState('')
  const [userRole, setUserRole] = useState('HOSPITAL_USER')
  const [userTenantId, setUserTenantId] = useState('')

  async function load() {
    const [t, n, u, a, notes] = await Promise.all([
      api<TenantDto[]>('/api/admin/tenants'),
      api<DicomNodeDto[]>('/api/admin/dicom-nodes'),
      api<UserDto[]>('/api/admin/users'),
      api<AuditEvent[]>('/api/admin/audit'),
      api<NotificationEventDto[]>('/api/admin/notifications'),
    ])
    setTenants(t)
    setNodes(n)
    setUsers(u)
    setAudit(a)
    setNotifications(notes)
    if (!aeTenantId && t[0]) setAeTenantId(t[0].id)
    if (!userTenantId && t[0]) setUserTenantId(t[0].id)
  }

  useEffect(() => {
    load().catch((err) => setError(err.message))
  }, [])

  async function createTenant(e: FormEvent) {
    e.preventDefault()
    await api('/api/admin/tenants', { method: 'POST', body: JSON.stringify({ code: tenantCode, name: tenantName }) })
    setTenantCode('')
    setTenantName('')
    setMessage('Hospital created')
    await load()
  }

  async function createNode(e: FormEvent) {
    e.preventDefault()
    await api('/api/admin/dicom-nodes', {
      method: 'POST',
      body: JSON.stringify({
        tenantId: aeTenantId,
        aeTitle,
        host: null,
        port: null,
        direction: 'IN',
        allowedIp: null,
      }),
    })
    setAeTitle('')
    setMessage('DICOM AE registered')
    await load()
  }

  async function createUser(e: FormEvent) {
    e.preventDefault()
    await api('/api/admin/users', {
      method: 'POST',
      body: JSON.stringify({
        email: userEmail,
        password: 'password',
        fullName: userName,
        role: userRole,
        tenantId: userRole === 'HOSPITAL_USER' ? userTenantId : null,
      }),
    })
    setUserEmail('')
    setUserName('')
    setMessage('User created (password: password)')
    await load()
  }

  return (
    <div className="page stack">
      <div className="page-header">
        <div>
          <h1>Admin</h1>
          <p className="subtitle">Tenants, DICOM nodes, users, notifications, and audit</p>
        </div>
      </div>
      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}

      <div className="panel">
        <h2>Hospitals</h2>
        <form className="row" onSubmit={createTenant}>
          <input placeholder="Code" value={tenantCode} onChange={(e) => setTenantCode(e.target.value)} required />
          <input placeholder="Name" value={tenantName} onChange={(e) => setTenantName(e.target.value)} required />
          <button className="btn" type="submit">Add</button>
        </form>
        <table className="table">
          <thead><tr><th>Code</th><th>Name</th><th>Active</th></tr></thead>
          <tbody>
            {tenants.map((t) => (
              <tr key={t.id}><td>{t.code}</td><td>{t.name}</td><td>{t.active ? 'yes' : 'no'}</td></tr>
            ))}
          </tbody>
        </table>
      </div>

      <ImageSettingsPanel tenants={tenants} />
      <OrderSettingsPanel tenants={tenants} />
      <ProceduresPanel />

      <div className="panel">
        <h2>DICOM AE titles (C-STORE allowlist)</h2>
        <form className="row" onSubmit={createNode}>
          <select value={aeTenantId} onChange={(e) => setAeTenantId(e.target.value)}>
            {tenants.map((t) => <option key={t.id} value={t.id}>{t.code}</option>)}
          </select>
          <input placeholder="AE Title" value={aeTitle} onChange={(e) => setAeTitle(e.target.value)} required maxLength={16} />
          <button className="btn" type="submit">Register AE</button>
        </form>
        <table className="table">
          <thead><tr><th>AE Title</th><th>Hospital</th><th>Direction</th><th>IP allow</th></tr></thead>
          <tbody>
            {nodes.map((n) => (
              <tr key={n.id}>
                <td>{n.aeTitle}</td>
                <td>{tenants.find((t) => t.id === n.tenantId)?.code || n.tenantId}</td>
                <td>{n.direction}</td>
                <td>{n.allowedIp || 'any'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h2>Users</h2>
        <form className="row" onSubmit={createUser}>
          <input placeholder="Email" value={userEmail} onChange={(e) => setUserEmail(e.target.value)} required />
          <input placeholder="Full name" value={userName} onChange={(e) => setUserName(e.target.value)} required />
          <select value={userRole} onChange={(e) => setUserRole(e.target.value)}>
            <option value="HOSPITAL_USER">HOSPITAL_USER</option>
            <option value="RADIOLOGIST">RADIOLOGIST</option>
            <option value="ADMIN">ADMIN</option>
          </select>
          {userRole === 'HOSPITAL_USER' && (
            <select value={userTenantId} onChange={(e) => setUserTenantId(e.target.value)}>
              {tenants.map((t) => <option key={t.id} value={t.id}>{t.code}</option>)}
            </select>
          )}
          <button className="btn" type="submit">Add user</button>
        </form>
        <table className="table">
          <thead><tr><th>Email</th><th>Name</th><th>Role</th><th>Tenant</th></tr></thead>
          <tbody>
            {users.map((u) => (
              <tr key={u.id}>
                <td>{u.email}</td>
                <td>{u.fullName}</td>
                <td>{u.role}</td>
                <td>{tenants.find((t) => t.id === u.tenantId)?.code || '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h2>Notifications (email / webhook)</h2>
        <table className="table">
          <thead><tr><th>When</th><th>Event</th><th>Channel</th><th>Recipient</th><th>Status</th></tr></thead>
          <tbody>
            {notifications.map((n) => (
              <tr key={n.id}>
                <td>{new Date(n.createdAt).toLocaleString()}</td>
                <td>{n.eventType}</td>
                <td>{n.channel}</td>
                <td>{n.recipient || '—'}</td>
                <td>
                  <span className={`badge ${n.status === 'FAILED' ? 'stat' : n.status === 'SENT' ? 'ready' : ''}`}>
                    {n.status}
                  </span>
                  {n.errorMessage && <div className="muted" style={{ fontSize: '0.75rem' }}>{n.errorMessage}</div>}
                </td>
              </tr>
            ))}
            {notifications.length === 0 && (
              <tr><td colSpan={5} className="muted">No notification events yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="panel">
        <h2>Recent audit</h2>
        <table className="table">
          <thead><tr><th>When</th><th>Action</th><th>Entity</th><th>Detail</th></tr></thead>
          <tbody>
            {audit.map((a) => (
              <tr key={a.id}>
                <td>{new Date(a.createdAt).toLocaleString()}</td>
                <td>{a.action}</td>
                <td>{a.entityType} {a.entityId}</td>
                <td>{a.detail}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
