import type { EvidenceItem } from '../../api/retrieval'
import { conceptLabel } from '../../lib/contractSemantics'
import { authorityLabel } from '../../lib/evidence'
import { ApplicabilityScopeSummary } from './ApplicabilityScopeSummary'
import { EvidenceItemSource } from './EvidenceItemSource'
import { EvidenceText } from './EvidenceText'

export function ApprovedDraftingCard({ item }: { item: EvidenceItem }) {
  const headingId = `evidence-${item.evidenceId}`
  const heading = item.legalConcept ? conceptLabel(item.legalConcept) : 'Approved wording'

  return (
    <article className="evidence-card" aria-labelledby={headingId}>
      <header className="evidence-card__header">
        <h3 id={headingId}>{heading}</h3>
        <span className="badge badge--muted">{authorityLabel(item.authorityClass)}</span>
      </header>
      <ApplicabilityScopeSummary scope={item.applicabilityScope} />
      <EvidenceText item={item} label={heading} />
      <EvidenceItemSource item={item} />
    </article>
  )
}
