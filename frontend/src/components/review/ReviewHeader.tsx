import { Link } from 'react-router'
import type { ReviewSummary } from '../../api/reviews'
import { contractPackLabel } from '../../lib/evidence'
import { reviewStatusLabel, reviewStatusModifier } from '../../lib/reviews'

function formatTimestamp(iso: string | null): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return iso
  return date.toLocaleString()
}

type Props = {
  contractId: string
  contractTitle: string
  summary: ReviewSummary
}

export function ReviewHeader({ contractId, contractTitle, summary }: Props) {
  return (
    <header className="review-header">
      <div className="review-header__title">
        <p className="review-header__eyebrow">
          <Link className="link-button" to={`/reviews/${contractId}/analysis`}>
            Automated reviews
          </Link>
        </p>
        <h1 id="review-heading" tabIndex={-1}>
          {contractTitle}
        </h1>
        <p className="review-header__meta">
          <span className={`badge ${reviewStatusModifier(summary.status)}`}>
            {reviewStatusLabel(summary.status)}
          </span>
          <span>v{summary.contractVersionNumber}</span>
          <span>
            started <time dateTime={summary.createdAt}>{formatTimestamp(summary.createdAt)}</time>
          </span>
          {summary.completedAt ? (
            <span>
              completed{' '}
              <time dateTime={summary.completedAt}>
                {formatTimestamp(summary.completedAt)}
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
            {summary.knowledgePinNumber > 0
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