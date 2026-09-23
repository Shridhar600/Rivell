import type { SemanticClause } from '../../api/semantics'
import { conceptLabel } from '../../lib/contractSemantics'

export function ClauseSemanticSummary({
  clause,
  onOpen,
}: {
  clause: SemanticClause
  onOpen: () => void
}) {
  const factCount = clause.effectiveFacts.length

  return (
    <section aria-labelledby="clause-summary-heading" className="panel clause-summary">
      <h3 id="clause-summary-heading">Clause semantics</h3>
      {clause.primaryConcept ? (
        <p className="clause-summary__concept">
          <span className="clause-summary__label">Primary concept</span>
          <span className="chip chip--primary">
            {conceptLabel(clause.primaryConcept)}
          </span>
        </p>
      ) : (
        <p className="semantic-empty">No primary concept was determined.</p>
      )}
      {clause.secondaryConcepts.length > 0 ? (
        <p className="clause-summary__concept">
          <span className="clause-summary__label">Also relates to</span>
          <span>
            {clause.secondaryConcepts.map((concept) => conceptLabel(concept)).join(', ')}
          </span>
        </p>
      ) : null}
      <p className="clause-summary__facts">
        {factCount === 0
          ? 'No typed facts were extracted for this clause.'
          : factCount === 1
            ? '1 typed fact was extracted for this clause.'
            : `${factCount} typed facts were extracted for this clause.`}
      </p>
      <button type="button" className="link-button" onClick={onOpen}>
        Open clause semantics
      </button>
    </section>
  )
}
