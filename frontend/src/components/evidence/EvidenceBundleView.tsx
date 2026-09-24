import type { EvidenceBundle, EvidenceItem } from '../../api/retrieval'
import { POLICY_MISSING_PRECEDENT_NOTE, evidenceLanesFor } from '../../lib/evidence'
import { ApprovedDraftingCard } from './ApprovedDraftingCard'
import { CurrentContractCard } from './CurrentContractCard'
import { EvidenceLane } from './EvidenceLane'
import { EvidenceSufficiencyBanner } from './EvidenceSufficiencyBanner'
import { EvidenceWarnings } from './EvidenceWarnings'
import { GenericEvidenceCard } from './GenericEvidenceCard'
import { PolicyEvidenceCard } from './PolicyEvidenceCard'
import { PrecedentCard } from './PrecedentCard'

export function EvidenceBundleView({
  bundle,
  regionLabels,
  clauseHeadings,
  onNavigateToNode,
  onOpenClauseSemantics,
}: {
  bundle: EvidenceBundle
  regionLabels: Map<string, string>
  clauseHeadings: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onOpenClauseSemantics?: (headingNodeId: string) => void
}) {
  const lanes = evidenceLanesFor(bundle.items, bundle.sufficiency)
  const precedentCount = bundle.items.filter(
    (item) => item.authorityClass === 'PRECEDENT',
  ).length

  const renderCard = (item: EvidenceItem) => {
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
            regionLabels={regionLabels}
            clauseHeadings={clauseHeadings}
            onNavigateToNode={onNavigateToNode}
            onOpenClauseSemantics={onOpenClauseSemantics}
          />
        )
      default:
        return <GenericEvidenceCard item={item} />
    }
  }

  return (
    <div className="evidence-bundle">
      <EvidenceSufficiencyBanner sufficiency={bundle.sufficiency} />
      <EvidenceWarnings warnings={bundle.degradationWarnings} />
      {lanes.map(({ lane, items }) => (
        <EvidenceLane
          key={lane.authority}
          lane={lane}
          count={items.length}
          emptyNote={
            lane.authority === 'POLICY' &&
            bundle.sufficiency === 'POLICY_MISSING' &&
            precedentCount > 0
              ? POLICY_MISSING_PRECEDENT_NOTE
              : undefined
          }
        >
          {items.map((item) => (
            <li key={item.evidenceId} className="evidence-list__item">
              {renderCard(item)}
            </li>
          ))}
        </EvidenceLane>
      ))}
    </div>
  )
}
