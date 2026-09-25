import { useState } from 'react'
import { Link } from 'react-router'
import type { ReviewSummary } from '../../api/reviews'
import { useReviewProgressQuery } from '../../api/reviews'
import {
  analysisWarningMessage,
  checkKeyLabel,
  hasNewerRun,
  progressPercent,
  reviewFailureMessage,
  runStatusLabel,
} from '../../lib/reviews'
import { LoadingState } from '../LoadingState'

function AnalysisWarnings({
  summary,
}: {
  summary: Pick<ReviewSummary, 'analysisWarnings' | 'analysisWarningsTruncated'>
}) {
  if (summary.analysisWarnings.length === 0) return null
  return (
    <div className="analysis-warnings">
      <h3>Analysis warnings</h3>
      <p className="panel__hint">
        These checks ran with reduced certainty. Their results may be incomplete.
      </p>
      <ul>
        {summary.analysisWarnings.map((warning, index) => (
          <li key={`${warning.checkKey}-${index}`}>
            <span className="analysis-warnings__check" title={warning.checkKey}>
              {checkKeyLabel(warning.checkKey)}
            </span>{' '}
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

function ProgressDetail({ reviewId, poll }: { reviewId: string; poll: boolean }) {
  const [open, setOpen] = useState(false)
  const progressQuery = useReviewProgressQuery(reviewId, open, open && poll)

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
            <dd>{runStatusLabel(progressQuery.data.latestRunStatus ?? 'UNKNOWN')}</dd>
          </div>
        </dl>
      ) : null}
    </details>
  )
}

type Props = {
  contractId: string
  reviewId: string
  summary: ReviewSummary
  onRefresh: () => void
}

export function ReviewLifecyclePanel({ contractId, reviewId, summary, onRefresh }: Props) {
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
          {summary.findings === 0 &&
          summary.degradedChecks === 0 &&
          summary.failedChecks === 0 ? (
            <p className="state state--success">
              Every planned check completed without findings.
            </p>
          ) : null}
          {summary.failedChecks > 0 ? (
            <p className="state state--warning">
              Some checks did not complete. Review the analysis warnings.
            </p>
          ) : null}
        </>
      ) : null}

      {summary.status === 'FAILED' ? (
        <div className="state state--error" role="alert">
          <p>{reviewFailureMessage(summary.failure)}</p>
          <p>A new review creates a fresh frozen snapshot of the current policy state.</p>
          <Link className="link-button" to={`/reviews/${contractId}/analysis`}>
            Back to review history
          </Link>
        </div>
      ) : null}

      {summary.status === 'UNKNOWN' ? (
        <div className="state state--warning">
          <p>
            This review is in a state this version of the app does not recognise. It may
            still be running.
          </p>
          <button type="button" className="button" onClick={onRefresh}>
            Check again
          </button>
        </div>
      ) : null}

      {hasNewerRun(summary) ? (
        <p className="state state--warning">
          A newer analysis attempt exists (
          {runStatusLabel(summary.latestRunStatus ?? 'UNKNOWN')}). This page shows the latest
          completed analysis.
        </p>
      ) : null}

      <AnalysisWarnings summary={summary} />

      <ProgressDetail reviewId={reviewId} poll={pending} />
    </section>
  )
}