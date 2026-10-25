import type { FindingSummary, ReviewSummary } from '../../api/reviews'
import { useReviewPacksQuery } from '../../api/reviews'
import { contractPackLabel } from '../../lib/evidence'
import { engineLabel, knowledgeUsedText, routeCountsLabel } from '../../lib/reviews'
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
  const playbooksUsed = knowledgeUsedText(snapshot?.playbooksUsed ?? null)

  return (
      <dl className="rv-ws-props" aria-label="Review details">
        <div>
          <dt>Review state</dt>
          <dd>Fixed when this review was created</dd>
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
              <dt>Party you represent</dt>
              <dd>{profile.ourSideRole ? roleLabel(profile.ourSideRole) : 'Not specified'}</dd>
            </div>
            <div>
              <dt>Whose template</dt>
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
            <dd>Review details unavailable</dd>
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
        {playbooksUsed !== null && playbooksUsed !== '' && snapshot?.playbooksUsed?.length ? (
          <div>
            <dt>Knowledge used</dt>
            <dd>{playbooksUsed}</dd>
          </div>
        ) : null}
        <div>
          <dt>Review pack</dt>
          <dd>
            {snapshot === null
              ? 'Review pack unavailable'
              : (packLabel ?? 'No review pack (reduced checks)')}
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
