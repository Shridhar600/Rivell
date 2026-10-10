import { useId, type FormEvent, type ReactNode } from 'react'
import { POSITION_ROLES, type ArtifactDetail, type Candidate, type PositionRole } from '../../../api/knowledge'
import { useTaxonomy } from '../../../api/taxonomy'
import { humanizeCode } from '../../../lib/domainLabels'
import { POSITION_LABELS, formatPositions, proposedPositions, positionsDiverge, scopeDifferences } from '../../../lib/semantics'
import type { SemanticDraft } from '../../../lib/semantics'
import type { Correction } from './useCorrection'

/** A labelled control. The label is tied to the control by id, so each one has a real accessible name. */
function Field({ label, hint, className, children }: { label: string; hint?: ReactNode; className?: string; children: (id: string) => ReactNode }) {
  const id = useId()
  return (
    <div className={`rv-kc-ef${className ? ` ${className}` : ''}`}>
      <label htmlFor={id}>{label}</label>
      {children(id)}
      {hint ? <small>{hint}</small> : null}
    </div>
  )
}

interface Props {
  id: string
  candidate: Candidate
  artifact: ArtifactDetail
  correction: Correction
  /** The decision bar's Save button submits this form. */
  onSubmit: () => void
}

/**
 * The editable fields of the structured interpretation, and only the ones the backend lets a
 * reviewer change: the wording, the spreadsheet columns, the roles, the escalation trigger, counsel
 * approval, the clause role, the applicability scope and the legal concept.
 */
