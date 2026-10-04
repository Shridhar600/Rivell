import type { ReviewListItem } from '../api/reviews'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  roleLabel,
} from './contractSemantics'
import { rowDate } from './dates'
import { mapReviewStatus } from './home'

/**
 * One row of the Reviews table, from `ReviewListItemView` alone. Every phrase is backed by a field
 * on that view: the lifecycle, decision progress, finalization and redline outcome are the
 * server's own facts, and readiness is never computed here. The vocabulary is the canonical
 * `ST` table of `rivell-reviews-v1.html`.
 */

/** Pill colour, following the canonical `.stb` variants. */
export type StatusVariant = 'plain' | 'ok' | 'am' | 'red' | 'dk'

/** `spin` is real in-flight analysis only. `lock` marks a frozen (finalized) review. */
export type StatusGlyphKind = 'none' | 'spin' | 'lock'

/** The canonical filter groups. A review that fits none is shown under All only. */
export type ReviewGroup = 'needs' | 'running' | 'done'

export type LifecycleKey =
  | 'queued'
  | 'analyzing'
  | 'failed'
  | 'completed'
  | 'progress'
  | 'blocked'
  | 'ready'
  | 'degraded'
  | 'finalized'
  | 'finalized_zero'
  | 'rl_verified'
  | 'rl_failed'
  | 'analysis_complete'
  | 'unknown'

export interface Lifecycle {
  key: LifecycleKey
  label: string
  variant: StatusVariant
  glyph: StatusGlyphKind
  group: ReviewGroup | null
}

const LIFECYCLE: Record<Exclude<LifecycleKey, 'unknown'>, Omit<Lifecycle, 'key'>> = {
  queued: { label: 'Queued', variant: 'plain', glyph: 'none', group: 'running' },
  analyzing: { label: 'Analyzing', variant: 'plain', glyph: 'spin', group: 'running' },
  failed: { label: 'Analysis failed', variant: 'red', glyph: 'none', group: 'needs' },
  completed: { label: 'Ready for review', variant: 'am', glyph: 'none', group: 'needs' },
  progress: { label: 'In review', variant: 'ok', glyph: 'none', group: 'needs' },
  blocked: { label: 'Escalation blocking', variant: 'am', glyph: 'none', group: 'needs' },
  ready: { label: 'Ready to finalize', variant: 'ok', glyph: 'none', group: 'needs' },
  // Not in the canonical table: a real domain state it has no word for. The analysis finished but
  // no planned check completed, so there is nothing to review and the server blocks finalizing.
  degraded: { label: 'Analysis degraded', variant: 'am', glyph: 'none', group: 'needs' },
  finalized: { label: 'Finalized', variant: 'dk', glyph: 'lock', group: 'done' },
  finalized_zero: { label: 'Review completed', variant: 'dk', glyph: 'lock', group: 'done' },
  rl_verified: { label: 'Verified redline', variant: 'ok', glyph: 'none', group: 'done' },
  rl_failed: { label: 'Redline failed', variant: 'red', glyph: 'none', group: 'needs' },
  // The server sent no human-review state, so no human attention is claimed either way.
  analysis_complete: { label: 'Analysis complete', variant: 'plain', glyph: 'none', group: null },
}

function lifecycleOf(key: LifecycleKey, review: ReviewListItem): Lifecycle {
  if (key === 'unknown') {
    return { key, label: mapReviewStatus(review.status).label, variant: 'plain', glyph: 'none', group: null }
  }
  return { key, ...LIFECYCLE[key] }
}

/**
 * Where a review is in its life, from what the server reports and in this order: analysis, then
 * finalization and redline, then human review. Human attention is classified only for a review
 * whose analysis completed. `ANALYSIS_NOT_COMPLETE` among the blockers of a queued or analyzing
 * review means the machine has not finished, so it never makes the review "need you".
 */
