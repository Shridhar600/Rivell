import { useId, type FormEvent, type ReactNode } from 'react'
import { AUTHORITY_LEVELS, POSITION_ROLES, type ArtifactDetail, type Candidate, type PositionRole } from '../../../api/knowledge'
import { useTaxonomy } from '../../../api/taxonomy'
import { humanizeCode } from '../../../lib/domainLabels'
import { artifactFormat } from '../../../lib/knowledgeCatalog'
import { POSITION_LABELS, formatPositions, proposedPositions, positionsDiverge, scopeDifferences } from '../../../lib/semantics'
import type { SemanticDraft } from '../../../lib/semantics'
import type { Correction } from './useCorrection'

/** The one stance a rule is edited for; an empty set means fallback and walk-away. */
const RULE_STANCE_OPTIONS = ['FALLBACK', 'PROHIBITED', 'BOTH'] as const

function ruleStanceValue(stances: PositionRole[]): (typeof RULE_STANCE_OPTIONS)[number] {
  if (stances.includes('PROHIBITED')) return 'PROHIBITED'
  if (stances.includes('FALLBACK')) return 'FALLBACK'
  return 'BOTH'
}

function stancesFromValue(value: (typeof RULE_STANCE_OPTIONS)[number]): PositionRole[] {
  if (value === 'FALLBACK') return ['FALLBACK']
  if (value === 'PROHIBITED') return ['PROHIBITED']
  return []
}

const RULE_STANCE_LABELS: Record<(typeof RULE_STANCE_OPTIONS)[number], string> = {
  FALLBACK: 'Fallback',
  PROHIBITED: 'Walk-away',
  BOTH: 'Fallback and walk-away',
}

