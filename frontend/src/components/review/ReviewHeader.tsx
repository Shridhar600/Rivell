import { Link } from 'react-router'
import type { ReviewSummary } from '../../api/reviews'
import { contractPackLabel } from '../../lib/evidence'
import { formatTimestamp } from '../../lib/knowledgeLabels'
import { reviewStatusLabel, reviewStatusModifier } from '../../lib/reviews'
import { useHeadingFocus } from '../../lib/useHeadingFocus'

function timestampOrDash(iso: string | null): string {
  return iso ? formatTimestamp(iso) : '—'
}

type Props = {
  contractId: string
  contractTitle: string
  summary: ReviewSummary
}

export function ReviewHeader({ contractId, contractTitle, summary }: Props) {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(summary.id)
  return (
    <header className="review-header">
      <div className="review-header__title">
        <p className="review-header__eyebrow">
          <Link className="link-button" to={`/reviews/${contractId}/analysis`}>
            Automated reviews
          </Link>
        </p>
        <h1 id="review-heading" tabIndex={-1} ref={headingRef}>
          {contractTitle}
        </h1>
        <p className="review-header__meta">
          <span className={`badge ${reviewStatusModifier(summary.status)}`}>
            {reviewStatusLabel(summary.status)}
          </span>
          <span>
            {summary.contractVersionNumber !== null
              ? `v${summary.contractVersionNumber}`
              : 'Version unknown'}
          </span>
          <span>
            created <time dateTime={summary.createdAt}>{timestampOrDash(summary.createdAt)}</time>
          </span>
          {summary.startedAt ? (
            <span>
              started{' '}
              <time dateTime={summary.startedAt}>{timestampOrDash(summary.startedAt)}</time>
            </span>
          ) : null}
          {summary.completedAt ? (
            <span>
              completed{' '}
              <time dateTime={summary.completedAt}>
                {timestampOrDash(summary.completedAt)}
              </time>
            </span>
          ) : null}
        </p>
      </div>

      <dl className="data-list profile-summary" aria-label="Frozen review context">
        <div className="data-list__row">
          <dt>Review state</dt>
          <dd>Frozen when this review was created</dd>
        </div>
        <div className="data-list__row">
          <dt>Knowledge publication</dt>
          <dd>
            {summary.knowledgePinNumber === null
              ? 'Knowledge pin unavailable'
              : summary.knowledgePinNumber > 0
                ? `Publication ${summary.knowledgePinNumber}`
                : 'No published knowledge at creation'}
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Contract pack</dt>
          <dd>
            {summary.contractPackKey
              ? contractPackLabel(summary.contractPackKey)
              : 'No pack (reduced checks)'}
          </dd>
        </div>
      </dl>
    </header>
  )
}