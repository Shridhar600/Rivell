import type { Candidate } from '../../api/knowledge'
import {
  REVIEW_CATEGORY_DESCRIPTIONS,
  REVIEW_CATEGORY_EXPLANATION,
  REVIEW_CATEGORY_LABELS,
  reviewReasonLabel,
} from '../../lib/semantics'

export function ReviewAttentionBadge({
  candidate,
}: {
  candidate: Candidate
}) {
  if (!candidate.reviewCategory) return null
  return (
    <span
      className={`badge badge--attention-${candidate.reviewCategory.toLowerCase()}`}
    >
      {REVIEW_CATEGORY_LABELS[candidate.reviewCategory]}
    </span>
  )
}

export function ReviewAttentionSection({ candidate }: { candidate: Candidate }) {
  if (!candidate.reviewCategory) return null

  return (
    <section aria-labelledby="candidate-attention-heading">
      <h4 id="candidate-attention-heading">Review attention</h4>
      <p className="meta">
        {REVIEW_CATEGORY_LABELS[candidate.reviewCategory]} —{' '}
        {REVIEW_CATEGORY_DESCRIPTIONS[candidate.reviewCategory]}{' '}
        {REVIEW_CATEGORY_EXPLANATION}
      </p>
      {candidate.reviewReasons.length > 0 ? (
        <ul className="reason-list">
          {candidate.reviewReasons.map((reason) => (
            <li key={reason}>{reviewReasonLabel(reason)}</li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}
