import type { PolicyPositionView, PositionEntry } from '../../lib/positionComparison'

interface Props {
  view: PolicyPositionView
  /** Evidence ids the finding's source sheet can open. A slot whose item is not among them has no link. */
  openable: ReadonlySet<string>
  onOpenSource: (evidenceId: string) => void
  /** One line on how the position was found, when it was not by the clause's topic. */
  caption?: string | null
}

function SourceLink({ evidenceId, label, openable, onOpenSource }: { evidenceId: string; label: string } & Pick<Props, 'openable' | 'onOpenSource'>) {
  if (!openable.has(evidenceId)) return null
  return (
    <button type="button" className="rv-ws-link" aria-label={`Open source: ${label}`} onClick={() => onOpenSource(evidenceId)}>
      Open source
    </button>
  )
}

function entryLabel(entry: PositionEntry): string {
  return entry.publication === null ? entry.authority : `${entry.authority}, knowledge version ${entry.publication}`
}

/**
 * The canonical three-cell comparison, filled from the server's structured slots. A cell lists every
 * item the server cited for it. The contract's value is its typed facts; policy wording is shown as
 * authored. Each item keeps the link to the frozen source it came from.
 */
export function PolicyPosition({ view, openable, onOpenSource, caption = null }: Props) {
  const contract = view.columns.find((column) => column.key === 'contract')
  const showFactTitles = (contract?.entries.flatMap((entry) => entry.facts).length ?? 0) > 1
  return (
    <div className="rv-ws-sec" data-testid="policy-position">
      <div className="rv-ws-sec-h">Policy position</div>
      {caption ? <p className="rv-ws-caption">{caption}</p> : null}
      {view.columns.length > 0 ? (
        <div className="rv-ws-pos" style={{ gridTemplateColumns: `repeat(${view.columns.length}, minmax(0, 1fr))` }}>
          {view.columns.map((column) => (
            <div key={column.key} className={column.key === 'contract' ? 'rv-ws-pos-bad' : undefined} role="group" aria-label={column.label}>
              <small>{column.label}</small>
              {column.entries.map((entry, index) => (
                <div key={`${column.key}-${index}`} className="rv-ws-pos-item">
                  {entry.text !== null ? (
                    <b className="rv-ws-clamp">
                      {column.key === 'fallback' && column.entries.length > 1 ? (
                        <span className="rv-ws-pos-n" aria-hidden="true">
                          {index + 1}
                        </span>
                      ) : null}
                      {entry.text}
                    </b>
                  ) : null}
                  {entry.facts.map((fact, factIndex) => (
                    <div key={`${fact.title}-${factIndex}`} className="rv-ws-pos-facts">
                      {showFactTitles ? <span className="rv-ws-pos-ft">{fact.title}</span> : null}
                      {fact.rows.map((row) => (
                        <span key={row.field} className="rv-ws-pos-row">
                          <b>{row.value}</b>
                          <small>{row.label}</small>
                        </span>
                      ))}
                    </div>
                  ))}
                  <span className="rv-ws-pos-src">
                    {entry.publication !== null ? `Knowledge version ${entry.publication}` : null}
                    <SourceLink evidenceId={entry.evidenceId} label={entryLabel(entry)} openable={openable} onOpenSource={onOpenSource} />
                  </span>
                </div>
              ))}
            </div>
          ))}
        </div>
      ) : null}
      {view.escalations.map((escalation, index) => (
        <div key={`${escalation.evidenceId}-${escalation.trigger}-${index}`} className="rv-ws-src">
          Escalate: {escalation.trigger}
          <SourceLink
            evidenceId={escalation.evidenceId}
            label={escalation.publication === null ? 'escalation condition' : `escalation condition, knowledge version ${escalation.publication}`}
            openable={openable}
            onOpenSource={onOpenSource}
          />
        </div>
      ))}
    </div>
  )
}
