import type { RedlineExecution } from '../../api/redline'
import { formatBytes, formatTimestamp } from '../../lib/knowledgeLabels'
import { redlineExecutionTitle, redlineVerificationOutcome } from '../../lib/redline'

export function RedlineExecutionStatus({ execution }: { execution: RedlineExecution }) {
  const title = redlineExecutionTitle(execution)
  const positive =
    execution.status === 'COMPLETED' &&
    execution.outcome === 'REDLINE_GENERATED' &&
    redlineVerificationOutcome(execution) === 'PASSED'
  const outcomeRecorded =
    execution.status !== 'COMPLETED' ||
    execution.outcome === 'REDLINE_GENERATED' ||
    execution.outcome === 'NO_CHANGES'
  return (
    <div className="redline__status">
      <span className={`status-badge ${positive ? 'status-badge--positive' : 'status-badge--neutral'}`}>
        {title}
      </span>
      {outcomeRecorded ? null : (
        <p className="panel__hint">
          The outcome was not recorded for this execution. Download and verification are
          unavailable.
        </p>
      )}
      <p className="panel__hint">
        Based on contract v{execution.contractVersionNumber} · change set version{' '}
        {execution.changeSetVersion}
        {execution.completedAt ? ` · generated ${formatTimestamp(execution.completedAt)}` : ''}
      </p>
      {execution.artifactFilename ? (
        <p className="panel__hint">
          File: {execution.artifactFilename}
          {execution.artifactSizeBytes !== null
            ? ` (${formatBytes(execution.artifactSizeBytes)})`
            : ''}
        </p>
      ) : null}
    </div>
  )
}
