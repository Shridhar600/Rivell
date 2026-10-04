import type { Candidate } from '../../../api/knowledge'
import { positionRow } from '../../../lib/knowledgePosition'
import { ChevronIcon } from '../../ui/Icon'

function excerpt(text: string | null): string {
  return text ? text.replace(/\s+/g, ' ').trim() : '—'
}

function Hidden({ children }: { children: string }) {
  return <span className="rv-visually-hidden">{children}</span>
}

function Cells({ candidate }: { candidate: Candidate }) {
  const row = positionRow(candidate)
  const text = excerpt(row.text)
  const escalate = row.escalation ? (
    <span>
      <Hidden>Escalate: </Hidden>
      {excerpt(row.escalation)}
    </span>
  ) : (
    <span className="rv-k-none">
      <Hidden>No escalation trigger</Hidden>
      <span aria-hidden="true">—</span>
    </span>
  )
  const none = (what: string) => (
    <span className="rv-k-none">
      <Hidden>{`No ${what} position`}</Hidden>
      <span aria-hidden="true">—</span>
    </span>
  )
  switch (row.layout) {
    case 'both':
      return (
        <>
          <span className="rv-k-both">
            <Hidden>Preferred and fallback: </Hidden>
            {text}
          </span>
          {escalate}
        </>
      )
    case 'preferred':
      return (
        <>
          <span>
            <Hidden>Preferred: </Hidden>
            {text}
          </span>
          {none('fallback')}
          {escalate}
        </>
      )
    case 'fallback':
      return (
        <>
          {none('preferred')}
          <span>
            <Hidden>Fallback: </Hidden>
            {text}
          </span>
          {escalate}
        </>
      )
    case 'passage':
      return (
        <>
          <span className="rv-k-both">{text}</span>
          {escalate}
        </>
      )
  }
}

interface PositionListProps {
  positions: Candidate[]
  selectedId: string | null
  onSelect: (candidateId: string) => void
}

/**
 * The canonical position table: concept, preferred, fallback, escalate. A passage that is both
 * preferred and fallback is shown once across both columns (see positionRow).
 */
export function PositionList({ positions, selectedId, onSelect }: PositionListProps) {
  return (
    <div className="rv-k-list">
      <div className="rv-k-pos-t rv-k-pos-t--head" aria-hidden="true">
        <span>Position</span>
        <span>Preferred</span>
        <span>Fallback</span>
        <span>Escalate</span>
      </div>
      <ul className="rv-k-rows" aria-label="Positions">
        {positions.map((candidate) => (
          <li key={candidate.id}>
            <button
              type="button"
              className="rv-k-row rv-k-posrow"
              aria-current={candidate.id === selectedId ? 'true' : undefined}
              onClick={() => onSelect(candidate.id)}
            >
              <span className="rv-k-pos-t">
                <b>{candidate.rawLabel}</b>
                <Cells candidate={candidate} />
              </span>
              <ChevronIcon className="rv-icon" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}
