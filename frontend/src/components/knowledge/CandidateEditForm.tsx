import { useState, type FormEvent } from 'react'
import {
  POSITION_ROLES,
  useEditCandidate,
  type ArtifactDetail,
  type Candidate,
  type PositionRole,
} from '../../api/knowledge'
import {
  effectiveContentText,
  effectivePayloadColumns,
} from '../../lib/candidates'
import {
  formatPositions,
  interpretationFor,
  positionsDiverge,
  proposedPositions,
  sameStringMap,
  scopeDifferences,
  scopeDraftChanged,
  scopeDraftFrom,
  scopeInputFrom,
  semanticDraftFrom,
  semanticInputFrom,
  POSITION_LABELS,
  type ScopeDraft,
  type SemanticDraft,
} from '../../lib/semantics'
import { ErrorState } from '../ErrorState'
import { ApplicabilityScopeEditor } from './ApplicabilityScopeEditor'

export function CandidateEditForm({
  candidate,
  artifact,
  onCancel,
}: {
  candidate: Candidate
  artifact: ArtifactDetail
  onCancel: () => void
}) {
  const edit = useEditCandidate()
  const initialColumns = effectivePayloadColumns(candidate)
  const [contentText, setContentText] = useState(
    effectiveContentText(candidate) ?? '',
  )
  const [columns, setColumns] = useState<Record<string, string>>(initialColumns)
  const [note, setNote] = useState('')
  const [validationError, setValidationError] = useState<string | null>(null)
  const [scope, setScope] = useState<ScopeDraft>(() => scopeDraftFrom(candidate))
  const [semantic, setSemantic] = useState<SemanticDraft>(() =>
    semanticDraftFrom(candidate),
  )

  const hasColumns = Object.keys(columns).length > 0
  const isPlaybook = candidate.candidateType === 'PLAYBOOK_RULE'
  const isClause = candidate.candidateType === 'CLAUSE_VARIANT'
  const showSemantic = isPlaybook || isClause

  const machinePositions = proposedPositions(candidate)
  const scopeDiffs = scopeDifferences(candidate, artifact)
  const scopeInterpretation = interpretationFor(candidate, 'scope')

  function togglePosition(role: PositionRole) {
    setSemantic((previous) => ({
      ...previous,
      positions: previous.positions.includes(role)
        ? previous.positions.filter((entry) => entry !== role)
        : [...previous.positions, role],
    }))
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const semanticInput = semanticInputFrom(semantic, candidate)
    const scopeChanged = scopeDraftChanged(scope, candidate)
    const initialContent = effectiveContentText(candidate) ?? ''
    const contentChanged = contentText !== initialContent
    const columnsChanged = hasColumns && !sameStringMap(columns, initialColumns)

    if (!contentChanged && !columnsChanged && !scopeChanged && semanticInput === undefined) {
      setValidationError('An edit must change content, a column, scope, or interpretation.')
      return
    }
    setValidationError(null)
    edit.mutate(
      {
        candidateId: candidate.id,
        contentText:
          contentChanged && contentText.trim().length > 0 ? contentText : undefined,
        payloadColumns: columnsChanged ? columns : undefined,
        scope: scopeChanged ? scopeInputFrom(scope) : undefined,
        semanticFields: semanticInput,
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

      {showSemantic ? (
        <fieldset className="field-group">
          <legend>Interpretation</legend>
          <p className="field__hint">
            Human corrections are stored beside the machine proposal; clearing a
            field keeps the machine proposal effective.
          </p>

          {isPlaybook ? (
            <>
              <div className="field">
                <span className="field__label">Negotiation position</span>
                <div className="checkbox-group">
                  {POSITION_ROLES.map((role) => (
                    <label key={role} className="checkbox">
                      <input
                        type="checkbox"
                        checked={semantic.positions.includes(role)}
                        disabled={edit.isPending}
                        onChange={() => togglePosition(role)}
                      />
                      {POSITION_LABELS[role]}
                    </label>
                  ))}
                </div>
                {positionsDiverge(candidate) && machinePositions ? (
                  <p className="field__hint">
                    Rivell proposed: {formatPositions(machinePositions)}
                  </p>
                ) : null}
              </div>

              <div className="field">
                <label htmlFor="edit-requires-approval">
                  General counsel approval
                </label>
                <select
                  id="edit-requires-approval"
                  value={semantic.requiresApproval}
                  disabled={edit.isPending}
                  onChange={(event) =>
                    setSemantic((previous) => ({
                      ...previous,
                      requiresApproval: event.target
                        .value as SemanticDraft['requiresApproval'],
                    }))
                  }
                >
                  <option value="unset">Not specified</option>
                  <option value="true">Required</option>
                  <option value="false">Not required</option>
                </select>
              </div>

              <div className="field">
                <label htmlFor="edit-escalation-trigger">Escalation trigger</label>
                <textarea
                  id="edit-escalation-trigger"
                  rows={2}
                  value={semantic.escalationTrigger}
                  disabled={edit.isPending}
                  onChange={(event) =>
                    setSemantic((previous) => ({
                      ...previous,
                      escalationTrigger: event.target.value,
                    }))
                  }
                />
              </div>
            </>
          ) : null}

          {isClause ? (
            <div className="field">
              <label htmlFor="edit-variant-role">Clause variant role</label>
              <select
                id="edit-variant-role"
                value={semantic.variantRole}
                disabled={edit.isPending}
                onChange={(event) =>
                  setSemantic((previous) => ({
                    ...previous,
                    variantRole: event.target.value as PositionRole,
                  }))
                }
              >
                {POSITION_ROLES.map((role) => (
                  <option key={role} value={role}>
                    {POSITION_LABELS[role]}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
        </fieldset>
      ) : null}

      <ApplicabilityScopeEditor
        draft={scope}
        onChange={setScope}
        disabled={edit.isPending}
        manifestNote={
          <>
            {scopeDiffs.length > 0 ? (
              <p className="field__hint">
                Currently adjusted from the artifact manifest:{' '}
                {scopeDiffs
                  .map(
                    (difference) =>
                      `${difference.label} (manifest: ${difference.manifestValue ?? 'not set'})`,
                  )
                  .join('; ')}
                .
              </p>
            ) : null}
            {scopeInterpretation &&
            scopeInterpretation.proposedValue !== scope.jurisdiction ? (
              <p className="field__hint">
                Rivell suggests narrowing jurisdiction to{' '}
                {scopeInterpretation.proposedValue} based on the source.
              </p>
            ) : null}
          </>
        }
      />

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
