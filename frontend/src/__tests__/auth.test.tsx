import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AuthProvider, useAuth } from '../auth'
import type { AuthUser } from '../types'

vi.mock('../api', async () => {
  const actual = await vi.importActual<typeof import('../api')>('../api')
  return {
    ...actual,
    api: vi.fn(),
  }
})

import { api, loadAuth } from '../api'

function Probe() {
  const { user, login, logout } = useAuth()
  return (
    <div>
      <div data-testid="email">{user?.email ?? 'none'}</div>
      <div data-testid="role">{user?.role ?? 'none'}</div>
      <button type="button" onClick={() => login('rad@telerad.local', 'password')}>
        login
      </button>
      <button type="button" onClick={() => logout()}>
        logout
      </button>
    </div>
  )
}

const authUser: AuthUser = {
  accessToken: 'abc',
  userId: '1',
  email: 'rad@telerad.local',
  fullName: 'Dr Rad',
  role: 'RADIOLOGIST',
  tenantId: null,
  tenantCode: null,
  tenantName: null,
}

describe('AuthProvider', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.mocked(api).mockReset()
  })

  it('hydrates user from localStorage', () => {
    localStorage.setItem('telerad_auth', JSON.stringify(authUser))
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    expect(screen.getByTestId('email')).toHaveTextContent('rad@telerad.local')
  })

  it('login stores user and updates state', async () => {
    vi.mocked(api).mockResolvedValue(authUser)
    const user = userEvent.setup()
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    expect(screen.getByTestId('email')).toHaveTextContent('none')
    await user.click(screen.getByText('login'))
    await waitFor(() => expect(screen.getByTestId('email')).toHaveTextContent('rad@telerad.local'))
    expect(loadAuth()?.accessToken).toBe('abc')
    expect(api).toHaveBeenCalledWith(
      '/api/auth/login',
      expect.objectContaining({ method: 'POST' }),
      null,
    )
  })

  it('logout clears user', async () => {
    localStorage.setItem('telerad_auth', JSON.stringify(authUser))
    const user = userEvent.setup()
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    )
    await user.click(screen.getByText('logout'))
    expect(screen.getByTestId('email')).toHaveTextContent('none')
    expect(loadAuth()).toBeNull()
  })

  it('useAuth throws outside provider', () => {
    let caught: Error | undefined
    function Boom() {
      try {
        useAuth()
      } catch (e) {
        caught = e as Error
      }
      return null
    }
    render(<Boom />)
    expect(caught?.message).toBe('useAuth outside provider')
  })
})
