import { useEffect, useRef } from 'react'
import type { ArtifactDetail, Candidate } from '../../../api/knowledge'
import { positionText } from '../../../lib/knowledgeCatalog'
import { ChevronIcon, CheckIcon } from '../../ui/Icon'
import { Button } from '../../ui/Button'
import { Transform, type SourceState } from './Transform'

interface Props {
  artifact: ArtifactDetail
  /** Exactly the candidates the server would accept for bulk approval. */
  candidates: Candidate[]
  sourceFor: (candidate: Candidate) => SourceState
  inspected: ReadonlySet<string>
  onToggle: (candidateId: string) => void
  onInspectAll: () => void
  /** Take one candidate out of the group, to review it alone. */
  onReviewAlone: (candidateId: string) => void
  onApproveAll: () => void
  pending: boolean
  error: string | null
}

/**
 * Grouped safe review. Every candidate here is one the server accepts for bulk approval. The
 * reviewer must have opened every row in the group before the group can be approved, and the
 * request names exactly these candidates: it can never approve whatever happens to be open.
 */
export function GroupedReview({ artifact, candidates, sourceFor, inspected, onToggle, onInspectAll, onReviewAlone, onApproveAll, pending, error }: Props) {
  const heading = useRef<HTMLHeadingElement>(null)
  useEffect(() => {
    heading.current?.focus()
  }, [])
  const done = candidates.filter((candidate) => inspected.has(candidate.id)).length
  const all = candidates.length > 0 && done === candidates.length
  return (
    <>
      <div className="rv-kc-scroll">
        <div className="rv-kc-wrap">
          <div className="rv-kc-head">
            <div className="rv-kc-kicker">Review together</div>
            <h1 tabIndex={-1} ref={heading}>
              {candidates.length} routine {candidates.length === 1 ? 'entry' : 'entries'}
            </h1>
            <div className="rv-kc-bulkintro">
              <p>
                These entries have no conflicts or open questions. Open each one before you approve them together.
              </p>
              <Button onClick={onInspectAll} disabled={all || pending}>
                Review all
              </Button>
            </div>
          </div>

          {candidates.map((candidate) => {
            const open = inspected.has(candidate.id)
            return (
              <div key={candidate.id} className={`rv-kc-brow${open ? ' rv-kc-brow--open' : ''}`}>
                <button type="button" className="rv-kc-brow__btn" aria-expanded={open} onClick={() => onToggle(candidate.id)}>
                  <ChevronIcon className="rv-icon rv-kc-brow__chev" />
                  <b>{candidate.rawLabel}</b>
                  <span className="rv-kc-brow__sum">{positionText(candidate) ?? ''}</span>
                  {open ? <span className="rv-kc-brow__seen">Reviewed</span> : null}
                </button>
                {open ? (
                  <div className="rv-kc-brow__body">
                    <Transform candidate={candidate} artifact={artifact} source={sourceFor(candidate)} />
                    <p>
                      <button type="button" className="rv-lnk" onClick={() => onReviewAlone(candidate.id)}>
                        Review individually instead
                      </button>
                    </p>
                  </div>
                ) : null}
              </div>
            )
          })}
        </div>
      </div>
      <div className="rv-kc-decide">
        <div className="rv-kc-kd">
          <span className="rv-kc-st" role="status">
            Reviewed {done} of {candidates.length} routine entries
          </span>
          <Button variant="primary" disabled={!all || pending} onClick={onApproveAll}>
            <CheckIcon className="rv-icon" />
            {pending ? 'Approving…' : `Approve all ${candidates.length}`}
          </Button>
        </div>
        {error ? (
          <p className="rv-kc-error rv-kc-error--bar" role="alert">
            {error}
          </p>
        ) : null}
      </div>
    </>
  )
}
