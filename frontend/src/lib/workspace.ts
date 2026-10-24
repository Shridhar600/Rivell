import type { FindingDetail, FindingSummary, ReviewSnapshot } from '../api/reviews'
import type { ChangeActionType, HumanDecision, HumanReview } from '../api/workflow'
import { CONTRACT_TYPE_LABELS, PAPER_SOURCE_LABELS, roleLabel } from './contractSemantics'
import { acceptEligibility, type AcceptEligibility } from './workflow'
import { buildDocumentTree, subtreeNodes, type StructureNode, type TreeNode } from './documentTree'

/**
 * View logic for the review workspace. Everything here reads fields the backend already sends.
 * Readiness to finalize is never computed here: the server owns it.
 */

export type FindingTone = 'open' | 'resolved' | 'escalated' | 'rejected'

export interface FindingState {
  tone: FindingTone
  label: string
}

/** Informational findings are recorded for context; they never block finalization. */
export function isActionable(finding: Pick<FindingSummary, 'reviewClassification'>): boolean {
  return finding.reviewClassification !== 'INFORMATIONAL'
}

export function isResolved(decision: HumanDecision | null | undefined): boolean {
  return (
    decision?.action === 'ACCEPT' ||
    decision?.action === 'EDIT' ||
    decision?.action === 'REJECT' ||
    decision?.action === 'RESOLVE'
  )
}

/** A decision that will become an approved change: accepted or edited, with a concrete change type. */
export function executableChange(
  decision: HumanDecision | null | undefined,
): { type: ChangeActionType; language: string | null } | null {
  if (decision?.action !== 'ACCEPT' && decision?.action !== 'EDIT') return null
  const type = decision.approvedAction
  if (type !== 'REPLACE' && type !== 'INSERT' && type !== 'DELETE') return null
  return { type, language: decision.approvedLanguage }
}

export function findingState(
  finding: Pick<FindingSummary, 'reviewClassification'>,
  decision: HumanDecision | null | undefined,
): FindingState {
  switch (decision?.action) {
    case 'ACCEPT':
      return { tone: 'resolved', label: 'Accepted' }
    case 'EDIT':
      return { tone: 'resolved', label: 'Edited' }
    case 'REJECT':
      if (decision.rejectReason === 'KEEP_AS_DRAFTED') {
        return { tone: 'resolved', label: 'Kept as drafted' }
      }
      if (decision.rejectReason === 'FINDING_WRONG') {
        return { tone: 'rejected', label: 'Dismissed' }
      }
      return { tone: 'rejected', label: 'Rejected' }
    case 'ESCALATE':
      return { tone: 'escalated', label: 'Escalated · unresolved' }
    case 'RESOLVE':
      return {
        tone: 'resolved',
        label: decision.approvedBy ? `Approved by ${decision.approvedBy}` : 'Approved',
      }
    default:
      return { tone: 'open', label: isActionable(finding) ? 'Needs decision' : 'Informational' }
  }
}

/**
 * The change a recommendation would make, when it makes one. A finding that only asks for human
 * attention proposes no change type, and none is invented for it.
 */
export function proposedChangeType(
  finding: Pick<FindingSummary, 'subjectKind' | 'proposedAction'>,
): ChangeActionType | null {
  switch (finding.proposedAction) {
    case 'USE_APPROVED_FALLBACK':
      return 'REPLACE'
    case 'ADD_REQUIRED_PROVISION':
      return 'INSERT'
    case 'CONSIDER_GENERATED_LANGUAGE':
      return finding.subjectKind === 'ABSENCE' ? 'INSERT' : 'REPLACE'
    default:
      return null
  }
}

/** "MSA, counterparty paper, our side: Customer", from the review's frozen profile only. */
export function profileLine(snapshot: ReviewSnapshot | null | undefined): string | null {
  const profile = snapshot?.profile
  if (!profile) return null
  const parts = [
    profile.contractType ? (CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType) : null,
    profile.paperSource ? (PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource).toLowerCase() : null,
    profile.ourSideRole ? `our side: ${roleLabel(profile.ourSideRole)}` : null,
  ].filter((part): part is string => part !== null)
  return parts.length > 0 ? parts.join(', ') : null
}

/** The next finding that still needs a human decision, wrapping around the list. */
export function nextOpenFinding(
  findings: readonly FindingSummary[],
  decisions: ReadonlyMap<string, HumanDecision>,
  afterId: string,
): FindingSummary | null {
  const start = findings.findIndex((finding) => finding.id === afterId)
  for (let step = 1; step <= findings.length; step += 1) {
    const candidate = findings[(start + step) % findings.length]
    if (isActionable(candidate) && !isResolved(decisions.get(candidate.id))) return candidate
  }
  return null
}

/** The finding to show when the URL names none: the first that needs a decision, else the first. */
export function defaultFinding(
  findings: readonly FindingSummary[],
  decisions: ReadonlyMap<string, HumanDecision>,
): FindingSummary | null {
  return (
    findings.find((finding) => isActionable(finding) && !isResolved(decisions.get(finding.id))) ??
    findings[0] ??
    null
  )
}

