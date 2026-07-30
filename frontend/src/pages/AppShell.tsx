import { Link, Outlet } from 'react-router-dom'
import { useAuth } from '../auth'
import { homeForRole } from './LoginPage'

export function AppShell() {
  const { user, logout } = useAuth()
  if (!user) return null

  return (
    <div className="app-shell">
      <header className="topbar">
        <Link to={homeForRole(user.role)} className="brand">
          Tele<span>Rad</span>
        </Link>
        <div className="topbar-meta">
          <span>{user.fullName}</span>
          <span className="badge">{user.role.replace('_', ' ')}</span>
          {user.tenantName && <span>{user.tenantName}</span>}
          <button className="btn secondary" onClick={logout} type="button">Sign out</button>
        </div>
      </header>
      <Outlet />
    </div>
  )
}
