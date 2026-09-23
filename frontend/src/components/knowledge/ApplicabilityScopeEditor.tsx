import type { ReactNode } from 'react'
import type { ScopeDraft } from '../../lib/semantics'

export function ApplicabilityScopeEditor({
  draft,
  onChange,
  disabled,
  manifestNote,
}: {
  draft: ScopeDraft
  onChange: (next: ScopeDraft) => void
  disabled: boolean
  manifestNote?: ReactNode
}) {
  function set(key: keyof ScopeDraft, value: string) {
    onChange({ ...draft, [key]: value })
  }

  return (
    <fieldset className="field-group">
      <legend>Applicability scope</legend>
      <p className="field__hint">
        The effective scope for this candidate. Editing scope is a human
        correction and is stored separately from the source-derived proposal.
      </p>
      {manifestNote}

      <div className="field">
        <label htmlFor="scope-contract-pack">Contract pack key</label>
        <input
          id="scope-contract-pack"
          type="text"
          value={draft.contractPackKey}
          disabled={disabled}
          onChange={(event) => set('contractPackKey', event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="scope-declared-types">Declared contract types</label>
        <input
          id="scope-declared-types"
          type="text"
          value={draft.declaredContractTypes}
          placeholder="MSA, NDA"
          disabled={disabled}
          onChange={(event) => set('declaredContractTypes', event.target.value)}
        />
        <p className="field__hint">Comma-separated.</p>
      </div>

      <div className="field">
        <label htmlFor="scope-party-perspective">Party perspective</label>
        <input
          id="scope-party-perspective"
          type="text"
          value={draft.partyPerspective}
          disabled={disabled}
          onChange={(event) => set('partyPerspective', event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="scope-paper-source">Paper source</label>
        <input
          id="scope-paper-source"
          type="text"
          value={draft.paperSource}
          disabled={disabled}
          onChange={(event) => set('paperSource', event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="scope-jurisdiction">Jurisdiction</label>
        <input
          id="scope-jurisdiction"
          type="text"
          value={draft.jurisdiction}
          disabled={disabled}
          onChange={(event) => set('jurisdiction', event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="scope-effective-from">Effective from</label>
        <input
          id="scope-effective-from"
          type="date"
          value={draft.effectiveFrom}
          disabled={disabled}
          onChange={(event) => set('effectiveFrom', event.target.value)}
        />
      </div>

      <div className="field">
        <label htmlFor="scope-effective-to">Effective to</label>
        <input
          id="scope-effective-to"
          type="date"
          value={draft.effectiveTo}
          disabled={disabled}
          onChange={(event) => set('effectiveTo', event.target.value)}
        />
      </div>
    </fieldset>
  )
}
