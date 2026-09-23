import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import {
  useCandidateQuery,
  usePublishKnowledge,
  type ArtifactDetail,
  type Candidate,
} from '../../api/knowledge'
import { CANDIDATE_TYPE_LABELS } from '../../lib/knowledgeLabels'
import {
  conceptLabel,
  hasSupersessionProposal,
  REVIEW_CATEGORY_LABELS,
} from '../../lib/semantics'
import { ErrorState } from '../ErrorState'

function PeerLabel({ candidateId }: { candidateId: string }) {
  const query = useCandidateQuery(candidateId)
  if (query.isPending || query.isError || !query.data) {
    return <span>published knowledge</span>
  }
  return <strong>{query.data.rawLabel}</strong>
}

export function PublicationDialog({
  artifact,
  approved,
  supersessionChoices,
  onClearSupersession,
  onClose,
}: {
  artifact: ArtifactDetail
  approved: Candidate[]
  supersessionChoices: Record<string, string>
  onClearSupersession: (candidateId: string) => void
  onClose: () => void
}) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const navigate = useNavigate()
  const publish = usePublishKnowledge()
  const [note, setNote] = useState('')

  useEffect(() => {
    const dialog = dialogRef.current
    if (dialog && typeof dialog.showModal === 'function' && !dialog.open) {
      dialog.showModal()
    }
    return () => {
      if (dialog && dialog.open && typeof dialog.close === 'function') {
        dialog.close()
      }
    }
  }, [])

  function closeDialog() {
    const dialog = dialogRef.current
    if (dialog && dialog.open && typeof dialog.close === 'function') {
      dialog.close()
    }
    onClose()
  }

  const supersessions = approved
    .filter(
      (candidate) =>
        supersessionChoices[candidate.id] && hasSupersessionProposal(candidate),
    )
    .map((candidate) => ({
      candidate,
      supersededId: supersessionChoices[candidate.id],
    }))
  const highAttention = approved.filter(
    (candidate) => candidate.reviewCategory === 'HIGH',
  ).length
  const elevated = approved.filter(
    (candidate) => candidate.reviewCategory === 'ELEVATED',
  ).length

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const supersededCandidates: Record<string, string> = {}
    for (const { candidate, supersededId } of supersessions) {
      supersededCandidates[candidate.id] = supersededId
    }
    publish.mutate(
      {
        candidateIds: approved.map((candidate) => candidate.id),
        supersededCandidates,
        note,
        artifactIds: [artifact.id],
      },
      {
        onSuccess: (publication) =>
          navigate(`/knowledge/publications/${publication.id}`),
      },
    )
  }

  return (
    <dialog
      ref={dialogRef}
      className="dialog"
      aria-labelledby="publish-dialog-heading"
      onCancel={(event) => {
        event.preventDefault()
        closeDialog()
      }}
    >
      <form className="dialog__form" onSubmit={handleSubmit}>
        <h2 id="publish-dialog-heading">Publish knowledge version</h2>
        <p>
          Publishing {approved.length} approved candidate
          {approved.length === 1 ? '' : 's'} creates a new organizational
          knowledge version. Published candidates become immutable and can no
          longer be reviewed.
        </p>

        <section aria-labelledby="publish-add-heading">
          <h3 id="publish-add-heading" className="dialog__section-heading">
            Adding new knowledge
          </h3>
          <ul className="dialog__list">
            {approved.map((candidate) => (
              <li key={candidate.id}>
                {candidate.rawLabel}
                <span className="meta">
                  {' '}
                  · {CANDIDATE_TYPE_LABELS[candidate.candidateType]}
                  {conceptLabel(candidate.legalConceptKey)
                    ? ` · ${conceptLabel(candidate.legalConceptKey)}`
                    : ''}
                </span>
                {candidate.reviewCategory ? (
                  <span className="meta">
                    {' '}
                    · {REVIEW_CATEGORY_LABELS[candidate.reviewCategory]}
                  </span>
                ) : null}
              </li>
            ))}
          </ul>
        </section>

        {supersessions.length > 0 ? (
          <section aria-labelledby="publish-supersede-heading">
            <h3 id="publish-supersede-heading" className="dialog__section-heading">
              Replacing published knowledge
            </h3>
            <ul className="dialog__list">
              {supersessions.map(({ candidate, supersededId }) => (
                <li key={candidate.id}>
                  {candidate.rawLabel} replaces <PeerLabel candidateId={supersededId} />{' '}
                  <button
                    type="button"
                    className="button"
                    onClick={() => onClearSupersession(candidate.id)}
                  >
                    Remove replacement
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        {highAttention > 0 || elevated > 0 ? (
          <p className="meta" role="status">
            This publication includes{' '}
            {highAttention > 0
              ? `${highAttention} candidate${highAttention === 1 ? '' : 's'} with high review attention`
              : ''}
            {highAttention > 0 && elevated > 0 ? ' and ' : ''}
            {elevated > 0
              ? `${elevated} with elevated review attention`
              : ''}
            . Publishing makes them organizational knowledge.
          </p>
        ) : null}

        <div className="field">
          <label htmlFor="publication-note">Version note (optional)</label>
          <textarea
            id="publication-note"
            rows={2}
            value={note}
            disabled={publish.isPending}
            onChange={(event) => setNote(event.target.value)}
          />
        </div>

        {publish.isError ? (
          <ErrorState message={publish.error.message || 'Publication failed.'} />
        ) : null}

        <div className="review-actions">
          <button
            type="submit"
            className="button button--primary"
            disabled={publish.isPending}
          >
            Publish version
          </button>
          <button
            type="button"
            className="button"
            disabled={publish.isPending}
            onClick={closeDialog}
          >
            Cancel
          </button>
        </div>
      </form>
    </dialog>
  )
}
