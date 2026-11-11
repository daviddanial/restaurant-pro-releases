import { describe, expect, it } from 'vitest'
import {
  locationLabel,
  measurementSummary,
  measurementsReportText,
  serializeAnnotation,
  type SavedMeasurement,
} from '../studyAnnotations'

describe('measurementSummary', () => {
  it('formats each tool', () => {
    expect(measurementSummary('Length', { t: { length: 23.44, unit: 'mm' } })).toBe('Length 23.4 mm')
    expect(measurementSummary('Angle', { t: { angle: 98.12 } })).toBe('Angle 98.1°')
    expect(measurementSummary('CobbAngle', { t: { angle: 19.84 } })).toBe('Cobb angle 19.8°')
    expect(measurementSummary('StackProbe', { t: { value: 38, modalityUnit: 'HU' } })).toBe('Density 38 HU')
    expect(measurementSummary('StackProbe', { t: { value: 469, modalityUnit: 'raw' } })).toBe('Signal 469')
    expect(measurementSummary('EllipticalROI', { t: { area: 859, areaUnit: 'mm²', mean: 83.6, modalityUnit: '' } }))
      .toBe('Ellipse area 859 mm², mean 83.6')
    expect(measurementSummary('EllipticalROI', {
      t: { area: 1678.2, areaUnit: 'mm²', mean: 185.04, stdDev: 605.3, min: -1196, max: 2157, modalityUnit: 'HU' },
    })).toBe('Ellipse area 1678 mm², mean 185.0 HU, SD 605.3, min -1196 / max 2157 HU')
  })
  it('returns null until statistics exist', () => {
    expect(measurementSummary('Length', undefined)).toBeNull()
    expect(measurementSummary('Length', { t: { length: undefined } })).toBeNull()
    expect(measurementSummary('Unknown', { t: { length: 1 } })).toBeNull()
  })
})

describe('report helpers', () => {
  const row = (summary: string | null, createdAt: string, location = 'Axial, image 5'): SavedMeasurement => ({
    id: createdAt, seriesInstanceUid: 's', sopInstanceUid: 'i', frameNumber: 1, toolName: 'Length', data: '{}',
    summary, location, createdBy: 'u', createdByName: null, mine: true, createdAt, updatedAt: createdAt,
  })
  it('lists measurements in drawing order and skips ones without a value', () => {
    const text = measurementsReportText([
      row('Angle 98.1°', '2026-11-11T10:02:00Z'),
      row('Length 23.4 mm', '2026-11-11T10:01:00Z'),
      row(null, '2026-11-11T10:03:00Z'),
    ])
    expect(text).toBe('MEASUREMENTS:\n- Length 23.4 mm (Axial, image 5)\n- Angle 98.1° (Axial, image 5)')
    expect(measurementsReportText([])).toBe('')
  })
  it('labels the location', () => {
    expect(locationLabel('Liver_NC 2.00', 45, 1, false)).toBe('Liver_NC 2.00, image 45')
    expect(locationLabel(null, 1, 3, true)).toBe('Series, image 1, frame 3')
  })
  it('does not store the image URL', () => {
    const json = serializeAnnotation({
      metadata: { toolName: 'Length', referencedImageId: 'wadouri:https://host/x', FrameOfReferenceUID: 'f' },
      data: { handles: { points: [[0, 0, 0]] } },
    })
    expect(json).not.toContain('wadouri')
    expect(JSON.parse(json).metadata.FrameOfReferenceUID).toBe('f')
  })
})
