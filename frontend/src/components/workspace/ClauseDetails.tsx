import { useId, useState, type FormEvent, type ReactNode } from 'react'
import type {
  SemanticClause,
  SemanticContract,
  SemanticFact,
  SemanticInterpretation,
} from '../../api/semantics'
import { isConflictError, useCorrectClauseConcept, useCorrectClauseFacts } from '../../api/semantics'
import { useTaxonomy } from '../../api/taxonomy'
import { humanizeCode } from '../../lib/domainLabels'
import { subtreeNodes, type StructureNode } from '../../lib/documentTree'
import {
  describeFact,
  effectivePrimaryConcept,
  factDivergence,
  factEditorFields,
  factSchemaLabel,
  interpretationKindDescription,
  machinePrimaryConcept,
  referenceStatusLabel,
  regionKindLabel,
  relationshipTypeVerb,
  verificationLabel,
} from '../../lib/contractSemantics'

interface Props {
  clause: SemanticClause
  semantics: SemanticContract
  nodes: StructureNode[]
  contractId: string
  /** The version the review pinned; corrections write to it only when it is the latest. */
  versionNumber: number
  /** False when the review pinned an older version: corrections then go to a version no review uses. */
  editable: boolean
  latestVersionNumber: number | null
  regionLabels: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onRefresh: () => void
}

