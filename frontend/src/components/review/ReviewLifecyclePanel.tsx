import { useState } from 'react'
import type { ReviewSummary } from '../../api/reviews'
import { useReviewProgressQuery } from '../../api/reviews'
import {
  analysisWarningMessage,
  hasNewerRun,
  progressPercent,
  reviewFailureMessage,
  runStatusLabel,
} from '../../lib/reviews'
import { LoadingState } from '../LoadingState'

function AnalysisWarnings({
  summary,
}: {
  summary: Pick<
    ReviewSummary,
    'analysisWarnings' | 'analysisWarningsTruncated'
  >
}) {
  if (summary.analysisWarnings.length === 0) return null
  return (
    <div className="analysis-warnings">
      <h3>Analysis warnings</h3>
      <p className="panel__hint">
        These checks ran with reduced certainty. The findings below are still shown.
      </p>
      <ul>
        {summary.analysisWarnings.map((warning, index) => (
          <li key={`${warning.checkKey}-${index}`}>
            <span className="analysis-warnings__check">{warning.checkKey}</span>{' '}
            {analysisWarningMessage(warning)}
          </li>
        ))}
      </ul>
      {summary.analysisWarningsTruncated ? (
        <p className="panel__hint">Additional warnings were omitted.</p>
      ) : null}
    </div>
  )
}

function ProgressDetail({ reviewId }: { reviewId: string }) {
  const [open, setOpen] = useState(false)
  const progressQuery = useReviewProgressQuery(reviewId, open)

  return (
    <details
      className="analysis-detail"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary>Analysis detail</summary>
      {progressQuery.isPending ? (
        <LoadingState label="Loading analysis detail…" />
      ) : null}
      {progressQuery.isError ? (
        <p className="state state--warning">Analysis detail is unavailable.</p>
      ) : null}
      {progressQuery.data ? (
        <dl className="data-list" aria-label="Analysis check counts">
          <div className="data-list__row">
            <dt>Planned checks</dt>
            <dd>{progressQuery.data.plannedChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Waiting</dt>
            <dd>{progressQuery.data.pendingChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Running</dt>
            <dd>{progressQuery.data.runningChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Completed</dt>
            <dd>{progressQuery.data.completedChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Degraded</dt>
            <dd>{progressQuery.data.degradedChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Failed</dt>
            <dd>{progressQuery.data.failedChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Skipped</dt>
            <dd>{progressQuery.data.skippedChecks}</dd>
          </div>
          <div className="data-list__row">
            <dt>Latest attempt</dt>
            <dd>
              {progressQuery.data.latestRunStatus
                ? runStatusLabel(progressQuery.data.latestRunStatus)
                : runStatusLabel('UNKNOWN')}
            </dd>
          </div>
        </dl>
      ) : null}
    </details>
  )
}

export function ReviewLifecyclePanel({
  reviewId,
  summary,
}: {
  reviewId: string
  summary: ReviewSummary
}) {
  const percent = progressPercent(summary)
  const pending = summary.status === 'ANALYSIS_QUEUED' || summary.status === 'ANALYZING'

  return (
    <section className="panel" aria-labelledby="analysis-state-heading">
      <h2 id="analysis-state-heading">Analysis state</h2>

      {summary.status === 'ANALYSIS_QUEUED' ? (
        <p role="status">
          The review is queued. Analysis starts when the review worker picks it up.
        </p>
      ) : null}

      {summary.status === 'ANALYZING' ? (
        <p role="status">Rivell is analysing this contract version.</p>
      ) : null}

      {pending ? (
        <div className="analysis-progress">
          <p>
            {summary.completedChecks} of {summary.plannedChecks} checks completed
            {percent !== null ? ` (${percent}%)` : ''}
          </p>
          {summary.plannedChecks > 0 ? (
            <progress
              value={summary.completedChecks}
              max={summary.plannedChecks}
              aria-label="Analysis progress"
            />
          ) : null}
        </div>
      ) : null}

      {summary.status === 'COMPLETED' ? (
        <>
          <p>
            {summary.findings === 0
              ? 'The automated review found no findings for this contract version.'
              : `${summary.findings} ${summary.findings === 1 ? 'finding' : 'findings'} from ${summary.completedChecks} completed checks.`}
          </p>
          {summary.findings === 0 && summary.degradedChecks === 0 ? (
            <p className="state state--success">
              Every planned check completed without findings.
            </p>
          ) : null}
        </>
      ) : null}

      {summary.status === 'FAILED' ? (
        <div className="state state--error" role="alert">
          <p>{reviewFailureMessage(summary.failureReason)}</p>
          <p>
            You can start a new review from the review history. Starting a new review creates
            a fresh frozen snapshot.
          </p>
        </div>
      ) : null}

      {hasNewerRun(summary) ? (
        <p className="state state--warning">
          A newer analysis attempt exists ({runStatusLabel(summary.latestRunStatus ?? 'UNKNOWN')}).
          This page shows the latest completed analysis.
        </p>
      ) : null}

      <AnalysisWarnings summary={summary} />

      <ProgressDetail reviewId={reviewId} />
    </section>
  )
}