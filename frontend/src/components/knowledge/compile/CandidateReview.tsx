import { useId, useImperativeHandle, useState, type RefObject } from 'react'
import { Link } from 'react-router'
import { useApproveCandidate, useRejectCandidate, type ArtifactDetail, type Candidate } from '../../../api/knowledge'
import {
  OUTCOME_LABELS,
  conditionsOf,
  kindLabel,
  needsConflictAcknowledgement,
  outcomeOf,
  type Readiness,
} from '../../../lib/compilation'
import type { PublicationRef } from '../../../lib/knowledgeCatalog'
import { useHeadingFocus } from '../../../lib/useHeadingFocus'
import { Button } from '../../ui/Button'
import { CautionIcon, CheckIcon, CloseIcon, EditIcon } from '../../ui/Icon'
import { ConditionChoices, ConditionNotice } from './Conditions'
import { CorrectionForm } from './CorrectionForm'
import { Transform, type SourceState } from './Transform'
import { useCorrection } from './useCorrection'

/** What the keyboard shortcuts can ask the open candidate to do. */
export interface ReviewActions {
  approve: () => void
  reject: () => void
  edit: () => void
}

interface Props {
  artifact: ArtifactDetail
  candidate: Candidate
  /** Position in the whole compilation, from one. */
  index: number
  total: number
  source: SourceState
  location: string | null
  readiness: Readiness
  replaces: string | null
  onReplace: (published: string | null) => void
  /** The publication this candidate is part of, once it is published. */
  publishedIn: PublicationRef | null
  /** Titles of the candidates this compilation was compared with, loaded once by the workspace. */
  candidateTitles: ReadonlyMap<string, string>
  actions: RefObject<ReviewActions | null>
  onDecided: (candidateId: string) => void
  onNext: () => void
  onContext: (opener: RefObject<HTMLButtonElement | null>) => void
  /** Called with the failure so the workspace can read the server's state again. */
  onFailed: () => void
}

/**
 * One candidate in review: where it came from, what Rivell made of it, why it needs attention,
 * and the reviewer's own decision. Every outcome shown is the one the server returned.
 */
