import { useEffect, useMemo, useRef, useState } from 'react'
import {
  REPORT_TEMPLATES,
  TEMPLATE_TYPES,
  applyTemplate,
  templatePreviewBody,
  templatesForType,
  uniqueSorted,
  type OrderFields,
  type ReportTemplate,
} from '../reportTemplates'

interface Props {
  open: boolean
  onClose: () => void
  onInsert: (result: { findings: string; impression: string }) => void
  order: OrderFields
}

export function InsertTemplateModal({ open, onClose, onInsert, order }: Props) {
  const [type, setType] = useState('')
  const [modality, setModality] = useState('')
  const [bodyPart, setBodyPart] = useState('')
  const [name, setName] = useState('')
  const [query, setQuery] = useState('')
  const onCloseRef = useRef(onClose)
  const ignoreBackdropClick = useRef(false)
  onCloseRef.current = onClose

  useEffect(() => {
    if (!open) return
    setType('')
    setModality('')
    setBodyPart('')
    setName('')
    setQuery('')
  }, [open])

  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCloseRef.current()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open])

  function guardNativeSelectClick() {
    ignoreBackdropClick.current = true
    window.setTimeout(() => {
      ignoreBackdropClick.current = false
    }, 400)
  }

  function handleBackdropClick() {
    if (ignoreBackdropClick.current) return
    onClose()
  }

  const byType = useMemo(() => templatesForType(type), [type])
  const byModality = useMemo(
    () => (modality ? byType.filter((t) => t.modality === modality) : byType),
    [byType, modality],
  )
  const byBody = useMemo(
    () => (bodyPart ? byModality.filter((t) => t.bodyPart === bodyPart) : byModality),
    [byModality, bodyPart],
  )
  const names = useMemo(() => {
    const q = query.trim().toLowerCase()
    return uniqueSorted(
      byBody
        .filter((t) => !q || t.name.toLowerCase().includes(q) || t.body.toLowerCase().includes(q))
        .map((t) => t.name),
    )
  }, [byBody, query])

  const selected: ReportTemplate | null = REPORT_TEMPLATES.find((t) => t.name === name) ?? null
  const modalityOptions = uniqueSorted(byType.map((t) => t.modality))
  const bodyPartOptions = uniqueSorted(byModality.map((t) => t.bodyPart))

  if (!open) return null

  return (
    <div className="tpl-overlay" role="dialog" aria-modal="true" aria-labelledby="insert-template-title">
      <div className="tpl-backdrop" onClick={handleBackdropClick} />
      <div className="tpl-modal" onMouseDown={(e) => e.stopPropagation()} onClick={(e) => e.stopPropagation()}>
        <header className="tpl-header">
          <h2 id="insert-template-title">Insert Report Template</h2>
          <button type="button" className="tpl-close" onClick={onClose} aria-label="Close">
            <svg viewBox="0 0 16 16" aria-hidden="true">
              <path d="M4 4l8 8M12 4l-8 8" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
            </svg>
          </button>
        </header>

        <div className="tpl-body">
          <div className="tpl-filters">
            <FilterSelect
              label="Template Type"
              value={type}
              options={[...TEMPLATE_TYPES]}
              disabled={false}
              onNativePick={guardNativeSelectClick}
              onChange={(v) => {
                setType(v)
                const next = templatesForType(v)
                const studyMod = order.modality || ''
                setModality(studyMod && next.some((t) => t.modality === studyMod) ? studyMod : '')
                setBodyPart('')
                setName('')
              }}
            />
            <FilterSelect
              label="Modality"
              value={modality}
              options={modalityOptions}
              disabled={!type}
              onNativePick={guardNativeSelectClick}
              onChange={(v) => {
                setModality(v)
                setBodyPart('')
                setName('')
              }}
            />
            <FilterSelect
              label="Body Part"
              value={bodyPart}
              options={bodyPartOptions}
              disabled={!modality}
              onNativePick={guardNativeSelectClick}
              onChange={(v) => {
                setBodyPart(v)
                setName('')
              }}
            />
            <FilterSelect
              label="Template Name"
              value={name}
              options={names}
              disabled={!bodyPart}
              onNativePick={guardNativeSelectClick}
              onChange={setName}
            />
          </div>

          <div className="tpl-search">
            <svg viewBox="0 0 20 20" aria-hidden="true">
              <path fill="currentColor" d="M8.5 3a5.5 5.5 0 0 1 4.38 8.82l3.65 3.65-1.06 1.06-3.65-3.65A5.5 5.5 0 1 1 8.5 3Zm0 1.5a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z" />
            </svg>
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search templates by name or keyword"
              aria-label="Search templates"
            />
          </div>

          <div className="tpl-preview-head">
            <h3>Preview</h3>
            <span>{selected ? selected.updated : 'Read only'}</span>
          </div>
          <div className="tpl-preview">
            {selected ? (
              <TemplatePreview text={templatePreviewBody(selected)} />
            ) : (
              <p>Select a template to preview its structure and placeholder fields.</p>
            )}
          </div>
          <p className="tpl-hint">
            <span className="tpl-token">{'{FIELDS}'}</span> are auto-filled from the order on insert.
          </p>
        </div>

        <footer className="tpl-footer">
          <button type="button" className="btn secondary" onClick={onClose}>Cancel</button>
          <button
            type="button"
            className="btn"
            disabled={!selected}
            onClick={() => {
              if (!selected) return
              onInsert(applyTemplate(selected, { ...order, bodyPart: selected.bodyPart }))
              onClose()
            }}
          >
            Insert
          </button>
        </footer>
      </div>
    </div>
  )
}

function FilterSelect({
  label,
  value,
  options,
  disabled,
  onChange,
  onNativePick,
}: {
  label: string
  value: string
  options: string[]
  disabled: boolean
  onChange: (value: string) => void
  onNativePick?: () => void
}) {
  const id = `tpl-${label.toLowerCase().replace(/\s+/g, '-')}`
  return (
    <div className={`tpl-field ${disabled ? 'disabled' : ''}`}>
      <label htmlFor={id}>{label}</label>
      <select
        id={id}
        value={value}
        disabled={disabled}
        onMouseDown={onNativePick}
        onPointerDown={onNativePick}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">Select</option>
        {options.map((opt) => (
          <option key={opt} value={opt}>{opt}</option>
        ))}
      </select>
    </div>
  )
}

function TemplatePreview({ text }: { text: string }) {
  const parts = text.split(/(\{[A-Z_]+\})/g)
  return (
    <pre>
      {parts.map((part, i) => (
        /^\{[A-Z_]+\}$/.test(part)
          ? <span key={i} className="tpl-token">{part}</span>
          : <span key={i}>{part}</span>
      ))}
    </pre>
  )
}
