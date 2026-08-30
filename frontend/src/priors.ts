export function formatStudyDate(value: string | null): string {
  if (!value) return 'undated'
  return new Date(value).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

/** Gap between a prior study and the current one, phrased the way a radiologist would say it. */
export function formatInterval(priorDate: string | null, currentDate: string | null): string | null {
  if (!priorDate || !currentDate) return null
  const days = Math.round(
    (new Date(currentDate).getTime() - new Date(priorDate).getTime()) / 86_400_000,
  )
  if (days < 1) return null
  if (days < 45) return `${days} day${days === 1 ? '' : 's'} earlier`
  const months = Math.round(days / 30)
  if (months < 24) return `${months} month${months === 1 ? '' : 's'} earlier`
  const years = Math.round(days / 365)
  return `${years} year${years === 1 ? '' : 's'} earlier`
}

type MatchableSeries = {
  seriesDescription: string | null
  seriesNumber: number | null
}

const normalize = (value: string | null) =>
  (value ?? '').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()

/**
 * The prior series that pairs with the one just selected on the current study, by
 * description first and acquisition order second. Returns -1 when neither matches, so the
 * caller can leave the prior pane alone rather than hang an unrelated sequence.
 */
export function matchPriorSeries(
  current: MatchableSeries | undefined,
  priorSeries: MatchableSeries[],
): number {
  if (!current || priorSeries.length === 0) return -1

  const target = normalize(current.seriesDescription)
  if (target) {
    const byDescription = priorSeries.findIndex((s) => normalize(s.seriesDescription) === target)
    if (byDescription >= 0) return byDescription
  }
  if (current.seriesNumber != null) {
    const byNumber = priorSeries.findIndex((s) => s.seriesNumber === current.seriesNumber)
    if (byNumber >= 0) return byNumber
  }
  return -1
}

/**
 * The prior a hanging protocol should open by default: the most recent one matching the
 * current modality. The API already sorts same-modality studies first, newest first.
 */
export function defaultPrior<T extends { id: string; modality: string | null }>(
  priors: T[],
  currentModality: string | null,
): T | null {
  return priors.find((p) => p.modality === currentModality) ?? null
}
