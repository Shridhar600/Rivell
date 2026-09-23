import type { SemanticClause, SemanticRelationship } from '../../api/semantics'
import {
  relationshipTypeVerb,
  resolveRegionLabel,
  type RegionLabelNode,
} from '../../lib/contractSemantics'
import { VerificationBadge } from './VerificationBadge'

function RelationshipRow({
  relationship,
  clauses,
  nodes,
  onNavigateToNode,
}: {
  relationship: SemanticRelationship
  clauses: SemanticClause[]
  nodes: RegionLabelNode[]
  onNavigateToNode: (nodeId: string) => void
}) {
  const sourceLabel =
    resolveRegionLabel(relationship.sourceRegionNodeId, clauses, nodes) ??
    'Selected clause'
  const targetLabel =
    resolveRegionLabel(relationship.targetRegionNodeId, clauses, nodes) ??
    'Referenced clause'

  return (
    <li className="relationship-row">
      <div className="relationship-row__clauses">
        <button
          type="button"
          className="link-button"
          onClick={() => onNavigateToNode(relationship.sourceRegionNodeId)}
        >
          {sourceLabel}
        </button>
        <span className="relationship-row__verb">
          {relationshipTypeVerb(relationship.type)}
        </span>
        <button
          type="button"
          className="link-button"
          onClick={() => onNavigateToNode(relationship.targetRegionNodeId)}
        >
          {targetLabel}
        </button>
      </div>
      <div className="relationship-row__meta">
        <span className="relationship-row__detector">
          {relationship.detector === 'DETERMINISTIC'
            ? 'Stated in the clause text'
            : 'Inferred from the clause texts'}
        </span>
        {relationship.verification !== 'NOT_VERIFIED' ? (
          <VerificationBadge verification={relationship.verification} />
        ) : null}
      </div>
    </li>
  )
}

export function RelationshipsPanel({
  relationships,
  clauses,
  nodes,
  onNavigateToNode,
}: {
  relationships: SemanticRelationship[]
  clauses: SemanticClause[]
  nodes: RegionLabelNode[]
  onNavigateToNode: (nodeId: string) => void
}) {
  return (
    <section aria-labelledby="relationships-heading">
      <h2 id="relationships-heading" tabIndex={-1} data-section-heading>
        Clause relationships
      </h2>
      {relationships.length === 0 ? (
        <p className="semantic-empty">
          No relationships between clauses were identified in this contract version.
        </p>
      ) : (
        <>
          <p className="section-note">
            How clauses affect each other, for example a carve-out, a dependency, or a
            clause that survives another.
          </p>
          <ul className="relationship-list">
            {relationships.map((relationship) => (
              <RelationshipRow
                key={relationship.id}
                relationship={relationship}
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
