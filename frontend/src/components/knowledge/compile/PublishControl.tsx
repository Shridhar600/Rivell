import type { Candidate } from '../../../api/knowledge'
import { kindLabel, readiness, reviewedCount } from '../../../lib/compilation'
import type { ArtifactType } from '../../../api/knowledge'
import { Button } from '../../ui/Button'
import { LockIcon } from '../../ui/Icon'

const PER_CANDIDATE_LIMIT = 30

function Segments({ candidates }: { candidates: Candidate[] }) {
  const state = readiness(candidates)
  const done = state.approved + state.published
  const label = `${reviewedCount(candidates)} of ${candidates.length} entries reviewed, ${state.rejected} rejected`
  if (candidates.length <= PER_CANDIDATE_LIMIT) {
    const cells = [
      ...Array.from({ length: done }, () => 'd'),
      ...Array.from({ length: state.rejected }, () => 'rj'),
      ...Array.from({ length: state.awaiting }, () => ''),
    ]
    return (
      <div className="rv-kc-seg" role="img" aria-label={label}>
        {cells.map((kind, position) => (
          <i key={position} className={kind} />
        ))}
      </div>
    )
  }
  // A long compilation shows proportions, not one mark per candidate.
  const part = (count: number) => `${(count / candidates.length) * 100}%`
  return (
    <div className="rv-kc-seg rv-kc-seg--bar" role="img" aria-label={label}>
      <i className="d" style={{ width: part(done) }} />
      <i className="rj" style={{ width: part(state.rejected) }} />
    </div>
  )
}

/** Progress, then the one control that crosses the publication boundary. */
export function PublishControl({ type, candidates, onPublish }: { type: ArtifactType; candidates: Candidate[]; onPublish: () => void }) {
  const state = readiness(candidates)
  const label = state.canPublish
    ? `Publish ${kindLabel(type).toLowerCase()}`
    : state.awaiting > 0
      ? `Publish (${state.awaiting} to review)`
      : state.published > 0 && state.approved === 0
        ? 'Published'
        : 'Nothing to publish'
  return (
    <>
      <div className="rv-kc-prog">
        <Segments candidates={candidates} />
        <span>
          {reviewedCount(candidates)} of {candidates.length} reviewed
        </span>
      </div>
      <Button variant="primary" disabled={!state.canPublish} onClick={onPublish} title={state.blocker ?? undefined}>
        <LockIcon className="rv-icon" />
        {label}
      </Button>
    </>
  )
}
