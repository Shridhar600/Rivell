import { formatTimestamp } from '../../lib/knowledgeLabels'
import { changeLanguageProvenanceLabel, changeActionLabel, decisionStateLabel } from '../../lib/workflow'
import type { HumanDecision } from '../../api/workflow'

export function DecisionSummary({ decision }: { decision: HumanDecision | null }) {
  if (!decision) {
    return <p className="decision-summary decision-summary--empty">No human decision recorded yet.</p>
  }
  return (
    <div className="decision-summary">
      <p className="decision-summary__state">
        <strong>{decisionStateLabel(decision)}</strong>
        <span className="decision-summary__revision"> — Revision {decision.revision}</span>
      </p>
      {decision.action === 'EDIT' && decision.approvedAction ? (
        <p className="decision-summary__change">
          Change: {changeActionLabel(decision.approvedAction)}
        </p>
      ) : null}
      {decision.approvedLanguage ? (
        <blockquote className="decision-summary__language">
          {decision.approvedLanguage}
        </blockquote>
      ) : null}
      {decision.languageSource ? (
        <p className="panel__hint">{changeLanguageProvenanceLabel(decision.languageSource)}</p>
      ) : null}
      {decision.comment ? (
        <p className="decision-summary__comment">
          Comment: <span>{decision.comment}</span>
        </p>
      ) : null}
      <p className="panel__hint">
        <span>Signed-in reviewer</span> ·{' '}
        <time dateTime={decision.decidedAt}>{formatTimestamp(decision.decidedAt)}</time>
      </p>
    </div>
  )
}
