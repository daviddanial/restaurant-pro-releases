import { Link, NavLink, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from '../auth'
import { homeForRole } from './LoginPage'

export function AppShell() {
  const { user, logout } = useAuth()
  const location = useLocation()
  if (!user) return null

  const initials = user.fullName
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((p) => p[0]?.toUpperCase() ?? '')
    .join('')

  const onReporting = location.pathname.startsWith('/studies/')
  const canRead = user.role === 'RADIOLOGIST' || user.role === 'ADMIN'

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link to={homeForRole(user.role)} className="brand">
          <span className="brand-mark">T</span>
          <span>Tele<span>Rad</span></span>
        </Link>

        <nav className="topbar-nav" aria-label="Primary">
          {(user.role === 'HOSPITAL_USER' || user.role === 'ADMIN') && (
            <NavLink to="/hospital" className={({ isActive }) => (isActive ? 'active' : undefined)}>
              Hospital Portal
            </NavLink>
          )}
          {canRead && (
            <NavLink
              to="/worklist"
              end
              className={({ isActive }) => (isActive && !onReporting ? 'active' : undefined)}
            >
              Worklist
            </NavLink>
          )}
          {canRead && (
            <span className={onReporting ? 'active nav-current' : 'nav-current muted'} aria-current={onReporting ? 'page' : undefined}>
              Reporting
            </span>
          )}
          {user.role === 'ADMIN' && (
            <NavLink to="/admin" className={({ isActive }) => (isActive ? 'active' : undefined)}>
              Admin
            </NavLink>
          )}
        </nav>

        <div className="topbar-meta">
          <span className="avatar" title={user.fullName}>{initials || 'U'}</span>
          <span className="topbar-name">{user.fullName}</span>
          <span className="badge">{user.role.replace('_', ' ')}</span>
          {user.tenantName && <span className="muted topbar-tenant">{user.tenantName}</span>}
          <button className="btn secondary" onClick={logout} type="button">Sign out</button>
        </div>
      </header>
      <Outlet />
    </div>
  )
}
