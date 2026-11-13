/**
 * A copy of the report being written, kept in this browser so text typed while the internet is
 * down survives a closed tab or a reload (KB note 13 §5). The server copy stays the real one;
 * this is only offered back when it differs from it.
 */
export interface DraftBackup {
  findings: string
  impression: string
  critical: boolean
  savedAt: string
}

const key = (studyId: string) => `telerad_report_backup:${studyId}`

export function saveDraftBackup(studyId: string, draft: Omit<DraftBackup, 'savedAt'>) {
  try {
    localStorage.setItem(key(studyId), JSON.stringify({ ...draft, savedAt: new Date().toISOString() }))
  } catch {
    /* storage full or blocked: the server autosave still runs */
  }
}

export function loadDraftBackup(studyId: string): DraftBackup | null {
  try {
    const raw = localStorage.getItem(key(studyId))
    if (!raw) return null
    const d = JSON.parse(raw) as DraftBackup
    if (typeof d.findings !== 'string' || typeof d.impression !== 'string') return null
    return { findings: d.findings, impression: d.impression, critical: !!d.critical, savedAt: String(d.savedAt) }
  } catch {
    return null
  }
}

export function clearDraftBackup(studyId: string) {
  try {
    localStorage.removeItem(key(studyId))
  } catch {
    /* ignore */
  }
}

export function sameDraft(a: Pick<DraftBackup, 'findings' | 'impression' | 'critical'>,
  b: Pick<DraftBackup, 'findings' | 'impression' | 'critical'>) {
  return a.findings === b.findings && a.impression === b.impression && a.critical === b.critical
}
