import { useState, type FormEvent } from 'react'
import type { SemanticClause } from '../../api/semantics'
import { useCorrectClauseConcept } from '../../api/semantics'
import {
  TAXONOMY_CONCEPTS,
  conceptLabel,
} from '../../lib/contractSemantics'
import { ClauseFacts } from './ClauseFacts'

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
}: {
  clause: SemanticClause
  contractId: string
  versionNumber: number
}) {
  const correct = useCorrectClauseConcept(contractId, versionNumber)
  const [selected, setSelected] = useState(
    clause.correctedPrimaryConcept ?? clause.primaryConcept ?? '',
  )
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
        <p role="alert" className="correction__error">
          {correct.error.message}
        </p>
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
}: {
  clause: SemanticClause
  contractId: string
  versionNumber: number
  onNavigateToNode: (nodeId: string) => void
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
        {clause.primaryConcept ? (
          <p className="clause-concept-primary">
            <span className="clause-concept-primary__label">Primary</span>
            <span className="chip chip--primary">
              {conceptLabel(clause.primaryConcept)}
            </span>
          </p>
        ) : (
          <p className="semantic-empty">
            No primary concept was determined for this clause.
          </p>
        )}
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
        />
      </div>
      <ClauseFacts
        clause={clause}
        contractId={contractId}
        versionNumber={versionNumber}
      />
    </div>
  )
}

export function ClausesPanel({
  clauses,
  selectedClauseId,
  onSelectClause,
  contractId,
  versionNumber,
  onNavigateToNode,
}: {
  clauses: SemanticClause[]
  selectedClauseId: string | null
  onSelectClause: (clauseId: string | null) => void
  contractId: string
  versionNumber: number
  onNavigateToNode: (nodeId: string) => void
}) {
  const selected =
    clauses.find((clause) => clause.id === selectedClauseId) ?? null

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
                    clause.id === selectedClauseId
                      ? 'clause-list__button clause-list__button--selected'
                      : 'clause-list__button'
                  }
                  aria-current={clause.id === selectedClauseId ? 'true' : undefined}
                  onClick={() => onSelectClause(clause.id)}
                >
                  <span className="clause-list__heading">{clause.heading}</span>
                  <span className="clause-list__meta">
                    {clause.primaryConcept
                      ? conceptLabel(clause.primaryConcept)
                      : 'No primary concept'}
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
                key={selected.id}
                clause={selected}
                contractId={contractId}
                versionNumber={versionNumber}
                onNavigateToNode={onNavigateToNode}
              />
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
