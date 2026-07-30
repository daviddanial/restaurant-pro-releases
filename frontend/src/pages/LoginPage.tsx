import { FormEvent, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('rad@telerad.local')
  const [password, setPassword] = useState('password')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  if (user) return <Navigate to={homeForRole(user.role)} replace />

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setLoading(true)
    setError(null)
    try {
      const result = await login(email.trim(), password.trim())
      navigate(homeForRole(result.role))
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={onSubmit}>
        <h1>Tele<span style={{ color: 'var(--accent)' }}>Rad</span></h1>
        <p>Secure teleradiology worklist, viewer, and report delivery.</p>
        {error && <div className="error">{error}</div>}
        <div className="field">
          <label>Email</label>
          <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        </div>
        <div className="field">
          <label>Password</label>
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" />
        </div>
        <button className="btn" disabled={loading} type="submit">
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
        <div className="hint">
          Demo accounts (password: <code>password</code>)<br />
          Radiologist: rad@telerad.local<br />
          Hospital: staff@demohosp.local<br />
          Admin: admin@telerad.local
        </div>
      </form>
    </div>
  )
}

export function homeForRole(role: string) {
  if (role === 'HOSPITAL_USER') return '/hospital'
  if (role === 'ADMIN') return '/admin'
  return '/worklist'
}
