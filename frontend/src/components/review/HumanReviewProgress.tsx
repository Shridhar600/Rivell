import type { FindingSummary } from '../../api/reviews'
import type { HumanReview } from '../../api/workflow'
import {
  blockerLabel,
  humanReviewCountRows,
  humanStatusLabel,
} from '../../lib/workflow'
import { findingSubjectLabel } from '../../lib/reviews'

type Props = {
  review: HumanReview
  findings: FindingSummary[]
  regionLabels: Map<string, string>
  onSelectFinding: (findingId: string) => void
}

const MAX_BLOCKER_FINDING_LINKS = 3

function statusModifier(status: HumanReview['status']): string {
  switch (status) {
    case 'READY_FOR_COMPLETION':
      return 'status-badge--positive'
    case 'COMPLETED':
      return 'status-badge--complete'
    default:
      return 'status-badge--neutral'
  }
}

export function HumanReviewProgress({ review, findings, regionLabels, onSelectFinding }: Props) {
  const findingsById = new Map(findings.map((finding) => [finding.id, finding]))
  const rows = humanReviewCountRows(review)
  const showInformational =
    review.informationalFindings > 0 || review.informationalDecided > 0

  return (
    <section className="human-review" aria-labelledby="human-review-heading">
      <div className="human-review__header">
        <h2 id="human-review-heading">Human review</h2>
        {review.status === 'UNKNOWN' ? (
          <span className="status-badge status-badge--neutral">State unavailable</span>
        ) : (
          <span className={`status-badge ${statusModifier(review.status)}`}>
            {humanStatusLabel(review.status)}
          </span>
        )}
      </div>

      <dl className="human-review__counts">
        {rows.map((row) => (
          <div key={row.label} className="human-review__count">
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </div>
        ))}
        {showInformational ? (
          <div className="human-review__count">
            <dt>Informational findings decided</dt>
            <dd>
              {review.informationalDecided} of {review.informationalFindings}
            </dd>
          </div>
        ) : null}
      </dl>

      {review.status === 'COMPLETED' ? (
        <p className="human-review__readiness" role="status">
          Human review completed. The approved change set is frozen.
        </p>
      ) : review.readyToFinalize ? (
        <p className="human-review__readiness" role="status">
          All required decisions are recorded. The review can be finalized.
        </p>
      ) : review.blockers.length > 0 ? (
        <div className="human-review__blockers">
          <h3>Before this review can be finalized</h3>
          <ul>
            {review.blockers.map((blocker) => (
              <li key={blocker.code}>
                <p>{blocker.message || blockerLabel(blocker.code)}</p>
                {blocker.findingIds.length > 0 ? (
                  <ul className="human-review__blocker-findings">
                    {blocker.findingIds.slice(0, MAX_BLOCKER_FINDING_LINKS).map((findingId) => {
                      const finding = findingsById.get(findingId)
                      return (
                        <li key={findingId}>
                          <button
                            type="button"
                            className="link-button"
                            onClick={() => onSelectFinding(findingId)}
                          >
                            {finding
                              ? `Open finding: ${findingSubjectLabel(finding, regionLabels)}`
                              : 'Open finding'}
                          </button>
                        </li>
                      )
                    })}
                  </ul>
                ) : null}
              </li>
            ))}
          </ul>
        </div>
      ) : review.status === 'UNKNOWN' ? null : (
        <p className="panel__hint">
          Finalization is blocked until the remaining items are resolved.
        </p>
      )}
    </section>
  )
}
