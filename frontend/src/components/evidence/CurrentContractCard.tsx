import type { EvidenceItem } from '../../api/retrieval'
import {
  conceptLabel,
  describeFact,
  factSchemaLabel,
} from '../../lib/contractSemantics'
import { authorityLabel } from '../../lib/evidence'
import { EvidenceItemSource } from './EvidenceItemSource'
import { EvidenceText } from './EvidenceText'

export function CurrentContractCard({
  item,
  regionLabels,
  onNavigateToNode,
  onOpenClauseSemantics,
}: {
  item: EvidenceItem
  regionLabels: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onOpenClauseSemantics?: (clauseId: string) => void
}) {
  const headingId = `evidence-${item.evidenceId}`
  const primaryNodeId = item.provenanceNodeIds[0] ?? null
  const regionLabel = primaryNodeId ? (regionLabels.get(primaryNodeId) ?? null) : null
  const heading =
    regionLabel ?? (item.legalConcept ? conceptLabel(item.legalConcept) : 'Contract evidence')

  return (
    <article className="evidence-card evidence-card--contract" aria-labelledby={headingId}>
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
        {primaryNodeId ? (
          <button
            type="button"
            className="link-button"
            onClick={() => onNavigateToNode(primaryNodeId)}
          >
            {`View ${regionLabel ?? 'source region'} in document`}
          </button>
        ) : null}
        {onOpenClauseSemantics && item.sourceClauseId ? (
          <button
            type="button"
            className="link-button"
            onClick={() => onOpenClauseSemantics(item.sourceClauseId as string)}
          >
            Open clause semantics
          </button>
        ) : null}
      </div>
      <EvidenceItemSource item={item} />
    </article>
  )
}
