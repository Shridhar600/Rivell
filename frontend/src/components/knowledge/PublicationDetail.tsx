import { Link } from 'react-router'
import {
  useCandidateQuery,
  usePublicationQuery,
  type PublicationItem,
} from '../../api/knowledge'
import {
  CANDIDATE_TYPE_LABELS,
  formatTimestamp,
  PUBLICATION_ACTION_LABELS,
} from '../../lib/knowledgeLabels'
import { useHeadingFocus } from '../../lib/useHeadingFocus'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'

function SupersededTarget({ candidateId }: { candidateId: string }) {
  const query = useCandidateQuery(candidateId)

  if (query.isPending) {
    return (
      <p className="meta" role="status">
        Loading superseded knowledge…
      </p>
    )
  }

  if (query.isError || !query.data) {
    return <p className="meta">Supersedes candidate {candidateId}</p>
  }

  return (
    <p className="meta">
      Supersedes{' '}
      <Link
        to={`/knowledge/sources/${query.data.artifactId}?position=${query.data.id}`}
      >
        {query.data.rawLabel}
      </Link>
    </p>
  )
}

function PublicationItemRow({ item }: { item: PublicationItem }) {
  const query = useCandidateQuery(item.candidateId)

  return (
    <li className="source-list__item">
      <div>
        {query.isPending ? (
          <span className="meta" role="status">
            Loading candidate…
          </span>
        ) : query.isError || !query.data ? (
          <span className="meta">Candidate {item.candidateId} could not be loaded.</span>
        ) : (
          <>
            <Link
              to={`/knowledge/sources/${query.data.artifactId}?position=${query.data.id}`}
              className="source-list__link"
            >
              {query.data.rawLabel}
            </Link>
            <p className="meta">
              {CANDIDATE_TYPE_LABELS[query.data.candidateType]}
            </p>
          </>
        )}
        {item.supersededCandidateId ? (
          <SupersededTarget candidateId={item.supersededCandidateId} />
        ) : null}
      </div>
      <span className={`badge badge--${item.action.toLowerCase()}`}>
        {PUBLICATION_ACTION_LABELS[item.action]}
      </span>
    </li>
  )
}

export function PublicationDetail({ publicationId }: { publicationId: string }) {
  const query = usePublicationQuery(publicationId)
  const headingRef = useHeadingFocus<HTMLHeadingElement>(query.data?.id)

  if (query.isPending) {
    return <LoadingState label="Loading published version…" />
  }

  if (query.isError || !query.data) {
    return (
      <ErrorState
        message={query.error?.message ?? 'Published version unavailable.'}
        onRetry={() => void query.refetch()}
      />
    )
  }

  const publication = query.data
  const adds = publication.items.filter((item) => item.action === 'ADD').length
  const supersedes = publication.items.filter(
    (item) => item.action === 'SUPERSEDE',
  ).length

  return (
    <section className="page" aria-labelledby="publication-heading">
      <header className="page__header">
        <div>
          <h1 id="publication-heading" tabIndex={-1} ref={headingRef}>
            Knowledge publication v{publication.publicationNumber}
          </h1>
          <p className="meta">
            Published {formatTimestamp(publication.createdAt)}
            {publication.note ? ` · ${publication.note}` : ''}
          </p>
          <p className="meta">
            {publication.items.length} item
            {publication.items.length === 1 ? '' : 's'} · {adds} added ·{' '}
            {supersedes} superseded
          </p>
        </div>
        <Link to="/knowledge/publications" className="button">
          All published versions
        </Link>
      </header>

      <section aria-labelledby="publication-items-heading">
        <h2 id="publication-items-heading">Published items</h2>
        {publication.items.length === 0 ? (
          <p className="meta">This version contains no items.</p>
        ) : (
          <ul className="source-list">
            {publication.items.map((item) => (
              <PublicationItemRow key={item.id} item={item} />
            ))}
          </ul>
        )}
      </section>
    </section>
  )
}
