import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './auth'
import { RequireAuth } from './RequireAuth'
import { AdminPage } from './pages/AdminPage'
import { AppShell } from './pages/AppShell'
import { DispatchPage } from './pages/DispatchPage'
import { HospitalPage } from './pages/HospitalPage'
import { LoginPage } from './pages/LoginPage'
import PatientSharePublicPage from './pages/PatientSharePublicPage'
import { ReportEditorPage } from './pages/ReportEditorPage'
import ShareSlipPage from './pages/ShareSlipPage'
import { SharedReportPage } from './pages/SharedReportPage'
import { WorklistPage } from './pages/WorklistPage'

// Defer Cornerstone / VTK (and xmlbuilder2) until the viewer route is opened
const StudyViewerPage = lazy(() =>
  import('./pages/StudyViewerPage').then((m) => ({ default: m.StudyViewerPage })),
)

export default function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/s/:token" element={<PatientSharePublicPage />} />
          <Route
            path="/s/:token/studies/:id"
            element={
              <Suspense fallback={<div className="page"><p className="muted">Loading viewer…</p></div>}>
                <StudyViewerPage shareMode />
              </Suspense>
            }
          />
          <Route element={<RequireAuth />}>
            <Route element={<RequireAuth roles={['HOSPITAL_USER', 'ADMIN']} />}>
              <Route path="/hospital/reports/:studyId" element={<SharedReportPage />} />
            </Route>
            <Route element={<AppShell />}>
              <Route element={<RequireAuth roles={['RADIOLOGIST', 'ADMIN']} />}>
                <Route path="/worklist" element={<WorklistPage />} />
                <Route
                  path="/studies/:id"
                  element={
                    <Suspense fallback={<div className="page"><p className="muted">Loading viewer…</p></div>}>
                      <StudyViewerPage />
                    </Suspense>
                  }
                />
                <Route path="/studies/:id/report" element={<ReportEditorPage />} />
              </Route>
              <Route element={<RequireAuth roles={['HOSPITAL_USER', 'ADMIN', 'RADIOLOGIST']} />}>
                <Route path="/shares/slip" element={<ShareSlipPage />} />
              </Route>
              <Route element={<RequireAuth roles={['HOSPITAL_USER', 'ADMIN']} />}>
                <Route path="/hospital" element={<HospitalPage />} />
                <Route path="/dispatch" element={<DispatchPage />} />
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