/** Where the approved wording came from, in the canonical authority line. */
export function languageAuthority(
  finding: Pick<FindingDetail, 'proposedLanguageSource' | 'quality'>,
  /** The publication the approved wording was quoted from, when its evidence names one. */
  publicationNumber: number | null = null,
): { strong: string; rest: string } | null {
  if (finding.proposedLanguageSource === 'APPROVED_DRAFTING') {
    // A search hit or a library variant is offered as approved only when the fit check passed.
    // UNKNOWN, a missing verdict and DOES_NOT_FIT are never shown as approved.
    if (finding.quality?.wordingFitVerdict === 'DOES_NOT_FIT') {
      return {
        strong: 'Library wording that does not fit this position',
        rest: 'Rivell did not propose this wording for the clause.',
      }
    }
    if (finding.quality?.wordingFitVerdict !== 'FITS') {
      return {
        strong: 'Library wording, not checked against this position',
        rest: 'Rivell could not confirm that it meets this position and cures the gap. Read it before you use it.',
      }
    }
    return {
      strong: 'Approved organizational drafting',
      rest: publicationNumber === null ? 'Quoted from published wording' : `Knowledge version ${publicationNumber} · Source: Clause library`,
    }
  }
  if (finding.proposedLanguageSource === 'GENERATIVE') {
    return { strong: 'Proposed by Rivell', rest: 'Rivell wrote this; it is not approved organizational language' }
  }
  return null
}

/** Split a text on a search term, so the match can be marked. Case-insensitive, literal. */
export function splitOnTerm(text: string, term: string): { text: string; match: boolean }[] {
  if (!term) return [{ text, match: false }]
  const parts: { text: string; match: boolean }[] = []
  const lower = text.toLowerCase()
  const needle = term.toLowerCase()
  let from = 0
  for (let at = lower.indexOf(needle); at !== -1; at = lower.indexOf(needle, from)) {
    if (at > from) parts.push({ text: text.slice(from, at), match: false })
    parts.push({ text: text.slice(at, at + needle.length), match: true })
    from = at + needle.length
  }
  if (from < text.length) parts.push({ text: text.slice(from), match: false })
  return parts
}

export function nodeLabel(node: StructureNode): string {
  return [node.numberingLabel, node.text].filter(Boolean).join(' ')
}

/** A node's own text and everything beneath it, one passage per line. */
export function nodeText(node: TreeNode): string {
  return [nodeLabel(node), ...node.children.map(nodeText)].filter(Boolean).join('\n')
}

/** The document's top level: the children of the BODY node. Clauses are cut from this level. */
export function topLevelNodes(nodes: StructureNode[]): TreeNode[] {
  const roots = buildDocumentTree(nodes)
  return roots.length === 1 && roots[0].nodeType === 'BODY' ? roots[0].children : roots
}

/**
 * A clause is a top-level heading and the top-level passages after it, up to the next heading. This
 * is the same rule the backend segmenter uses, so a finding's heading names the same passage here.
 */
export function clauseBody(top: readonly TreeNode[], headingIndex: number): TreeNode[] {
  const body: TreeNode[] = []
  for (let i = headingIndex + 1; i < top.length && top[i].nodeType !== 'HEADING'; i += 1) body.push(top[i])
  return body
}

/**
 * Every node of a clause by the same rule: the top-level heading, the passages after it up to the
 * next heading, and everything beneath them. A heading below the top level is its own subtree.
 */
export function clauseNodeIds(nodes: StructureNode[], headingNodeId: string): Set<string> {
  const top = topLevelNodes(nodes)
  const at = top.findIndex((node) => node.id === headingNodeId)
  const ids = new Set<string>()
  const walk = (node: TreeNode) => {
    ids.add(node.id)
    node.children.forEach(walk)
  }
  if (at === -1) subtreeNodes(nodes, headingNodeId).forEach((node) => ids.add(node.id))
  else [top[at], ...clauseBody(top, at)].forEach(walk)
  return ids
}

/** The clause as received: its heading and body, one passage per line. */
export function regionText(nodes: StructureNode[], headingNodeId: string): string | null {
  const top = topLevelNodes(nodes)
  const at = top.findIndex((node) => node.id === headingNodeId)
  if (at === -1) return null
  const text = [nodeText(top[at]), ...clauseBody(top, at).map(nodeText)].filter(Boolean).join('\n')
  return text || null
}

/**
 * The body of a clause, without its heading. A replacement keeps the heading, so this is
 * what an edit starts from; see regionText for the clause as the reader sees it.
 */
export function regionBodyText(nodes: StructureNode[], headingNodeId: string): string | null {
  const top = topLevelNodes(nodes)
  const at = top.findIndex((node) => node.id === headingNodeId)
  if (at === -1) return null
  const text = clauseBody(top, at).map(nodeText).filter(Boolean).join('\n')
  return text || null
}

