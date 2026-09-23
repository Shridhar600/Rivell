import { useState, type FormEvent } from 'react'
import type { SemanticClause } from '../../api/semantics'
import { useCorrectClauseConcept } from '../../api/semantics'
import {
  TAXONOMY_CONCEPTS,
  conceptLabel,
  effectivePrimaryConcept,
  factDivergence,
  machinePrimaryConcept,
} from '../../lib/contractSemantics'
import { ClauseFacts } from './ClauseFacts'
import { CorrectionFailure } from './CorrectionFailure'

function ConceptChips({ concepts }: { concepts: string[] }) {
  return (
    <ul className="chip-list">
      {concepts.map((concept) => (
        <li className="chip" key={concept}>
          {conceptLabel(concept)}
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
  const replacesFactCorrection =
    factDivergence(clause.machineFacts, clause.effectiveFacts).length > 0
  const effective = effectivePrimaryConcept(clause) ?? ''
  const [serverConcept, setServerConcept] = useState(effective)
  const [selected, setSelected] = useState(effective)
  if (serverConcept !== effective) {
    setServerConcept(effective)
    setSelected(effective)
  }
  const [error, setError] = useState<string | null>(null)

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
            disabled={correct.isPending}
          >
            <option value="">Select a concept…</option>
            {TAXONOMY_CONCEPTS.map((concept) => (
              <option key={concept} value={concept}>
                {conceptLabel(concept)}
              </option>
            ))}
          </select>
        </div>
        <p className="correction__note">
          This corrects what the clause is about. It is not an approval or rejection of
          the clause.
        </p>
        {replacesFactCorrection ? (
          <p className="correction__warning" role="status">
            Saving a classification correction replaces the fact-value correction on
            this clause.
          </p>
        ) : null}
        {error ? (
          <p role="alert" className="correction__error">
            {error}
          </p>
        ) : null}
        <button type="submit" disabled={correct.isPending}>
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
}: {
  clause: SemanticClause
  contractId: string
  versionNumber: number
  onNavigateToNode: (nodeId: string) => void
  onRefresh: () => void
}) {
  return (
    <div className="clause-detail">
      <div className="clause-detail__header">
        <h3 tabIndex={-1} data-clause-heading>
          {clause.heading}
        </h3>
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
              {conceptLabel(effectivePrimaryConcept(clause) as string)}
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
            {conceptLabel(machinePrimaryConcept(clause) as string)}.
          </p>
        ) : null}
        {clause.secondaryConcepts.length > 0 ? (
          <div className="clause-concept-secondary">
            <h5>Also relates to</h5>
            <ConceptChips concepts={clause.secondaryConcepts} />
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
      />
    </div>
  )
}

export function ClausesPanel({
  clauses,
  selectedClauseKey,
  onSelectClause,
  contractId,
  versionNumber,
  onNavigateToNode,
  onRefresh,
}: {
  clauses: SemanticClause[]
  selectedClauseKey: string | null
  onSelectClause: (clauseKey: string | null) => void
  contractId: string
  versionNumber: number
  onNavigateToNode: (nodeId: string) => void
  onRefresh: () => void
}) {
  const selected =
    clauses.find((clause) => clause.headingNodeId === selectedClauseKey) ?? null

  return (
    <section aria-labelledby="clauses-heading">
      <h2 id="clauses-heading" tabIndex={-1} data-section-heading>
        Clause semantics
      </h2>
      {clauses.length === 0 ? (
        <p className="semantic-empty">
          No clause regions were identified in this contract version.
        </p>
      ) : (
        <div className="clause-workspace">
          <div className="clause-list" role="list">
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
                      ? conceptLabel(effectivePrimaryConcept(clause) as string)
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
