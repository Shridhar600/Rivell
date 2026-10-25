import { useId, useState } from 'react'
import { Link } from 'react-router'
import type { ReviewSummary } from '../../api/reviews'
import { useReviewProgressQuery } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import {
  analysisWarningCheckLabel,
  analysisWarningMessage,
  hasNewerRun,
  progressPercent,
  reviewFailureMessage,
  runStatusLabel,
} from '../../lib/reviews'
import { LoadingState } from '../ui/State'

export function AnalysisWarnings({
  summary,
}: {
  summary: Pick<ReviewSummary, 'analysisWarnings' | 'analysisWarningsTruncated'>
}) {
  const { labelFor } = useTaxonomy()
  if (summary.analysisWarnings.length === 0) return null
  return (
    <div className="rv-ws-sec rv-ws-life-warn">
      <h3 className="rv-ws-sec-h">Analysis warnings</h3>
      <p className="rv-ws-caption">
        Some checks were incomplete.
      </p>
      <ul>
        {summary.analysisWarnings.map((warning, index) => {
          const heading = analysisWarningCheckLabel(warning, labelFor)
          return (
            <li key={`${warning.checkKey}-${index}`}>
              {heading ? (
                <>
                  <span className="rv-ws-cov-c">{heading}</span>{' '}
                </>
              ) : null}
              {analysisWarningMessage(warning)}
            </li>
          )
        })}
      </ul>
      {summary.analysisWarningsTruncated ? (
        <p className="rv-ws-caption">Additional warnings were omitted.</p>
      ) : null}
    </div>
  )
}

function ProgressDetail({ reviewId, poll }: { reviewId: string; poll: boolean }) {
  const [open, setOpen] = useState(false)
  const regionId = useId()
  const progressQuery = useReviewProgressQuery(reviewId, open, open && poll)

  return (
    <div className="rv-ws-sec">
      <button
        type="button"
        className="rv-ws-link"
        aria-expanded={open}
        aria-controls={regionId}
        onClick={() => setOpen((value) => !value)}
      >
        Analysis detail
      </button>
      <div id={regionId} hidden={!open}>
      {open && progressQuery.isPending ? (
        <LoadingState label="Loading analysis detail…" />
      ) : null}
      {open && progressQuery.isError ? (
        <p className="rv-ws-warn">Analysis detail is unavailable.</p>
      ) : null}
      {open && progressQuery.data ? (
        <dl className="rv-ws-props" aria-label="Analysis check counts">
          <div>
            <dt>Planned checks</dt>
            <dd>{progressQuery.data.plannedChecks}</dd>
          </div>
          <div>
            <dt>Waiting</dt>
            <dd>{progressQuery.data.pendingChecks}</dd>
          </div>
          <div>
            <dt>Running</dt>
            <dd>{progressQuery.data.runningChecks}</dd>
          </div>
          <div>
            <dt>Completed</dt>
            <dd>{progressQuery.data.completedChecks}</dd>
          </div>
          <div>
            <dt>Degraded</dt>
            <dd>{progressQuery.data.degradedChecks}</dd>
          </div>
          <div>
            <dt>Failed</dt>
            <dd>{progressQuery.data.failedChecks}</dd>
          </div>
          <div>
            <dt>Skipped</dt>
            <dd>{progressQuery.data.skippedChecks}</dd>
          </div>
          <div>
            <dt>Latest attempt</dt>
            <dd>{runStatusLabel(progressQuery.data.latestRunStatus ?? 'UNKNOWN')}</dd>
          </div>
        </dl>
      ) : null}
      </div>
    </div>
  )
}

type Props = {
  reviewId: string
  summary: ReviewSummary
  coverageLimited: boolean
  onRefresh: () => void
}

export function ReviewLifecyclePanel({
  reviewId,
  summary,
  coverageLimited,
  onRefresh,
}: Props) {
  const percent = progressPercent(summary)
  const pending = summary.status === 'ANALYSIS_QUEUED' || summary.status === 'ANALYZING'

  return (
    <section className="rv-ws-life" aria-labelledby="analysis-state-heading">
      <h2 id="analysis-state-heading" className="rv-ws-sec-h">Analysis state</h2>

      {summary.status === 'ANALYSIS_QUEUED' ? (
        <p role="status">
          The review is waiting to start. Analysis has not started yet and begins on its own.
        </p>
      ) : null}

      {summary.status === 'ANALYZING' ? (
        <p role="status">Rivell is analysing this contract version.</p>
      ) : null}

      {pending ? (
        <div className="rv-ws-life-prog">
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
          summary.plannedChecks > 0 &&
          summary.degradedChecks === 0 &&
          summary.failedChecks === 0 &&
          !coverageLimited ? (
            <p>
              Every planned check completed without findings.
            </p>
          ) : null}
          {summary.findings === 0 && summary.plannedChecks === 0 ? (
            <p className="rv-ws-warn">
              No checks were planned for this contract version.
            </p>
          ) : null}
          {summary.findings === 0 && coverageLimited ? (
            <p className="rv-ws-warn">
              No findings were recorded, but review coverage is limited (see Review coverage).
            </p>
          ) : null}
          {summary.failedChecks > 0 ? (
            <p className="rv-ws-warn">
              Some checks did not complete. Review the analysis warnings.
            </p>
          ) : null}
        </>
      ) : null}

      {summary.status === 'FAILED' ? (
        <div className="rv-ws-life-alert" role="alert">
          <p>{reviewFailureMessage(summary.failure)}</p>
          <p>A new review uses the knowledge published at the time.</p>
          <Link className="rv-ws-link" to="/reviews">
            Back to reviews
          </Link>
        </div>
      ) : null}

      {summary.status === 'UNKNOWN' ? (
        <div className="rv-ws-life-alert">
          <p>
            This review is in a state this version of the app does not recognise. It may
            still be running.
          </p>
          <button type="button" className="rv-btn" onClick={onRefresh}>
            Check again
          </button>
        </div>
      ) : null}

      {hasNewerRun(summary) ? (
        <p className="rv-ws-warn">
          A newer analysis has run (
          {runStatusLabel(summary.latestRunStatus ?? 'UNKNOWN')}). This page shows the latest
          completed one.
        </p>
      ) : null}

      <AnalysisWarnings summary={summary} />

      <ProgressDetail reviewId={reviewId} poll={pending} />
    </section>
  )
}