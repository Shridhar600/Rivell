import type { ReviewSummary } from '../../api/reviews'
import { useReviewPacksQuery } from '../../api/reviews'
import { contractPackLabel } from '../../lib/evidence'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from '../../lib/contractSemantics'

/** The frozen context this review was created with: what it was checked against. */
export function ReviewContext({ summary }: { summary: ReviewSummary }) {
  const packsQuery = useReviewPacksQuery()
  const snapshot = summary.snapshot
  const profile = snapshot?.profile ?? null
  const packKey = profile?.contractPackKey ?? null
  const packLabel = packKey
    ? (packsQuery.data?.find((pack) => pack.key === packKey)?.displayName ?? contractPackLabel(packKey))
    : null

  return (
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
  )
}
