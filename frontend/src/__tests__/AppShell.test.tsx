import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { AppShell } from '../pages/AppShell'
import type { AuthUser } from '../types'

const logoutMock = vi.fn()
const useAuthMock = vi.fn()

vi.mock('../auth', () => ({
  useAuth: () => useAuthMock(),
}))

describe('AppShell', () => {
  it('renders nothing without user', () => {
    useAuthMock.mockReturnValue({ user: null, logout: logoutMock })
    const { container } = render(
      <MemoryRouter>
        <AppShell />
      </MemoryRouter>,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('shows user meta and signs out', async () => {
    const user = userEvent.setup()
    useAuthMock.mockReturnValue({
      user: {
        accessToken: 't',
        userId: '1',
        email: 'staff@demohosp.local',
        fullName: 'Hospital Staff',
        role: 'HOSPITAL_USER',
        tenantId: 'h1',
        tenantCode: 'DEMO',
        tenantName: 'Demo Hospital',
      } satisfies AuthUser,
      logout: logoutMock,
    })
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/" element={<div>child</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByText('Hospital Staff')).toBeInTheDocument()
    expect(screen.getByText('Demo Hospital')).toBeInTheDocument()
    expect(screen.getByText('Dispatch & Triage')).toBeInTheDocument()
    expect(screen.getByText('child')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /sign out/i }))
    expect(logoutMock).toHaveBeenCalled()
  })
})