/** How many characters of a clause the detail panel shows before it stops: about two lines. */
export const CLAUSE_EXCERPT_CHARS = 180

/**
 * A short excerpt of the clause as received, so the detail panel does not repeat the document
 * beside it. At most two passages inside a character budget: a passage that would overflow is cut
 * at the budget rather than dropped, and an ellipsis marks anything the excerpt leaves out. The
 * full clause stays one "Show in document" away.
 */
export function clauseExcerpt(text: string, maxChars: number = CLAUSE_EXCERPT_CHARS): string {
  const trimmed = text.trim()
  const lines = trimmed
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
  const kept: string[] = []
  let used = 0
  for (const line of lines) {
    if (kept.length >= 2) break
    const room = maxChars - used
    if (room <= 1) break
    if (line.length <= room) {
      kept.push(line)
      used += line.length + 1
      continue
    }
    kept.push(line.slice(0, room - 1).trimEnd())
    break
  }
  const excerpt = kept.join('\n')
  return excerpt.length < trimmed.length ? `${excerpt.trimEnd()}…` : excerpt
}

/**
 * What the Finalize dialog tells the reviewer will be frozen. Counts are the server's. The split by
 * change type is added only when the loaded decisions account for every approved change.
 */
export function finalizeSummary(
  review: Pick<HumanReview, 'accepted' | 'edited' | 'rejected'>,
  loaded: readonly { type: ChangeActionType }[] | null,
  resolvedEscalations = 0,
): string[] {
  const approved = review.accepted + review.edited
  const split =
    loaded && loaded.length === approved
      ? `: ${(['REPLACE', 'INSERT', 'DELETE'] as const)
          .map((type) => `${loaded.filter((change) => change.type === type).length} ${type.toLowerCase()}`)
          .join(', ')}`
      : ''
  const lines = [
    `${approved} approved ${approved === 1 ? 'change' : 'changes'} (${review.accepted} accepted, ${review.edited} edited)${split}`,
    `${review.rejected} ${review.rejected === 1 ? 'finding' : 'findings'} with no approved change`,
  ]
  if (resolvedEscalations > 0) {
    lines.push(
      `${resolvedEscalations} ${resolvedEscalations === 1 ? 'escalation' : 'escalations'} resolved with an approver name; no approved change created`,
    )
  }
  return lines
}

/** Approved wording is groundable only when the evidence it cites is part of the finding. */
export function isWordingGrounded(finding: Pick<FindingDetail, 'proposedLanguageSource' | 'approvedDraftingEvidenceId' | 'evidenceItems'>): boolean {
  if (finding.proposedLanguageSource !== 'APPROVED_DRAFTING') return true
  return finding.evidenceItems.some(
    (item) => item.evidenceId === finding.approvedDraftingEvidenceId && item.authorityClass === 'APPROVED_DRAFTING',
  )
}

/** Accept adopts the proposal as written, so it needs executable language whose source can be shown. */
export function acceptEligibilityFor(finding: FindingDetail): AcceptEligibility {
  const base = acceptEligibility(finding)
  if (!base.eligible || isWordingGrounded(finding)) return base
  return {
    eligible: false,
    reason: 'The approved wording cannot be traced to its source in this review’s evidence. Use Edit to author the change, or keep the clause as drafted, dismiss or escalate the finding.',
  }
}

/**
 * A "needs your look" item: Rivell did not decide it. The two reviewers disagreed, a second opinion
 * was not available, the playbook is silent on a clause the whole-contract read picked, or Rivell is
 * unsure a missing provision is required.
 */
export function isNeedsYourLook(finding: Pick<FindingSummary, 'decidedBy'>): boolean {
  return finding.decidedBy === 'NEEDS_YOUR_LOOK'
}

type Ordered = Pick<FindingSummary, 'decidedBy'> & {
  subjectKind?: FindingSummary['subjectKind']
  firstReviewCategory?: string | null
}

/**
 * "Needs your look" items with the first review's walk-away answer first: a possible walk-away
 * breach is the one to look at before the rest. Disagreements about a clause then come before
 * absence findings, whose subject is a provision the contract does not have. Each part keeps
 * the server's order.
 */
export function orderNeedsYourLook<T extends Ordered>(look: readonly T[]): T[] {
  const walkAway = (finding: T) => finding.firstReviewCategory === 'WALK_AWAY'
  const absence = (finding: T) => finding.subjectKind === 'ABSENCE'
  const rest = look.filter((finding) => !walkAway(finding))
  return [
    ...look.filter(walkAway),
    ...rest.filter((finding) => !absence(finding)),
    ...rest.filter(absence),
  ]
}

/** Findings in review order: "needs your look" items after the others, each group in server order. */
export function orderForReview<T extends Ordered>(findings: readonly T[]): T[] {
  return [
    ...findings.filter((finding) => !isNeedsYourLook(finding)),
    ...orderNeedsYourLook(findings.filter(isNeedsYourLook)),
  ]
}
