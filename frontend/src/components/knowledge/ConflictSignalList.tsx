import { useState } from 'react'
import { useCandidateQuery, type ConflictSignal } from '../../api/knowledge'
import { CANDIDATE_TYPE_LABELS, REVIEW_STATUS_LABELS } from '../../lib/knowledgeLabels'
import { LoadingState } from '../LoadingState'

function PublishedCandidateDisclosure({ candidateId }: { candidateId: string }) {
  const [open, setOpen] = useState(false)
  const query = useCandidateQuery(open ? candidateId : undefined)

  if (!open) {
    return (
      <button
        type="button"
        className="button"
        onClick={() => setOpen(true)}
      >
        Compare with published candidate
      </button>
    )
  }

  if (query.isPending) {
    return <LoadingState label="Loading published candidate…" />
  }

  if (query.isError || !query.data) {
    return (
      <p className="meta" role="status">
        The published candidate could not be loaded.
      </p>
    )
  }

  const published = query.data

  return (
    <div className="published-candidate">
      <p className="meta">
        Published knowledge: {published.rawLabel} ·{' '}
        {CANDIDATE_TYPE_LABELS[published.candidateType]} ·{' '}
        {REVIEW_STATUS_LABELS[published.reviewStatus]}
      </p>
      {published.contentText ? (
        <p className="published-candidate__text">{published.contentText}</p>
      ) : null}
      {Object.entries(published.payloadColumns).length > 0 ? (
        <dl className="data-list">
          {Object.entries(published.payloadColumns).map(([column, value]) => (
            <div key={column} className="data-list__row">
              <dt>{column}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </div>
  )
}

export function ConflictSignalList({ signals }: { signals: ConflictSignal[] }) {
  return (
    <div className="conflict-shell">
      <p className="meta">
        Deterministic checks compare this candidate with published knowledge.
        Signals are advisory in this milestone; they never block review.
      </p>
      <ul className="conflict-list">
        {signals.map((signal, index) => (
          <li
            key={`${signal.conflictType}:${signal.publishedCandidateId ?? 'none'}:${index}`}
            className="conflict"
          >
            <p>
              <span className="badge badge--conflict">
                {signal.conflictType.replaceAll('_', ' ').toLowerCase()}
              </span>{' '}
              {signal.message}
            </p>
            {signal.publishedCandidateId ? (
              <PublishedCandidateDisclosure
                candidateId={signal.publishedCandidateId}
              />
            ) : null}
          </li>
        ))}
      </ul>
    </div>
  )
}
