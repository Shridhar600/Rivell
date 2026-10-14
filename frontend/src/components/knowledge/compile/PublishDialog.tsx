import { useId, useState } from 'react'
import { useCandidateQuery, type ArtifactDetail, type Candidate } from '../../../api/knowledge'
import { PublicationNotReadyError, usePublishCompilation, type PublishedCompilation } from '../../../api/knowledgeCompile'
import { conditionsOf, kindLabel, readiness } from '../../../lib/compilation'
import { hasHumanCorrection } from '../../../lib/compilationFields'
import { Button } from '../../ui/Button'
import { Dialog } from '../../ui/Dialog'

function PeerLabel({ candidateId }: { candidateId: string }) {
  const peer = useCandidateQuery(candidateId)
  return <strong>{peer.data ? peer.data.rawLabel : 'earlier published knowledge'}</strong>
}

interface Props {
  artifact: ArtifactDetail
  candidates: Candidate[]
  /** New candidate id to the published candidate it replaces. */
  supersede: Record<string, string>
  onClose: () => void
  onPublished: (result: PublishedCompilation) => void
  /** The server's answer may have changed what is approved, so the list should be read again. */
  onStale: () => void
}

/**
 * The publication boundary. It states what publishing does, lists what will enter and what will
 * not, and publishes only on an explicit confirmation. What is listed comes from the candidates
 * the server last returned, and the publish request reads them again before it is sent.
 */
export function PublishDialog({ artifact, candidates, supersede, onClose, onPublished, onStale }: Props) {
  const titleId = useId()
  const publish = usePublishCompilation(artifact.id)
  const [note, setNote] = useState('')
  const state = readiness(candidates)
  const approved = candidates.filter((candidate) => candidate.reviewStatus === 'APPROVED')
  const approvedIds = new Set(approved.map((candidate) => candidate.id))
  const replacing = Object.entries(supersede).filter(([id]) => approvedIds.has(id))
  const corrected = approved.filter((candidate) => hasHumanCorrection(candidate, artifact)).length
  // A possible replacement the reviewer did not choose. Said plainly, so it is never a silent omission.
  const notReplacing = approved.filter(
    (candidate) => !supersede[candidate.id] && candidate.conflictSignals.some((signal) => signal.conflictType === 'SUPERSEDES_EXISTING' && signal.publishedCandidateId),
  )
  const flagged = approved.filter((candidate) => conditionsOf(candidate).length > 0).length
  const kind = kindLabel(artifact.artifactType).toLowerCase()
  const busy = publish.isPending
  const notReady = publish.error instanceof PublicationNotReadyError

  return (
    <Dialog labelledBy={titleId} className="rv-dlg--confirm" onClose={() => (busy ? undefined : onClose())}>
      <form
        className="rv-dlg__in"
        aria-busy={busy}
        onSubmit={(event) => {
          event.preventDefault()
          publish.mutate(
            { note, supersede },
            {
              onSuccess: onPublished,
              onError: onStale,
            },
          )
        }}
      >
        <h2 id={titleId}>Publish this {kind}?</h2>
        <p>
          Publishing turns the reviewed structured candidates into a new, numbered, immutable organizational publication that Rivell can use authoritatively in future reviews. Once published it cannot be edited, and it does not rewrite earlier publications.
        </p>
        <ul className="rv-dlg__list">
          <li>
            {approved.length} reviewed {approved.length === 1 ? 'candidate enters' : 'candidates enter'} the new publication
            {corrected > 0 ? `, including ${corrected} corrected by you` : ''}
          </li>
          {state.rejected > 0 ? (
            <li>
              {state.rejected} rejected {state.rejected === 1 ? 'candidate stays' : 'candidates stay'} out of published knowledge
            </li>
          ) : null}
          {replacing.length > 0 ? (
            <li>
              Replaces earlier published knowledge:{' '}
              {replacing.map(([id, old], position) => (
                <span key={id}>
                  {position > 0 ? ', ' : ''}
                  <PeerLabel candidateId={old} />
                </span>
              ))}
              . It stays visible in earlier publications.
            </li>
          ) : null}
          <li>The uploaded original stays unchanged</li>
          <li>Earlier publications remain reproducible</li>
        </ul>
        {notReplacing.length > 0 ? (
          <p className="rv-dlg__note" role="status">
            {notReplacing.length} {notReplacing.length === 1 ? 'candidate' : 'candidates'} may replace published knowledge, and you did not choose that. The earlier knowledge stays in use alongside {notReplacing.length === 1 ? 'it' : 'them'}.
          </p>
        ) : null}
        {flagged > 0 ? (
          <p className="rv-dlg__note" role="status">
            {flagged} of these {flagged === 1 ? 'was' : 'were'} flagged for closer review. Publishing makes {flagged === 1 ? 'it' : 'them'} organizational knowledge.
          </p>
        ) : null}
        <label className="rv-dlg__field">
          <span className="rv-dlg__lb">Publication note, optional</span>
          <textarea rows={2} value={note} disabled={busy} onChange={(event) => setNote(event.target.value)} />
        </label>
        {publish.isError ? (
          <p className="rv-kc-error" role="alert">
            {notReady
              ? `This compilation changed. ${(publish.error as PublicationNotReadyError).readiness.blocker ?? ''} Review it, then publish again.`
              : publish.error.message}
          </p>
        ) : null}
        <div className="rv-dlg__foot">
          <Button disabled={busy} onClick={onClose}>
            Keep reviewing
          </Button>
          <Button variant="primary" type="submit" disabled={busy || approved.length === 0 || notReady}>
            {busy ? 'Publishing…' : 'Publish'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
