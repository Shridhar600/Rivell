import { useState, type KeyboardEvent } from 'react'
import type { FindingSummary } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import type { HumanDecision } from '../../api/workflow'
import { comparisonCategoryLabel, findingIssueLabel, findingSubjectLabel } from '../../lib/reviews'
import { findingState, isNeedsYourLook, orderNeedsYourLook, type FindingTone } from '../../lib/workspace'
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
  /** The empty frozen playbook set, stated at the top of the list; null when a playbook applied. */
  noPlaybookNotice: string | null
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
  noPlaybookNotice,
  onRetry,
  onSelect,
}: Props) {
  const { labelFor } = useTaxonomy()
  const [lookOpen, setLookOpen] = useState(false)
  const look = orderNeedsYourLook(findings.filter(isNeedsYourLook))
  // the group opens by itself when one of its items is selected (keyboard or URL)
  const showLook = lookOpen || look.some((finding) => finding.id === selectedId)
  function onKeyDown(event: KeyboardEvent<HTMLDivElement>, id: string) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onSelect(id)
    }
  }

  /** What a row says the item is. A look item says why Rivell left it to the user. */
  function issueLine(finding: FindingSummary): string {
    if (finding.relatedPosition) return 'Related position'
    if (isNeedsYourLook(finding) && finding.firstReviewCategory) {
      return `First review: ${comparisonCategoryLabel(finding.firstReviewCategory)}`
    }
    return findingIssueLabel(finding)
  }

  function row(finding: FindingSummary) {
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
            {issueLine(finding)}
          </span>
          {finding.subjectKind === 'ABSENCE' ? <div className="rv-ws-miss">Not in contract</div> : null}
        </div>
      </div>
    )
  }

  return (
    <aside className="rv-ws-pane rv-ws-fl" aria-label="Findings">
      <div className="rv-ws-fl-h">
        Findings
        <small>{remaining !== null ? `${remaining} open` : enabled && !isPending ? findings.length : ''}</small>
      </div>
      {noPlaybookNotice !== null && analysisComplete ? (
        <p className="rv-ws-fl-note">{noPlaybookNotice}</p>
      ) : null}
      {!enabled ? <p className="rv-ws-fl-note">Findings appear when analysis finishes.</p> : null}
      {enabled && isPending ? <LoadingState label="Loading findings…" /> : null}
      {enabled && isError ? <ErrorState message="Findings could not be loaded." onRetry={onRetry} /> : null}
      {enabled && !isPending && !isError && findings.length === 0 ? (
        <p className="rv-ws-fl-note">
          {analysisComplete
            ? 'No findings for this contract version.'
            : 'This review did not complete an analysis, so there are no findings to show.'}
        </p>
      ) : null}
      <div role="listbox" aria-label="Findings list">
        {findings.filter((finding) => !isNeedsYourLook(finding)).map(row)}
      </div>
      {look.length > 0 ? (
        <div className="rv-ws-look">
          <button
            type="button"
            className="rv-ws-look-h"
            aria-expanded={showLook}
            onClick={() => setLookOpen(!showLook)}
          >
            Needs your look ({look.length})
          </button>
          <p
            className="rv-ws-fl-note"
            title="Rivell did not decide these. The first check and a second opinion disagreed, a second opinion was not available, your playbook is silent on the clause, or Rivell is not sure that a missing provision is required."
          >
            Rivell left these to you.
          </p>
          {showLook ? (
            <div role="listbox" aria-label="Needs your look list">
              {look.map(row)}
            </div>
          ) : null}
        </div>
      ) : null}
      {truncated ? <p className="rv-ws-fl-note">Showing the first {findings.length} findings only.</p> : null}
    </aside>
  )
}
