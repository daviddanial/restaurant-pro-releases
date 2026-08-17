import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { LoginPage, homeForRole } from '../pages/LoginPage'
import type { AuthUser } from '../types'

const loginMock = vi.fn()
const useAuthMock = vi.fn()

vi.mock('../auth', () => ({
  useAuth: () => useAuthMock(),
}))

const navigateMock = vi.fn()
vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return {
    ...actual,
    useNavigate: () => navigateMock,
  }
})

describe('homeForRole', () => {
  it('maps roles to home paths', () => {
    expect(homeForRole('HOSPITAL_USER')).toBe('/hospital')
    expect(homeForRole('ADMIN')).toBe('/admin')
    expect(homeForRole('RADIOLOGIST')).toBe('/worklist')
    expect(homeForRole('UNKNOWN')).toBe('/worklist')
  })
})

describe('LoginPage', () => {
  beforeEach(() => {
    loginMock.mockReset()
    navigateMock.mockReset()
    useAuthMock.mockReturnValue({ user: null, login: loginMock, logout: vi.fn() })
  })

  it('redirects when already logged in', () => {
    useAuthMock.mockReturnValue({
      user: {
        accessToken: 't',
        userId: '1',
        email: 'admin@telerad.local',
        fullName: 'Admin',
        role: 'ADMIN',
        tenantId: null,
        tenantCode: null,
        tenantName: null,
      } satisfies AuthUser,
      login: loginMock,
      logout: vi.fn(),
    })
    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    )
    expect(screen.queryByRole('button', { name: /log in/i })).not.toBeInTheDocument()
  })

  it('submits credentials and navigates on success', async () => {
    const user = userEvent.setup()
    loginMock.mockResolvedValue({
      accessToken: 't',
      userId: '1',
      email: 'rad@telerad.local',
      fullName: 'Dr',
      role: 'RADIOLOGIST',
      tenantId: null,
      tenantCode: null,
      tenantName: null,
    } satisfies AuthUser)

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    )

    // Defaults are already rad@telerad.local / password; submit as-is
    await user.click(screen.getByRole('button', { name: /log in/i }))

    await waitFor(() => {
      expect(loginMock).toHaveBeenCalledWith('rad@telerad.local', 'password')
      expect(navigateMock).toHaveBeenCalledWith('/worklist')
    })
  })

  it('shows error message on failure', async () => {
    const user = userEvent.setup()
    loginMock.mockRejectedValue(new Error('Invalid credentials'))

    render(
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>,
    )
    await user.click(screen.getByRole('button', { name: /log in/i }))
    expect(await screen.findByText('Invalid credentials')).toBeInTheDocument()
  })
})