export function reviewLifecycle(review: ReviewListItem): Lifecycle {
  switch (review.status) {
    case 'ANALYSIS_QUEUED':
      return lifecycleOf('queued', review)
    case 'ANALYZING':
      return lifecycleOf('analyzing', review)
    case 'FAILED':
      return lifecycleOf('failed', review)
    case 'COMPLETED':
      break
    default:
      return lifecycleOf('unknown', review)
  }

  const { finalization, redline, humanReview } = review
  if (finalization) {
    if (redline?.status === 'FAILED') return lifecycleOf('rl_failed', review)
    if (
      redline?.status === 'COMPLETED' &&
      redline.outcome === 'REDLINE_GENERATED' &&
      redline.downloadAvailable
    ) {
      return lifecycleOf('rl_verified', review)
    }
    return lifecycleOf(finalization.approvedChanges === 0 ? 'finalized_zero' : 'finalized', review)
  }

  if (!humanReview || humanReview.blockers.includes('ANALYSIS_NOT_COMPLETE')) {
    return lifecycleOf('analysis_complete', review)
  }
  if (humanReview.blockers.includes('ANALYSIS_DEGRADED')) return lifecycleOf('degraded', review)
  if (humanReview.blockers.includes('ACTIVE_ESCALATION')) return lifecycleOf('blocked', review)
  if (humanReview.readyToFinalize) return lifecycleOf('ready', review)
  if (humanReview.status === 'NOT_STARTED') return lifecycleOf('completed', review)
  return lifecycleOf('progress', review)
}

export interface ReviewRowView {
  id: string
  /** The existing Review route. Temporary until the review-centric routes arrive. */
  to: string
  title: string
  versionChip: string | null
  typeChip: string | null
  lifecycle: Lifecycle
  /** Second line under the title: the declared review context, or empty when none was declared. */
  detail: string
  /** Progress column. A dash where the list proves nothing. */
  progress: string
  uploadedLabel: string
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/**
 * "Customer side, counterparty paper, United Kingdom". Only what the reviewer declared: a missing
 * side or paper source is left out, and an undeclared jurisdiction is not named.
 */
function contextLine(review: ReviewListItem): string {
  const context = review.context
  if (!context) return ''
  const parts: string[] = []
  if (context.ourSideRole) parts.push(`${roleLabel(context.ourSideRole)} side`)
  if (context.paperSource) {
    parts.push((PAPER_SOURCE_LABELS[context.paperSource] ?? context.paperSource).toLowerCase())
  }
  if (context.jurisdiction && context.jurisdiction !== 'UNSPECIFIED') {
    parts.push(JURISDICTION_LABELS[context.jurisdiction] ?? context.jurisdiction)
  }
  return parts.join(', ')
}

/** The canonical `prog()`, from the server's own counts. */
function progressFor(review: ReviewListItem, lifecycle: Lifecycle): string {
  switch (lifecycle.key) {
    case 'queued':
    case 'analyzing':
    case 'failed':
    case 'degraded':
    case 'unknown':
      return '—'
    case 'progress':
    case 'blocked': {
      const human = review.humanReview
      return human ? `${human.resolvedFindings} of ${human.actionableFindings} decided` : '—'
    }
    case 'finalized_zero':
      return 'No approved changes'
    case 'finalized':
      return plural(review.finalization?.approvedChanges ?? 0, 'approved change', 'approved changes')
    case 'rl_verified': {
      const count = review.redline?.verifiedChangeCount
      return count === null || count === undefined ? 'Verified' : plural(count, 'change verified', 'changes verified')
    }
    case 'rl_failed':
      return 'Execution failed'
    default:
      return plural(review.findings, 'Finding', 'Findings')
  }
}

export function reviewRowView(review: ReviewListItem): ReviewRowView {
  const lifecycle = reviewLifecycle(review)
  const contractType = review.context?.contractType
  return {
    id: review.id,
    to: `/reviews/${review.contractId}/overview/${review.id}`,
    title: review.contractFilename?.trim() || 'Untitled contract',
    versionChip: review.contractVersionNumber === null ? null : `v${review.contractVersionNumber}`,
    typeChip: contractType ? (CONTRACT_TYPE_LABELS[contractType] ?? contractType) : null,
    lifecycle,
    detail: contextLine(review),
    progress: progressFor(review, lifecycle),
    uploadedLabel: review.uploadedAt ? rowDate(review.uploadedAt) : '—',
  }
}

/** Pages can overlap if a review is created between two reads, so a row is never listed twice. */
export function mergeReviewPages(pages: readonly { items: readonly ReviewListItem[] }[]): ReviewListItem[] {
  const seen = new Set<string>()
  return pages.flatMap((page) => page.items).filter((review) => !seen.has(review.id) && seen.add(review.id))
}
