import { useDecisionHistoryQuery } from '../../api/workflow'
import { dateTime } from '../../lib/dates'
import { changeActionLabel, changeLanguageProvenanceLabel, decisionStateLabel } from '../../lib/workflow'
import { LoadingState } from '../ui/State'

/** Every recorded revision of one finding's decision, newest first. */
export function DecisionHistorySheet({ reviewId, findingId, title }: { reviewId: string; findingId: string; title: string }) {
  const query = useDecisionHistoryQuery(reviewId, findingId, true)
  return (
    <>
      <div className="rv-ws-authority">Decision history</div>
      <h2>{title}</h2>
      {query.isPending ? <LoadingState label="Loading decision history…" /> : null}
      {query.isError ? (
        <p className="rv-ws-warn" role="alert">
          Decision history could not be loaded.{' '}
          <button type="button" className="rv-ws-link" onClick={() => void query.refetch()}>
            Try again
          </button>
        </p>
      ) : null}
      {query.data ? (
        <div className="rv-ws-history">
          {query.data.items.length === 0 ? <div className="rv-ws-history-item">No earlier decision versions recorded.</div> : null}
          {query.data.items.map((revision) => (
            <div key={revision.id} className="rv-ws-history-item">
              <b>
                Decision {revision.revision} · {decisionStateLabel(revision)}
              </b>
              <small>{dateTime(revision.decidedAt) || 'Saved'}</small>
              {revision.approvedAction && revision.approvedAction !== 'UNKNOWN' ? (
                <p>{changeActionLabel(revision.approvedAction)}</p>
              ) : null}
              {revision.approvedLanguage ? <p>{revision.approvedLanguage}</p> : null}
              {revision.languageSource ? <p>{changeLanguageProvenanceLabel(revision.languageSource)}</p> : null}
              {revision.comment ? <p>{revision.comment}</p> : null}
            </div>
          ))}
        </div>
      ) : null}
      {query.data?.truncated ? <p className="rv-ws-caption">Showing the most recent versions.</p> : null}
    </>
  )
}
