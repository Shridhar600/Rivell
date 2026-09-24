import type { EvidenceItem } from '../../api/retrieval'
import {
  conceptLabel,
  describeFact,
  factSchemaLabel,
} from '../../lib/contractSemantics'
import { authorityLabel } from '../../lib/evidence'
import { SourceRegionLinks } from '../contract/SourceRegionLinks'
import { EvidenceItemSource } from './EvidenceItemSource'
import { EvidenceText } from './EvidenceText'

export function CurrentContractCard({
  item,
  regionLabels,
  clauseHeadings,
  onNavigateToNode,
  onOpenClauseSemantics,
}: {
  item: EvidenceItem
  regionLabels: Map<string, string>
  clauseHeadings: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onOpenClauseSemantics?: (headingNodeId: string) => void
}) {
  const headingId = `evidence-${item.evidenceId}`
  const primaryNodeId = item.provenanceNodeIds[0] ?? null
  const regionLabel = primaryNodeId ? (regionLabels.get(primaryNodeId) ?? null) : null
  const heading =
    regionLabel ?? (item.legalConcept ? conceptLabel(item.legalConcept) : 'Contract evidence')
  const clauseHeading = item.sourceClauseId
    ? (clauseHeadings.get(item.sourceClauseId) ?? null)
    : null

  return (
    <article className="evidence-card" aria-labelledby={headingId}>
      <header className="evidence-card__header">
        <h3 id={headingId}>{heading}</h3>
        <span className="badge badge--muted">{authorityLabel(item.authorityClass)}</span>
      </header>
      <EvidenceText item={item} />
      {item.contractFacts.length > 0 ? (
        <div className="evidence-card__facts">
          {item.contractFacts.map((fact) => (
            <div className="fact-card" key={`${fact.factSchema}-${fact.schemaVersion}`}>
              <h4>{factSchemaLabel(fact.factSchema)}</h4>
              <dl className="data-list fact-list">
                {describeFact({ ...fact, sourceNodeIds: [] }).map((row) => (
                  <div className="data-list__row" key={row.field}>
                    <dt>{row.label}</dt>
                    <dd>{row.value}</dd>
                  </div>
                ))}
              </dl>
            </div>
          ))}
        </div>
      ) : null}
      <div className="evidence-card__actions">
        <SourceRegionLinks
          nodeIds={item.provenanceNodeIds}
          labelByNodeId={regionLabels}
          onNavigateToNode={onNavigateToNode}
          prefix="View in document:"
        />
        {onOpenClauseSemantics && clauseHeading ? (
          <button
            type="button"
            className="link-button"
            onClick={() => onOpenClauseSemantics(clauseHeading)}
          >
            Open clause semantics
          </button>
        ) : null}
      </div>
      <EvidenceItemSource item={item} />
    </article>
  )
}
