import type { EvidenceItem } from '../../api/retrieval'
import { useTaxonomy } from '../../api/taxonomy'
import {
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
  /** Absent when the review is pinned to an older version than the one the links would open. */
  onNavigateToNode?: (nodeId: string) => void
  onOpenClauseSemantics?: (headingNodeId: string) => void
}) {
  const { labelFor } = useTaxonomy()
  const headingId = `evidence-${item.evidenceId}`
  const primaryNodeId = item.provenanceNodeIds[0] ?? null
  const regionLabel = primaryNodeId ? (regionLabels.get(primaryNodeId) ?? null) : null
  const heading =
    regionLabel ?? (item.legalConcept ? labelFor(item.legalConcept) : 'Contract evidence')
  const clauseHeading = item.sourceClauseId
    ? (clauseHeadings.get(item.sourceClauseId) ?? null)
    : null

  return (
    <article className="evidence-card" aria-labelledby={headingId}>
      <header className="evidence-card__header">
        <h3 id={headingId}>{heading}</h3>
        <span className="badge badge--muted">{authorityLabel(item.authorityClass)}</span>
      </header>
      <EvidenceText item={item} label={heading} />
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
        {onNavigateToNode ? (
          <SourceRegionLinks
            nodeIds={item.provenanceNodeIds}
            labelByNodeId={regionLabels}
            onNavigateToNode={onNavigateToNode}
            prefix="View in document:"
          />
        ) : null}
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
