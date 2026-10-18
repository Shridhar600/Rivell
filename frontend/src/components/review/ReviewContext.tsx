import type { FindingSummary, ReviewSummary } from '../../api/reviews'
import { useReviewPacksQuery } from '../../api/reviews'
import { contractPackLabel } from '../../lib/evidence'
import { engineLabel, routeCountsLabel } from '../../lib/reviews'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from '../../lib/contractSemantics'

/** The frozen context this review was created with: what it was checked against. */
export function ReviewContext({
  summary,
  findings = [],
}: {
  summary: ReviewSummary
  /** The review's findings, to count what a second opinion settled and what needs a look. */
  findings?: readonly FindingSummary[]
}) {
  const packsQuery = useReviewPacksQuery()
  const snapshot = summary.snapshot
  const profile = snapshot?.profile ?? null
  const packKey = profile?.contractPackKey ?? null
  const packLabel = packKey
    ? (packsQuery.data?.find((pack) => pack.key === packKey)?.displayName ?? contractPackLabel(packKey))
    : null

  return (
      <dl className="rv-ws-props" aria-label="Frozen review context">
        <div>
          <dt>Review state</dt>
          <dd>Frozen when this review was created</dd>
        </div>
        {profile ? (
          <>
            <div>
              <dt>Contract type</dt>
              <dd>
                {profile.contractType
                  ? (CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType)
                  : 'Not specified'}
              </dd>
            </div>
            <div>
              <dt>Our side</dt>
              <dd>{profile.ourSideRole ? roleLabel(profile.ourSideRole) : 'Not specified'}</dd>
            </div>
            <div>
              <dt>Paper source</dt>
              <dd>
                {profile.paperSource
                  ? (PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource)
                  : 'Not specified'}
              </dd>
            </div>
            <div>
              <dt>Jurisdiction</dt>
              <dd>
                {profile.jurisdiction
                  ? (JURISDICTION_LABELS[profile.jurisdiction] ?? profile.jurisdiction)
                  : 'Not specified'}
              </dd>
            </div>
          </>
        ) : (
          <div>
            <dt>Review profile</dt>
            <dd>Frozen profile unavailable</dd>
          </div>
        )}
        <div>
          <dt>Knowledge version</dt>
          <dd>
            {snapshot?.knowledgePinNumber == null
              ? 'Knowledge version unavailable'
              : snapshot.knowledgePinNumber > 0
                ? `Knowledge version ${snapshot.knowledgePinNumber}`
                : 'No published knowledge at creation'}
          </dd>
        </div>
        <div>
          <dt>Contract pack</dt>
          <dd>
            {snapshot === null
              ? 'Pack context unavailable'
              : (packLabel ?? 'No pack (reduced checks)')}
          </dd>
        </div>
        <div>
          <dt>Review method</dt>
          <dd>
            {[engineLabel(snapshot?.engine ?? null), routeCountsLabel(findings)].filter(Boolean).join(' · ')}
          </dd>
        </div>
        {snapshot?.effectiveOn ? (
          <div>
            <dt>Effective on</dt>
            <dd>{snapshot.effectiveOn}</dd>
          </div>
        ) : null}
      </dl>
  )
}
