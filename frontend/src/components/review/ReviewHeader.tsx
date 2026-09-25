import { Link } from 'react-router'
import type { ReviewSummary } from '../../api/reviews'
import { useReviewPacksQuery } from '../../api/reviews'
import { contractPackLabel } from '../../lib/evidence'
import { formatTimestamp } from '../../lib/knowledgeLabels'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from '../../lib/contractSemantics'
import { reviewStatusLabel, reviewStatusModifier } from '../../lib/reviews'
import { useHeadingFocus } from '../../lib/useHeadingFocus'

function timestampOrDash(iso: string | null): string {
  return iso ? formatTimestamp(iso) : '—'
}

type Props = {
  contractId: string
  contractTitle: string
  summary: ReviewSummary
}

export function ReviewHeader({ contractId, contractTitle, summary }: Props) {
  const headingRef = useHeadingFocus<HTMLHeadingElement>(summary.id)
  const packsQuery = useReviewPacksQuery()
  const snapshot = summary.snapshot
  const profile = snapshot?.profile ?? null
  const packKey = profile?.contractPackKey ?? null
  const packLabel = packKey
    ? (packsQuery.data?.find((pack) => pack.key === packKey)?.displayName ??
      contractPackLabel(packKey))
    : null

  return (
    <header className="review-header">
      <div className="review-header__title">
        <p className="review-header__eyebrow">
          <Link className="link-button" to={`/reviews/${contractId}/analysis`}>
            Automated reviews
          </Link>
        </p>
        <h1 id="review-heading" tabIndex={-1} ref={headingRef}>
          {contractTitle}
        </h1>
        <p className="review-header__meta">
          <span className={`badge ${reviewStatusModifier(summary.status)}`}>
            {reviewStatusLabel(summary.status)}
          </span>
          <span>
            {summary.contractVersionNumber !== null
              ? `v${summary.contractVersionNumber}`
              : 'Version unknown'}
          </span>
          <span>
            created <time dateTime={summary.createdAt}>{timestampOrDash(summary.createdAt)}</time>
          </span>
          {summary.startedAt ? (
            <span>
              started{' '}
              <time dateTime={summary.startedAt}>{timestampOrDash(summary.startedAt)}</time>
            </span>
          ) : null}
          {summary.completedAt ? (
            <span>
              completed{' '}
              <time dateTime={summary.completedAt}>
                {timestampOrDash(summary.completedAt)}
              </time>
            </span>
          ) : null}
        </p>
      </div>

      <dl className="data-list profile-summary" aria-label="Frozen review context">
        <div className="data-list__row">
          <dt>Review state</dt>
          <dd>Frozen when this review was created</dd>
        </div>
        {profile ? (
          <>
            <div className="data-list__row">
              <dt>Contract type</dt>
              <dd>
                {profile.contractType
                  ? (CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType)
                  : 'Not specified'}
              </dd>
            </div>
            <div className="data-list__row">
              <dt>Our side</dt>
              <dd>{profile.ourSideRole ? roleLabel(profile.ourSideRole) : 'Not specified'}</dd>
            </div>
            <div className="data-list__row">
              <dt>Paper source</dt>
              <dd>
                {profile.paperSource
                  ? (PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource)
                  : 'Not specified'}
              </dd>
            </div>
            <div className="data-list__row">
              <dt>Jurisdiction</dt>
              <dd>
                {profile.jurisdiction
                  ? (JURISDICTION_LABELS[profile.jurisdiction] ?? profile.jurisdiction)
                  : 'Not specified'}
              </dd>
            </div>
          </>
        ) : (
          <div className="data-list__row">
            <dt>Review profile</dt>
            <dd>Frozen profile unavailable</dd>
          </div>
        )}
        <div className="data-list__row">
          <dt>Knowledge publication</dt>
          <dd>
            {snapshot?.knowledgePinNumber == null
              ? 'Knowledge pin unavailable'
              : snapshot.knowledgePinNumber > 0
                ? `Publication ${snapshot.knowledgePinNumber}`
                : 'No published knowledge at creation'}
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Contract pack</dt>
          <dd>
            {snapshot === null
              ? 'Pack context unavailable'
              : (packLabel ?? 'No pack (reduced checks)')}
          </dd>
        </div>
        {snapshot?.effectiveOn ? (
          <div className="data-list__row">
            <dt>Effective on</dt>
            <dd>{snapshot.effectiveOn}</dd>
          </div>
        ) : null}
      </dl>
    </header>
  )
}