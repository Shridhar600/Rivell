import type { SemanticReference } from '../../api/semantics'
import { referenceKindLabel, referenceStatusLabel } from '../../lib/contractSemantics'
import { SourceRegionLinks } from './SourceRegionLinks'

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
  labelByNodeId,
  onNavigateToNode,
}: {
  reference: SemanticReference
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
  let resolution
  if (reference.status === 'RESOLVED' && reference.targetNodeId) {
    resolution = (
      <SourceRegionLinks
        nodeIds={[reference.targetNodeId]}
        labelByNodeId={labelByNodeId}
        onNavigateToNode={onNavigateToNode}
        prefix="Resolved target: "
        emptyLabel="Resolved target is not part of this document version"
      />
    )
  } else if (reference.status === 'AMBIGUOUS') {
    resolution =
      reference.candidateNodeIds.length > 0 ? (
        <SourceRegionLinks
          nodeIds={reference.candidateNodeIds}
          labelByNodeId={labelByNodeId}
          onNavigateToNode={onNavigateToNode}
          prefix="Possible targets: "
        />
      ) : (
        <span className="reference-note">Matches more than one possible target</span>
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
      <div className="reference-row__resolution">
        <SourceRegionLinks
          nodeIds={reference.sourceNodeId ? [reference.sourceNodeId] : []}
          labelByNodeId={labelByNodeId}
          onNavigateToNode={onNavigateToNode}
          prefix="Occurrence: "
          emptyLabel="Occurrence region not available"
        />
        {resolution}
      </div>
    </li>
  )
}

export function ReferencesPanel({
  references,
  labelByNodeId,
  onNavigateToNode,
}: {
  references: SemanticReference[]
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
}) {
  const sorted = [...references].sort((a, b) => statusOrder(a) - statusOrder(b))

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
            Where each reference appears, and where it points.
          </p>
          <ul className="reference-list">
            {sorted.map((reference) => (
              <ReferenceRow
                key={reference.id}
                reference={reference}
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
