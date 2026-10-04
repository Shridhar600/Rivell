import { Link } from 'react-router'
import type { ReviewListItem } from '../../api/reviews'
import { formatTimestamp } from '../../lib/knowledgeLabels'
import { reviewStatusLabel, reviewStatusModifier } from '../../lib/reviews'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

type Props = {
  contractId: string
  reviews: ReviewListItem[]
  truncated: boolean
  isPending: boolean
  isError: boolean
  onRetry: () => void
}

export function ReviewHistoryList({
  contractId,
  reviews,
  truncated,
  isPending,
  isError,
  onRetry,
}: Props) {
  return (
    <section className="panel" aria-labelledby="review-history-heading">
      <h2 id="review-history-heading">Review history</h2>

      {isPending ? <LoadingState label="Loading reviews…" /> : null}

      {isError ? (
        <ErrorState
          message="The review history could not be loaded. Your contract and its analyses are unaffected."
          onRetry={onRetry}
        />
      ) : null}

      {!isPending && !isError && reviews.length === 0 ? (
        <EmptyState
          title="No automated reviews yet"
          message="Start a review to analyse this contract version against published policy."
        />
      ) : null}

      {!isPending && !isError && reviews.length > 0 ? (
        <>
          <ul className="review-history__list">
            {reviews.map((review) => (
              <li key={review.id} className="review-history__item">
                <span className={`badge ${reviewStatusModifier(review.status)}`}>
                  {reviewStatusLabel(review.status)}
                </span>
                <span className="review-history__meta">
                  {review.contractVersionNumber !== null
                    ? `v${review.contractVersionNumber}`
                    : 'version unknown'}{' '}
                  · created{' '}
                  <time dateTime={review.createdAt}>{formatTimestamp(review.createdAt)}</time>
                  {review.completedAt
                    ? ` · completed ${formatTimestamp(review.completedAt)}`
                    : ''}
                </span>
                <Link
                  className="link-button"
                  to={`/reviews/${contractId}/overview/${review.id}`}
                  aria-label={`Open review ${review.contractVersionNumber !== null ? `v${review.contractVersionNumber}` : 'unknown version'}, ${reviewStatusLabel(review.status)}, created ${formatTimestamp(review.createdAt)}`}
                >
                  Open review
                </Link>
              </li>
            ))}
          </ul>
          {truncated ? (
            <p className="panel__hint">Showing the most recent reviews only.</p>
          ) : null}
        </>
      ) : null}
    </section>
  )
}