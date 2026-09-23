import { useEffect, useRef, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router'
import {
  usePublishKnowledge,
  type ArtifactDetail,
  type Candidate,
} from '../../api/knowledge'
import { CANDIDATE_TYPE_LABELS } from '../../lib/knowledgeLabels'
import { ErrorState } from '../ErrorState'

export function PublicationDialog({
  artifact,
  approved,
  onClose,
}: {
  artifact: ArtifactDetail
  approved: Candidate[]
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
  }, [])

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    publish.mutate(
      {
        candidateIds: approved.map((candidate) => candidate.id),
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
      onCancel={onClose}
    >
      <form className="dialog__form" onSubmit={handleSubmit}>
        <h2 id="publish-dialog-heading">Publish knowledge version</h2>
        <p>
          Publishing {approved.length} approved candidate
          {approved.length === 1 ? '' : 's'} creates a new organizational
          knowledge version. Published candidates become immutable and can no
          longer be reviewed.
        </p>
        <ul className="dialog__list">
          {approved.map((candidate) => (
            <li key={candidate.id}>
              {candidate.rawLabel}{' '}
              <span className="meta">
                {CANDIDATE_TYPE_LABELS[candidate.candidateType]}
              </span>
            </li>
          ))}
        </ul>
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
            onClick={onClose}
          >
            Cancel
          </button>
        </div>
      </form>
    </dialog>
  )
}
