import { Link } from 'react-router'
import { usePublicationsQuery } from '../../api/knowledge'
import { formatTimestamp } from '../../lib/knowledgeLabels'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

export function PublicationsList({ limit }: { limit?: number }) {
  const query = usePublicationsQuery()

  if (query.isPending) {
    return <LoadingState label="Loading published versions…" />
  }

  if (query.isError) {
    return (
      <ErrorState
        message={query.error.message || 'Published versions unavailable.'}
        onRetry={() => void query.refetch()}
      />
    )
  }

  if (query.data.length === 0) {
    return (
      <EmptyState
        title="No published versions yet"
        message="Approved candidates become an organizational knowledge version when they are published."
      />
    )
  }

  const publications =
    limit === undefined ? query.data : query.data.slice(0, limit)

  return (
    <ul className="source-list">
      {publications.map((publication) => (
        <li key={publication.id} className="source-list__item">
          <div>
            <Link
              to={`/knowledge/publications/${publication.id}`}
              className="source-list__link"
            >
              Publication v{publication.publicationNumber}
            </Link>
            <p className="meta">
              {formatTimestamp(publication.createdAt)} ·{' '}
              {publication.items.length} item
              {publication.items.length === 1 ? '' : 's'}
              {publication.note ? ` · ${publication.note}` : ''}
            </p>
          </div>
          <span className="badge badge--published">Published</span>
        </li>
      ))}
    </ul>
  )
}
