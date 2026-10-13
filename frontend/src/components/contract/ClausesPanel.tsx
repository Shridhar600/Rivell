import { useState, type FormEvent } from 'react'
import type { SemanticClause } from '../../api/semantics'
import { useCorrectClauseConcept } from '../../api/semantics'
import { useTaxonomy } from '../../api/taxonomy'
import { humanizeCode } from '../../lib/domainLabels'
import {
  effectivePrimaryConcept,
  machinePrimaryConcept,
  regionKindLabel,
} from '../../lib/contractSemantics'
import { ClauseFacts } from './ClauseFacts'
import { CorrectionFailure } from './CorrectionFailure'

function ConceptChips({ concepts, labelFor }: { concepts: string[]; labelFor: (key: string) => string }) {
  return (
    <ul className="chip-list">
      {concepts.map((concept) => (
        <li className="chip" key={concept}>
          {labelFor(concept)}
        </li>
      ))}
    </ul>
  )
}

function ConceptEditor({
  clause,
  contractId,
  versionNumber,
  onRefresh,
}: {
  clause: SemanticClause
  contractId: string
  versionNumber: number
  onRefresh: () => void
}) {
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
  const selectedKnown =
    selected === '' ||
    selectedIsLeaf ||
    taxonomy.parents.some((parent) => parent.key === selected)

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (!selected) {
      setError('Choose the concept this clause expresses.')
      return
    }
    setError(null)
    correct.mutate({ clauseId: clause.id, primary: selected })
  }

  return (
    <details className="correction">
      <summary>Correct classification</summary>
      <form onSubmit={handleSubmit} aria-busy={correct.isPending}>
        <div className="profile-form__field">
          <label htmlFor={`concept-${clause.id}`}>Primary concept</label>
          <select
            id={`concept-${clause.id}`}
            value={selected}
            onChange={(event) => setSelected(event.target.value)}
            disabled={pickerDisabled}
          >
            <option value="">Select a concept…</option>
            {selectedIsLeaf ? null : (
              <option value={selected}>
                {humanizeCode(selected)}
                {!taxonomy.isPending && !taxonomy.isError && !selectedKnown
                  ? ' (not in the current taxonomy)'
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
          {taxonomy.isPending ? (
            <small role="status">Loading legal topics…</small>
          ) : null}
          {taxonomy.isError ? (
            <small role="status">
              The legal topic list could not be loaded. Classification cannot be changed right now.
            </small>
          ) : null}
        </div>
        <p className="correction__note">
          This corrects what the clause is about. It is not an approval or rejection of
          the clause.
        </p>
        {error ? (
          <p role="alert" className="correction__error">
            {error}
          </p>
        ) : null}
        <button type="submit" disabled={pickerDisabled}>
          {correct.isPending ? 'Saving…' : 'Save classification'}
        </button>
      </form>
      {correct.isError ? (
        <CorrectionFailure error={correct.error} onRefresh={onRefresh} />
      ) : null}
      {correct.isSuccess && !correct.isPending ? (
        <p role="status" className="correction__status">
          Classification updated. Rivell&apos;s original classification is kept.
        </p>
      ) : null}
    </details>
  )
}

function ClauseDetail({
  clause,
  contractId,
  versionNumber,
  onNavigateToNode,
  onRefresh,
  labelByNodeId,
}: {
  clause: SemanticClause
  contractId: string
  versionNumber: number
  onNavigateToNode: (nodeId: string) => void
  onRefresh: () => void
  labelByNodeId: Map<string, string>
}) {
  const { labelFor } = useTaxonomy()
  return (
    <div className="clause-detail">
      <div className="clause-detail__header">
        <h3 tabIndex={-1} data-clause-heading>
          {clause.heading}
        </h3>
        {clause.regionKind !== 'CLAUSE' ? (
          <span className="badge">{regionKindLabel(clause.regionKind)}</span>
        ) : null}
        {clause.corrected ? (
          <span className="badge badge--edited">Corrected</span>
        ) : null}
        <button
          type="button"
          className="link-button"
          onClick={() => onNavigateToNode(clause.headingNodeId)}
        >
          View clause in document
        </button>
      </div>
      <div className="clause-detail__concepts">
        <h4>Concepts</h4>
        {effectivePrimaryConcept(clause) ? (
          <p className="clause-concept-primary">
            <span className="clause-concept-primary__label">Primary</span>
            <span className="chip chip--primary">
              {labelFor(effectivePrimaryConcept(clause) as string)}
            </span>
          </p>
        ) : (
          <p className="semantic-empty">
            No primary concept was determined for this clause.
          </p>
        )}
        {machinePrimaryConcept(clause) ? (
          <p className="clause-concept-machine">
            Rivell originally classified this clause as{' '}
            {labelFor(machinePrimaryConcept(clause) as string)}.
          </p>
        ) : null}
        {clause.secondaryConcepts.length > 0 ? (
          <div className="clause-concept-secondary">
            <h5>Also relates to</h5>
            <ConceptChips concepts={clause.secondaryConcepts} labelFor={labelFor} />
          </div>
        ) : null}
        <ConceptEditor
          clause={clause}
          contractId={contractId}
          versionNumber={versionNumber}
          onRefresh={onRefresh}
        />
      </div>
      <ClauseFacts
        clause={clause}
        contractId={contractId}
        versionNumber={versionNumber}
        onRefresh={onRefresh}
        labelByNodeId={labelByNodeId}
        onNavigateToNode={onNavigateToNode}
      />
    </div>
  )
}

export function ClausesPanel({
  clauses,
  structuralRegions,
  selectedClauseKey,
  onSelectClause,
  contractId,
  versionNumber,
  onNavigateToNode,
  onRefresh,
  labelByNodeId,
}: {
  clauses: SemanticClause[]
  structuralRegions: SemanticClause[]
  selectedClauseKey: string | null
  onSelectClause: (clauseKey: string | null) => void
  contractId: string
  versionNumber: number
  onNavigateToNode: (nodeId: string) => void
  onRefresh: () => void
  labelByNodeId: Map<string, string>
}) {
  const selected =
    clauses.find((clause) => clause.headingNodeId === selectedClauseKey) ??
    structuralRegions.find(
      (region) => region.headingNodeId === selectedClauseKey,
    ) ??
    null
  const { labelFor } = useTaxonomy()

  return (
    <section aria-labelledby="clauses-heading">
      <h2 id="clauses-heading" tabIndex={-1} data-section-heading>
        Clause semantics
      </h2>
      {clauses.length === 0 && structuralRegions.length === 0 ? (
        <p className="semantic-empty">
          No clause regions were identified in this contract version.
        </p>
      ) : (
        <div className="clause-workspace">
          <div className="clause-list" role="list">
            {clauses.length > 0 ? (
              <h3 className="clause-list__group">Clauses</h3>
            ) : null}
            {clauses.map((clause) => (
              <div role="listitem" key={clause.id}>
                <button
                  type="button"
                  className={
                    clause.headingNodeId === selectedClauseKey
                      ? 'clause-list__button clause-list__button--selected'
                      : 'clause-list__button'
                  }
                  aria-current={
                    clause.headingNodeId === selectedClauseKey ? 'true' : undefined
                  }
                  onClick={() => onSelectClause(clause.headingNodeId)}
                >
                  <span className="clause-list__heading">{clause.heading}</span>
                  <span className="clause-list__meta">
                    {effectivePrimaryConcept(clause)
                      ? labelFor(effectivePrimaryConcept(clause) as string)
                      : 'No primary concept'}
                    {clause.secondaryConcepts.length > 0
                      ? ` · +${clause.secondaryConcepts.length} more`
                      : ''}
                    {clause.effectiveFacts.length > 0
                      ? ` · ${clause.effectiveFacts.length} fact${
                          clause.effectiveFacts.length === 1 ? '' : 's'
                        }`
                      : ''}
                  </span>
                </button>
              </div>
            ))}
            {structuralRegions.length > 0 ? (
              <>
                <h3 className="clause-list__group">
                  Schedules and other regions
                </h3>
                {structuralRegions.map((region) => (
                  <div role="listitem" key={region.id}>
                    <button
                      type="button"
                      className={
                        region.headingNodeId === selectedClauseKey
                          ? 'clause-list__button clause-list__button--selected'
                          : 'clause-list__button'
                      }
                      aria-current={
                        region.headingNodeId === selectedClauseKey
                          ? 'true'
                          : undefined
                      }
                      onClick={() => onSelectClause(region.headingNodeId)}
                    >
                      <span className="clause-list__heading">{region.heading}</span>
                      <span className="clause-list__meta">
                        {regionKindLabel(region.regionKind)}
                        {effectivePrimaryConcept(region)
                          ? ` · ${labelFor(effectivePrimaryConcept(region) as string)}`
                          : ''}
                        {region.effectiveFacts.length > 0
                          ? ` · ${region.effectiveFacts.length} fact${
                              region.effectiveFacts.length === 1 ? '' : 's'
                            }`
                          : ''}
                      </span>
                    </button>
                  </div>
                ))}
              </>
            ) : null}
          </div>
          <div className="clause-workspace__detail">
            {selected ? (
              <ClauseDetail
                key={selected.headingNodeId}
                clause={selected}
                contractId={contractId}
                versionNumber={versionNumber}
                onNavigateToNode={onNavigateToNode}
                onRefresh={onRefresh}
                labelByNodeId={labelByNodeId}
              />
            ) : selectedClauseKey ? (
              <div className="state state--warning" role="status">
                <p>
                  This clause selection is not available in the current analysis. It
                  may belong to an earlier analysis run.
                </p>
                <button type="button" onClick={() => onSelectClause(null)}>
                  Clear clause selection
                </button>
              </div>
            ) : (
              <p className="semantic-empty">
                Select a clause to inspect its concepts, facts and interpretations.
              </p>
            )}
          </div>
        </div>
      )}
    </section>
  )
}
