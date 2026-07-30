import { Navigate, Outlet } from 'react-router-dom'
import { useAuth } from './auth'
import type { Role } from './types'

export function RequireAuth({ roles }: { roles?: Role[] }) {
  const { user } = useAuth()
  if (!user) return <Navigate to="/login" replace />
  if (roles && !roles.includes(user.role)) {
    if (user.role === 'HOSPITAL_USER') return <Navigate to="/hospital" replace />
    if (user.role === 'ADMIN') return <Navigate to="/admin" replace />
    return <Navigate to="/worklist" replace />
  }
  return <Outlet />
}