export function CorrectionForm({ id, candidate, artifact, correction, onSubmit }: Props) {
  const { draft, setDraft, pending, error } = correction
  const taxonomy = useTaxonomy()
  const isPlaybook = candidate.candidateType === 'PLAYBOOK_RULE'
  const isClause = candidate.candidateType === 'CLAUSE_VARIANT'
  const columns = Object.keys(draft.columns)
  const machinePositions = proposedPositions(candidate)
  const scopeDiffs = scopeDifferences(candidate, artifact)
  const conceptDisabled = pending || taxonomy.isPending || taxonomy.isError
  const selectedIsLeaf = draft.concept === '' || taxonomy.leaves.some((leaf) => leaf.key === draft.concept)

  const semantic = (change: Partial<SemanticDraft>) => setDraft((previous) => ({ ...previous, semantic: { ...previous.semantic, ...change } }))
  const scope = (key: keyof typeof draft.scope) => (event: { target: { value: string } }) =>
    setDraft((previous) => ({ ...previous, scope: { ...previous.scope, [key]: event.target.value } }))
  const togglePosition = (role: PositionRole) =>
    semantic({
      positions: draft.semantic.positions.includes(role) ? draft.semantic.positions.filter((entry) => entry !== role) : [...draft.semantic.positions, role],
    })

  function submit(event: FormEvent) {
    event.preventDefault()
    onSubmit()
  }

  return (
    <form id={id} className="rv-kc-edit" onSubmit={submit} aria-label="Correct the structured candidate">
      <Field
        label="Legal concept"
        hint={
          <>
            Changing it asks Rivell to compare this candidate with published knowledge again.
            {candidate.legalConceptKey ? ` Rivell classified it as ${taxonomy.labelFor(candidate.legalConceptKey)}.` : ' Rivell did not classify it.'}
          </>
        }
      >
        {(id) => (
          <>
            <select id={id} value={draft.concept} disabled={conceptDisabled} onChange={(event) => setDraft((previous) => ({ ...previous, concept: event.target.value }))}>
              <option value="">Not classified</option>
              {selectedIsLeaf ? null : (
                <option value={draft.concept}>
                  {humanizeCode(draft.concept)}
                  {!taxonomy.isPending && !taxonomy.isError ? ' (not in the current taxonomy)' : ''}
                </option>
              )}
              {taxonomy.groups.map((group) => (
                <optgroup key={group.parentKey ?? 'other'} label={group.label}>
                  {group.leaves.map((concept) => (
                    <option key={concept.key} value={concept.key}>
                      {concept.label}
                    </option>
                  ))}
                </optgroup>
              ))}
            </select>
            {taxonomy.isPending ? <small role="status">Loading legal topics…</small> : null}
            {taxonomy.isError ? (
              <small role="status">
                The legal topic list could not be loaded. The concept cannot be changed right now.
              </small>
            ) : null}
          </>
        )}
      </Field>

      {isClause ? (
        <Field label="Role">
          {(id) => (
            <select id={id} value={draft.semantic.variantRole} disabled={pending} onChange={(event) => semantic({ variantRole: event.target.value as PositionRole })}>
              {POSITION_ROLES.map((role) => (
                <option key={role} value={role}>
                  {POSITION_LABELS[role]}
                </option>
              ))}
            </select>
          )}
        </Field>
      ) : null}

      {isPlaybook ? (
        <>
          <fieldset className="rv-kc-ef" disabled={pending}>
            <legend>Position</legend>
            <div className="rv-kc-checks">
              {POSITION_ROLES.map((role) => (
                <label key={role}>
                  <input type="checkbox" checked={draft.semantic.positions.includes(role)} onChange={() => togglePosition(role)} />
                  {POSITION_LABELS[role]}
                </label>
              ))}
            </div>
            {positionsDiverge(candidate) && machinePositions ? <small>Rivell proposed: {formatPositions(machinePositions)}</small> : null}
          </fieldset>
          <Field label="Escalate">
            {(id) => <input id={id} value={draft.semantic.escalationTrigger} disabled={pending} onChange={(event) => semantic({ escalationTrigger: event.target.value })} />}
          </Field>
          <Field label="Counsel approval">
            {(id) => (
              <select
                id={id}
                value={draft.semantic.requiresApproval}
                disabled={pending}
                onChange={(event) => semantic({ requiresApproval: event.target.value as SemanticDraft['requiresApproval'] })}
              >
                <option value="unset">Not specified</option>
                <option value="true">Required</option>
                <option value="false">Not required</option>
              </select>
            )}
          </Field>
        </>
      ) : null}

      <Field label={isClause ? 'Approved wording' : 'Wording'} hint="This is the wording Rivell uses and publishes.">
        {(id) => (
          <textarea id={id} rows={5} value={draft.contentText} disabled={pending} onChange={(event) => setDraft((previous) => ({ ...previous, contentText: event.target.value }))} />
        )}
      </Field>

      {columns.length > 0 ? (
        <fieldset className="rv-kc-ef" disabled={pending}>
          <legend>Spreadsheet columns</legend>
          {columns.map((name) => (
            <Field key={name} label={name} className="rv-kc-ef--inline">
              {(id) => (
                <input id={id} value={draft.columns[name] ?? ''} onChange={(event) => setDraft((previous) => ({ ...previous, columns: { ...previous.columns, [name]: event.target.value } }))} />
              )}
            </Field>
          ))}
          <small>
            Column values are recorded with the candidate and are published as the corrected wording. Wording you
            set above takes precedence over them.
          </small>
        </fieldset>
      ) : null}

      <fieldset className="rv-kc-ef" disabled={pending}>
        <legend>Applies to</legend>
        {scopeDiffs.length > 0 ? (
          <small>Differs from the file&apos;s declared scope: {scopeDiffs.map((entry) => `${entry.label} (file: ${entry.manifestValue ?? 'not set'})`).join('; ')}.</small>
        ) : null}
        <div className="rv-kc-grid">
          <label>
            Contract types
            <input value={draft.scope.declaredContractTypes} onChange={scope('declaredContractTypes')} placeholder="MSA, NDA" />
          </label>
          <label>
            Contract pack
            <input value={draft.scope.contractPackKey} onChange={scope('contractPackKey')} />
          </label>
          <label>
            Party perspective
            <input value={draft.scope.partyPerspective} onChange={scope('partyPerspective')} />
          </label>
          <label>
            Paper source
            <input value={draft.scope.paperSource} onChange={scope('paperSource')} />
          </label>
          <label>
            Jurisdiction
            <input value={draft.scope.jurisdiction} onChange={scope('jurisdiction')} />
          </label>
          <label>
            Effective from
            <input type="date" value={draft.scope.effectiveFrom} onChange={scope('effectiveFrom')} />
          </label>
          <label>
            Effective to
            <input type="date" value={draft.scope.effectiveTo} onChange={scope('effectiveTo')} />
          </label>
        </div>
      </fieldset>

      <Field label="Review note, optional">
        {(id) => <input id={id} value={draft.note} disabled={pending} onChange={(event) => setDraft((previous) => ({ ...previous, note: event.target.value }))} />}
      </Field>

      {error ? (
        <p className="rv-kc-error" role="alert">
          {error}
        </p>
      ) : null}
    </form>
  )
}
