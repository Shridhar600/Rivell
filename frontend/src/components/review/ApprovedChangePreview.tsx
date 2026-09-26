import { Link } from 'react-router'
import type { FindingSummary } from '../../api/reviews'
import type { ApprovedChange, HumanDecision } from '../../api/workflow'
import { findingSubjectLabel } from '../../lib/reviews'
import {
  changeActionLabel,
  changeLanguageProvenanceLabel,
  changeTargetLabel,
  decisionStateLabel,
} from '../../lib/workflow'

type Props = {
  change: ApprovedChange
  finding: FindingSummary | undefined
  decision: HumanDecision | undefined
  regionLabels: Map<string, string>
  contractId: string
  canDeepLink: boolean
  onSelectFinding: (findingId: string) => void
}

export function ApprovedChangePreview({
  change,
  finding,
  decision,
  regionLabels,
  contractId,
  canDeepLink,
  onSelectFinding,
}: Props) {
  const targetLabel = changeTargetLabel(change, regionLabels)
  return (
    <article className="approved-change" aria-label={`${changeActionLabel(change.actionType)}: ${targetLabel}`}>
      <header className="approved-change__header">
        <h3>
          {changeActionLabel(change.actionType)} — {targetLabel}
        </h3>
        <button
          type="button"
          className="link-button"
          onClick={() => onSelectFinding(change.findingId)}
        >
          {finding
            ? `From finding: ${findingSubjectLabel(finding, regionLabels)}`
            : 'Open source finding'}
        </button>
        {decision ? (
          <p className="panel__hint">
            Decision: {decisionStateLabel(decision)} · Revision {decision.revision}
          </p>
        ) : null}
      </header>
      {change.approvedLanguage ? (
        <>
          <blockquote className="approved-change__language">
            {change.approvedLanguage}
          </blockquote>
          <p className="panel__hint">
            {changeLanguageProvenanceLabel(change.languageSource)}
          </p>
        </>
      ) : (
        <p className="panel__hint">
          {change.actionType === 'DELETE'
            ? changeLanguageProvenanceLabel('NONE')
            : 'No approved language is recorded for this change.'}
        </p>
      )}
      {change.actionType === 'INSERT' ? (
        <p className="panel__hint">
          The insertion location is not part of the approved change set.
        </p>
      ) : null}
      {(change.actionType === 'REPLACE' || change.actionType === 'DELETE') &&
      change.targetKind === 'CLAUSE' &&
      change.targetHeadingNodeId &&
      canDeepLink ? (
        <p>
          <Link
            className="link-button"
            to={`/reviews/${contractId}?view=document&node=${change.targetHeadingNodeId}`}
          >
            View clause in contract
          </Link>
        </p>
      ) : null}
    </article>
  )
}