/** A disclosure in the workspace's own style: a link-styled button that shows and hides its region. */
function Disclosure({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const regionId = useId()
  return (
    <div className="rv-ws-cd-disc">
      <button type="button" className="rv-ws-link" aria-expanded={open} aria-controls={regionId} onClick={() => setOpen((value) => !value)}>
        {label}
      </button>
      <div id={regionId} hidden={!open}>
        {open ? children : null}
      </div>
    </div>
  )
}

function CorrectionFailure({ error, onRefresh }: { error: Error; onRefresh: () => void }) {
  if (isConflictError(error)) {
    return (
      <div role="alert" className="rv-ws-life-alert">
        <p>The analysis of this contract version is not ready. Refresh it and try again.</p>
        <button type="button" className="rv-btn rv-btn--compact" onClick={onRefresh}>
          Refresh analysis state
        </button>
      </div>
    )
  }
  return (
    <p role="alert" className="rv-ws-error">
      {error.message}
    </p>
  )
}

function RegionButtons({
  nodeIds,
  regionLabels,
  onNavigateToNode,
  empty,
}: {
  nodeIds: string[]
  regionLabels: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  empty?: string
}) {
  const known = nodeIds.filter((nodeId) => regionLabels.has(nodeId))
  if (known.length === 0) return empty ? <span className="rv-ws-caption">{empty}</span> : null
  return (
    <span className="rv-ws-srcd-acts">
      {known.map((nodeId) => (
        <button key={nodeId} type="button" className="rv-ws-link" onClick={() => onNavigateToNode(nodeId)}>
          {regionLabels.get(nodeId)}
        </button>
      ))}
    </span>
  )
}

function ConceptEditor({ clause, contractId, versionNumber, onRefresh }: { clause: SemanticClause; contractId: string; versionNumber: number; onRefresh: () => void }) {
  const correct = useCorrectClauseConcept(contractId, versionNumber)
  const taxonomy = useTaxonomy()
  const effective = effectivePrimaryConcept(clause) ?? ''
  const [serverConcept, setServerConcept] = useState(effective)
  const [selected, setSelected] = useState(effective)
  if (serverConcept !== effective) {
    setServerConcept(effective)
    setSelected(effective)
  }
  const [error, setError] = useState<string | null>(null)
  const pickerDisabled = correct.isPending || taxonomy.isPending || taxonomy.isError
  const selectedIsLeaf = selected === '' || taxonomy.leaves.some((leaf) => leaf.key === selected)
  const selectedKnown = selected === '' || selectedIsLeaf || taxonomy.parents.some((parent) => parent.key === selected)
  const inputId = `concept-${clause.id}`

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!selected) {
      setError('Choose the topic this clause is about.')
      return
    }
    setError(null)
    correct.mutate({ clauseId: clause.id, primary: selected })
  }

  return (
    <>
      <form className="rv-ws-addf" onSubmit={submit} aria-busy={correct.isPending} noValidate>
        <label htmlFor={inputId}>Topic</label>
        <select id={inputId} value={selected} onChange={(event) => setSelected(event.target.value)} disabled={pickerDisabled}>
          <option value="">Select a topic…</option>
          {selectedIsLeaf ? null : (
            <option value={selected}>
              {humanizeCode(selected)}
              {!taxonomy.isPending && !taxonomy.isError && !selectedKnown ? ' (not in the current topic list)' : ''}
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
        {taxonomy.isPending ? <p role="status" className="rv-ws-caption">Loading legal topics…</p> : null}
        {taxonomy.isError ? (
          <p role="status" className="rv-ws-caption">
            The legal topic list could not be loaded. The topic cannot be changed right now.
          </p>
        ) : null}
        <p className="rv-ws-caption">
          This corrects what the clause is about. It is not an approval or rejection of the clause.
        </p>
        {error ? (
          <p role="alert" className="rv-ws-addf-err">
            {error}
          </p>
        ) : null}
        <div className="rv-ws-addf-act">
          <button type="submit" className="rv-btn rv-btn--compact" disabled={pickerDisabled}>
            {correct.isPending ? 'Saving…' : 'Save topic'}
          </button>
        </div>
      </form>
      {correct.isError ? <CorrectionFailure error={correct.error} onRefresh={onRefresh} /> : null}
      {correct.isSuccess && !correct.isPending ? (
        <p role="status" className="rv-ws-caption">
          Topic updated. Rivell&apos;s own reading is kept.
        </p>
      ) : null}
    </>
  )
}

function FactEditor({ fact, clauseId, contractId, versionNumber, onRefresh }: { fact: SemanticFact; clauseId: string; contractId: string; versionNumber: number; onRefresh: () => void }) {
  const fields = factEditorFields(fact.factSchema)
  const correct = useCorrectClauseFacts(contractId, versionNumber)
  const [serverValues, setServerValues] = useState(fact.values)
  const [draft, setDraft] = useState<Record<string, string>>(() => ({ ...fact.values }))
  const [error, setError] = useState<string | null>(null)
  if (serverValues !== fact.values) {
    setServerValues(fact.values)
    setDraft({ ...fact.values })
  }
  if (!fields) return null

  const changed = Object.fromEntries(Object.entries(draft).filter(([field, value]) => fact.values[field] !== value))
  const hasChanges = Object.keys(changed).length > 0
  const set = (field: string, value: string) => setDraft((previous) => ({ ...previous, [field]: value }))

  function validate(): string | null {
    for (const field of fields ?? []) {
      const value = draft[field.field]
      if (value === undefined) continue
      if (value.trim() === '') return `${field.label} is required.`
      if (field.kind === 'decimal') {
        const number = Number(value)
        if (!Number.isFinite(number) || number <= 0) return `${field.label} must be a number greater than zero.`
      }
      if (field.kind === 'integer') {
        const number = Number(value)
        if (!Number.isInteger(number) || number < 0) return `${field.label} must be a whole number of zero or more.`
      }
    }
    return null
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    setError(null)
    correct.mutate({ clauseId, values: changed })
  }

  return (
    <Disclosure label="Correct the values">
      <form className="rv-ws-addf" onSubmit={submit} aria-busy={correct.isPending} noValidate>
        {fields.map((field) => {
          const inputId = `fact-${clauseId}-${fact.factSchema}-${field.field}`
          return (
            <label key={field.field} htmlFor={inputId}>
              {field.label}
              {field.kind === 'boolean' ? (
                <select id={inputId} value={draft[field.field] ?? 'false'} onChange={(event) => set(field.field, event.target.value)} disabled={correct.isPending}>
                  <option value="true">{field.booleanLabels?.whenTrue ?? 'Yes'}</option>
                  <option value="false">{field.booleanLabels?.whenFalse ?? 'No'}</option>
                </select>
              ) : field.kind === 'enum' ? (
                <select id={inputId} value={draft[field.field] ?? ''} onChange={(event) => set(field.field, event.target.value)} disabled={correct.isPending}>
                  {(field.options ?? []).map((option) => (
                    <option key={option} value={option}>
                      {field.optionLabels?.[option] ?? humanizeCode(option)}
                    </option>
                  ))}
                </select>
              ) : (
                <input
                  id={inputId}
                  type="number"
                  step={field.kind === 'decimal' ? '0.1' : '1'}
                  value={draft[field.field] ?? ''}
                  onChange={(event) => set(field.field, event.target.value)}
                  disabled={correct.isPending}
                />
              )}
            </label>
          )
        })}
        {error ? (
          <p role="alert" className="rv-ws-addf-err">
            {error}
          </p>
        ) : null}
        <div className="rv-ws-addf-act">
          <button type="submit" className="rv-btn rv-btn--compact" disabled={correct.isPending || !hasChanges}>
            {correct.isPending ? 'Saving…' : 'Save values'}
          </button>
        </div>
      </form>
      {correct.isError ? <CorrectionFailure error={correct.error} onRefresh={onRefresh} /> : null}
      {correct.isSuccess && !correct.isPending ? (
        <p role="status" className="rv-ws-caption">
          Values updated. Rivell&apos;s own reading is kept beside them.
        </p>
      ) : null}
    </Disclosure>
  )
}

function FactSection({
  fact,
  machineFact,
  interpretations,
  editor,
  regionLabels,
  onNavigateToNode,
}: {
  fact: SemanticFact
  machineFact: SemanticFact | null
  interpretations: SemanticInterpretation[]
  editor: ReactNode
  regionLabels: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
  const machineRows = new Map((machineFact ? describeFact(machineFact) : []).map((row) => [row.field, row.value]))
  const divergence = new Map(factDivergence(machineFact ? [machineFact] : [], [fact]).map((entry) => [entry.field, entry]))
  const interpretationByField = new Map(
    interpretations.filter((entry) => entry.field in fact.values).map((entry) => [entry.field, entry]),
  )
  return (
    <div className="rv-ws-sec">
      <h3 className="rv-ws-sec-h">{factSchemaLabel(fact.factSchema)}</h3>
      <dl className="rv-ws-props">
        {describeFact(fact).map((row) => {
          const corrected = divergence.get(row.field)
          const interpretation = interpretationByField.get(row.field)
          return (
            <div key={row.field}>
              <dt>{row.label}</dt>
              <dd>
                <span>{row.value}</span>{' '}
                {corrected ? (
                  <span className="rv-ws-chip">Corrected by you</span>
                ) : interpretation ? (
                  <span className="rv-ws-chip">{verificationLabel(interpretation.verification)}</span>
                ) : null}
                {corrected ? (
                  <span className="rv-ws-cd-machine">
                    Rivell read: {machineRows.get(row.field) ?? corrected.machineValue}
                  </span>
                ) : null}
                {interpretation ? (
                  <span className="rv-ws-cd-machine">
                    {interpretationKindDescription(interpretation.kind)}{' '}
                    <RegionButtons nodeIds={interpretation.sourceNodeIds} regionLabels={regionLabels} onNavigateToNode={onNavigateToNode} empty="no source region recorded" />
                  </span>
                ) : null}
              </dd>
            </div>
          )
        })}
      </dl>
      {editor}
    </div>
  )
}

/**
 * One clause's semantics, in place in the review workspace: the topic Rivell read (and the
 * reviewer's correction beside it), extracted facts, the defined terms it uses, the references it
 * makes and its relationships to other clauses. Corrections feed the next review of this contract;
 * they never change this review, which is frozen.
 */
export function ClauseDetails({ clause, semantics, nodes, contractId, versionNumber, editable, latestVersionNumber, regionLabels, onNavigateToNode, onRefresh }: Props) {
  const { labelFor } = useTaxonomy()
  const headingId = useId()
  const effective = effectivePrimaryConcept(clause)
  const machine = machinePrimaryConcept(clause)
  const inClause = new Set(subtreeNodes(nodes, clause.headingNodeId).map((node) => node.id))
  const machineBySchema = new Map(clause.machineFacts.map((fact) => [fact.factSchema, fact]))
  const covered = new Set(clause.effectiveFacts.flatMap((fact) => Object.keys(fact.values)))
  const otherInterpretations = clause.interpretations.filter((entry) => !covered.has(entry.field))
  const terms = semantics.definedTerms.filter(
    (term) => !inClause.has(term.definingNodeId) && term.referenceNodeIds.some((nodeId) => inClause.has(nodeId)),
  )
  const references = semantics.references.filter((reference) => reference.sourceNodeId !== null && inClause.has(reference.sourceNodeId))
  const relationships = semantics.relationships.filter(
    (relationship) => relationship.sourceRegionNodeId === clause.headingNodeId || relationship.targetRegionNodeId === clause.headingNodeId,
  )

  return (
    <article className="rv-ws-srcd" aria-labelledby={headingId}>
      <div className="rv-ws-kicker">
        <span>{regionKindLabel(clause.regionKind)}</span>
        {clause.corrected ? <span className="rv-ws-chip">Corrected by you</span> : null}
      </div>
      <h2 id={headingId}>{clause.heading}</h2>
      <div className="rv-ws-srcd-acts">
        <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(clause.headingNodeId)}>
          View in document
        </button>
      </div>

      <div className="rv-ws-sec">
        <h3 className="rv-ws-sec-h">What the clause is about</h3>
        <dl className="rv-ws-props">
          <div>
            <dt>Topic</dt>
            <dd>{effective ? labelFor(effective) : 'Not determined'}</dd>
          </div>
          {machine && machine !== effective ? (
            <div>
              <dt>Rivell read</dt>
              <dd>{labelFor(machine)}</dd>
            </div>
          ) : null}
          {clause.secondaryConcepts.length > 0 ? (
            <div>
              <dt>Also relates to</dt>
              <dd>{clause.secondaryConcepts.map((concept) => labelFor(concept)).join(', ')}</dd>
            </div>
          ) : null}
        </dl>
        {editable ? (
          <Disclosure label="Correct the topic">
            <ConceptEditor clause={clause} contractId={contractId} versionNumber={versionNumber} onRefresh={onRefresh} />
          </Disclosure>
        ) : null}
      </div>

      {clause.effectiveFacts.length === 0 && otherInterpretations.length === 0 ? (
        <p className="rv-ws-caption">No typed facts were extracted for this clause.</p>
      ) : null}
      {clause.effectiveFacts.map((fact) => (
        <FactSection
          key={fact.factSchema}
          fact={fact}
          machineFact={machineBySchema.get(fact.factSchema) ?? null}
          interpretations={clause.interpretations}
          regionLabels={regionLabels}
          onNavigateToNode={onNavigateToNode}
          editor={editable ? <FactEditor fact={fact} clauseId={clause.id} contractId={contractId} versionNumber={versionNumber} onRefresh={onRefresh} /> : null}
        />
      ))}
      {otherInterpretations.length > 0 ? (
        <div className="rv-ws-sec">
          <h3 className="rv-ws-sec-h">Other interpreted fields</h3>
          <dl className="rv-ws-props">
            {otherInterpretations.map((entry) => (
              <div key={`${entry.field}-${entry.proposedValue}`}>
                <dt>{humanizeCode(entry.field)}</dt>
                <dd>
                  <span>{entry.proposedValue}</span> <span className="rv-ws-chip">{verificationLabel(entry.verification)}</span>
                  <span className="rv-ws-cd-machine">
                    <RegionButtons nodeIds={entry.sourceNodeIds} regionLabels={regionLabels} onNavigateToNode={onNavigateToNode} empty="no source region recorded" />
                  </span>
                </dd>
              </div>
            ))}
          </dl>
        </div>
      ) : null}

      {terms.length > 0 ? (
        <div className="rv-ws-sec">
          <h3 className="rv-ws-sec-h">Defined terms it uses</h3>
          <dl className="rv-ws-cd-terms">
            {terms.map((term) => (
              <div key={term.id}>
                <dt>
                  <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(term.definingNodeId)}>
                    {term.term}
                  </button>
                </dt>
                <dd>{term.definition}</dd>
              </div>
            ))}
          </dl>
        </div>
      ) : null}

      {references.length > 0 ? (
        <div className="rv-ws-sec">
          <h3 className="rv-ws-sec-h">References it makes</h3>
          <ul className="rv-ws-cov">
            {references.map((reference) => (
              <li key={reference.id}>
                <span className="rv-ws-cov-c">{reference.referenceText}</span>
                {reference.targetNodeId && regionLabels.has(reference.targetNodeId) ? (
                  <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(reference.targetNodeId as string)}>
                    {regionLabels.get(reference.targetNodeId)}
                  </button>
                ) : (
                  <span className="rv-ws-chip">{referenceStatusLabel(reference.status)}</span>
                )}
              </li>
            ))}
          </ul>
        </div>
      ) : null}

      {relationships.length > 0 ? (
        <div className="rv-ws-sec">
          <h3 className="rv-ws-sec-h">Related clauses</h3>
          <ul className="rv-ws-cov">
            {relationships.map((relationship) => {
              const outgoing = relationship.sourceRegionNodeId === clause.headingNodeId
              const other = outgoing ? relationship.targetRegionNodeId : relationship.sourceRegionNodeId
              const otherLink = (
                <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(other)}>
                  {regionLabels.get(other) ?? 'Another clause'}
                </button>
              )
              return (
                <li key={relationship.id}>
                  {outgoing ? <span className="rv-ws-cov-e">This clause</span> : otherLink}
                  <span className="rv-ws-cov-e">{relationshipTypeVerb(relationship.type)}</span>
                  {outgoing ? otherLink : <span className="rv-ws-cov-e">this clause</span>}
                </li>
              )
            })}
          </ul>
        </div>
      ) : null}

      <p className="rv-ws-caption">
        {editable
          ? 'A correction is used by the next review of this contract. It does not change this review, which stays as it was when it was created.'
          : `Corrections apply to the latest contract version${latestVersionNumber ? ` (version ${latestVersionNumber})` : ''}. This review uses version ${versionNumber}, so this view is read-only.`}
      </p>
    </article>
  )
}
