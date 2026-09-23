import type { ReactNode } from 'react'
import type { SemanticClause, SemanticReference } from '../../api/semantics'
import { referenceKindLabel, referenceStatusLabel } from '../../lib/contractSemantics'
import type { RegionLabelNode } from '../../lib/contractSemantics'
import { resolveRegionLabel } from '../../lib/contractSemantics'

function statusOrder(reference: SemanticReference): number {
  switch (reference.status) {
    case 'BROKEN':
      return 0
    case 'AMBIGUOUS':
      return 1
    case 'EXTERNAL':
      return 2
    case 'RESOLVED':
      return 3
    default:
      return 4
  }
}

function ReferenceRow({
  reference,
  clauses,
  nodes,
  onNavigateToNode,
}: {
  reference: SemanticReference
  clauses: SemanticClause[]
  nodes: RegionLabelNode[]
  onNavigateToNode: (nodeId: string) => void
}) {
  const targetLabel = reference.targetNodeId
    ? resolveRegionLabel(reference.targetNodeId, clauses, nodes)
    : null

  let resolution: ReactNode
  if (reference.status === 'RESOLVED' && reference.targetNodeId) {
    resolution = (
      <button
        type="button"
        className="link-button"
        onClick={() => onNavigateToNode(reference.targetNodeId as string)}
      >
        Go to {targetLabel ?? 'referenced region'}
      </button>
    )
  } else if (reference.status === 'AMBIGUOUS') {
    resolution = (
      <span className="reference-note">
        {reference.candidateCount === 1
          ? 'Matches more than one possible target'
          : `Matches ${reference.candidateCount} possible targets`}
      </span>
    )
  } else if (reference.status === 'BROKEN') {
    resolution = (
      <span className="reference-note reference-note--warning">
        No matching section was found in this document
      </span>
    )
  } else if (reference.status === 'EXTERNAL') {
    resolution = <span className="reference-note">Points outside this document</span>
  } else {
    resolution = <span className="reference-note">Not resolved</span>
  }

  return (
    <li className="reference-row">
      <div className="reference-row__main">
        <span className="reference-row__text">{reference.referenceText}</span>
        <span className="reference-row__kind">
          {referenceKindLabel(reference.kind)}
        </span>
        <span className={`badge badge--reference-${reference.status.toLowerCase()}`}>
          {referenceStatusLabel(reference.status)}
        </span>
      </div>
      <div className="reference-row__resolution">{resolution}</div>
    </li>
  )
}

export function ReferencesPanel({
  references,
  clauses,
  nodes,
  onNavigateToNode,
}: {
  references: SemanticReference[]
  clauses: SemanticClause[]
  nodes: RegionLabelNode[]
  onNavigateToNode: (nodeId: string) => void
}) {
  const sorted = [...references].sort(
    (a, b) => statusOrder(a) - statusOrder(b),
  )

  return (
    <section aria-labelledby="references-heading">
      <h2 id="references-heading" tabIndex={-1} data-section-heading>
        Cross-references
      </h2>
      {references.length === 0 ? (
        <p className="semantic-empty">
          No internal cross-references were identified in this contract version.
        </p>
      ) : (
        <>
          <p className="section-note">
            References between parts of this contract and where they point.
          </p>
          <ul className="reference-list">
            {sorted.map((reference) => (
              <ReferenceRow
                key={reference.id}
                reference={reference}
                clauses={clauses}
                nodes={nodes}
                onNavigateToNode={onNavigateToNode}
              />
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
