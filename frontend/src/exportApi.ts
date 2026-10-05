import { useCallback, useEffect, useRef, useState } from 'react'
import { api, apiBlob } from './api'

export type ExportScope = 'IMAGE' | 'SERIES' | 'STUDY'

export type ExportJobDto = {
  id: string
  studyId: string
  scope: ExportScope
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED'
  totalObjects: number
  completedObjects: number
  errorMessage: string | null
  downloadReady: boolean
}

export type StartExportBody = {
  scope: ExportScope
  seriesInstanceUid?: string | null
  sopInstanceUid?: string | null
}

export async function startDicomExport(studyId: string, body: StartExportBody): Promise<ExportJobDto> {
  return api<ExportJobDto>(`/api/studies/${studyId}/export`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export async function getExportJob(jobId: string): Promise<ExportJobDto> {
  return api<ExportJobDto>(`/api/exports/${jobId}`)
}

export async function downloadExportZip(jobId: string): Promise<Blob> {
  return apiBlob(`/api/exports/${jobId}/download`)
}

/** Poll an export job until DONE/FAILED; calls onProgress each tick. */
export function useExportJobPoller() {
  const [job, setJob] = useState<ExportJobDto | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const timer = useRef<number | null>(null)

  const clear = useCallback(() => {
    if (timer.current != null) {
      window.clearInterval(timer.current)
      timer.current = null
    }
  }, [])

  useEffect(() => () => clear(), [clear])

  const start = useCallback(async (studyId: string, body: StartExportBody, filename: string) => {
    clear()
    setError(null)
    setBusy(true)
    setJob(null)
    try {
      const created = await startDicomExport(studyId, body)
      setJob(created)

      const finish = async (done: ExportJobDto) => {
        clear()
        setJob(done)
        setBusy(false)
        if (done.status === 'FAILED') {
          setError(done.errorMessage || 'Export failed')
          return
        }
        const blob = await downloadExportZip(done.id)
        const { downloadBlob } = await import('./viewerCapture')
        downloadBlob(blob, filename)
      }

      if (created.status === 'DONE' && created.downloadReady) {
        await finish(created)
        return
      }

      timer.current = window.setInterval(async () => {
        try {
          const next = await getExportJob(created.id)
          setJob(next)
          if (next.status === 'DONE' || next.status === 'FAILED') {
            await finish(next)
          }
        } catch (err) {
          clear()
          setBusy(false)
          setError(err instanceof Error ? err.message : 'Export poll failed')
        }
      }, 700)
    } catch (err) {
      setBusy(false)
      setError(err instanceof Error ? err.message : 'Export failed')
    }
  }, [clear])

  const dismiss = useCallback(() => {
    clear()
    setBusy(false)
    setJob(null)
    setError(null)
  }, [clear])

  return { job, busy, error, start, dismiss }
}
