import type { FindingSummary } from '../../api/reviews'
import type { RedlineExecution } from '../../api/redline'
import { useTaxonomy } from '../../api/taxonomy'
import type { ApprovedChange } from '../../api/workflow'
import { failureNote, overallResult, reportLocation, reportResult } from '../../lib/lifecycleView'
import { changeTitle } from '../../lib/lifecycleView'
import { CrossIcon, TickIcon } from './icons'
import { TypeChip } from './ChangesPanel'

interface Props {
  execution: RedlineExecution
  approvedChanges: ApprovedChange[]
  findingsById: Map<string, FindingSummary>
  regionLabels: Map<string, string>
}

/**
 * The per-change report, ported from the canonical `vReport()`. Every cell is the server's own
 * execution record: nothing is re-verified in the browser. When the execution failed, the same
 * table is a diagnostic only, and the closing banner says no artifact is offered.
 */
export function ReportPanel({ execution, approvedChanges, findingsById, regionLabels }: Props) {
  const { labelFor } = useTaxonomy()
  const approvedById = new Map(approvedChanges.map((change) => [change.id, change]))
  const failed = execution.status === 'FAILED'
  const result = overallResult(execution)
  const heading = result.tone === 'ok' ? 'Change report' : 'Diagnostic report'
  return (
    <>
      <div className="rv-lc-sech">
        <h2>{heading}</h2>
        <span>
          {result.tone === 'ok'
            ? 'Applied and checked against the approved changes'
            : 'Why no verified file was produced'}
        </span>
      </div>
      <div className="rv-lc-klist">
        <table className="rv-lc-rep">
          <caption className="rv-visually-hidden">{heading}</caption>
          <thead>
            <tr>
              <th scope="col" style={{ width: 36 }}>
                #
              </th>
              <th scope="col">Change</th>
              <th scope="col">Resolved location</th>
              <th scope="col" style={{ textAlign: 'right' }}>
                Result
              </th>
            </tr>
          </thead>
          <tbody>
            {execution.changes.map((change, position) => {
              const approved = approvedById.get(change.approvedChangeId)
              const pill = reportResult(execution, change)
              const note = failureNote(change)
              return (
                <tr key={change.approvedChangeId}>
                  <td>{position + 1}</td>
                  <td>
                    <TypeChip type={change.actionType} />{' '}
                    <b>{approved ? changeTitle(approved, findingsById.get(approved.findingId), regionLabels, labelFor) : 'Contract change'}</b>
                    {note ? <div className="rv-lc-rep__note">{note}</div> : null}
                  </td>
                  <td>{reportLocation(change, approved, regionLabels, failed, labelFor)}</td>
                  <td style={{ textAlign: 'right' }}>
                    <span className={`rv-stb${pill.variant === 'neutral' ? '' : ` rv-stb--${pill.variant}`}`}>{pill.label}</span>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <div className={`rv-lc-frozen${result.tone === 'red' ? ' rv-lc-frozen--red' : ''}`}>
        {result.tone === 'red' ? <CrossIcon className="rv-icon" /> : <TickIcon className="rv-icon" />}
        <span>
          <b>Overall result: {result.word}.</b> {result.sentence}
        </span>
      </div>
    </>
  )
}
