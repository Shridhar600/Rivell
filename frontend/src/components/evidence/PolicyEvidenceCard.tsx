import type { EvidenceItem } from '../../api/retrieval'
import { useTaxonomy } from '../../api/taxonomy'
import { authorityLabel, policyPositionLabels } from '../../lib/evidence'
import { ApplicabilityScopeSummary } from './ApplicabilityScopeSummary'
import { EvidenceItemSource } from './EvidenceItemSource'
import { EvidenceText } from './EvidenceText'

export function PolicyEvidenceCard({ item }: { item: EvidenceItem }) {
  const { labelFor } = useTaxonomy()
  const headingId = `evidence-${item.evidenceId}`
  const heading = item.legalConcept ? labelFor(item.legalConcept) : 'Policy rule'
  const positions = item.policyFacts ? policyPositionLabels(item.policyFacts.positions) : []

  return (
    <article className="evidence-card" aria-labelledby={headingId}>
      <header className="evidence-card__header">
        <h3 id={headingId}>{heading}</h3>
        <span className="badge badge--muted">{authorityLabel(item.authorityClass)}</span>
      </header>
      <ApplicabilityScopeSummary scope={item.applicabilityScope} />
      <EvidenceText item={item} label={heading} />
      {item.policyFacts ? (
        <dl className="data-list evidence-card__facts">
          {positions.length > 0 ? (
            <div className="data-list__row">
              <dt>Positions</dt>
              <dd>{positions.join(', ')}</dd>
            </div>
          ) : null}
          {item.policyFacts.requiresApproval !== null ? (
            <div className="data-list__row">
              <dt>Approval</dt>
              <dd>
                {item.policyFacts.requiresApproval
                  ? 'Approval required'
                  : 'No approval required'}
              </dd>
            </div>
          ) : null}
          {item.policyFacts.escalationTrigger ? (
            <div className="data-list__row">
              <dt>Escalation</dt>
              <dd>{item.policyFacts.escalationTrigger}</dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      <EvidenceItemSource item={item} />
    </article>
  )
}
