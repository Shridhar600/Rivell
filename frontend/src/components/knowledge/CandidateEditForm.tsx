import { useState, type FormEvent } from 'react'
import { useEditCandidate, type Candidate } from '../../api/knowledge'
import {
  effectiveContentText,
  effectivePayloadColumns,
} from '../../lib/candidates'
import { ErrorState } from '../ErrorState'

export function CandidateEditForm({
  candidate,
  onCancel,
}: {
  candidate: Candidate
  onCancel: () => void
}) {
  const edit = useEditCandidate()
  const initialColumns = effectivePayloadColumns(candidate)
  const [contentText, setContentText] = useState(
    effectiveContentText(candidate) ?? '',
  )
  const [columns, setColumns] =
    useState<Record<string, string>>(initialColumns)
  const [note, setNote] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)

  const hasColumns = Object.keys(columns).length > 0

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!hasColumns && contentText.trim().length === 0) {
      setValidationError('An edit must keep content text or a column value.')
      return
    }
    setValidationError(null)
    edit.mutate(
      {
        candidateId: candidate.id,
        contentText: contentText.trim().length > 0 ? contentText : undefined,
        payloadColumns: hasColumns ? columns : undefined,
        note,
      },
      { onSuccess: onCancel },
    )
  }

  return (
    <form className="edit-form" aria-label="Edit candidate" onSubmit={handleSubmit}>
      {hasColumns ? (
        <fieldset className="field-group">
          <legend>Column values</legend>
          {Object.keys(columns).map((column) => (
            <div key={column} className="field">
              <label htmlFor={`edit-column-${column}`}>{column}</label>
              <textarea
                id={`edit-column-${column}`}
                rows={2}
                value={columns[column]}
                disabled={edit.isPending}
                onChange={(event) =>
                  setColumns((previous) => ({
                    ...previous,
                    [column]: event.target.value,
                  }))
                }
              />
            </div>
          ))}
        </fieldset>
      ) : null}

      <div className="field">
        <label htmlFor="edit-content-text">
          {hasColumns ? 'Extracted text' : 'Content text'}
        </label>
        <textarea
          id="edit-content-text"
          rows={hasColumns ? 3 : 6}
          value={contentText}
          disabled={edit.isPending}
          onChange={(event) => setContentText(event.target.value)}
        />
        <p className="field__hint">
          The machine-extracted original is preserved; this edit is recorded
          separately.
        </p>
      </div>

      <div className="field">
        <label htmlFor="edit-note">Review note (optional)</label>
        <textarea
          id="edit-note"
          rows={2}
          value={note}
          disabled={edit.isPending}
          onChange={(event) => setNote(event.target.value)}
        />
      </div>

      {validationError ? (
        <p className="field__error" role="alert">
          {validationError}
        </p>
      ) : null}

      <div className="review-actions">
        <button
          type="submit"
          className="button button--primary"
          disabled={edit.isPending}
        >
          Save edit
        </button>
        <button
          type="button"
          className="button"
          disabled={edit.isPending}
          onClick={onCancel}
        >
          Cancel
        </button>
      </div>

      {edit.isError ? (
        <ErrorState message={edit.error.message || 'Edit failed.'} />
      ) : null}
    </form>
  )
}
