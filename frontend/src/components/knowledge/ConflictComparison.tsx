import { useState } from 'react'
import { useCandidateQuery, type Candidate, type ConflictType } from '../../api/knowledge'
import { CANDIDATE_TYPE_LABELS } from '../../lib/knowledgeLabels'
import {
  conceptLabel,
  formatPositions,
  POSITION_LABELS,
  proposedPositions,
} from '../../lib/semantics'
import { LoadingState } from '../LoadingState'

const RELATIONSHIP_LABELS: Record<ConflictType, string> = {
  DUPLICATE: 'Possible duplicate',
  CONTRADICTS_EXISTING: 'Potential conflict',
  SUPERSEDES_EXISTING: 'Possible supersession',
  OVERLAPPING_EFFECTIVE_PERIOD: 'Overlapping effective period',
  JURISDICTION_MISMATCH: 'Jurisdiction differs',
  AUTHORITY_CONFLICT: 'Authority conflict',
  UNKNOWN: 'Relationship not determined',
}

const RELATIONSHIP_EXPLANATIONS: Record<ConflictType, string> = {
  DUPLICATE:
    'Knowledge with the same content and scope is already published.',
  CONTRADICTS_EXISTING:
    'This may conflict with a published position of the same concept and scope. Review both before approving.',
  SUPERSEDES_EXISTING:
    'Rivell believes this candidate may replace published knowledge. Replacing is your decision and only happens on publication.',
  OVERLAPPING_EFFECTIVE_PERIOD:
    'The effective period overlaps published knowledge of the same concept.',
  JURISDICTION_MISMATCH:
    'The jurisdiction differs from the published peer. Rivell does not treat this as a conflict by itself.',
  AUTHORITY_CONFLICT:
    'The published peer has different authority for this concept.',
  UNKNOWN:
    'The relationship could not be determined; semantic analysis may have been unavailable.',
}

function PublishedPeer({ candidateId }: { candidateId: string }) {
  const query = useCandidateQuery(candidateId)

  if (query.isPending) {
    return <LoadingState label="Loading published knowledge…" />
  }

  if (query.isError || !query.data) {
    return (
      <p className="meta" role="status">
        The published knowledge could not be loaded.
      </p>
    )
  }

  const peer = query.data
  const positions = peer.positions.length > 0 ? peer.positions : proposedPositions(peer)

  return (
    <div className="published-candidate">
      <p className="meta">
        Published knowledge: {peer.rawLabel} ·{' '}
        {CANDIDATE_TYPE_LABELS[peer.candidateType]}
        {conceptLabel(peer.legalConceptKey)
          ? ` · ${conceptLabel(peer.legalConceptKey)}`
          : ''}
      </p>
      {peer.contentText ? (
        <p className="published-candidate__text">{peer.contentText}</p>
      ) : null}
      {positions && positions.length > 0 ? (
        <p className="meta">
          Published positions: {formatPositions(positions)}
        </p>
      ) : null}
      {peer.variantRole ? (
        <p className="meta">
          Published variant role: {POSITION_LABELS[peer.variantRole]}
        </p>
      ) : null}
      <p className="meta">
        Scope: {peer.jurisdiction ?? 'jurisdiction not set'} ·{' '}
        {peer.effectiveFrom ?? 'no start date'} to{' '}
        {peer.effectiveTo ?? 'open ended'}
      </p>
    </div>
  )
}

function ConflictRow({
  signal,
  supersessionChoice,
  onSupersessionChange,
}: {
  signal: Candidate['conflictSignals'][number]
  supersessionChoice: string | null
  onSupersessionChange: (peerId: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const peerId = signal.publishedCandidateId

  return (
    <li className="conflict">
      <p>
        <span className="badge badge--conflict">
          {RELATIONSHIP_LABELS[signal.conflictType]}
        </span>{' '}
        {RELATIONSHIP_EXPLANATIONS[signal.conflictType]}
      </p>
      {peerId ? (
        <>
          <button
            type="button"
            className="button"
            onClick={() => setOpen((previous) => !previous)}
          >
            {open ? 'Hide published knowledge' : 'Compare with published knowledge'}
          </button>
          {open ? <PublishedPeer candidateId={peerId} /> : null}
          {signal.conflictType === 'SUPERSEDES_EXISTING' ? (
            <label className="checkbox conflict__supersession">
              <input
                type="checkbox"
                checked={supersessionChoice === peerId}
                onChange={(event) =>
                  onSupersessionChange(event.target.checked ? peerId : null)
                }
              />
              Replace this published knowledge when publishing
            </label>
          ) : null}
        </>
      ) : null}
    </li>
  )
}

export function ConflictComparison({
  candidate,
  supersessionChoice,
  onSupersessionChange,
}: {
  candidate: Candidate
  supersessionChoice: string | null
  onSupersessionChange: (peerId: string | null) => void
}) {
  const hasSignals = candidate.conflictSignals.length > 0
  if (!hasSignals && !candidate.supersedingCandidateId) return null

  return (
    <div className="conflict-shell">
      {hasSignals ? (
        <>
          <p className="meta">
            Rivell compares this candidate with published knowledge of the same
            concept and scope. Signals are advisory; scope and overlap decisions
            come from the backend, and a human decides what to publish.
          </p>
          <ul className="conflict-list">
            {candidate.conflictSignals.map((signal) => (
              <ConflictRow
                key={`${signal.conflictType}:${signal.publishedCandidateId ?? 'none'}`}
                signal={signal}
                supersessionChoice={supersessionChoice}
                onSupersessionChange={onSupersessionChange}
              />
            ))}
          </ul>
        </>
      ) : null}
      {candidate.supersedingCandidateId ? (
        <p className="meta">
          On publication this candidate replaces published knowledge{' '}
          <PublishedPeerLink candidateId={candidate.supersedingCandidateId} />.
        </p>
      ) : null}
    </div>
  )
}

function PublishedPeerLink({ candidateId }: { candidateId: string }) {
  const query = useCandidateQuery(candidateId)
  if (query.isPending || query.isError || !query.data) {
    return <span>previously published knowledge</span>
  }
  return <span>{query.data.rawLabel}</span>
}
