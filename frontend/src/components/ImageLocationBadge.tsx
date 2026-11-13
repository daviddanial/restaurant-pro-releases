import type { StudyListItem } from '../types'

/** Where a study's images are, in words (KB note 13). */
export function imageLocationLabel(s: Pick<StudyListItem, 'cloudStatus' | 'onHospitalServer'>): string {
  switch (s.cloudStatus) {
    case 'REQUESTED':
      return 'Sending to cloud'
    case 'EDGE_ONLY':
      return s.onHospitalServer ? 'Hospital server' : 'Not stored'
    default:
      return s.onHospitalServer ? 'Hospital + cloud' : 'Cloud'
  }
}

export function ImageLocationBadge({ study }: { study: Pick<StudyListItem, 'cloudStatus' | 'onHospitalServer'> }) {
  const cls = study.cloudStatus === 'EDGE_ONLY'
    ? 'location-hospital'
    : study.cloudStatus === 'REQUESTED' ? 'location-sending' : ''
  const title = study.cloudStatus === 'EDGE_ONLY'
    ? 'Images are only on the hospital server: readable inside the hospital, or after sending to the cloud'
    : study.cloudStatus === 'REQUESTED'
      ? 'The hospital server is uploading the images'
      : 'Images can be read from anywhere'
  return <span className={`badge ${cls}`} title={title}>{imageLocationLabel(study)}</span>
}
