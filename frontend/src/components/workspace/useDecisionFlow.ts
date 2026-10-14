import { useRef, useState } from 'react'
import type { FindingDetail } from '../../api/reviews'
import {
  isWorkflowConflictError,
  isWorkflowForbiddenError,
  useSubmitDecision,
  workflowErrorMessage,
  type HumanDecision,
  type HumanReview,
} from '../../api/workflow'
import { newRequestId } from '../../lib/requestId'
import { classifyDecisionConflict, decisionConflictNotice } from '../../lib/workflow'
import { acceptEligibilityFor } from '../../lib/workspace'
import type { DecisionSubmission } from './DecisionComposer'

export type ComposeKind = 'EDIT' | 'REJECT' | 'ESCALATE'

interface Input {
  reviewId: string
  finding: FindingDetail | undefined
  decision: HumanDecision | null
  humanReview: HumanReview | null
  analysisComplete: boolean
  decisionsLoaded: boolean
  /** Called once the server has recorded a decision, so the page can move on. */
  onSaved: (findingId: string) => void
}

/**
 * One decision at a time, against the server. Every write carries the revision the reviewer saw
 * (`expectedVersion`), so a decision changed elsewhere is refused rather than overwritten, and the
 * refusal is explained from the server's current state. Nothing is shown as saved until it is.
 */
export function useDecisionFlow({ reviewId, finding, decision, humanReview, analysisComplete, decisionsLoaded, onSaved }: Input) {
  const mutation = useSubmitDecision(reviewId)
  const [open, setOpen] = useState<{ findingId: string; kind: ComposeKind } | null>(null)
  const [conflict, setConflict] = useState<{ findingId: string; action: DecisionSubmission['action']; expectedVersion: number } | null>(null)
  const requestId = useRef<{ signature: string; id: string } | null>(null)

  const findingId = finding?.id ?? null
  // State belongs to the finding it was started on, so moving to another finding never carries a
  // half-written edit or an old error with it.
  const compose = open && open.findingId === findingId ? open.kind : null
  const mine = findingId !== null && mutation.variables?.findingId === findingId
  const conflictKind =
    conflict && conflict.findingId === findingId
      ? classifyDecisionConflict({
          attemptedAction: conflict.action,
          expectedVersion: conflict.expectedVersion,
          currentDecision: decision,
          decisionStateKnown: decisionsLoaded,
          humanReview,
          analysisComplete,
        })
      : null
  const readOnly = mutation.isError && mine && isWorkflowForbiddenError(mutation.error)
  const error = conflictKind
    ? decisionConflictNotice(conflictKind)
    : mutation.isError && mine
      ? workflowErrorMessage(mutation.error)
      : null

  const eligibility = finding ? acceptEligibilityFor(finding) : { eligible: false as const, reason: '' }

  function submit(submission: DecisionSubmission) {
    if (!finding) return
    const expectedVersion = decision?.revision ?? 0
    const signature = JSON.stringify([finding.id, expectedVersion, submission])
    if (requestId.current?.signature !== signature) {
      const id = newRequestId()
      requestId.current = id ? { signature, id } : null
    }
    setConflict(null)
    mutation.mutate(
      { ...submission, findingId: finding.id, expectedVersion, clientRequestId: requestId.current?.id },
      {
        onSuccess: () => {
          setOpen(null)
          onSaved(finding.id)
        },
        onError: (failure) => {
          if (isWorkflowConflictError(failure)) {
            setConflict({ findingId: finding.id, action: submission.action, expectedVersion })
          }
        },
      },
    )
  }

  function decide(kind: 'ACCEPT' | ComposeKind) {
    if (!finding) return
    mutation.reset()
    setConflict(null)
    if (kind === 'ACCEPT') {
      if (eligibility.eligible) submit({ action: 'ACCEPT' })
      return
    }
    setOpen({ findingId: finding.id, kind })
  }

  function cancel() {
    setOpen(null)
    setConflict(null)
    mutation.reset()
  }

  return {
    compose,
    decide,
    submit,
    cancel,
    error,
    readOnly,
    isSubmitting: mutation.isPending,
    acceptEligibility: eligibility,
  }
}

export type DecisionFlow = ReturnType<typeof useDecisionFlow>
