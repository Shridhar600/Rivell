import { Link } from 'react-router'
import { useArtifactsQuery } from '../../api/knowledge'
import {
  ARTIFACT_TYPE_LABELS,
  formatBytes,
  formatTimestamp,
  INGEST_STATUS_LABELS,
  shortSha,
} from '../../lib/knowledgeLabels'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

export function KnowledgeSourcesList() {
  const query = useArtifactsQuery()

  if (query.isPending) {
    return <LoadingState label="Loading knowledge sources…" />
  }

  if (query.isError) {
    return (
      <ErrorState
        message={query.error.message || 'Knowledge sources unavailable.'}
        onRetry={() => void query.refetch()}
      />
    )
  }

  if (query.data.length === 0) {
    return (
      <EmptyState
        title="No knowledge sources yet"
        message="Add a playbook, clause library, template, precedent, or reference to start building organizational knowledge."
      />
    )
  }

  return (
    <ul className="source-list">
      {query.data.map((artifact) => (
        <li key={artifact.id} className="source-list__item">
          <div>
            <Link
              to={`/knowledge/artifacts/${artifact.id}`}
              className="source-list__link"
            >
              {artifact.filename}
            </Link>
            <p className="meta">
              {ARTIFACT_TYPE_LABELS[artifact.artifactType]} ·{' '}
              {formatBytes(artifact.sizeBytes)} · added{' '}
              {formatTimestamp(artifact.createdAt)}
            </p>
            <p className="meta meta--mono">
              sha256 {shortSha(artifact.sha256)}
            </p>
          </div>
          <span className={`badge badge--${artifact.ingestStatus.toLowerCase()}`}>
            {INGEST_STATUS_LABELS[artifact.ingestStatus]}
          </span>
        </li>
      ))}
    </ul>
  )
}