export function CandidateReview({ artifact, candidate, index, total, source, location, readiness, replaces, onReplace, publishedIn, candidateTitles, actions, onDecided, onNext, onContext, onFailed }: Props) {
  const approve = useApproveCandidate()
  const reject = useRejectCandidate()
  const [wantsEdit, setEditing] = useState(false)
  const [acknowledged, setAcknowledged] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const formId = useId()
  const headingRef = useHeadingFocus<HTMLHeadingElement>(candidate.id)
  const correction = useCorrection(candidate, () => setEditing(false))

  const outcome = outcomeOf(candidate)
  const published = outcome === 'published'
  // A candidate the server has since published can no longer be edited, whatever was open.
  const editing = wantsEdit && !published
  const open = outcome === 'pending' || outcome === 'corrected'
  const conditions = open ? conditionsOf(candidate) : []
  const blockedByConflict = open && needsConflictAcknowledgement(candidate) && !acknowledged
  const busy = approve.isPending || reject.isPending || correction.pending
  const canApprove = (outcome === 'pending' || outcome === 'corrected') && !blockedByConflict && !editing && !busy
  const canReject = !published && !editing && !busy
  const canEdit = !published && !editing && !busy

  function doApprove() {
    if (!canApprove) return
    setError(null)
    approve.mutate({ candidateId: candidate.id }, { onSuccess: () => onDecided(candidate.id), onError: (failure) => { setError(failure.message); onFailed() } })
  }
  function doReject() {
    if (!canReject) return
    setError(null)
    reject.mutate({ candidateId: candidate.id }, { onSuccess: () => onDecided(candidate.id), onError: (failure) => { setError(failure.message); onFailed() } })
  }
  function doEdit() {
    if (canEdit) setEditing(true)
  }
  useImperativeHandle(actions, () => ({ approve: doApprove, reject: doReject, edit: doEdit }))

  const statusText = (() => {
    if (published) return `Published${publishedIn ? ` in knowledge version ${publishedIn.number}` : ''}. Published knowledge cannot be changed.`
    if (outcome === 'approved') return 'Approved. The outcome can still change before it is published.'
    if (outcome === 'rejected') return 'Rejected. A rejected entry can be brought back only by correcting it.'
    if (outcome === 'corrected') return 'Corrected. Approve the corrected value to include it in the new version.'
    return `Does this match the ${kindLabel(artifact.artifactType).toLowerCase()} source?`
  })()

  return (
    <>
      <div className="rv-kc-scroll">
        <div className="rv-kc-wrap">
          <div className="rv-kc-head">
            <div className="rv-kc-kicker">
              Entry {index} of {total}
            </div>
            <h1 tabIndex={-1} ref={headingRef}>
              {candidate.title}
            </h1>
            <div className="rv-kc-meta">
              <span className={`rv-kc-stb${outcome === 'approved' || outcome === 'published' || outcome === 'corrected' ? ' rv-kc-stb--ok' : ''}`}>{OUTCOME_LABELS[outcome]}</span>
              {conditions[0] ? (
                <span className="rv-kc-condition">
                  <CautionIcon className="rv-icon" />
                  {conditions[0].label}
                </span>
              ) : null}
              <span className="rv-chip">{kindLabel(artifact.artifactType)}</span>
              {location ? <span className="rv-chip">{location}</span> : null}
            </div>
          </div>

          {readiness.canPublish && !published ? (
            <div className="rv-kc-pubready" role="status">
              <CheckIcon className="rv-icon" />
              <div>
                <b>Draft review complete</b>
                Every entry has been reviewed. This {kindLabel(artifact.artifactType).toLowerCase()} is ready to publish.
              </div>
            </div>
          ) : null}

          {open ? <ConditionNotice candidate={candidate} /> : null}

          <Transform
            candidate={candidate}
            artifact={artifact}
            source={source}
            onContext={onContext}
            editing={editing ? <CorrectionForm id={formId} candidate={candidate} artifact={artifact} correction={correction} onSubmit={() => void correction.save()} /> : null}
          />

          {open ? <ConditionChoices candidate={candidate} acknowledged={acknowledged} onAcknowledge={setAcknowledged} replaces={replaces} onReplace={onReplace} peerTitles={candidateTitles} /> : null}

          {candidate.reviewNote && !editing ? <p className="rv-kc-note">Review note: {candidate.reviewNote}</p> : null}
        </div>
      </div>

      <div className="rv-kc-decide">
        <div className="rv-kc-kd">
          {editing ? (
            <>
              <span className="rv-kc-st">Edit this entry. Rivell&apos;s original reading is kept and the source stays unchanged.</span>
              <Button disabled={correction.pending} onClick={() => setEditing(false)}>
                Cancel
              </Button>
              <Button variant="primary" type="submit" form={formId} disabled={correction.pending}>
                {correction.pending ? 'Saving…' : 'Save correction'}
              </Button>
            </>
          ) : published ? (
            <>
              <span className="rv-kc-st">{statusText}</span>
              <Link className="rv-btn" to={`/knowledge/sources/${artifact.id}?position=${candidate.id}`}>
                Open in the source
              </Link>
            </>
          ) : (
            <>
              <span className="rv-kc-st" role="status">
                {statusText}
              </span>
              <Button disabled={!canReject} onClick={doReject} title="Reject (R)">
                <CloseIcon className="rv-icon" />
                Reject
              </Button>
              <Button disabled={!canEdit} onClick={doEdit} title="Edit (E)" variant={outcome === 'rejected' ? 'primary' : 'default'}>
                <EditIcon className="rv-icon" />
                Edit
              </Button>
              {outcome === 'approved' ? (
                <Button disabled title="Already approved">
                  <CheckIcon className="rv-icon" />
                  Approved
                </Button>
              ) : (
                <Button
                  variant="primary"
                  disabled={outcome === 'rejected' || !canApprove}
                  onClick={doApprove}
                  title={
                    outcome === 'rejected'
                      ? 'Correct it first'
                      : blockedByConflict
                        ? 'Confirm the comparison with the published knowledge first'
                        : 'Approve (A)'
                  }
                >
                  <CheckIcon className="rv-icon" />
                  {outcome === 'corrected' ? 'Approve correction' : 'Approve'}
                </Button>
              )}
              <Button onClick={onNext} title="Next (N)">
                Next <kbd>N</kbd>
              </Button>
            </>
          )}
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
