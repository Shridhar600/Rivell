import type { FindingSummary, ReviewSnapshotProfile } from '../api/reviews'
import type { RedlineExecution, RedlineExecutionChange } from '../api/redline'
import type { ConceptLabel } from '../api/taxonomy'
import type { DocumentNode } from '../api/contracts'
import type { ApprovedChange, ApprovedChangeSet, HumanDecision, HumanReview } from '../api/workflow'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from './contractSemantics'
import { redlineFailureTitle, redlinePlacementLabel, redlineVerificationOutcome } from './redline'
import { changeSetCounts, changeTargetLabel, decisionStateLabel } from './workflow'

/** A short, human name for a Finding, from the facts the server sent about it. */
export function findingTitle(finding: FindingSummary | undefined, label: ConceptLabel): string | null {
  if (!finding) return null
  if (finding.subjectKind === 'ABSENCE' && finding.absenceConcept) {
    return `Missing: ${label(finding.absenceConcept)}`
  }
  return finding.concept ? label(finding.concept) : null
}

export interface ClassificationCount {
  label: string
  count: number
}

/**
 * The three cells shown when analysis is done. The canonical sample splits by proposed change
 * type, which the API does not expose; the review classification is the nearest real split.
 */
export function classificationCounts(findings: FindingSummary[]): ClassificationCount[] {
  const count = (classification: string) =>
    findings.filter((finding) => finding.reviewClassification === classification).length
  return [
    { label: 'Blocking', count: count('BLOCKING') },
    { label: 'Review required', count: count('REVIEW_REQUIRED') },
    { label: 'Informational', count: count('INFORMATIONAL') },
  ]
}

/**
 * The first Finding the server still lists as undecided. Null when the server names none, or when
 * the Finding is not in the list this page holds, so a wrong label is never guessed.
 */
