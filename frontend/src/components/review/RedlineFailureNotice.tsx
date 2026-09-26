import type { RedlineExecution } from '../../api/redline'
import { redlineExecutionTitle, redlineFailureTitle } from '../../lib/redline'

export function RedlineFailureNotice({ execution }: { execution: RedlineExecution }) {
  return (
    <div className="redline__failure">
      <h3 className="redline__failure-title" role="alert">
        {redlineExecutionTitle(execution)}
      </h3>
      <p>{redlineFailureTitle(execution.failureCode ?? 'UNKNOWN')}</p>
      {execution.failureDetail ? (
        <p className="panel__hint">{execution.failureDetail}</p>
      ) : null}
      <p className="panel__hint">
        The legal review is complete and the approved change set is unchanged. Document
        execution could not safely apply the approved changes.
      </p>
    </div>
  )
}
