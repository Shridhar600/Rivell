import { useEffect, useRef, useState } from 'react'
import type { FindingDetail } from '../../api/reviews'
import type { HumanDecision, HumanReview } from '../../api/workflow'
import { isWorkflowConflictError, useSubmitDecision, workflowErrorMessage } from '../../api/workflow'
import { classifyDecisionConflict, decisionConflictNotice } from '../../lib/workflow'
import type { DecisionConflictKind } from '../../lib/workflow'
import { DecisionEditor } from './DecisionEditor'
import type { DecisionSubmission } from './DecisionEditor'
import { DecisionHistory } from './DecisionHistory'
import { DecisionSummary } from './DecisionSummary'

type Props = {
  reviewId: string
  finding: FindingDetail
  currentDecision: HumanDecision | null
  humanReview: HumanReview | null
  isFinalized: boolean
  canDecide: boolean
  analysisComplete: boolean
  decisionsTruncated: boolean
}

export function HumanDecisionControls({
  reviewId,
  finding,
  currentDecision,
  humanReview,
  isFinalized,
  canDecide,
  analysisComplete,
  decisionsTruncated,
}: Props) {
  const mutation = useSubmitDecision(reviewId)
  const [isOpen, setOpen] = useState(false)
  const [conflictAttempt, setConflictAttempt] = useState<{
    action: DecisionSubmission['action']
    expectedVersion: number
  } | null>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    if (mutation.isSuccess) headingRef.current?.focus()
  }, [mutation.isSuccess])

  const editorVisible = isOpen && !mutation.isSuccess

  const conflictKind: DecisionConflictKind | null = conflictAttempt
    ? classifyDecisionConflict({
        attemptedAction: conflictAttempt.action,
        expectedVersion: conflictAttempt.expectedVersion,
        currentDecision,
        humanReview,
        analysisComplete,
      })
    : null

  const submitError = conflictKind
    ? decisionConflictNotice(conflictKind)
    : mutation.isError
      ? workflowErrorMessage(mutation.error)
      : null

  function handleSubmit(submission: DecisionSubmission) {
    setConflictAttempt(null)
    mutation.mutate(
      { ...submission, findingId: finding.id, expectedVersion: currentDecision?.revision ?? 0 },
      {
        onError: (error) => {
          if (isWorkflowConflictError(error)) {
            setConflictAttempt({
              action: submission.action,
              expectedVersion: currentDecision?.revision ?? 0,
            })
          }
        },
      },
    )
  }

  return (
    <section className="finding-section finding-decision" aria-labelledby="finding-decision-heading">
      <h3 id="finding-decision-heading" tabIndex={-1} ref={headingRef}>
        Human decision
      </h3>
      {mutation.isSuccess ? (
        <p className="state state--success" role="status">
          Decision recorded.
        </p>
      ) : null}
      {decisionsTruncated ? (
        <p className="panel__hint">
          Only the most recent decisions of this review are loaded, so an earlier decision may
          not be shown here.
        </p>
      ) : null}
      <DecisionSummary decision={currentDecision} />
      {isFinalized ? (
        <p className="panel__hint">
          This review was finalized. Decisions are locked; the recorded decision stays visible
          for audit.
        </p>
      ) : !canDecide ? (
        <p className="panel__hint">
          Decision controls are unavailable until the human review state is available.
        </p>
      ) : editorVisible ? (
        <DecisionEditor
          finding={finding}
          currentDecision={currentDecision}
          isSubmitting={mutation.isPending}
          submitError={submitError}
          onSubmit={handleSubmit}
          onCancel={() => {
            setOpen(false)
            mutation.reset()
          }}
        />
      ) : (
        <button
          type="button"
          className="button"
          onClick={() => {
            mutation.reset()
            setConflictAttempt(null)
            setOpen(true)
          }}
        >
          {currentDecision ? 'Change decision' : 'Record decision'}
        </button>
      )}
      <DecisionHistory reviewId={reviewId} findingId={finding.id} />
    </section>
  )
}
