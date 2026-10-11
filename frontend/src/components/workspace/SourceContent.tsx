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
  /** Reveals the node in the workspace document, which is the version the review pinned. */
  onNavigateToNode?: (nodeId: string) => void
  /** Clause semantics open the contract's latest version, so given only when the review matches it. */
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
