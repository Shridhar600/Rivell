import type { KeyboardEvent } from 'react'
import type { FindingSummary } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import type { HumanDecision } from '../../api/workflow'
import { findingIssueLabel, findingSubjectLabel, isRelatedPositionSummary } from '../../lib/reviews'
import { findingState, type FindingTone } from '../../lib/workspace'
import { ErrorState, LoadingState } from '../ui/State'
import { GlyphEscalated, GlyphOpen, GlyphRejected, GlyphResolved } from './WorkspaceIcons'

const GLYPHS: Record<FindingTone, typeof GlyphOpen> = {
  open: GlyphOpen,
  resolved: GlyphResolved,
  escalated: GlyphEscalated,
  rejected: GlyphRejected,
}

interface Props {
  findings: FindingSummary[]
  truncated: boolean
  selectedId: string | null
  decisions: ReadonlyMap<string, HumanDecision>
  /** Decision state is shown only once the server's decisions have loaded. */
  showState: boolean
  /** The server's own count of findings still needing a decision. */
  remaining: number | null
  regionLabels: Map<string, string>
  enabled: boolean
  /** Whether the automated analysis finished. A failed review has no findings because none ran. */
  analysisComplete: boolean
  isPending: boolean
  isError: boolean
  onRetry: () => void
  onSelect: (findingId: string) => void
}

export function FindingNavigator({
  findings,
  truncated,
  selectedId,
  decisions,
  showState,
  remaining,
  regionLabels,
  enabled,
  analysisComplete,
  isPending,
  isError,
  onRetry,
  onSelect,
}: Props) {
  const { labelFor } = useTaxonomy()
  function onKeyDown(event: KeyboardEvent<HTMLDivElement>, id: string) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onSelect(id)
    }
  }

  return (
    <aside className="rv-ws-pane rv-ws-fl" aria-label="Findings">
      <div className="rv-ws-fl-h">
        Findings
        <small>{remaining !== null ? `${remaining} unresolved` : enabled && !isPending ? findings.length : ''}</small>
      </div>
      {!enabled ? <p className="rv-ws-fl-note">Findings appear here when the automated analysis completes.</p> : null}
      {enabled && isPending ? <LoadingState label="Loading findings…" /> : null}
      {enabled && isError ? <ErrorState message="Findings could not be loaded." onRetry={onRetry} /> : null}
      {enabled && !isPending && !isError && findings.length === 0 ? (
        <p className="rv-ws-fl-note">
          {analysisComplete
            ? 'No findings. The automated review recorded none for this contract version.'
            : 'This review did not complete an analysis, so there are no findings to show.'}
        </p>
      ) : null}
      <div role="listbox" aria-label="Findings list">
        {findings.map((finding) => {
          const state = findingState(finding, decisions.get(finding.id))
          const Glyph = GLYPHS[state.tone]
          const selected = finding.id === selectedId
          return (
            <div
              key={finding.id}
              className="rv-ws-f"
              role="option"
              aria-selected={selected}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(finding.id)}
              onKeyDown={(event) => onKeyDown(event, finding.id)}
            >
              <Glyph className={`rv-icon rv-ws-g--${state.tone}`} />
              <div>
                <b>{findingSubjectLabel(finding, regionLabels, labelFor)}</b>
                <span>
                  {showState ? `${state.label} · ` : ''}
                  {isRelatedPositionSummary(finding) ? 'Related position' : findingIssueLabel(finding)}
                </span>
                {finding.subjectKind === 'ABSENCE' ? <div className="rv-ws-miss">Not in contract</div> : null}
              </div>
            </div>
          )
        })}
      </div>
      {truncated ? <p className="rv-ws-fl-note">Showing the first {findings.length} findings only.</p> : null}
    </aside>
  )
}
