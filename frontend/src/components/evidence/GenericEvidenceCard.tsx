import type { EvidenceItem } from '../../api/retrieval'
import { useTaxonomy } from '../../api/taxonomy'
import { authorityLabel, sourceTypeLabel } from '../../lib/evidence'
import { ApplicabilityScopeSummary } from './ApplicabilityScopeSummary'
import { EvidenceItemSource } from './EvidenceItemSource'
import { EvidenceText } from './EvidenceText'

export function GenericEvidenceCard({ item }: { item: EvidenceItem }) {
  const { labelFor } = useTaxonomy()
  const headingId = `evidence-${item.evidenceId}`
  const heading =
    item.legalConcept && item.legalConcept !== ''
      ? labelFor(item.legalConcept)
      : sourceTypeLabel(item.sourceType)

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
