import { useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { EvidenceItem } from '../../api/retrieval'
import type { FindingClaim, FindingDetail, FindingQuality } from '../../api/reviews'
import type { ConceptLabel } from '../../api/taxonomy'
import { useTaxonomy } from '../../api/taxonomy'
import type { HumanDecision } from '../../api/workflow'
import { authorityLabel } from '../../lib/evidence'
import {
  absenceClaims,
  assessmentSummary,
  claimKindLabel,
  classificationLabel,
  comparisonCategoryLabel,
  decidedByLabel,
  findingIssueLabel,
  findingSubjectLabel,
  isRelatedPosition,
  observationKindLabel,
  policyNoteText,
  proposedActionLabel,
  qualityFactorRows,
  recommendationClaims,
  whyClaims,
} from '../../lib/reviews'
import { dateTime } from '../../lib/dates'
import { policyPositionView } from '../../lib/positionComparison'
import {
  clauseExcerpt,
  findingState,
  isWordingGrounded,
  languageAuthority,
  proposedChangeType,
} from '../../lib/workspace'
import { Button } from '../ui/Button'
import { BookIcon, ChevronIcon } from '../ui/Icon'
import { ErrorState, LoadingState } from '../ui/State'
import { DecisionComposer } from './DecisionComposer'
import { PolicyPosition } from './PolicyPosition'
import type { DecisionFlow } from './useDecisionFlow'
import { CheckIcon, EditIcon, EscalateIcon, CloseIcon, LockIcon } from './WorkspaceIcons'

const SOURCES_SHOWN = 3

/** How a position the clause's topics did not bring was found (engine v2 SEARCH route). */
const SEARCH_CAPTION = 'Found by searching your playbook. No position on this topic matched.'

/** Where focus lands after Finalize, so it is not lost when the Finalize button becomes disabled. */
export const SUMMARY_LINK_ID = 'rv-ws-summary-link'

interface Props {
  finding: FindingDetail | undefined
  hasSelection: boolean
  isPending: boolean
  isError: boolean
  onRetry: () => void
  position: { index: number; total: number } | null
  context: string | null
  regionLabels: Map<string, string>
  /** The clause as received, from the pinned contract structure. */
  currentText: string | null
  decision: HumanDecision | null
  decisionsLoaded: boolean
  decisionsTruncated: boolean
  /** Whether the server's workflow state is available, so a decision can be recorded at all. */
  canDecide: boolean
  analysisComplete: boolean
  isFinalized: boolean
  /** Size of the frozen approved change set, once it has loaded. */
  frozenChanges: number | null
  flow: DecisionFlow
  onShowInDocument: () => void
  onOpenSource: (evidenceId: string) => void
  onOpenHistory: () => void
  /** The review summary, where the frozen change set and its redline are handled. */
  summaryPath: string
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/** Each statement lists the evidence it rests on. A reference the response lacks is said so. */
function ClaimEvidence({ claim, items, onOpen }: { claim: FindingClaim; items: Map<string, EvidenceItem>; onOpen: (id: string) => void }) {
  if (claim.evidenceItemIds.length === 0) return null
  return (
    <>
      {' '}
      <span className="rv-ws-refs">
        Evidence:{' '}
        {claim.evidenceItemIds.map((id, index) => {
          const item = items.get(id)
          // Several items of one authority are told apart by their place in the finding's evidence.
          const same = item ? [...items.values()].filter((other) => other.authorityClass === item.authorityClass) : []
          const label = item
            ? `${authorityLabel(item.authorityClass)}${same.length > 1 ? ` ${same.indexOf(item) + 1}` : ''}`
            : null
          return (
            <span key={id}>
              {index > 0 ? ', ' : ''}
              {label ? (
                <button type="button" className="rv-ws-link" onClick={() => onOpen(id)}>
                  {label}
                </button>
              ) : (
                'unavailable in this response'
              )}
            </span>
          )
        })}
      </span>
    </>
  )
}

function sourceRow(item: EvidenceItem, regionLabels: Map<string, string>, label: ConceptLabel): { title: string; detail: string } {
  const region = item.provenanceNodeIds.map((id) => regionLabels.get(id)).find(Boolean)
  const subject = region ?? (item.legalConcept ? label(item.legalConcept) : '')
  const snippet = item.text.replace(/\s+/g, ' ').trim()
  return { title: authorityLabel(item.authorityClass), detail: [subject, snippet].filter(Boolean).join(' · ') }
}

/**
 * Once finalized, the frozen state stays reachable whatever the selected finding is doing: a
 * finding that fails to load, or a review with no findings, must not hide it. The Workspace owns
 * decisions and Finalize. The frozen change set and its redline belong to the review summary.
 */
function FrozenCard(props: Pick<Props, 'isFinalized' | 'frozenChanges' | 'summaryPath'>) {
  if (!props.isFinalized) return null
  return (
    <div className="rv-ws-phase">
      <div className="rv-ws-phase-head">
        <LockIcon />
        <div>
          <b>Approved changes frozen</b>
          <p>
            {props.frozenChanges === null
              ? 'The approved change set is immutable.'
              : `${plural(props.frozenChanges, 'approved change', 'approved changes')} in the immutable approved change set.`}
          </p>
        </div>
      </div>
      <div className="rv-ws-phase-actions">
        <Link id={SUMMARY_LINK_ID} className="rv-btn rv-btn--primary" to={props.summaryPath}>
          View review summary
        </Link>
      </div>
    </div>
  )
}

export function FindingPanel(props: Props) {
  const { finding, flow, decision } = props
  const { labelFor } = useTaxonomy()
  const [showAll, setShowAll] = useState(false)

  // The loading, empty and error states share the host elements of the full panel, and the frozen
  // card is keyed, so the card is never remounted as the finding arrives.
  const shell = (content: ReactNode) => (
    <aside className="rv-ws-pane rv-ws-dt" aria-label="Finding detail">
      <div key="in" className="rv-ws-dt-in">
        {content}
        <FrozenCard key="frozen" {...props} />
      </div>
    </aside>
  )
  if (!props.hasSelection || (!finding && !props.isPending && !props.isError)) {
    return shell(<p className="rv-ws-state">Select a finding to see Rivell’s analysis and record your decision.</p>)
  }
  if (props.isPending) {
    return shell(<LoadingState label="Loading finding…" />)
  }
  if (props.isError || !finding) {
    return shell(<ErrorState message="This finding could not be loaded." onRetry={props.onRetry} />)
  }

  const items = new Map(finding.evidenceItems.map((item) => [item.evidenceId, item]))
  const why = whyClaims(finding.claims)
  const recommendations = recommendationClaims(finding.claims)
  const absences = absenceClaims(finding.claims)
  const quality = qualityFactorRows(finding.quality)
  // a playbook-silent item shows the triage estimate in its two views, not as a confidence in an issue
  const silent = finding.quality?.route === 'NEEDS_YOUR_LOOK' && finding.quality.uncertaintyReason === 'playbook_silent'
  const assessment = silent ? null : assessmentSummary(finding.quality)
  // a finding a reviewer added has no machine decider; its kicker and note say who raised it
  const added = Boolean(finding.addedBy)
  const decidedBy = added ? null : decidedByLabel(finding.quality)
  const note = policyNoteText(finding)
  const position = policyPositionView(finding.positionComparison)
  // A within-fallback finding is a note, not a defect; a position that names an approver makes it
  // an approval question instead.
  const withinFallback = finding.issueFamily === 'POSITION_ACCEPTABLE_FALLBACK'
  const approvalNeeded = withinFallback && finding.proposedAction === 'ESCALATE_TO_HUMAN'
  const escalationTriggers = (finding.positionComparison?.escalation ?? [])
    .map((slot) => slot.trigger.trim())
    .filter((trigger) => trigger !== '')
  const change = proposedChangeType(finding)
  const grounded = isWordingGrounded(finding)
  const authority = !grounded ? null : languageAuthority(finding, items.get(finding.approvedDraftingEvidenceId ?? '')?.publicationNumber ?? null)
  const state = findingState(finding, decision)
  const sources = showAll ? finding.evidenceItems : finding.evidenceItems.slice(0, SOURCES_SHOWN)
  const edited = decision?.action === 'EDIT' && decision.approvedLanguage !== finding.proposedLanguage
  const eligible = flow.acceptEligibility.eligible
  const reason = flow.acceptEligibility.eligible ? '' : flow.acceptEligibility.reason
  const missingEvidence = finding.claims.some((claim) => claim.evidenceItemIds.some((id) => !items.has(id)))
  const title = findingSubjectLabel(finding, props.regionLabels, labelFor)
  // A position on a topic the clause only touches is a related note, never a defect in the clause.
  const related = isRelatedPosition(finding)
  const excerpt = props.currentText ? clauseExcerpt(props.currentText) : null

  return (
    <aside className="rv-ws-pane rv-ws-dt" aria-label="Finding detail">
      <p className="rv-visually-hidden" role="status">
        {props.position ? `Finding ${props.position.index} of ${props.position.total}: ${title}` : title}
      </p>
      <div key="in" className="rv-ws-dt-in">
        <div className="rv-ws-kicker">
          {related ? (
            <span className="rv-ws-typ rv-ws-typ--related">Related position</span>
          ) : (
            <span className={`rv-ws-typ${change ? ` rv-ws-typ--${change}` : ''}`}>
              {change ?? classificationLabel(finding.reviewClassification)}
            </span>
          )}
          {finding.subjectKind === 'ABSENCE'
            ? 'No location in contract'
            : related
              ? 'On a topic the clause only touches'
              : findingIssueLabel(finding)}
          <span className="rv-ws-sp" />
          {props.position ? `${props.position.index} of ${props.position.total}` : null}
        </div>
        <h2>{title}</h2>
        {props.context ? <div className="rv-ws-ctx">{props.context}</div> : null}
        {decidedBy ? (
          <p className="rv-ws-badge" data-testid="finding-decided-by">
            {decidedBy}
          </p>
        ) : null}
        {related ? (
          <p className="rv-ws-note rv-ws-note--related">
            <b>Related position, not a breach.</b> It covers a topic this clause touches but does not
            govern.
          </p>
        ) : null}

        <div className="rv-ws-sec">
          <div className="rv-ws-sec-h">{added ? 'Reviewer’s note' : 'Rivell found'}</div>
          <div className="rv-ws-machine">
            <p>
              {finding.observationText}
              {added ? null : (
                <>
                  <br />
                  <span className="rv-ws-authority">
                    {related ? 'Related position on a secondary topic' : observationKindLabel(finding.observationKind)}
                  </span>
                </>
              )}
            </p>
            {assessment ? (
              <p className="rv-ws-caption" data-testid="finding-assessment">
                Assessment{related ? ' (secondary topic)' : ''}: {assessment}
              </p>
            ) : null}
            {absences.map((claim) => (
              <p key={claim.ordinal}>
                {claim.text}
                <ClaimEvidence claim={claim} items={items} onOpen={props.onOpenSource} />
              </p>
            ))}
            {why.map((claim) => (
              <p key={claim.ordinal}>
                <span className="rv-ws-authority">{claimKindLabel(claim.claimKind)}: </span>
                {claim.text}
                <ClaimEvidence claim={claim} items={items} onOpen={props.onOpenSource} />
              </p>
            ))}
            {added ? null : (
              <p>
                <b>Recommendation:</b> {proposedActionLabel(finding.proposedAction)}.
                {recommendations.map((claim) => (
                  <span key={claim.ordinal}>
                    {' '}
                    {claim.text}
                    <ClaimEvidence claim={claim} items={items} onOpen={props.onOpenSource} />
                  </span>
                ))}
              </p>
            )}
            {note ? <p>{note}</p> : null}
            {missingEvidence ? (
              <p className="rv-ws-warn">Some supporting sources are missing from this view.</p>
            ) : null}
          </div>
        </div>

        {finding.quality?.route === 'NEEDS_YOUR_LOOK' && finding.quality.secondOpinionOutcome ? (
          <SecondOpinionViews quality={finding.quality} />
        ) : null}
        {silent && finding.quality ? <PlaybookSilentViews quality={finding.quality} /> : null}

        {withinFallback ? (
          <p className={approvalNeeded ? 'rv-ws-note rv-ws-note--approval' : 'rv-ws-note'}>
            {approvalNeeded ? (
              <>
                <b>Approval is needed.</b> This position names an approver, so approve it before
                accepting.
                {escalationTriggers.length > 0 ? ` Escalate when: ${escalationTriggers.join('; ')}.` : ''}
              </>
            ) : (
              <>
                <b>This is a note, not a problem.</b> The clause departs from the preferred position
                but stays within an acceptable fallback; accept and record it.
              </>
            )}
          </p>
        ) : null}

        {position ? (
          <PolicyPosition
            view={position}
            openable={new Set(items.keys())}
            onOpenSource={props.onOpenSource}
            caption={finding.quality?.foundBy === 'SEARCH' ? SEARCH_CAPTION : null}
          />
        ) : null}

        <div className="rv-ws-sec">
          <div className="rv-ws-sec-h">
            In the contract
            {finding.subjectKind === 'CLAUSE' && props.currentText ? (
              <button type="button" className="rv-ws-link" onClick={props.onShowInDocument}>
                Show in document
              </button>
            ) : null}
          </div>
          <div className="rv-ws-incl">
            {finding.subjectKind === 'ABSENCE'
              ? 'This provision is not in this contract version.'
              : (excerpt ?? 'The clause text for this version is not available.')}
          </div>
        </div>

        {finding.proposedLanguage && grounded ? (
          <div className="rv-ws-sec">
            <div className="rv-ws-sec-h">Proposed language</div>
            <div className="rv-ws-prop">
              <ins>{finding.proposedLanguage}</ins>
            </div>
            {authority ? (
              <div className="rv-ws-authority">
                <b>{authority.strong}</b>
                <span>{authority.rest}</span>
              </div>
            ) : null}
            {edited ? <div className="rv-ws-src">Your edit differs from the proposed wording.</div> : null}
          </div>
        ) : null}

        {finding.evidenceItems.length > 0 ? (
          // Condensed: the policy position and the proposed wording are above already, so the full
          // source list is one disclosure away, each item still labelled with its own authority.
          <details className="rv-ws-sec rv-ws-srcs">
            <summary className="rv-ws-sec-h">
              Sources
              <small>{finding.evidenceItems.length}</small>
            </summary>
            {sources.map((item) => {
              const row = sourceRow(item, props.regionLabels, labelFor)
              return (
                <button key={item.evidenceId} type="button" className="rv-ws-srow" onClick={() => props.onOpenSource(item.evidenceId)}>
                  <span className="rv-ws-sico">
                    <BookIcon className="rv-icon" />
                  </span>
                  <span className="rv-ws-stxt">
                    <b>{row.title}</b>
                    <small>{row.detail}</small>
                  </span>
                  <ChevronIcon className="rv-icon rv-ws-chev-r" />
                </button>
              )
            })}
            {finding.evidenceItems.length > SOURCES_SHOWN ? (
              <button type="button" className="rv-ws-link" onClick={() => setShowAll((value) => !value)}>
                {showAll ? 'Show fewer sources' : `Show all ${finding.evidenceItems.length} sources`}
              </button>
            ) : null}
            {quality.map((row) => (
              <div key={row} className="rv-ws-caption">
                {row}
              </div>
            ))}
          </details>
        ) : null}

        {decision && props.decisionsLoaded ? (
          <div className="rv-ws-meta">
            <div className="rv-ws-meta-top">
              <strong>{state.label}</strong>
              <small>
                Version {decision.revision} · {dateTime(decision.decidedAt) || 'Saved'}
              </small>
            </div>
            {decision.comment ? <p>{decision.comment}</p> : null}
            <div className="rv-ws-meta-actions">
              {props.isFinalized ? null : (
                <button
                  type="button"
                  className="rv-ws-link"
                  onClick={() => document.getElementById('rv-ws-decide')?.scrollIntoView({ block: 'end', behavior: 'smooth' })}
                >
                  Change decision
                </button>
              )}
              {decision.revision > 1 ? (
                <button type="button" className="rv-ws-link" onClick={props.onOpenHistory}>
                  History ({decision.revision})
                </button>
              ) : null}
            </div>
          </div>
        ) : null}
        {props.decisionsTruncated ? (
          <p className="rv-ws-caption">Earlier decisions may not be shown.</p>
        ) : null}

        {flow.compose ? (
          <DecisionComposer
            key={`${finding.id}-${flow.compose}`}
            kind={flow.compose}
            finding={finding}
            currentText={props.currentText}
            initialLanguage={decision?.action === 'EDIT' ? (decision.approvedLanguage ?? '') : ''}
            isSubmitting={flow.isSubmitting}
            error={flow.error}
            onSubmit={flow.submit}
            onCancel={flow.cancel}
          />
        ) : flow.error ? (
          <p className="rv-ws-error" role="alert">
            {flow.error}
          </p>
        ) : null}

        <FrozenCard key="frozen" {...props} />
      </div>

      <div className="rv-ws-decide" id="rv-ws-decide">
        {props.isFinalized ? (
          <div className="rv-ws-lock">
            <LockIcon />
            <div>
              <b>Decision frozen: {decision ? state.label : 'None'}</b>
              <div>This review is finalized. Approved changes cannot be changed.</div>
            </div>
          </div>
        ) : !props.analysisComplete ? (
          <p className="rv-ws-caption">Decisions open when the automated analysis completes.</p>
        ) : !props.canDecide || !props.decisionsLoaded ? (
          <p className="rv-ws-caption">Decisions are unavailable right now.</p>
        ) : flow.readOnly ? (
          <p className="rv-ws-caption">Decision controls are read-only for this account.</p>
        ) : (
          <>
            <div className="rv-ws-decide-h">
              <span>Your decision{decision ? `: ${state.label}` : ''}</span>
              {added ? null : <span>Rivell’s recommendation is kept separately</span>}
            </div>
            <div className="rv-ws-dbtns">
              <Button
                aria-pressed={decision?.action === 'ACCEPT'}
                disabled={flow.isSubmitting || !eligible}
                title={eligible ? 'Shortcut A' : reason}
                onClick={() => flow.decide('ACCEPT')}
              >
                <CheckIcon />
                Accept
              </Button>
              <Button aria-pressed={decision?.action === 'EDIT'} disabled={flow.isSubmitting} title="Shortcut E" onClick={() => flow.decide('EDIT')}>
                <EditIcon />
                Edit
              </Button>
              <Button
                className="rv-ws-rej"
                aria-pressed={decision?.action === 'REJECT'}
                disabled={flow.isSubmitting}
                title="Shortcut R"
                onClick={() => flow.decide('REJECT')}
              >
                <CloseIcon />
                Reject
              </Button>
              <Button
                className="rv-ws-esc"
                aria-pressed={decision?.action === 'ESCALATE'}
                disabled={flow.isSubmitting}
                title="Shortcut S"
                onClick={() => flow.decide('ESCALATE')}
              >
                <EscalateIcon />
                Escalate
              </Button>
            </div>
            {!eligible && !decision ? (
              <div className="rv-ws-caption">Accept unavailable. Edit the wording, or reject or escalate this finding.</div>
            ) : null}
          </>
        )}
      </div>
    </aside>
  )
}

/** Engine v2 both-views panel (spec §9): the first review's answer and the second opinion's, side by side. */
function SecondOpinionViews({ quality }: { quality: FindingQuality }) {
  const estimate =
    quality.flagProbability === null ? '' : ` (issue estimate ${Math.round(quality.flagProbability * 100)}%)`
  return (
    <div className="rv-ws-sec" data-testid="second-opinion-views">
      <div className="rv-ws-sec-h">The two checks disagree</div>
      <dl className="rv-ws-views">
        <div>
          <dt>First review</dt>
          <dd>
            {quality.jevCategory ? comparisonCategoryLabel(quality.jevCategory) : 'No answer'}
            {estimate}
          </dd>
        </div>
        <div>
          <dt>Second opinion</dt>
          <dd>
            {quality.secondOpinionCategory
              ? comparisonCategoryLabel(quality.secondOpinionCategory)
              : 'Not available'}
            {quality.secondOpinionQuote ? <blockquote>“{quality.secondOpinionQuote}”</blockquote> : null}
            {quality.secondOpinionReason ? <p>{quality.secondOpinionReason}</p> : null}
          </dd>
        </div>
      </dl>
    </div>
  )
}

const SPOTTED_SEVERITY_LABELS: Record<string, string> = {
  HIGH: 'Picked as high risk',
  MEDIUM: 'Picked as medium risk',
}

/**
 * Engine v2 playbook-silent item (P4 D3): no position addresses the clause and the first review did
 * not flag it as unusual, but the whole-contract read picked it. Both views, so the user decides.
 */
function PlaybookSilentViews({ quality }: { quality: FindingQuality }) {
  const estimate =
    quality.uncoveredProbability === null ? '' : ` (estimate ${Math.round(quality.uncoveredProbability * 100)}%)`
  return (
    <div className="rv-ws-sec" data-testid="playbook-silent-views">
      <div className="rv-ws-sec-h">The two checks disagree</div>
      <dl className="rv-ws-views">
        <div>
          <dt>First review</dt>
          <dd>
            No position in your playbook covers this clause. Rivell did not flag it as unusual{estimate}.
          </dd>
        </div>
        <div>
          <dt>Full-contract read</dt>
          <dd>
            {SPOTTED_SEVERITY_LABELS[quality.spottedSeverity ?? ''] ?? 'Picked for a look'}
            {quality.spottedReason ? <p>{quality.spottedReason}</p> : null}
          </dd>
        </div>
      </dl>
    </div>
  )
}

