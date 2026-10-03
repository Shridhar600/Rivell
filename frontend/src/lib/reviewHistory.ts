import type { ReviewListItem } from '../api/reviews'
import { rowDate } from './dates'
import { mapReviewStatus } from './home'
import { reviewFailureMessage } from './reviews'

/**
 * One row of the Reviews table, from `ReviewListItemView` alone. Every phrase is backed by a field
 * on that view. The list carries no decision progress, finalization, redline outcome or declared
 * review context, so nothing here claims them: see "Backend gaps" in the F2.1 report.
 */

/** Pill colour, following the canonical `.stb` variants. */
export type StatusVariant = 'plain' | 'ok' | 'red'

export interface ReviewRowView {
  id: string
  /** The existing Review route. Temporary until the review-centric routes arrive. */
  to: string
  title: string
  versionChip: string | null
  stateLabel: string
  variant: StatusVariant
  /** Only real in-flight analysis shows the glyph, as in the canonical table. */
  analyzing: boolean
  /** Second line under the title. */
  detail: string
  /** Progress column. A dash where the list proves nothing. */
  progress: string
  createdLabel: string
  running: boolean
}

function plural(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/**
 * Canonical row says "Supplier side, counterparty paper, England and Wales". The list read has
 * none of that, so this line states the analysis checks instead, which it does have.
 */
function detailFor(review: ReviewListItem): string {
  switch (review.status) {
    case 'FAILED':
      return reviewFailureMessage(review.failure)
    case 'ANALYSIS_QUEUED':
      return plural(review.plannedChecks, 'check planned', 'checks planned')
    default: {
      const parts = [`${review.completedChecks} of ${plural(review.plannedChecks, 'check', 'checks')} complete`]
      if (review.failedChecks > 0) parts.push(`${review.failedChecks} failed`)
      if (review.degradedChecks > 0) parts.push(`${review.degradedChecks} degraded`)
      return parts.join(' · ')
    }
  }
}

export function reviewRowView(review: ReviewListItem): ReviewRowView {
  const state = mapReviewStatus(review.status)
  const completed = review.status === 'COMPLETED'
  return {
    id: review.id,
    to: `/reviews/${review.contractId}/analysis/${review.id}`,
    title: review.contractFilename?.trim() || 'Untitled contract',
    versionChip: review.contractVersionNumber === null ? null : `v${review.contractVersionNumber}`,
    stateLabel: state.label,
    variant: state.group === 'failed' ? 'red' : completed ? 'ok' : 'plain',
    analyzing: review.status === 'ANALYZING',
    detail: detailFor(review),
    progress: completed ? plural(review.findings, 'Finding', 'Findings') : '—',
    createdLabel: rowDate(review.createdAt),
    running: state.group === 'running',
  }
}

/** Pages can overlap if a review is created between two reads, so a row is never listed twice. */
export function mergeReviewPages(pages: readonly { items: readonly ReviewListItem[] }[]): ReviewListItem[] {
  const seen = new Set<string>()
  return pages.flatMap((page) => page.items).filter((review) => !seen.has(review.id) && seen.add(review.id))
}