export function nextUnresolvedTitle(
  humanReview: HumanReview,
  findings: FindingSummary[],
  label: ConceptLabel,
): string | null {
  const pending = humanReview.blockers.find(
    (blocker) =>
      (blocker.code === 'UNRESOLVED_BLOCKING_FINDINGS' || blocker.code === 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS') &&
      blocker.findingIds.length > 0,
  )
  const id = pending?.findingIds[0]
  return id ? findingTitle(findings.find((finding) => finding.id === id), label) : null
}

/** One segment per Finding that needs a decision; filled for each one decided. */
export function decisionSegments(humanReview: HumanReview): boolean[] {
  const total = Math.max(0, humanReview.actionableFindings)
  const decided = Math.min(total, Math.max(0, humanReview.resolvedFindings))
  return Array.from({ length: total }, (_, index) => index < decided)
}

/** Approved changes by kind, as the Generate-redline summary shows them. */
export function changeSetFigures(changeSet: ApprovedChangeSet): { total: number; replace: number; insert: number; delete: number } {
  return changeSetCounts(changeSet.changes)
}

/**
 * The clause text a REPLACE or DELETE acts on: the paragraphs after the clause heading, up to the
 * next heading of the same or a higher level. This is the region the redline engine edits (the
 * heading stays), read in document order from the pinned version's own structure. Null when the
 * heading is unknown or has no body text, so an empty "before" is never shown as a deletion.
 */
export function clauseBodyText(nodes: DocumentNode[], headingNodeId: string): string | null {
  const ordered = [...nodes].sort((a, b) => a.sourceSpan.bodyIndex - b.sourceSpan.bodyIndex)
  const start = ordered.findIndex((node) => node.id === headingNodeId)
  if (start < 0) return null
  const level = ordered[start].headingLevel ?? 1
  const body: string[] = []
  for (const node of ordered.slice(start + 1)) {
    if (node.nodeType === 'HEADING' && (node.headingLevel ?? 1) <= level) break
    const text = node.text?.trim()
    if ((node.nodeType === 'PARAGRAPH' || node.nodeType === 'HEADING') && text) body.push(text)
  }
  return body.length > 0 ? body.join('\n\n') : null
}

export function changeTitle(
  change: ApprovedChange,
  finding: FindingSummary | undefined,
  regionLabels: Map<string, string>,
  label: ConceptLabel,
): string {
  return findingTitle(finding, label) ?? changeTargetLabel(change, regionLabels, label)
}

/** Where the change lands. An insertion has no reviewer-chosen place, so it says so. */
export function changeLocation(
  change: ApprovedChange,
  regionLabels: Map<string, string>,
  label: ConceptLabel,
): string {
  if (change.actionType === 'INSERT') return 'Rivell places this when it builds the redline'
  return changeTargetLabel(change, regionLabels, label)
}

/** The outcome chip on a change row: the human decision it came from. */
export function changeOutcome(decision: HumanDecision | undefined): string {
  return decision ? decisionStateLabel(decision) : 'Approved'
}

export interface ResultPill {
  label: string
  variant: 'ok' | 'red' | 'neutral'
}

/**
 * The per-change Result cell. A change applied inside an execution that failed overall is never
 * shown as a success: the canonical sample colours it teal, but the spec forbids a partial-success
 * look, so it is neutral and says what it is.
 */
export function reportResult(execution: RedlineExecution, change: RedlineExecutionChange): ResultPill {
  if (change.status === 'APPLIED' && change.verification === 'VERIFIED' && execution.status === 'COMPLETED') {
    return { label: 'Applied · Verified', variant: 'ok' }
  }
  if (change.status === 'FAILED') return { label: 'Failed', variant: 'red' }
  if (change.status === 'NOT_APPLIED') return { label: 'Not applied', variant: 'neutral' }
  if (change.status === 'APPLIED' && execution.status === 'FAILED') {
    return { label: 'Applied before overall failure', variant: 'neutral' }
  }
  if (change.status === 'APPLIED' && change.verification === 'FAILED') {
    return { label: 'Applied · Check failed', variant: 'red' }
  }
  if (change.status === 'APPLIED') return { label: 'Applied · Not checked', variant: 'neutral' }
  return { label: 'State unknown', variant: 'neutral' }
}

export interface OverallResult {
  word: 'Verified' | 'Failed' | 'Not verified'
  sentence: string
  tone: 'ok' | 'red'
}

export function overallResult(execution: RedlineExecution): OverallResult {
  if (execution.status === 'FAILED') {
    return {
      word: 'Failed',
      sentence: 'Not every change could be applied. No file is offered.',
      tone: 'red',
    }
  }
  if (redlineVerificationOutcome(execution) === 'PASSED') {
    return { word: 'Verified', sentence: 'All approved changes applied and checked.', tone: 'ok' }
  }
  return {
    word: 'Not verified',
    sentence: 'Not every approved change was applied and checked. No file is offered.',
    tone: 'red',
  }
}

/** Where a change was resolved in the document, for the report's location column. */
export function reportLocation(
  change: RedlineExecutionChange,
  approved: ApprovedChange | undefined,
  regionLabels: Map<string, string>,
  failedOverall: boolean,
  label: ConceptLabel,
): string {
  if (change.actionType === 'INSERT') {
    if (change.status !== 'APPLIED') return 'Not resolved safely'
    return redlinePlacementLabel(change.placement, label) ?? 'Placement recorded by Rivell'
  }
  if (change.resolvedTarget) return change.resolvedTarget
  if (approved) return changeTargetLabel(approved, regionLabels, label)
  return failedOverall ? 'Not resolved' : 'Target not recorded'
}

export function failureNote(change: RedlineExecutionChange): string | null {
  return change.failureCode ? redlineFailureTitle(change.failureCode) : null
}

export function pluralise(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`
}

export interface ContextRow {
  label: string
  value: string
}

/** The confirmed review context, from the profile frozen on the Review. */
export function contextRows(profile: ReviewSnapshotProfile | null, packLabel: string | null): ContextRow[] {
  const none = 'Not specified'
  return [
    {
      label: 'Type',
      value: profile?.contractType ? (CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType) : none,
    },
    { label: 'Our side', value: profile?.ourSideRole ? roleLabel(profile.ourSideRole) : none },
    {
      label: 'Paper',
      value: profile?.paperSource ? (PAPER_SOURCE_LABELS[profile.paperSource] ?? profile.paperSource) : none,
    },
    {
      label: 'Jurisdiction',
      value: profile?.jurisdiction ? (JURISDICTION_LABELS[profile.jurisdiction] ?? profile.jurisdiction) : none,
    },
    { label: 'Pack', value: packLabel ?? 'None' },
  ]
}

/** The "{role} side" chip. Null when no side was declared. */
export function sideChip(profile: ReviewSnapshotProfile | null): string | null {
  return profile?.ourSideRole ? `${roleLabel(profile.ourSideRole)} side` : null
}

export function typeChip(profile: ReviewSnapshotProfile | null): string | null {
  return profile?.contractType ? (CONTRACT_TYPE_LABELS[profile.contractType] ?? profile.contractType) : null
}
