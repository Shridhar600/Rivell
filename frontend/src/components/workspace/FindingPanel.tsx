import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type { EvidenceItem } from '../../api/retrieval'
import type { FindingClaim, FindingDetail, FindingQuality, PlaybookUsed } from '../../api/reviews'
import type { ConceptLabel } from '../../api/taxonomy'
import { useTaxonomy } from '../../api/taxonomy'
import type { HumanDecision } from '../../api/workflow'
import { authorityLabel } from '../../lib/evidence'
import {
  absenceClaims,
  approvalConflictLine,
  approvalSourceLabel,
  approvalSummary,
  assessmentSummary,
  claimKindLabel,
  classificationLabel,
  comparisonCategoryLabel,
  decidedByLabel,
  escalateIfLine,
  escalateIfText,
  findingGapText,
  findingIssueLabel,
  findingPlaybookRule,
  findingQuote,
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
  /** The playbooks the review froze, used to name the source of a rule; null when unknown. */
  playbooksUsed: readonly PlaybookUsed[] | null
  /** The clause as received, from the pinned contract structure. */
  currentText: string | null
  /** The clause body a replacement starts from, without its heading. */
  replacementText: string | null
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

function sourceRow(
  item: EvidenceItem,
  regionLabels: Map<string, string>,
  label: ConceptLabel,
  wordingFitVerdict: string | null = null,
): { title: string; detail: string } {
  const region = item.provenanceNodeIds.map((id) => regionLabels.get(id)).find(Boolean)
  const subject = region ?? (item.legalConcept ? label(item.legalConcept) : '')
  const snippet = item.text.replace(/\s+/g, ' ').trim()
  const title =
    item.authorityClass === 'APPROVED_DRAFTING'
      ? wordingFitVerdict === 'DOES_NOT_FIT'
        ? 'Library wording (did not fit this position)'
        : wordingFitVerdict === 'UNKNOWN'
          ? 'Library wording (not checked against this position)'
          : wordingFitVerdict === 'FITS'
            ? authorityLabel(item.authorityClass)
            : 'Library wording'
      : authorityLabel(item.authorityClass)
  return { title, detail: [subject, snippet].filter(Boolean).join(' · ') }
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
          <b>Approved changes are final</b>
          <p>
            {props.frozenChanges === null
              ? 'The approved changes cannot be changed.'
              : `${plural(props.frozenChanges, 'approved change', 'approved changes')} fixed for this review.`}{' '}
            Next, create the redline as a Word file.
          </p>
        </div>
      </div>
      <div className="rv-ws-phase-actions">
        <Link id={SUMMARY_LINK_ID} className="rv-btn rv-btn--primary" to={props.summaryPath}>
          Go to the redline
        </Link>
      </div>
    </div>
  )
}

