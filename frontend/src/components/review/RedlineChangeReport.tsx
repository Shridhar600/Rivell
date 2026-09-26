import type { RedlineChangeRow } from '../../lib/redline'

type Props = {
  rows: RedlineChangeRow[]
  onSelectFinding: (findingId: string) => void
}

export function RedlineChangeReport({ rows, onSelectFinding }: Props) {
  if (rows.length === 0) return null
  return (
    <div className="redline__report-block">
      <h3>Change execution report</h3>
      <ol className="redline__report" aria-label="Redline change report">
        {rows.map((row) => {
          const findingId = row.findingId
          return (
          <li key={row.id} className="redline__report-row">
            <article aria-label={`${row.actionLabel}: ${row.targetLabel}`}>
              <h4>
                {row.ordinal}. {row.actionLabel} — {row.targetLabel}
              </h4>
              <ul className="redline__report-facts">
                <li>Execution: {row.statusLabel}</li>
                {row.verificationLabel ? <li>Verification: {row.verificationLabel}</li> : null}
                {row.placementLabel ? <li>Placement: {row.placementLabel}</li> : null}
                {row.paragraphSummary ? <li>{row.paragraphSummary}</li> : null}
              </ul>
              {row.failureTitle ? (
                <p className="state state--warning">{row.failureTitle}</p>
              ) : null}
              {findingId ? (
                <button
                  type="button"
                  className="link-button"
                  onClick={() => onSelectFinding(findingId)}
                >
                  Open source finding
                </button>
              ) : null}
            </article>
          </li>
          )
        })}
      </ol>
    </div>
  )
}
