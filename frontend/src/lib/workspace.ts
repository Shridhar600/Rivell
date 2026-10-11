import type { FindingDetail, FindingSummary, ReviewSnapshot } from '../api/reviews'
import type { ChangeActionType, HumanDecision, HumanReview } from '../api/workflow'
import { CONTRACT_TYPE_LABELS, PAPER_SOURCE_LABELS, roleLabel } from './contractSemantics'
import { acceptEligibility, type AcceptEligibility } from './workflow'
import { buildDocumentTree, type StructureNode, type TreeNode } from './documentTree'

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
  return decision?.action === 'ACCEPT' || decision?.action === 'EDIT' || decision?.action === 'REJECT'
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
      return { tone: 'rejected', label: 'Rejected' }
    case 'ESCALATE':
      return { tone: 'escalated', label: 'Escalated · unresolved' }
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

/** "MSA, counterparty paper, our side Customer", from the review's frozen profile only. */
export function profileLine(snapshot: ReviewSnapshot | null | undefined): string | null {
  const profile = snapshot?.profile
  if (!profile) return null
  const parts = [
    profile.contractType ? (CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType) : null,
    profile.paperSource ? (PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource).toLowerCase() : null,
    profile.ourSideRole ? `our side ${roleLabel(profile.ourSideRole).toLowerCase()}` : null,
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
  finding: Pick<FindingDetail, 'proposedLanguageSource'>,
  /** The publication the approved wording was quoted from, when its evidence names one. */
  publicationNumber: number | null = null,
): { strong: string; rest: string } | null {
  if (finding.proposedLanguageSource === 'APPROVED_DRAFTING') {
    return {
      strong: 'Approved organizational drafting',
      rest: publicationNumber === null ? 'Quoted from published wording' : `Publication ${publicationNumber} · Source: Clause library`,
    }
  }
  if (finding.proposedLanguageSource === 'GENERATIVE') {
    return { strong: 'Machine-proposed', rest: 'Produced by a model; not approved organizational language' }
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

/** The clause as received: its heading and body, one passage per line. */
export function regionText(nodes: StructureNode[], headingNodeId: string): string | null {
  const top = topLevelNodes(nodes)
  const at = top.findIndex((node) => node.id === headingNodeId)
  if (at === -1) return null
  const text = [nodeText(top[at]), ...clauseBody(top, at).map(nodeText)].filter(Boolean).join('\n')
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
): string[] {
  const approved = review.accepted + review.edited
  const split =
    loaded && loaded.length === approved
      ? `: ${(['REPLACE', 'INSERT', 'DELETE'] as const)
          .map((type) => `${loaded.filter((change) => change.type === type).length} ${type.toLowerCase()}`)
          .join(', ')}`
      : ''
  return [
    `${approved} approved ${approved === 1 ? 'change' : 'changes'} (${review.accepted} accepted, ${review.edited} edited)${split}`,
    `${review.rejected} rejected ${review.rejected === 1 ? 'Finding' : 'Findings'}; no approved change created`,
  ]
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
    reason: 'The approved wording cannot be traced to its source in this review’s evidence. Use Edit to author the change, or Reject or Escalate it.',
  }
}
