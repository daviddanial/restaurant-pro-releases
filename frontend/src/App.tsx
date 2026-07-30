import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth'
import { RequireAuth } from './RequireAuth'
import { AdminPage } from './pages/AdminPage'
import { AppShell } from './pages/AppShell'
import { HospitalPage } from './pages/HospitalPage'
import { LoginPage } from './pages/LoginPage'
import { StudyViewerPage } from './pages/StudyViewerPage'
import { WorklistPage } from './pages/WorklistPage'

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<RequireAuth />}>
            <Route element={<AppShell />}>
              <Route element={<RequireAuth roles={['RADIOLOGIST', 'ADMIN']} />}>
                <Route path="/worklist" element={<WorklistPage />} />
                <Route path="/studies/:id" element={<StudyViewerPage />} />
              </Route>
              <Route element={<RequireAuth roles={['HOSPITAL_USER', 'ADMIN']} />}>
                <Route path="/hospital" element={<HospitalPage />} />
              </Route>
              <Route element={<RequireAuth roles={['ADMIN']} />}>
                <Route path="/admin" element={<AdminPage />} />
              </Route>
            </Route>
          </Route>
          <Route path="*" element={<Navigate to="/login" replace />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  )
}
