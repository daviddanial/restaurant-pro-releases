import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { RequireAuth } from '../RequireAuth'
import type { AuthUser, Role } from '../types'

const useAuthMock = vi.fn()

vi.mock('../auth', () => ({
  useAuth: () => useAuthMock(),
}))

function user(role: Role): AuthUser {
  return {
    accessToken: 't',
    userId: '1',
    email: 'u@t.local',
    fullName: 'U',
    role,
    tenantId: role === 'HOSPITAL_USER' ? 'h1' : null,
    tenantCode: role === 'HOSPITAL_USER' ? 'DEMO' : null,
    tenantName: role === 'HOSPITAL_USER' ? 'Demo' : null,
  }
}

function renderWithAuth(initialPath: string, roles?: Role[]) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route path="/login" element={<div>login-page</div>} />
        <Route path="/hospital" element={<div>hospital-home</div>} />
        <Route path="/admin" element={<div>admin-home</div>} />
        <Route path="/worklist" element={<div>worklist-home</div>} />
        <Route element={<RequireAuth roles={roles} />}>
          <Route path="/protected" element={<div>protected-ok</div>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('RequireAuth', () => {
  it('redirects unauthenticated users to login', () => {
    useAuthMock.mockReturnValue({ user: null })
    renderWithAuth('/protected')
    expect(screen.getByText('login-page')).toBeInTheDocument()
  })

  it('renders outlet when authenticated without role restriction', () => {
    useAuthMock.mockReturnValue({ user: user('RADIOLOGIST') })
    renderWithAuth('/protected')
    expect(screen.getByText('protected-ok')).toBeInTheDocument()
  })

  it('allows matching role', () => {
    useAuthMock.mockReturnValue({ user: user('ADMIN') })
    renderWithAuth('/protected', ['ADMIN'])
    expect(screen.getByText('protected-ok')).toBeInTheDocument()
  })

  it('redirects hospital user to /hospital on role mismatch', () => {
    useAuthMock.mockReturnValue({ user: user('HOSPITAL_USER') })
    renderWithAuth('/protected', ['ADMIN'])
    expect(screen.getByText('hospital-home')).toBeInTheDocument()
  })

  it('redirects admin to /admin on role mismatch', () => {
    useAuthMock.mockReturnValue({ user: user('ADMIN') })
    renderWithAuth('/protected', ['RADIOLOGIST'])
    expect(screen.getByText('admin-home')).toBeInTheDocument()
  })

  it('redirects radiologist to /worklist on role mismatch', () => {
    useAuthMock.mockReturnValue({ user: user('RADIOLOGIST') })
    renderWithAuth('/protected', ['ADMIN'])
    expect(screen.getByText('worklist-home')).toBeInTheDocument()
  })
})
