import { useId, useState, type FormEvent } from 'react'
import type { ReviewProfileInput } from '../../api/semantics'
import {
  CONTRACT_TYPES,
  CONTRACT_TYPE_LABELS,
  JURISDICTIONS,
  JURISDICTION_LABELS,
  PAPER_SOURCES,
  PAPER_SOURCE_LABELS,
  PARTY_ROLES,
  roleLabel,
} from '../../lib/contractSemantics'
import type { ReviewProfile } from '../../api/semantics'

type Draft = {
  contractType: string
  ourSideRole: string
  paperSource: string
  jurisdiction: string
}

function draftFromProfile(profile: ReviewProfile | null): Draft {
  return {
    contractType: profile?.contractType ?? '',
    ourSideRole: profile?.ourSideRole ?? '',
    paperSource: profile?.paperSource ?? '',
    jurisdiction: profile?.jurisdiction ?? '',
  }
}

function toInput(draft: Draft): ReviewProfileInput {
  const input: ReviewProfileInput = {
    contractType: draft.contractType as ReviewProfileInput['contractType'],
  }
  if (draft.ourSideRole) {
    input.ourSideRole = draft.ourSideRole as ReviewProfileInput['ourSideRole']
  }
  if (draft.paperSource) {
    input.paperSource = draft.paperSource as ReviewProfileInput['paperSource']
  }
  if (draft.jurisdiction) {
    input.jurisdiction = draft.jurisdiction as ReviewProfileInput['jurisdiction']
  }
  return input
}

export function ReviewProfileForm({
  initialProfile = null,
  submitLabel,
  pending,
  onSubmit,
}: {
  initialProfile?: ReviewProfile | null
  submitLabel: string
  pending: boolean
  onSubmit: (profile: ReviewProfileInput) => void
}) {
  const [draft, setDraft] = useState<Draft>(() => draftFromProfile(initialProfile))
  const [error, setError] = useState<string | null>(null)
  const contractTypeId = useId()
  const sideRoleId = useId()
  const paperSourceId = useId()
  const jurisdictionId = useId()

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!draft.contractType) {
      setError('Choose a contract type so Rivell can interpret the document.')
      return
    }
    setError(null)
    onSubmit(toInput(draft))
  }

  const update = (key: keyof Draft) => (value: string) =>
    setDraft((previous) => ({ ...previous, [key]: value }))

  return (
    <form className="profile-form" onSubmit={handleSubmit} aria-busy={pending}>
      <p className="profile-form__note">
        The profile tells Rivell how to interpret this document. It does not decide
        whether any term is acceptable.
      </p>
      <div className="profile-form__grid">
        <div className="profile-form__field">
          <label htmlFor={contractTypeId}>Contract type</label>
          <select
            id={contractTypeId}
            value={draft.contractType}
            onChange={(event) => update('contractType')(event.target.value)}
            disabled={pending}
            aria-required="true"
          >
            <option value="">Select a contract type…</option>
            {CONTRACT_TYPES.map((type) => (
              <option key={type} value={type}>
                {CONTRACT_TYPE_LABELS[type]}
              </option>
            ))}
          </select>
        </div>
        <div className="profile-form__field">
          <label htmlFor={sideRoleId}>Our side in this agreement</label>
          <select
            id={sideRoleId}
            value={draft.ourSideRole}
            onChange={(event) => update('ourSideRole')(event.target.value)}
            disabled={pending}
          >
            <option value="">Not specified</option>
            {PARTY_ROLES.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </select>
        </div>
        <div className="profile-form__field">
          <label htmlFor={paperSourceId}>Paper source</label>
          <select
            id={paperSourceId}
            value={draft.paperSource}
            onChange={(event) => update('paperSource')(event.target.value)}
            disabled={pending}
          >
            <option value="">Not specified</option>
            {PAPER_SOURCES.map((source) => (
              <option key={source} value={source}>
                {PAPER_SOURCE_LABELS[source]}
              </option>
            ))}
          </select>
        </div>
        <div className="profile-form__field">
          <label htmlFor={jurisdictionId}>Jurisdiction</label>
          <select
            id={jurisdictionId}
            value={draft.jurisdiction}
            onChange={(event) => update('jurisdiction')(event.target.value)}
            disabled={pending}
          >
            <option value="">Not specified</option>
            {JURISDICTIONS.map((jurisdiction) => (
              <option key={jurisdiction} value={jurisdiction}>
                {JURISDICTION_LABELS[jurisdiction]}
              </option>
            ))}
          </select>
        </div>
      </div>
      {error ? (
        <p className="profile-form__error" role="alert">
          {error}
        </p>
      ) : null}
      <button type="submit" className="button button--primary" disabled={pending}>
        {pending ? 'Analysing…' : submitLabel}
      </button>
    </form>
  )
}
