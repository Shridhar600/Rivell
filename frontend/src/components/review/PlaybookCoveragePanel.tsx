import { useId, useState } from 'react'
import type { PlaybookCoverageEntry } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import {
  playbookCoverageReasonLabel,
  playbookCoverageStateLabel,
  playbookCoverageSummary,
  playbookCoverageVerdictLabel,
} from '../../lib/reviews'

type Props = {
  /** Null when the run predates the coverage list; an empty list means none applied. */
  coverage: PlaybookCoverageEntry[] | null
  onOpenFinding?: (findingId: string) => void
  onNavigateToNode?: (nodeId: string) => void
}

/**
 * What this review did with every published playbook position that applies to its contract type.
 * The four states are deliberately not a score: "not compared" and "not found" are both gaps, and
 * only a clean search may say "not found".
 */
export function PlaybookCoveragePanel({ coverage, onOpenFinding, onNavigateToNode }: Props) {
  const { labelFor } = useTaxonomy()
  const [open, setOpen] = useState((coverage?.length ?? 0) <= 6)
  const bodyId = useId()
  if (coverage === null) return null
  if (coverage.length === 0) {
    return (
      <section className="rv-ws-sec" aria-label="Playbook coverage">
        <h2 className="rv-ws-sec-h">Playbook coverage</h2>
        <p className="rv-ws-caption">
          No published playbook positions applied to this contract type.
        </p>
      </section>
    )
  }
  return (
    <section className="rv-ws-ca-group" aria-label="Playbook coverage">
      <button
        type="button"
        className="rv-ws-ca-head"
        aria-expanded={open}
        aria-controls={bodyId}
        onClick={() => setOpen((value) => !value)}
      >
        <span>Playbook coverage</span>
        <span className="rv-ws-chip">{coverage.length}</span>
      </button>
      <div id={bodyId} hidden={!open} className="rv-ws-ca-body">
        <p className="rv-ws-caption">
          Each published position in the playbooks this review froze, and what the review did with
          it.
        </p>
        <p className="rv-ws-caption">{playbookCoverageSummary(coverage)}</p>
        <ul className="rv-ws-cov">
          {coverage.map((entry) => {
            const clauseId = entry.clauseHeadingNodeId
            const findingId = entry.findingId
            return (
              <li key={entry.positionCandidateId}>
                <span className="rv-ws-cov-c">
                  {entry.label}
                  {entry.code && entry.code !== entry.label ? ` (${entry.code})` : ''}
                </span>
                <span className="rv-ws-cov-e">
                  {entry.topic ? labelFor(entry.topic) : 'No topic'}
                </span>
                <span
                  className="rv-ws-chip"
                  data-tone={
                    entry.state === 'COMPARED' || entry.state === 'OUT_OF_SCOPE'
                      ? undefined
                      : 'amber'
                  }
                >
                  {playbookCoverageStateLabel(entry.state)}
                </span>
                {entry.verdict ? (
                  <span className="rv-ws-cov-e">
                    {playbookCoverageVerdictLabel(entry.verdict)}
                  </span>
                ) : null}
                {entry.state === 'NOT_COMPARED' ? (
                  <span className="rv-ws-cov-e">
                    {playbookCoverageReasonLabel(entry.reason)}
                  </span>
                ) : null}
                {clauseId && onNavigateToNode ? (
                  <button
                    type="button"
                    className="rv-ws-link"
                    aria-label={`Open clause for ${entry.label}`}
                    onClick={() => onNavigateToNode(clauseId)}
                  >
                    Clause
                  </button>
                ) : null}
                {findingId && onOpenFinding ? (
                  <button
                    type="button"
                    className="rv-ws-link"
                    aria-label={`Open finding for ${entry.label}`}
                    onClick={() => onOpenFinding(findingId)}
                  >
                    Finding
                  </button>
                ) : null}
              </li>
            )
          })}
        </ul>
      </div>
    </section>
  )
}