const AUTHORITY_SOURCE_OPTIONS = {
  ENTRY: 'Entry',
  MATRIX: 'Approval matrix',
  TABLE: 'Approval table',
} as const satisfies Record<(typeof AUTHORITY_LEVELS)[number], string>

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
  const tableCells = artifactFormat(artifact) === 'Word file'
  const columnLabel = (name: string) => (/^(Column|Field) \d+$/.test(name) ? 'Value' : name)
  const machinePositions = proposedPositions(candidate)
  const scopeDiffs = scopeDifferences(candidate, artifact)
  const conceptDisabled = pending || taxonomy.isPending || taxonomy.isError
  const selectedIsLeaf = draft.concept === '' || taxonomy.leaves.some((leaf) => leaf.key === draft.concept)
  const selectedKnown =
    selectedIsLeaf || taxonomy.parents.some((parent) => parent.key === draft.concept)

  const semantic = (change: Partial<SemanticDraft>) => setDraft((previous) => ({ ...previous, semantic: { ...previous.semantic, ...change } }))
  const rule = (index: number, change: Partial<SemanticDraft['authorityRules'][number]>) =>
    semantic({
      authorityRules: draft.semantic.authorityRules.map((entry, position) =>
        position === index ? { ...entry, ...change } : entry,
      ),
    })
  const removeRule = (index: number) =>
    semantic({
      authorityRules: draft.semantic.authorityRules.filter((_, position) => position !== index),
    })
  const addRule = () =>
    semantic({
      authorityRules: [
        ...draft.semantic.authorityRules,
        { approver: '', stances: ['FALLBACK'], condition: '', source: 'ENTRY', quote: null },
      ],
    })
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
    <form id={id} className="rv-kc-edit" onSubmit={submit} aria-label="Edit this entry">
      <Field label="Title" hint="A clear, short name for this entry. Clearing a correction uses the title Rivell read.">
        {(id) => (
          <input id={id} value={draft.title} maxLength={200} disabled={pending} onChange={(event) => setDraft((previous) => ({ ...previous, title: event.target.value }))} />
        )}
      </Field>

      <Field
        label="Topic"
        hint={
          <>
            Changing the topic asks Rivell to compare this entry with published knowledge again.
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
                  {!taxonomy.isPending && !taxonomy.isError && !selectedKnown
                    ? ' (not in the current topic list)'
                    : ''}
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
            {taxonomy.isPending ? <small role="status">Loading topics…</small> : null}
            {taxonomy.isError ? (
              <small role="status">
                The topic list could not be loaded. The topic cannot be changed right now.
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
          <Field label="Preferred wording" hint="The preferred position exactly as the source states it. An empty field means the source states none.">
            {(id) => (
              <textarea id={id} rows={3} value={draft.semantic.preferredText} disabled={pending} onChange={(event) => semantic({ preferredText: event.target.value })} />
            )}
          </Field>
          <Field label="Fallback wording" hint="One fallback per line, in source order. An empty field means the source states none.">
            {(id) => (
              <textarea id={id} rows={3} value={draft.semantic.fallbackTexts} disabled={pending} onChange={(event) => semantic({ fallbackTexts: event.target.value })} />
            )}
          </Field>
          <Field label="Walk-away wording" hint="The walk-away position exactly as the source states it. An empty field means the source states none.">
            {(id) => (
              <textarea id={id} rows={3} value={draft.semantic.prohibitedText} disabled={pending} onChange={(event) => semantic({ prohibitedText: event.target.value })} />
            )}
          </Field>
          <fieldset className="rv-kc-ef" disabled={pending}>
            <legend>Approvals</legend>
            <small>
              An approval exists only when a rule here names an approver. Leave the list empty when the source states none.
            </small>
            {draft.semantic.authorityRules.map((entry, index) => (
              <div className="rv-kc-grid" key={`rule-${index}`}>
                <Field label="Approver">
                  {(id) => (
                    <input id={id} value={entry.approver} onChange={(event) => rule(index, { approver: event.target.value })} />
                  )}
                </Field>
                <Field label="For">
                  {(id) => (
                    <select
                      id={id}
                      value={ruleStanceValue(entry.stances)}
                      onChange={(event) =>
                        rule(index, {
                          stances: stancesFromValue(event.target.value as (typeof RULE_STANCE_OPTIONS)[number]),
                        })
                      }
                    >
                      {RULE_STANCE_OPTIONS.map((option) => (
                        <option key={option} value={option}>
                          {RULE_STANCE_LABELS[option]}
                        </option>
                      ))}
                    </select>
                  )}
                </Field>
                <Field label="Condition, optional">
                  {(id) => (
                    <input id={id} value={entry.condition} onChange={(event) => rule(index, { condition: event.target.value })} />
                  )}
                </Field>
                <Field label="Stated in">
                  {(id) => (
                    <select
                      id={id}
                      value={entry.source}
                      onChange={(event) => rule(index, { source: event.target.value as SemanticDraft['authorityRules'][number]['source'] })}
                    >
                      {AUTHORITY_LEVELS.map((level) => (
                        <option key={level} value={level}>
                          {AUTHORITY_SOURCE_OPTIONS[level]}
                        </option>
                      ))}
                    </select>
                  )}
                </Field>
                <button type="button" className="rv-lnk" onClick={() => removeRule(index)}>
                  Remove this approval
                </button>
              </div>
            ))}
            <button type="button" className="rv-lnk" onClick={addRule}>
              Add an approval
            </button>
          </fieldset>
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
          <legend>{tableCells ? 'Table cells' : 'Spreadsheet columns'}</legend>
          {columns.map((name) => (
            <Field key={name} label={columnLabel(name)} className="rv-kc-ef--inline">
              {(id) => (
                <input id={id} value={draft.columns[name] ?? ''} onChange={(event) => setDraft((previous) => ({ ...previous, columns: { ...previous.columns, [name]: event.target.value } }))} />
              )}
            </Field>
          ))}
          <small>
            {tableCells ? 'Cell values' : 'Column values'} are saved with this entry and published as its wording. Wording set above takes precedence.
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
            Review pack
            <input value={draft.scope.contractPackKey} onChange={scope('contractPackKey')} />
          </label>
          <label>
            Party perspective
            <input value={draft.scope.partyPerspective} onChange={scope('partyPerspective')} />
          </label>
          <label>
            Whose template
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
