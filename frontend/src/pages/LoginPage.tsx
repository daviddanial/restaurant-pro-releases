import { FormEvent, useState } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth'

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const [email, setEmail] = useState('rad@telerad.local')
  const [password, setPassword] = useState('password')
  const [showPassword, setShowPassword] = useState(false)
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
      <div style={{ width: 'min(400px, 100%)' }}>
        <div className="login-brand">
          <span className="brand-mark">T</span>
          <span>Tele<span style={{ color: 'var(--accent)' }}>Rad</span></span>
        </div>
        <form className="login-card" onSubmit={onSubmit}>
          <h1>Sign in</h1>
          <p>Continue to the imaging network worklist and reporting workspace.</p>
          {error && <div className="error">{error}</div>}
          <div className="field">
            <label>Email</label>
            <input
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
              placeholder="you@hospital.local"
            />
          </div>
          <div className="field">
            <label>Password</label>
            <div style={{ position: 'relative' }}>
              <input
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                style={{ paddingRight: '2.5rem' }}
              />
              <button
                type="button"
                className="btn secondary"
                onClick={() => setShowPassword((v) => !v)}
                style={{
                  position: 'absolute',
                  right: 4,
                  top: 4,
                  bottom: 4,
                  padding: '0 0.55rem',
                  fontSize: '0.7rem',
                  border: 'none',
                  background: 'transparent',
                  color: 'var(--muted)',
                }}
              >
                {showPassword ? 'Hide' : 'Show'}
              </button>
            </div>
          </div>
          <button className="btn" disabled={loading} type="submit" style={{ width: '100%', marginTop: '0.25rem' }}>
            {loading ? 'Signing in…' : 'Log In'}
          </button>
          <div className="hint">
            Demo (password: <code>password</code>)<br />
            Radiologist: rad@telerad.local · Hospital: staff@demohosp.local · Admin: admin@telerad.local
          </div>
        </form>
        <div className="login-footer">Secured platform — access controlled and audited</div>
      </div>
    </div>
  )
}

export function homeForRole(role: string) {
  if (role === 'HOSPITAL_USER') return '/hospital'
  if (role === 'ADMIN') return '/admin'
  return '/worklist'
}
