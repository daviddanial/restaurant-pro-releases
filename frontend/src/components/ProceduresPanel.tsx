import { FormEvent, useEffect, useMemo, useState } from 'react'
import { adminProcedures, createProcedure, updateProcedure } from '../ordersApi'
import type { ProcedureDto } from '../types'

/** Codes offered when adding; any other DICOM code can be typed. */
const MODALITY_CHOICES: [string, string][] = [
  ['DX', 'X-ray'],
  ['US', 'Ultrasound'],
  ['CT', 'CT'],
  ['MR', 'MRI'],
  ['RF', 'Fluoroscopy'],
  ['MG', 'Mammography'],
  ['BMD', 'Bone density'],
]
const label = (code: string) => MODALITY_CHOICES.find(([c]) => c === code)?.[1] ?? code
const errText = (e: unknown, fallback: string) => (e instanceof Error ? e.message : fallback)

/** The central procedure list shared by all hospitals (KB note 15 §9). Nothing is deleted: switch off instead. */
export function ProceduresPanel() {
  const [items, setItems] = useState<ProcedureDto[]>([])
  const [modality, setModality] = useState('DX')
  const [search, setSearch] = useState('')
  const [showOff, setShowOff] = useState(false)
  const [newName, setNewName] = useState('')
  const [editing, setEditing] = useState<string | null>(null)
  const [editName, setEditName] = useState('')
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () =>
    adminProcedures()
      .then(setItems)
      .catch((e) => setError(errText(e, 'Could not load procedures')))

  useEffect(() => {
    void load()
  }, [])

  const codes = useMemo(() => {
    const known = MODALITY_CHOICES.map(([c]) => c)
    const extra = [...new Set(items.map((p) => p.modality))].filter((c) => !known.includes(c)).sort()
    return [...known, ...extra]
  }, [items])

  const shown = items.filter(
    (p) =>
      p.modality === modality &&
      (showOff || p.active) &&
      p.name.toLowerCase().includes(search.trim().toLowerCase()),
  )
  const counts = (code: string) => items.filter((p) => p.modality === code && p.active).length

  async function run(fn: () => Promise<unknown>, done: string) {
    setError(null)
    setMessage(null)
    try {
      await fn()
      setMessage(done)
      await load()
    } catch (e) {
      setError(errText(e, 'Save failed'))
    }
  }

  function add(e: FormEvent) {
    e.preventDefault()
    const name = newName.trim()
    void run(() => createProcedure({ modality, name }), `Added “${name}” to ${label(modality)}.`).then(() => setNewName(''))
  }

  return (
    <div className="panel procedures-panel">
      <h2>Procedures</h2>
      <p className="muted">
        One list for all hospitals. Reception sees only the procedures of the modality it chooses. The side (left /
        right) is chosen on the order, so it is not part of the name. Procedures are never deleted: switch one off and
        it stays on old orders.
      </p>
      {error && <div className="error">{error}</div>}
      {message && <div className="success">{message}</div>}

      <div className="proc-tabs" role="tablist" aria-label="Modality">
        {codes.map((c) => (
          <button
            key={c}
            type="button"
            role="tab"
            aria-selected={c === modality}
            className={c === modality ? 'active' : ''}
            onClick={() => setModality(c)}
          >
            {label(c)} <span className="muted">{counts(c)}</span>
          </button>
        ))}
      </div>

      <div className="proc-tools">
        <input
          aria-label="Search procedures"
          placeholder={`Search ${label(modality)} procedures`}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <label className="check-line">
          <input type="checkbox" checked={showOff} onChange={(e) => setShowOff(e.target.checked)} /> Show switched off
        </label>
      </div>

      <form className="proc-add" onSubmit={add}>
        <input
          aria-label="New procedure name"
          placeholder={`New ${label(modality)} procedure, e.g. ${modality === 'DX' ? 'Knee X-ray [AP & lateral]' : 'name'}`}
          value={newName}
          onChange={(e) => setNewName(e.target.value)}
          required
          maxLength={255}
        />
        <button className="btn btn-small" type="submit">Add to {label(modality)}</button>
      </form>

      <table className="table proc-table">
        <thead>
          <tr>
            <th>Procedure</th>
            <th>Status</th>
            <th aria-label="Actions" />
          </tr>
        </thead>
        <tbody>
          {shown.map((p) => (
            <tr key={p.id} className={p.active ? undefined : 'row-closed'}>
              <td>
                {editing === p.id ? (
                  <form
                    className="inline-form"
                    onSubmit={(e) => {
                      e.preventDefault()
                      void run(() => updateProcedure(p.id, { name: editName }), 'Renamed.').then(() => setEditing(null))
                    }}
                  >
                    <input aria-label="Procedure name" value={editName} onChange={(e) => setEditName(e.target.value)} required />
                    <button className="btn btn-small" type="submit">Save</button>
                    <button className="btn-link" type="button" onClick={() => setEditing(null)}>Cancel</button>
                  </form>
                ) : (
                  p.name
                )}
              </td>
              <td>{p.active ? 'On' : 'Off'}</td>
              <td className="proc-actions">
                {editing !== p.id && (
                  <button
                    type="button"
                    className="btn-link"
                    onClick={() => {
                      setEditing(p.id)
                      setEditName(p.name)
                    }}
                  >
                    Rename
                  </button>
                )}
                <button
                  type="button"
                  className="btn secondary btn-small"
                  onClick={() =>
                    void run(() => updateProcedure(p.id, { active: !p.active }), p.active ? `Switched off “${p.name}”.` : `Switched on “${p.name}”.`)
                  }
                >
                  {p.active ? 'Switch off' : 'Switch on'}
                </button>
              </td>
            </tr>
          ))}
          {shown.length === 0 && (
            <tr>
              <td colSpan={3} className="muted" style={{ textAlign: 'center', padding: '1rem' }}>
                No procedures match.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  )
}
