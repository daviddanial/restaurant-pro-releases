export type WlPreset = { label: string; center: number; width: number }

export const WL_PRESETS: Record<string, WlPreset[]> = {
  CT: [
    { label: 'Soft tissue', center: 40, width: 400 },
    { label: 'Lung', center: -600, width: 1500 },
    { label: 'Bone', center: 400, width: 1800 },
    { label: 'Brain', center: 40, width: 80 },
  ],
  XR: [
    { label: 'Default', center: 2048, width: 4096 },
    { label: 'High contrast', center: 2000, width: 2000 },
  ],
  MR: [
    { label: 'Wide', center: 1024, width: 2048 },
    { label: 'Narrow', center: 512, width: 512 },
  ],
  DEFAULT: [
    { label: 'Wide', center: 128, width: 512 },
    { label: 'Default', center: 128, width: 256 },
  ],
}

export function presetsForModality(modality?: string | null): WlPreset[] {
  const m = (modality || '').toUpperCase()
  if (m === 'CT') return WL_PRESETS.CT
  if (m === 'CR' || m === 'DX' || m === 'XR' || m === 'MG') return WL_PRESETS.XR
  if (m === 'MR') return WL_PRESETS.MR
  return WL_PRESETS.DEFAULT
}
