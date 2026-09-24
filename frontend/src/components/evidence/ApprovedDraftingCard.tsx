import type { EvidenceItem } from '../../api/retrieval'
import { conceptLabel } from '../../lib/contractSemantics'
import { authorityLabel } from '../../lib/evidence'
import { EvidenceItemSource } from './EvidenceItemSource'
import { EvidenceText } from './EvidenceText'

export function ApprovedDraftingCard({ item }: { item: EvidenceItem }) {
  const headingId = `evidence-${item.evidenceId}`

  return (
    <article className="evidence-card" aria-labelledby={headingId}>
      <header className="evidence-card__header">
        <h3 id={headingId}>
          {item.legalConcept ? conceptLabel(item.legalConcept) : 'Approved wording'}
        </h3>
        <span className="badge badge--muted">{authorityLabel(item.authorityClass)}</span>
      </header>
      <EvidenceText item={item} />
      <EvidenceItemSource item={item} />
    </article>
  )
}
