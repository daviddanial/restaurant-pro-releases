import type { StudyListItem } from '../types'

const HELD: Record<string, string> = {
  NEEDS_CHECK: 'Waiting for a check (Reception)',
  SUGGESTED: 'Waiting for a check (Reception)',
  UNMATCHED: 'No matching order (Reception)',
}

/** Small warnings from the order link (KB note 15 §5–6), shown next to the patient name. */
export function OrderFlags({ study }: { study: StudyListItem }) {
  const held = study.matchState ? HELD[study.matchState] : undefined
  return (
    <>
      {study.nameDiffers && (
        <span
          className="flag-warn"
          title={`Name typed at the scanner: ${study.scannerPatientName ?? ''}. The order's name is shown.`}
        >
          Name differs at scanner
        </span>
      )}
      {held && <span className="flag-warn">{held}</span>}
      {study.imagesAfterReportAt && (
        <span className="flag-warn" title={new Date(study.imagesAfterReportAt).toLocaleString()}>
          New images after the report
        </span>
      )}
    </>
  )
}
