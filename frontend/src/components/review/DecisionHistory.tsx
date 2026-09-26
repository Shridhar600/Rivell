import { useState } from 'react'
import { formatTimestamp } from '../../lib/knowledgeLabels'
import { changeActionLabel, changeLanguageProvenanceLabel, decisionStateLabel } from '../../lib/workflow'
import { useDecisionHistoryQuery } from '../../api/workflow'

export function DecisionHistory({
  reviewId,
  findingId,
}: {
  reviewId: string
  findingId: string
}) {
  const [isEnabled, setEnabled] = useState(false)
  const query = useDecisionHistoryQuery(reviewId, findingId, isEnabled)

  return (
    <details className="decision-history">
      <summary onClick={() => setEnabled(true)}>Decision history</summary>
      {query.isPending ? (
        <p className="panel__hint">Loading decision history…</p>
      ) : query.isError ? (
        <p className="panel__hint" role="alert">
          Decision history could not be loaded.{' '}
          <button type="button" className="link-button" onClick={() => void query.refetch()}>
            Try again
          </button>
        </p>
      ) : query.data.items.length === 0 ? (
        <p className="panel__hint">No decision revisions recorded.</p>
      ) : (
        <ol className="decision-history__list" aria-label="Decision revisions">
          {query.data.items.map((revision) => (
            <li key={revision.id}>
              <p>
                Revision {revision.revision} — {decisionStateLabel(revision)}
              </p>
              {revision.action === 'EDIT' && revision.approvedAction ? (
                <p className="panel__hint">{changeActionLabel(revision.approvedAction)}</p>
              ) : null}
              {revision.approvedLanguage ? (
                <blockquote className="decision-summary__language">
                  {revision.approvedLanguage}
                </blockquote>
              ) : null}
              {revision.languageSource ? (
                <p className="panel__hint">
                  {changeLanguageProvenanceLabel(revision.languageSource)}
                </p>
              ) : null}
              {revision.comment ? <p>{revision.comment}</p> : null}
              <p className="panel__hint">
                <span>Signed-in reviewer</span> ·{' '}
                <time dateTime={revision.decidedAt}>
                  {formatTimestamp(revision.decidedAt)}
                </time>
              </p>
            </li>
          ))}
        </ol>
      )}
      {query.data?.truncated ? (
        <p className="panel__hint">Showing the most recent revisions.</p>
      ) : null}
    </details>
  )
}
