import type { SemanticClause } from '../../api/semantics'
import { useTaxonomy } from '../../api/taxonomy'
import {
  effectivePrimaryConcept,
  machinePrimaryConcept,
  regionKindLabel,
} from '../../lib/contractSemantics'

export function ClauseSemanticSummary({
  clause,
  onOpen,
}: {
  clause: SemanticClause
  onOpen: () => void
}) {
  const { labelFor } = useTaxonomy()
  const factCount = clause.effectiveFacts.length

  const isClause = clause.regionKind === 'CLAUSE'

  return (
    <section aria-labelledby="clause-summary-heading" className="panel clause-summary">
      <h3 id="clause-summary-heading">
        {isClause ? 'Clause semantics' : `${regionKindLabel(clause.regionKind)} semantics`}
      </h3>
      {effectivePrimaryConcept(clause) ? (
        <p className="clause-summary__concept">
          <span className="clause-summary__label">Primary concept</span>
          <span className="chip chip--primary">
            {labelFor(effectivePrimaryConcept(clause) as string)}
          </span>
        </p>
      ) : (
        <p className="semantic-empty">No primary concept was determined.</p>
      )}
      {machinePrimaryConcept(clause) ? (
        <p className="clause-summary__machine">
          Rivell originally classified this clause as{' '}
          {labelFor(machinePrimaryConcept(clause) as string)}.
        </p>
      ) : null}
      {clause.secondaryConcepts.length > 0 ? (
        <p className="clause-summary__concept">
          <span className="clause-summary__label">Also relates to</span>
          <span>
            {clause.secondaryConcepts.map((concept) => labelFor(concept)).join(', ')}
          </span>
        </p>
      ) : null}
      <p className="clause-summary__facts">
        {factCount === 0
          ? `No typed facts were extracted for this ${isClause ? 'clause' : 'region'}.`
          : factCount === 1
            ? `1 typed fact was extracted for this ${isClause ? 'clause' : 'region'}.`
            : `${factCount} typed facts were extracted for this ${
                isClause ? 'clause' : 'region'
              }.`}
      </p>
      <button type="button" className="link-button" onClick={onOpen}>
        {isClause ? 'Open clause semantics' : 'Open region semantics'}
      </button>
    </section>
  )
}
