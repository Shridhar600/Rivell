import type { RedlineExecution } from '../../api/redline'
import { formatBytes, formatTimestamp } from '../../lib/knowledgeLabels'
import { redlineExecutionTitle } from '../../lib/redline'

export function RedlineExecutionStatus({ execution }: { execution: RedlineExecution }) {
  const title = redlineExecutionTitle(execution)
  const positive = execution.status === 'COMPLETED' && execution.outcome === 'REDLINE_GENERATED'
  return (
    <div className="redline__status">
      <span className={`status-badge ${positive ? 'status-badge--positive' : 'status-badge--neutral'}`}>
        {title}
      </span>
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
