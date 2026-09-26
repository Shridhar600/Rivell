import { useEffect, useRef } from 'react'
import type { ApprovedChangeSet } from '../../api/workflow'
import {
  RedlineApiError,
  isRedlineForbiddenError,
  isRedlineNotFoundError,
  useRedlineExecutionQuery,
  useTriggerRedline,
} from '../../api/redline'
import {
  buildRedlineChangeRows,
  classifyTriggerError,
  redlineTriggerErrorNotice,
} from '../../lib/redline'
import { RedlineChangeReport } from './RedlineChangeReport'
import { RedlineDownloadAction } from './RedlineDownloadAction'
import { RedlineExecutionStatus } from './RedlineExecutionStatus'
import { RedlineFailureNotice } from './RedlineFailureNotice'
import { RedlineVerificationSummary } from './RedlineVerificationSummary'

type Props = {
  reviewId: string
  isFinalized: boolean
  approvedChangeSet: ApprovedChangeSet | null
  regionLabels: Map<string, string>
  onSelectFinding: (findingId: string) => void
}

export function RedlineExecutionPanel({
  reviewId,
  isFinalized,
  approvedChangeSet,
  regionLabels,
  onSelectFinding,
}: Props) {
  const executionQuery = useRedlineExecutionQuery(reviewId, isFinalized)
  const trigger = useTriggerRedline(reviewId)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const failureRef = useRef<HTMLHeadingElement>(null)
  const execution = executionQuery.data ?? null

  useEffect(() => {
    if (trigger.isSuccess) headingRef.current?.focus()
  }, [trigger.isSuccess])

  useEffect(() => {
    if (trigger.isError && execution?.status === 'FAILED') failureRef.current?.focus()
  }, [trigger.isError, execution?.status])

  if (!isFinalized) return null
  const notStarted = executionQuery.isError && isRedlineNotFoundError(executionQuery.error)
  const forbidden = executionQuery.isError && isRedlineForbiddenError(executionQuery.error)
  const triggerError = trigger.isError
    ? classifyTriggerError({
        status: trigger.error instanceof RedlineApiError ? trigger.error.status : null,
        execution,
        executionStateKnown: notStarted || executionQuery.isSuccess,
      })
    : null

  return (
    <section className="redline" aria-labelledby="redline-heading">
      <h2 id="redline-heading" tabIndex={-1} ref={headingRef}>
        Redline execution
      </h2>
      {execution ? (
        <>
          {execution.status === 'FAILED' ? (
            <RedlineFailureNotice execution={execution} titleRef={failureRef} />
          ) : (
            <>
              <RedlineExecutionStatus execution={execution} />
              <RedlineVerificationSummary execution={execution} />
              {execution.outcome === 'NO_CHANGES' ? (
                <p>No approved document changes were produced for this review.</p>
              ) : null}
              <RedlineDownloadAction reviewId={reviewId} execution={execution} />
            </>
          )}
          <RedlineChangeReport
            rows={buildRedlineChangeRows(execution, {
              approvedChanges: approvedChangeSet?.changes ?? [],
              regionLabels,
            })}
            onSelectFinding={onSelectFinding}
          />
        </>
      ) : forbidden ? (
        <p className="state state--warning" role="alert">
          You do not have permission to generate or download redlines for this review.
        </p>
      ) : notStarted ? (
        <>
          <p className="panel__hint">
            Rivell applies the approved changes to the frozen contract as tracked changes,
            then reopens the document to verify the result. The original document is never
            modified.
          </p>
          {triggerError ? (
            <p className="state state--warning" role="alert">
              {redlineTriggerErrorNotice(triggerError)}
            </p>
          ) : null}
          <div className="redline__actions">
            <button
              type="button"
              className="button"
              disabled={trigger.isPending || triggerError === 'FORBIDDEN'}
              onClick={() => trigger.mutate()}
            >
              Generate redline
            </button>
            {trigger.isPending ? (
              <span role="status">Generating and verifying the redlined document…</span>
            ) : null}
          </div>
        </>
      ) : executionQuery.isError ? (
        <p className="state state--warning" role="alert">
          The redline execution could not be loaded.{' '}
          <button
            type="button"
            className="link-button"
            onClick={() => void executionQuery.refetch()}
          >
            Try again
          </button>
        </p>
      ) : (
        <p role="status">Loading redline execution…</p>
      )}
    </section>
  )
}