export function FindingPanel(props: Props) {
  const { finding, flow, decision } = props
  const { labelFor } = useTaxonomy()
  const [showAll, setShowAll] = useState(false)
  // A library wording that did not fit is still the person's to take: this opens Edit with it.
  const [libraryPrefill, setLibraryPrefill] = useState<{ text: string; seq: number } | null>(null)
  const prefillSeq = useRef(0)
  useEffect(() => {
    setLibraryPrefill(null)
  }, [props.finding?.id])

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
  // A wording that did not fit, or was not checked, is still offered as a source; the person can
  // take it from here when it did not fit.
  const libraryWording =
    finding.proposedLanguageSource === 'APPROVED_DRAFTING' ||
    finding.quality?.wordingFitVerdict === 'DOES_NOT_FIT'
      ? (finding.evidenceItems.find((item) => item.authorityClass === 'APPROVED_DRAFTING') ?? null)
      : null
  const canTakeLibraryWording =
    !props.isFinalized &&
    props.analysisComplete &&
    props.canDecide &&
    props.decisionsLoaded &&
    !flow.readOnly &&
    !flow.isSubmitting
  const editBaseline = props.replacementText ?? props.currentText ?? ''
  const takeLibraryWording = (text: string) => {
    if (!canTakeLibraryWording) return
    prefillSeq.current += 1
    setLibraryPrefill({ text, seq: prefillSeq.current })
    flow.decide('EDIT')
  }
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
  // A within-fallback finding is a note, not a defect. It becomes an approval question only
  // when a typed rule says the fallback needs approval (council §A.3), never from a trigger text.
  const withinFallback = finding.issueFamily === 'POSITION_ACCEPTABLE_FALLBACK'
  const approval = approvalSummary(finding.quality)
  const approvalConflict = approvalConflictLine(approval)
  const escalateIf = escalateIfLine(approval)
  const approvalSource = approvalSourceLabel(approval?.source ?? null)
  const change = proposedChangeType(finding)
  const grounded = isWordingGrounded(finding)
  const authority = !grounded ? null : languageAuthority(finding, items.get(finding.approvedDraftingEvidenceId ?? '')?.publicationNumber ?? null)
  const state = findingState(finding, decision)
  const sources = showAll ? finding.evidenceItems : finding.evidenceItems.slice(0, SOURCES_SHOWN)
  const edited = decision?.action === 'EDIT' && decision.approvedLanguage !== finding.proposedLanguage
  const eligible = flow.acceptEligibility.eligible
  const escalated = decision?.action === 'ESCALATE'
  const keptAsDrafted = decision?.action === 'REJECT' && decision.rejectReason === 'KEEP_AS_DRAFTED'
  const dismissed = decision?.action === 'REJECT' && decision.rejectReason === 'FINDING_WRONG'
  const missingEvidence = finding.claims.some((claim) => claim.evidenceItemIds.some((id) => !items.has(id)))
  const title = findingSubjectLabel(finding, props.regionLabels, labelFor)
  // A position on a topic the clause only touches is a related note, never a defect in the clause.
  const related = isRelatedPosition(finding)
  // The four things a reader needs first: the words, the rule, the gap and the fix. Everything
  // else is one closed disclosure away.
  const gapText = findingGapText(finding, labelFor)
  const quote = findingQuote(finding, props.currentText)
  const quoteText = quote ? (quote.source === 'contract' ? clauseExcerpt(quote.text) : quote.text) : null
  const quoteReason =
    quote?.source === 'second-opinion' ? (finding.quality?.secondOpinionReason?.trim() ?? null) : null
  const playbookRule = findingPlaybookRule(finding, labelFor, props.playbooksUsed)
  const playbookCondition = escalateIfText(playbookRule?.escalation)
  const noApproverCondition = approval ? null : playbookCondition
  // the rule's condition and the playbook trigger can be the same words; show them once
  const sameEscalation =
    approval !== null && playbookCondition !== null && playbookCondition === approval.condition
  const proposedLanguage = finding.proposedLanguage
  const showDetails =
    !added ||
    Boolean(
      decidedBy ||
        assessment ||
        related ||
        withinFallback ||
        quality.length > 0 ||
        why.length > 0 ||
        absences.length > 0 ||
        note ||
        recommendations.length > 0 ||
        (finding.quality?.route === 'NEEDS_YOUR_LOOK' && finding.quality.secondOpinionOutcome) ||
        (silent && finding.quality) ||
        position ||
        finding.evidenceItems.length > 0 ||
        missingEvidence,
    )

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
        <div className="rv-ws-main" data-testid="finding-main">
          {quote && quoteText ? (
            <div className="rv-ws-sec">
              <div className="rv-ws-sec-h">
                <span>What the contract says</span>
                {finding.subjectKind === 'CLAUSE' && props.currentText ? (
                  <button type="button" className="rv-ws-link" onClick={props.onShowInDocument}>
                    Show in document
                  </button>
                ) : null}
              </div>
              <blockquote className="rv-ws-quote">“{quoteText}”</blockquote>
              {quoteReason ? <p className="rv-ws-caption">{quoteReason}</p> : null}
            </div>
          ) : null}

          {playbookRule && (playbookRule.label || playbookRule.text || playbookRule.basedOn) ? (
            <div className="rv-ws-sec">
              <div className="rv-ws-sec-h">Your playbook</div>
              {playbookRule.label ? (
                <div className="rv-ws-authority">
                  <b>{playbookRule.label}</b>
                </div>
              ) : null}
              {playbookRule.text ? <div className="rv-ws-incl">{playbookRule.text}</div> : null}
              {playbookRule.basedOn ? (
                <p className="rv-ws-caption">Based on: {playbookRule.basedOn}</p>
              ) : null}
              {playbookRule.escalation && !sameEscalation && (approval || noApproverCondition) ? (
                <p className="rv-ws-caption">
                  {approval
                    ? `Escalate when: ${playbookRule.escalation}.`
                    : `Escalate if: ${noApproverCondition}. No approver is named.`}
                </p>
              ) : null}
            </div>
          ) : null}

          {gapText ? (
            <div className="rv-ws-sec">
              <div className="rv-ws-sec-h">{added ? 'Reviewer’s note' : 'The gap'}</div>
              <p className="rv-ws-gap">{gapText}</p>
            </div>
          ) : null}

          {finding.quality?.wordingFitVerdict === 'DOES_NOT_FIT' && !proposedLanguage ? (
            <p className="rv-ws-note">
              The clause library wording for this position did not fit this clause. No replacement
              wording is proposed; please check it yourself.
              {canTakeLibraryWording && libraryWording ? (
                <>
                  {' '}
                  <button
                    type="button"
                    className="rv-ws-link"
                    onClick={() => takeLibraryWording(libraryWording.text)}
                  >
                    Use the library wording
                  </button>
                </>
              ) : null}
            </p>
          ) : null}

          {finding.quality?.foundBy === 'SEARCH' && finding.quality?.fitVerdict === 'UNKNOWN' ? (
            <p className="rv-ws-note">
              Rivell could not check that this rule governs this clause. Read it before you rely on
              it.
            </p>
          ) : null}

          {proposedLanguage && grounded ? (
            <div className="rv-ws-sec">
              <div className="rv-ws-sec-h">Suggested change</div>
              <div className="rv-ws-prop">
                <ins>{proposedLanguage}</ins>
              </div>
              {authority ? (
                <div className="rv-ws-authority">
                  <b>{authority.strong}</b>
                  <span>{authority.rest}</span>
                </div>
              ) : null}
              {finding.quality?.wordingFitVerdict === 'DOES_NOT_FIT' ? (
                <p className="rv-ws-caption">
                  The clause library wording for this position did not fit this clause, so Rivell's proposal is
                  shown instead. Check it before you use it.
                  {canTakeLibraryWording && libraryWording ? (
                    <>
                      {' '}
                      <button
                        type="button"
                        className="rv-ws-link"
                        onClick={() => takeLibraryWording(libraryWording.text)}
                      >
                        Use the library wording
                      </button>
                    </>
                  ) : null}
                </p>
              ) : null}
              {edited ? <div className="rv-ws-src">Your edit differs from the proposed wording.</div> : null}
            </div>
          ) : null}

          {approval ? (
            <p className="rv-ws-note rv-ws-note--approval">
              <b>Approval is needed.</b>{' '}
              {approval.approver} approves{' '}
              {approval.stance === 'PROHIBITED' ? 'this walk-away' : 'this fallback'}
              {approvalSource ? `, ${approvalSource}` : ''}.{' '}
              {withinFallback
                ? 'The clause stays within an acceptable fallback, and this rule says the fallback needs approval.'
                : 'Escalate the finding, then resolve it with the approver’s name.'}
              {escalateIf ? ` ${escalateIf}` : ''}
              {approvalConflict ? ` ${approvalConflict}` : ''}
            </p>
          ) : null}

          {withinFallback && !approval ? (
            <p className="rv-ws-note">
              <b>This is a note, not a problem.</b> The clause departs from the preferred position
              but stays within an acceptable fallback; accept and record it.
            </p>
          ) : null}
        </div>

        {showDetails ? (
          <details className="rv-ws-sec rv-ws-srcs" data-testid="finding-details">
            <summary className="rv-ws-sec-h">Details</summary>
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
            {assessment ? (
              <p className="rv-ws-caption" data-testid="finding-assessment">
                Assessment{related ? ' (secondary topic)' : ''}: {assessment}
              </p>
            ) : null}

            {added ? null : (
              <div className="rv-ws-sec">
                <div className="rv-ws-sec-h">Rivell found</div>
                <div className="rv-ws-machine">
                  <p>
                    <span className="rv-ws-authority">
                      {related ? 'Related position on a secondary topic' : observationKindLabel(finding.observationKind)}
                    </span>
                  </p>
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
                  <p>
                    <b>Recommendation:</b> {proposedActionLabel(finding.proposedAction, finding)}.
                    {recommendations.map((claim) => (
                      <span key={claim.ordinal}>
                        {' '}
                        {claim.text}
                        <ClaimEvidence claim={claim} items={items} onOpen={props.onOpenSource} />
                      </span>
                    ))}
                  </p>
                  {note ? <p>{note}</p> : null}
                  {missingEvidence ? (
                    <p className="rv-ws-warn">Some supporting sources are missing from this view.</p>
                  ) : null}
                </div>
              </div>
            )}

            {finding.quality?.route === 'NEEDS_YOUR_LOOK' && finding.quality.secondOpinionOutcome ? (
              <SecondOpinionViews quality={finding.quality} />
            ) : null}
            {silent && finding.quality ? <PlaybookSilentViews quality={finding.quality} /> : null}

            {position ? (
              <PolicyPosition
                view={position}
                openable={new Set(items.keys())}
                onOpenSource={props.onOpenSource}
                caption={finding.quality?.foundBy === 'SEARCH' ? SEARCH_CAPTION : null}
              />
            ) : null}

            {finding.evidenceItems.length > 0 ? (
              // Condensed: the main blocks say what matters, so the full source list is one
              // disclosure away, each item still labelled with its own authority.
              <details className="rv-ws-sec rv-ws-srcs">
                <summary className="rv-ws-sec-h">
                  Sources
                  <small>{finding.evidenceItems.length}</small>
                </summary>
                {sources.map((item) => {
                  const row = sourceRow(
                    item,
                    props.regionLabels,
                    labelFor,
                    item.evidenceId === libraryWording?.evidenceId
                      ? (finding.quality?.wordingFitVerdict ?? 'UNKNOWN')
                      : null,
                  )
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
            ) : quality.length > 0 ? (
              <div className="rv-ws-sec">
                <div className="rv-ws-sec-h">Checks</div>
                {quality.map((row) => (
                  <div key={row} className="rv-ws-caption">
                    {row}
                  </div>
                ))}
              </div>
            ) : null}
          </details>
        ) : null}

        {decision && props.decisionsLoaded ? (
          <div className="rv-ws-meta">
            <div className="rv-ws-meta-top">
              <strong>{state.label}</strong>
              <small>
                Decision {decision.revision} · {dateTime(decision.decidedAt) || 'Saved'}
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
            key={`${finding.id}-${flow.compose}-${libraryPrefill?.seq ?? 0}`}
            kind={flow.compose}
            finding={finding}
            currentText={props.currentText}
            initialLanguage={
              libraryPrefill?.text ??
              (decision?.action === 'EDIT' ? (decision.approvedLanguage ?? '') : editBaseline)
            }
            baselineLanguage={editBaseline}
            isSubmitting={flow.isSubmitting}
            error={flow.error}
            onSubmit={(submission) => {
              setLibraryPrefill(null)
              flow.submit(submission)
            }}
            onCancel={() => {
              setLibraryPrefill(null)
              flow.cancel()
            }}
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
              <b>Decision final: {decision ? state.label : 'None'}</b>
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
              {eligible ? (
                <Button
                  aria-pressed={decision?.action === 'ACCEPT'}
                  disabled={flow.isSubmitting}
                  title="Shortcut A"
                  onClick={() => flow.decide('ACCEPT')}
                >
                  <CheckIcon />
                  Accept
                </Button>
              ) : null}
              {escalated ? (
                <Button
                  variant="primary"
                  disabled={flow.isSubmitting}
                  onClick={() => flow.decide('RESOLVE')}
                >
                  <CheckIcon />
                  Resolve
                </Button>
              ) : null}
              <Button aria-pressed={decision?.action === 'EDIT'} disabled={flow.isSubmitting} title="Shortcut E" onClick={() => flow.decide('EDIT')}>
                <EditIcon />
                Edit
              </Button>
              <Button
                aria-pressed={keptAsDrafted}
                disabled={flow.isSubmitting}
                title="Shortcut K"
                onClick={() => flow.decide('KEEP_AS_DRAFTED')}
              >
                <CheckIcon />
                Keep as drafted
              </Button>
              <Button
                className="rv-ws-rej"
                aria-pressed={dismissed}
                disabled={flow.isSubmitting}
                title="Shortcut D"
                onClick={() => flow.decide('FINDING_WRONG')}
              >
                <CloseIcon />
                Dismiss
              </Button>
              {escalated ? null : (
                <Button
                  className="rv-ws-esc"
                  disabled={flow.isSubmitting}
                  title="Shortcut S"
                  onClick={() => flow.decide('ESCALATE')}
                >
                  <EscalateIcon />
                  Escalate
                </Button>
              )}
            </div>
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
  const heading =
    quality.secondOpinionOutcome === 'DISAGREED'
      ? 'Two readings of this clause disagree'
      : quality.secondOpinionOutcome === 'AGREED'
        ? 'Two readings of this clause agree'
        : 'Only one reading is available'
  return (
    <div className="rv-ws-sec" data-testid="second-opinion-views">
      <div className="rv-ws-sec-h">{heading}</div>
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
      <div className="rv-ws-sec-h">Two readings of this clause disagree</div>
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

