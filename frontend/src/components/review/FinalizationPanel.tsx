import { useRef, useState } from 'react'
import type { HumanReview } from '../../api/workflow'
import { formatTimestamp } from '../../lib/knowledgeLabels'

type Props = {
  review: HumanReview
  isFinalizing: boolean
  finalizeError: string | null
  onFinalize: () => void
}

export function FinalizationPanel({ review, isFinalizing, finalizeError, onFinalize }: Props) {
  const [isConfirming, setConfirming] = useState(false)
  const confirmRef = useRef<HTMLButtonElement>(null)

  if (review.status === 'COMPLETED') {
    return (
      <section className="finalization" aria-labelledby="finalization-heading">
        <h2 id="finalization-heading">Finalization</h2>
        <p className="finalization__complete" role="status">
          Human review completed. The approved change set is frozen and decisions are read-only.
        </p>
        <p className="panel__hint">
          {review.finalizedAt
            ? `Finalized ${formatTimestamp(review.finalizedAt)}`
            : 'Finalized'}
        </p>
      </section>
    )
  }

  return (
    <section className="finalization" aria-labelledby="finalization-heading">
      <h2 id="finalization-heading">Finalization</h2>
      <ul className="finalization__summary">
        <li>Accepted: {review.accepted}</li>
        <li>Edited: {review.edited}</li>
        <li>Rejected: {review.rejected}</li>
      </ul>
      {review.readyToFinalize ? (
        <>
          <p>
            All required decisions are recorded. Finalizing freezes these decisions into an
            approved change set.
          </p>
          {isConfirming ? (
            <div className="finalization__confirm" role="group" aria-label="Confirm finalization">
              <p className="state state--warning">
                Finalizing freezes every decision in this review, creates the approved change
                set, and locks decisions. It cannot be undone.
              </p>
              <button
                type="button"
                className="button"
                ref={confirmRef}
                disabled={isFinalizing}
                onClick={onFinalize}
              >
                {isFinalizing ? 'Finalizing…' : 'Confirm finalize'}
              </button>
              <button
                type="button"
                className="link-button"
                onClick={() => setConfirming(false)}
              >
                Cancel
              </button>
            </div>
          ) : (
            <button
              type="button"
              className="button"
              onClick={() => setConfirming(true)}
            >
              Finalize review
            </button>
          )}
        </>
      ) : (
        <p className="panel__hint">
          The review cannot be finalized yet. The items that must be resolved are listed under
          Human review.
        </p>
      )}
      {finalizeError ? (
        <p className="state state--warning" role="alert">
          {finalizeError}
        </p>
      ) : null}
    </section>
  )
}
