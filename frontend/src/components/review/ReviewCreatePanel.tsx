import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  reviewErrorStatus,
  useCreateReview,
  useReviewPacksQuery,
} from '../../api/reviews'
import type { ReviewProfile } from '../../api/semantics'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from '../../lib/contractSemantics'
import { newRequestId } from '../../lib/requestId'
import { classifyCreateOutcome, type CreateOutcome } from '../../lib/reviewCreation'
import { LoadingState } from '../LoadingState'

type RecoveryOutcome = { ok: boolean; ids: string[] }

type Props = {
  contractId: string
  versionNumber: number
  profile: ReviewProfile | null
  reviewIds: string[]
  onCheckReviews: () => Promise<RecoveryOutcome>
}

export function ReviewCreatePanel({
  contractId,
  versionNumber,
  profile,
  reviewIds,
  onCheckReviews,
}: Props) {
  const navigate = useNavigate()
  const create = useCreateReview(contractId, versionNumber)
  const packsQuery = useReviewPacksQuery()
  const [packKey, setPackKey] = useState('')
  const selectedPack = (packsQuery.data ?? []).find((pack) => pack.key === packKey) ?? null
  const [outcome, setOutcome] = useState<CreateOutcome | null>(null)
  const [blocked, setBlocked] = useState(false)
  const [recoveryState, setRecoveryState] = useState<
    'none' | 'needs-check' | 'refreshed' | 'found-review' | 'refresh-failed'
  >('none')
  const [foundReviewId, setFoundReviewId] = useState<string | null>(null)
  const [rechecking, setRechecking] = useState(false)
  const submitting = useRef(false)
  const knownReviewIds = useRef<string[]>([])
  const requestRef = useRef<{ signature: string; id: string | null } | null>(null)
  const requestSignature = `${contractId}:${versionNumber}:${packKey}`

  function requestId(): string | undefined {
    if (requestRef.current?.signature !== requestSignature) {
      requestRef.current = { signature: requestSignature, id: newRequestId() }
    }
    return requestRef.current.id ?? undefined
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || create.isPending) return
    if (blocked && recoveryState !== 'refreshed') return
    submitting.current = true
    knownReviewIds.current = reviewIds
    setOutcome(null)
    setRecoveryState('none')
    setFoundReviewId(null)
    create.mutate(
      { contractPackKey: packKey || null, clientRequestId: requestId() },
      {
        onSuccess: (summary) =>
          navigate(`/reviews/${contractId}/analysis/${summary.id}`),
        onError: (error) => {
          const failure = classifyCreateOutcome(reviewErrorStatus(error))
          setOutcome(failure)
          setBlocked(failure.uncertain)
          setRecoveryState(failure.uncertain ? 'needs-check' : 'none')
        },
        onSettled: () => {
          submitting.current = false
        },
      },
    )
  }

  async function handleRecoveryCheck() {
    if (rechecking) return
    setRechecking(true)
    const outcome = await onCheckReviews()
    setRechecking(false)
    if (!outcome.ok) {
      setRecoveryState('refresh-failed')
      return
    }
    const known = new Set(knownReviewIds.current)
    const appeared = outcome.ids.find((id) => !known.has(id))
    if (appeared) {
      setFoundReviewId(appeared)
      setRecoveryState('found-review')
    } else {
      setRecoveryState('refreshed')
    }
  }

  return (
    <section aria-labelledby="create-review-heading" className="panel">
      <h2 id="create-review-heading">Start an automated review</h2>
      <p className="panel__hint">
        The review runs against this contract version's confirmed review profile and freezes
        the policy state it sees at that moment.
      </p>

      {profile ? (
        <dl className="data-list profile-summary" aria-label="Confirmed review profile">
          <div className="data-list__row">
            <dt>Contract type</dt>
            <dd>{CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType}</dd>
          </div>
          <div className="data-list__row">
            <dt>Our side</dt>
            <dd>{profile.ourSideRole ? roleLabel(profile.ourSideRole) : 'Not specified'}</dd>
          </div>
          <div className="data-list__row">
            <dt>Paper source</dt>
            <dd>
              {profile.paperSource
                ? PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource
                : 'Not specified'}
            </dd>
          </div>
          <div className="data-list__row">
            <dt>Jurisdiction</dt>
            <dd>
              {profile.jurisdiction
                ? JURISDICTION_LABELS[profile.jurisdiction] ?? profile.jurisdiction
                : 'Not specified'}
            </dd>
          </div>
        </dl>
      ) : (
        <p className="state state--warning">
          The contract's review profile could not be read. Reviews still use the profile
          stored with the contract.
        </p>
      )}

      <form onSubmit={handleSubmit} aria-label="Start automated review">
        <div className="field-group">
          <label htmlFor="review-pack">Contract pack (optional)</label>
          <select
            id="review-pack"
            value={packKey}
            onChange={(event) => setPackKey(event.target.value)}
            disabled={create.isPending || (blocked && recoveryState !== 'refreshed')}
            aria-describedby="review-pack-hint"
          >
            <option value="">No contract pack</option>
            {(packsQuery.data ?? []).map((pack) => (
              <option key={pack.key} value={pack.key}>
                {pack.displayName}
              </option>
            ))}
          </select>
          {packsQuery.isError ? (
            <p className="state state--warning" role="status">
              The review pack catalog could not be loaded. You can still run a review without a
              pack.{' '}
              <button
                type="button"
                className="link-button"
                onClick={() => void packsQuery.refetch()}
              >
                Try again
              </button>
            </p>
          ) : (
            <p className="field__hint" id="review-pack-hint">
              {selectedPack
                ? `${selectedPack.summary} (catalog version ${selectedPack.version}).`
                : "A pack adds completeness checks for that contract type. If this contract's review profile declares a pack, that pack is used; otherwise only generally applicable clause checks run — a valid, reduced review."}
            </p>
          )}
        </div>

        {create.isPending ? (
          <LoadingState label="Starting review…" />
        ) : blocked && recoveryState === 'needs-check' ? (
          <p className="state state--warning">
            Re-check the review history before starting another review.
          </p>
        ) : blocked && recoveryState === 'refresh-failed' ? (
          <p className="state state--warning">
            The review history could not be refreshed. Do not start another review until you
            can confirm whether the first one was created.
          </p>
        ) : blocked && recoveryState === 'found-review' && foundReviewId ? (
          <p className="state state--warning">
            A review was created while the outcome was unknown.{' '}
            <Link
              className="link-button"
              to={`/reviews/${contractId}/analysis/${foundReviewId}`}
            >
              Open the created review
            </Link>
          </p>
        ) : (
          <>
            {blocked && recoveryState === 'refreshed' ? (
              <p role="status">
                No new review appeared in the history. You can start the review again.
              </p>
            ) : null}
            <button type="submit" className="button button--primary">
              Start review
            </button>
          </>
        )}
      </form>

      {outcome ? (
        <div role="alert" className="state state--error">
          <p>{outcome.message}</p>
          {outcome.kind === 'CONFLICT' ? (
            <Link className="link-button" to={`/reviews/${contractId}?view=semantics`}>
              Open the contract&apos;s semantic analysis
            </Link>
          ) : null}
          {outcome.uncertain ? (
            <button
              type="button"
              className="button"
              disabled={rechecking}
              aria-busy={rechecking}
              onClick={() => void handleRecoveryCheck()}
            >
              {rechecking ? 'Re-checking reviews…' : 'Re-check reviews'}
            </button>
          ) : null}
        </div>
      ) : null}
    </section>
  )
}
