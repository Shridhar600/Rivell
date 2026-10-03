import type { EvidenceItem } from '../../api/retrieval'
import { ApprovedDraftingCard } from '../evidence/ApprovedDraftingCard'
import { CurrentContractCard } from '../evidence/CurrentContractCard'
import { GenericEvidenceCard } from '../evidence/GenericEvidenceCard'
import { PolicyEvidenceCard } from '../evidence/PolicyEvidenceCard'
import { PrecedentCard } from '../evidence/PrecedentCard'

interface Props {
  item: EvidenceItem
  regionLabels: Map<string, string>
  clauseHeadings: Map<string, string>
  /** Deep links open the contract's latest version, so they are given only when the review matches it. */
  onNavigateToNode?: (nodeId: string) => void
  onOpenClauseSemantics?: (headingNodeId: string) => void
}

/** The full evidence card for one item, exactly as the review's analysis recorded it. */
export function SourceContent({ item, regionLabels, clauseHeadings, onNavigateToNode, onOpenClauseSemantics }: Props) {
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
