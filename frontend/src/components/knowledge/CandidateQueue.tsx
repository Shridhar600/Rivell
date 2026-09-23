import type { ReactNode } from 'react'
import { REVIEW_STATUSES, type Candidate } from '../../api/knowledge'
import {
  countByReviewStatus,
  filterCandidates,
  hasConflicts,
  type ReviewStatusFilter,
} from '../../lib/candidates'
import {
  CANDIDATE_TYPE_LABELS,
  REVIEW_STATUS_LABELS,
} from '../../lib/knowledgeLabels'
import { hasSupersessionProposal, isBulkEligible } from '../../lib/semantics'
import { ReviewAttentionBadge } from './ReviewAttention'

export function CandidateQueue({
  candidates,
  selectedId,
  onSelect,
  statusFilter,
  onStatusFilterChange,
  conflictsOnly,
  onConflictsOnlyChange,
  publishAction,
  selectedForBulk,
  onToggleBulk,
}: {
  candidates: Candidate[]
  selectedId: string | null
  onSelect: (id: string) => void
  statusFilter: ReviewStatusFilter
  onStatusFilterChange: (filter: ReviewStatusFilter) => void
  conflictsOnly: boolean
  onConflictsOnlyChange: (value: boolean) => void
  publishAction?: ReactNode
  selectedForBulk: Set<string>
  onToggleBulk: (id: string) => void
}) {
  const counts = countByReviewStatus(candidates)
  const filtered = filterCandidates(candidates, { status: statusFilter, conflictsOnly })

  return (
    <section className="queue" aria-labelledby="candidate-queue-heading">
      <div className="section-header">
        <h2 id="candidate-queue-heading">Candidates</h2>
        {publishAction}
      </div>
      <p className="meta">
        {counts.total} candidate{counts.total === 1 ? '' : 's'} ·{' '}
        {counts.APPROVED} approved · {counts.EDITED} edited · {counts.PENDING}{' '}
        pending
        {counts.REJECTED > 0 ? ` · ${counts.REJECTED} rejected` : ''}
        {counts.PUBLISHED > 0 ? ` · ${counts.PUBLISHED} published` : ''}
      </p>
      {publishAction && counts.APPROVED === 0 ? (
        <p className="meta">
          Approve candidates to publish a new knowledge version.
        </p>
      ) : null}

      <div className="queue__filters">
        <div className="field">
          <label htmlFor="candidate-status-filter">Status</label>
          <select
            id="candidate-status-filter"
            value={statusFilter}
            onChange={(event) =>
              onStatusFilterChange(event.target.value as ReviewStatusFilter)
            }
          >
            <option value="ALL">All statuses</option>
            {REVIEW_STATUSES.map((status) => (
              <option key={status} value={status}>
                {REVIEW_STATUS_LABELS[status]} ({counts[status]})
              </option>
            ))}
          </select>
        </div>
        <label className="checkbox">
          <input
            type="checkbox"
            checked={conflictsOnly}
            onChange={(event) => onConflictsOnlyChange(event.target.checked)}
          />
          Conflicts only
        </label>
      </div>

      {filtered.length === 0 ? (
        <p className="meta">No candidates match this filter.</p>
      ) : (
        <ul className="candidate-list">
          {filtered.map((candidate) => (
            <li key={candidate.id} className="candidate-list__row">
              {isBulkEligible(candidate) ? (
                <label className="checkbox candidate-list__select">
                  <input
                    type="checkbox"
                    checked={selectedForBulk.has(candidate.id)}
                    aria-label={`Select ${candidate.rawLabel} for bulk approval`}
                    onChange={() => onToggleBulk(candidate.id)}
                  />
                </label>
              ) : null}
              <button
                type="button"
                className={
                  candidate.id === selectedId
                    ? 'candidate-list__button candidate-list__button--selected'
                    : 'candidate-list__button'
                }
                aria-current={candidate.id === selectedId ? 'true' : undefined}
                onClick={() => onSelect(candidate.id)}
              >
                <span className="candidate-list__label">{candidate.rawLabel}</span>
                <span className="meta">
                  {CANDIDATE_TYPE_LABELS[candidate.candidateType]}
                </span>
                <span className="candidate-list__badges">
                  <ReviewAttentionBadge candidate={candidate} />
                  <span
                    className={`badge badge--${candidate.reviewStatus.toLowerCase()}`}
                  >
                    {REVIEW_STATUS_LABELS[candidate.reviewStatus]}
                  </span>
                  {hasSupersessionProposal(candidate) ? (
                    <span className="badge badge--conflict">Supersession proposed</span>
                  ) : null}
                  {hasConflicts(candidate) && !hasSupersessionProposal(candidate) ? (
                    <span className="badge badge--conflict">Conflict</span>
                  ) : null}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
