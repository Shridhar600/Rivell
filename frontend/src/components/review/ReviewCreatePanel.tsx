import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import {
  isReviewBadRequestError,
  isReviewConflictError,
  isReviewForbiddenError,
  isReviewNotFoundError,
  useCreateReview,
} from '../../api/reviews'
import type { ReviewProfile } from '../../api/semantics'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from '../../lib/contractSemantics'
import { REVIEW_PACK_OPTIONS } from '../../lib/reviews'
import { LoadingState } from '../LoadingState'

type CreateErrorCopy = { message: string; uncertain: boolean }

function describeCreateError(error: Error): CreateErrorCopy {
  if (isReviewConflictError(error)) {
    return {
      message:
        'This contract version is not ready for an automated review yet. Check the semantic analysis status, then start the review again.',
      uncertain: false,
    }
  }
  if (isReviewBadRequestError(error)) {
    return {
      message:
        "The selected contract pack conflicts with this contract's review profile. Choose a different pack, or run the review without a pack.",
      uncertain: false,
    }
  }
  if (isReviewNotFoundError(error)) {
    return {
      message: 'This contract version is not available for review.',
      uncertain: false,
    }
  }
  if (isReviewForbiddenError(error)) {
    return { message: 'This review could not be started.', uncertain: false }
  }
  return {
    message:
      'We could not confirm whether the review started. Check the review history before starting another review.',
    uncertain: true,
  }
}

type Props = {
  contractId: string
  versionNumber: number
  profile: ReviewProfile | null
  onCheckReviews: () => void
}

export function ReviewCreatePanel({ contractId, versionNumber, profile, onCheckReviews }: Props) {
  const navigate = useNavigate()
  const create = useCreateReview(contractId, versionNumber)
  const [packKey, setPackKey] = useState('')
  const [errorCopy, setErrorCopy] = useState<CreateErrorCopy | null>(null)
  const [checked, setChecked] = useState(false)
  const submitting = useRef(false)

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || create.isPending) return
    submitting.current = true
    setErrorCopy(null)
    setChecked(false)
    create.mutate(
      { contractPackKey: packKey || null },
      {
        onSuccess: (summary) =>
          navigate(`/reviews/${contractId}/analysis/${summary.id}`),
        onError: (error) => setErrorCopy(describeCreateError(error)),
        onSettled: () => {
          submitting.current = false
        },
      },
    )
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
            disabled={create.isPending}
          >
            <option value="">No contract pack</option>
            {REVIEW_PACK_OPTIONS.map((option) => (
              <option key={option.key} value={option.key}>
                {option.label}
              </option>
            ))}
          </select>
          <p className="field__hint">
            A pack adds completeness checks for that contract type. Without a pack, only
            generally applicable clause checks run — that is a valid, reduced review.
          </p>
        </div>

        {create.isPending ? (
          <LoadingState label="Starting review…" />
        ) : (
          <button type="submit" className="button button--primary">
            Start review
          </button>
        )}
      </form>

      {errorCopy ? (
        <div role="alert" className="state state--error">
          <p>{errorCopy.message}</p>
          {errorCopy.uncertain ? (
            <button
              type="button"
              className="button"
              onClick={() => {
                onCheckReviews()
                setChecked(true)
              }}
            >
              Re-check reviews
            </button>
          ) : isReviewConflictError(create.error) ? (
            <Link className="link-button" to={`/reviews/${contractId}?view=semantics`}>
              Open the contract's semantic analysis
            </Link>
          ) : null}
          {checked ? <p role="status">Review history refreshed below.</p> : null}
        </div>
      ) : null}
    </section>
  )
}