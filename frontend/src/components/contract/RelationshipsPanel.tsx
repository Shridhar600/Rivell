import type { SemanticRelationship } from '../../api/semantics'
import { relationshipTypeVerb } from '../../lib/contractSemantics'
import { VerificationBadge } from './VerificationBadge'

function detectorLabel(detector: string): string {
  if (detector === 'DETERMINISTIC') return 'Stated in the clause text'
  if (detector === 'SEMANTIC') return 'Inferred from the clause texts'
  return 'Detection method not recognised'
}

function RelationshipRow({
  relationship,
  labelByNodeId,
  onNavigateToNode,
}: {
  relationship: SemanticRelationship
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
  const sourceLabel =
    labelByNodeId.get(relationship.sourceRegionNodeId) ?? 'Selected clause'
  const targetLabel =
    labelByNodeId.get(relationship.targetRegionNodeId) ?? 'Referenced clause'

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
          {detectorLabel(relationship.detector)}
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
  labelByNodeId,
  onNavigateToNode,
}: {
  relationships: SemanticRelationship[]
  labelByNodeId: Map<string, string>
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
                labelByNodeId={labelByNodeId}
                onNavigateToNode={onNavigateToNode}
              />
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
