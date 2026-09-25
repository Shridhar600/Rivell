import { Link } from 'react-router'
import type { EvidenceItem } from '../../api/retrieval'
import { ApprovedDraftingCard } from '../evidence/ApprovedDraftingCard'
import { CurrentContractCard } from '../evidence/CurrentContractCard'
import { GenericEvidenceCard } from '../evidence/GenericEvidenceCard'
import { PolicyEvidenceCard } from '../evidence/PolicyEvidenceCard'
import { PrecedentCard } from '../evidence/PrecedentCard'

const LANES: { authority: string; title: string }[] = [
  { authority: 'POLICY', title: 'Policy' },
  { authority: 'APPROVED_DRAFTING', title: 'Approved drafting' },
  { authority: 'CURRENT_CONTRACT', title: 'Current contract' },
  { authority: 'PRECEDENT', title: 'Precedent' },
  { authority: 'EXTERNAL_REFERENCE', title: 'External reference' },
]

function cardFor(
  item: EvidenceItem,
  props: {
    regionLabels: Map<string, string>
    clauseHeadings: Map<string, string>
    onNavigateToNode: (nodeId: string) => void
    onOpenClauseSemantics: (headingNodeId: string) => void
  },
) {
  switch (item.authorityClass) {
    case 'POLICY':
      return <PolicyEvidenceCard item={item} />
    case 'APPROVED_DRAFTING':
      return <ApprovedDraftingCard item={item} />
    case 'PRECEDENT':
      return <PrecedentCard item={item} />
    case 'CURRENT_CONTRACT':
      return (
        <CurrentContractCard
          item={item}
          regionLabels={props.regionLabels}
          clauseHeadings={props.clauseHeadings}
          onNavigateToNode={props.onNavigateToNode}
          onOpenClauseSemantics={props.onOpenClauseSemantics}
        />
      )
    default:
      return <GenericEvidenceCard item={item} />
  }
}

export type RelatedProvision = { nodeId: string; label: string }

type Props = {
  items: EvidenceItem[]
  relatedProvisions: RelatedProvision[]
  canDeepLink: boolean
  contractId: string
  regionLabels: Map<string, string>
  clauseHeadings: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onOpenClauseSemantics: (headingNodeId: string) => void
}

export function FindingEvidencePanel({
  items,
  relatedProvisions,
  canDeepLink,
  contractId,
  regionLabels,
  clauseHeadings,
  onNavigateToNode,
  onOpenClauseSemantics,
}: Props) {
  const lanes = LANES.map((lane) => ({
    ...lane,
    items: items.filter((item) => item.authorityClass === lane.authority),
  })).filter((lane) => lane.items.length > 0)
  const otherItems = items.filter(
    (item) => !LANES.some((lane) => lane.authority === item.authorityClass),
  )

  return (
    <section className="finding-section" aria-labelledby="finding-evidence-heading">
      <h3 id="finding-evidence-heading">Evidence</h3>
      <p className="panel__hint">
        The exact evidence recorded by this review's analysis, grouped by authority.
      </p>

      {lanes.map((lane) => (
        <section
          key={lane.authority}
          className="finding-evidence__lane"
          aria-labelledby={`finding-evidence-${lane.authority}`}
        >
          <h4 id={`finding-evidence-${lane.authority}`}>{lane.title}</h4>
          <ul className="evidence-list">
            {lane.items.map((item) => (
              <li key={item.evidenceId} id={`finding-evidence-${item.evidenceId}`}>
                {cardFor(item, {
                  regionLabels,
                  clauseHeadings,
                  onNavigateToNode,
                  onOpenClauseSemantics,
                })}
              </li>
            ))}
          </ul>
        </section>
      ))}

      {otherItems.length > 0 ? (
        <section className="finding-evidence__lane" aria-labelledby="finding-evidence-other">
          <h4 id="finding-evidence-other">Other evidence</h4>
          <ul className="evidence-list">
            {otherItems.map((item) => (
              <li key={item.evidenceId} id={`finding-evidence-${item.evidenceId}`}>
                {cardFor(item, {
                  regionLabels,
                  clauseHeadings,
                  onNavigateToNode,
                  onOpenClauseSemantics,
                })}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {relatedProvisions.length > 0 ? (
        <section className="finding-evidence__lane" aria-labelledby="finding-related-heading">
          <h4 id="finding-related-heading">Related provisions</h4>
          <ul className="source-links">
            {relatedProvisions.map((provision) => (
              <li key={provision.nodeId}>
                {canDeepLink ? (
                  <Link to={`/reviews/${contractId}?view=document&node=${provision.nodeId}`}>
                    {provision.label}
                  </Link>
                ) : (
                  <span>{provision.label}</span>
                )}
              </li>
            ))}
          </ul>
          {!canDeepLink ? (
            <p className="panel__hint">
              This review is pinned to an earlier contract version; open the contract to view the
              current version.
            </p>
          ) : null}
        </section>
      ) : null}
    </section>
  )
}
