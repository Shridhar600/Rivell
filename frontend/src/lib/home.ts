import type { ReviewListItem } from '../api/reviews'
import { shortDate } from './dates'
import { reviewRowView, type Lifecycle, type LifecycleKey } from './reviewHistory'

/**
 * Home is the Review work queue. It answers four questions: what needs me, what is running, what
 * finished, and where I left off. Knowledge has its own area, so nothing about Knowledge appears here.
 *
 * Every Review is placed by `reviewLifecycle` (through `reviewRowView`), the same function the
 * Reviews page uses, so Home and Reviews can never disagree. Readiness and human attention are the
 * server's facts. Nothing here computes them.
 */

/** Home's three sections. They are the Reviews page's `needs`, `running` and `done` groups. */
export type HomeSectionKey = 'needs' | 'running' | 'done'

export const HOME_SECTION_LABELS: Record<HomeSectionKey, string> = {
  needs: 'Needs you',
  running: 'Running',
  done: 'Recently completed',
}

/** Result rows shown on Home. The Reviews page keeps the full history. */
export const RECENT_RESULTS_LIMIT = 5

export type HomeTone = 'active' | 'attention' | 'danger' | 'neutral' | 'complete'

export interface HomeDetail {
  label: string
  value: string
}

export interface HomeReview {
  id: string
  title: string
  /** Version and progress, for the row's second line. */
  meta: string | null
  /** Decision or result progress where the list proves one, such as "5 of 8 decided". */
  progress: string | null
  chip: string | null
  stateLabel: string
  tone: HomeTone
  /** Null for a Review whose state the server did not describe. Home claims nothing about it. */
  section: HomeSectionKey | null
  lifecycle: LifecycleKey
  /** Waiting for analysis to start, as opposed to analyzing. */
  queued: boolean
  /** Short, honest date for the row's time column. Never a relative guess. */
  timeLabel: string
  /** The raw API timestamp behind `timeLabel`, for a full date in the inspector. */
  timeValue: string
  /** The time is when the Review was added, or when something last happened to it. */
  timeLabelKind: 'added' | 'updated'
  /** The Review's front door. The workspace is reached from there. */
  to: string
  primaryActionLabel: string
  guidance: string | null
  details: HomeDetail[]
}

const FAILURE_KEYS: readonly LifecycleKey[] = ['failed', 'rl_failed']

function toneOf(lifecycle: Lifecycle): HomeTone {
  if (FAILURE_KEYS.includes(lifecycle.key)) return 'danger'
  if (lifecycle.group === 'needs') return 'attention'
  if (lifecycle.group === 'running') return 'active'
  if (lifecycle.group === 'done') return 'complete'
  return 'neutral'
}

const GUIDANCE: Partial<Record<LifecycleKey, string>> = {
  analyzing: 'You can leave this page. Analysis keeps running.',
  failed: 'The contract is unchanged. Open the review to see what stopped it.',
  completed: "No decision yet. Your decisions are kept separate from Rivell's recommendations.",
  progress: 'Open the review to continue deciding.',
  blocked: 'An escalated finding blocks finalizing until it is resolved.',
  ready: 'Every decision is made. Finalizing is yours to do.',
  degraded: 'No check completed, so there is nothing to finalize.',
  finalized: 'Finalized. The decisions cannot be changed.',
  finalized_zero: 'Finalized with no approved changes, so no redline was needed.',
  rl_verified: 'Built from your final decisions and checked.',
  rl_failed: 'The redline could not be produced. Your decisions are unchanged.',
}

/** After this long, a waiting analysis is no longer ordinary queueing. */
export const WAITING_LONG_MS = 10 * 60 * 1000

/** How long an analysis has waited, in words. Null when the timestamp cannot be read. */
export function waitingText(createdAt: string, now = Date.now()): string | null {
  const started = Date.parse(createdAt)
  if (!Number.isFinite(started)) return null
  const minutes = Math.max(0, Math.floor((now - started) / 60000))
  if (minutes < 1) return 'Waiting under 1 min'
  if (minutes < 60) return `Waiting ${minutes} min`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `Waiting ${hours} hr${hours === 1 ? '' : 's'}`
  const days = Math.floor(hours / 24)
  return `Waiting ${days} day${days === 1 ? '' : 's'}`
}

export function isWaitingLong(createdAt: string, now = Date.now()): boolean {
  const started = Date.parse(createdAt)
  return Number.isFinite(started) && now - started >= WAITING_LONG_MS
}

/** The waiting state's own line: how long it has waited, then the retry promise after ten minutes. */
export function queuedGuidance(createdAt: string, now = Date.now()): string {
  const waited = waitingText(createdAt, now)
  const line = `${waited ? `${waited}. ` : ''}You can leave this page. Analysis starts on its own.`
  return isWaitingLong(createdAt, now)
    ? `${line} Taking longer than usual. Rivell keeps retrying.`
    : line
}

/** When something last happened to the Review, by the most specific fact the server gives. */
function timeOf(review: ReviewListItem): { value: string; kind: 'added' | 'updated' } {
  const updated =
    review.redline?.completedAt ?? review.finalization?.finalizedAt ?? review.completedAt ?? review.startedAt
  return updated ? { value: updated, kind: 'updated' } : { value: review.createdAt, kind: 'added' }
}

function detailsOf(review: ReviewListItem, lifecycle: Lifecycle, typeLabel: string | null): HomeDetail[] {
  const details: HomeDetail[] = []
  if (review.contractVersionNumber !== null) {
    details.push({ label: 'Contract version', value: `v${review.contractVersionNumber}` })
  }
  if (typeLabel) details.push({ label: 'Agreement', value: typeLabel })
  const human = review.humanReview
  // A finding count means nothing until analysis has finished: before that, zero is "not yet".
  const analysed = lifecycle.group === 'needs' || lifecycle.group === 'done'
  if (analysed && lifecycle.key !== 'failed') {
    details.push({ label: 'Findings', value: review.findings === 0 ? 'None' : String(review.findings) })
  }
  if (human && (lifecycle.key === 'progress' || lifecycle.key === 'blocked' || lifecycle.key === 'ready')) {
    details.push({ label: 'Decided', value: `${human.resolvedFindings} of ${human.actionableFindings}` })
    if (human.escalated > 0) details.push({ label: 'Escalated', value: String(human.escalated) })
  }
  if (review.finalization) {
    details.push({ label: 'Approved changes', value: String(review.finalization.approvedChanges) })
  }
  const verified = review.redline?.verifiedChangeCount
  if (lifecycle.key === 'rl_verified' && verified !== null && verified !== undefined) {
    details.push({ label: 'Verified changes', value: String(verified) })
  }
  return details
}

export function homeReview(review: ReviewListItem, now = Date.now()): HomeReview {
  const row = reviewRowView(review)
  const { lifecycle } = row
  const time = timeOf(review)
  // The row's second line is the real progress where the list proves one, and the version otherwise.
  const progress = row.progress === '—' ? null : row.progress
  const meta = [row.versionChip, progress].filter(Boolean).join(' · ')
  const guidance =
    lifecycle.key === 'queued' ? queuedGuidance(review.createdAt, now) : (GUIDANCE[lifecycle.key] ?? null)
  return {
    id: review.id,
    title: row.title,
    meta: meta || null,
    progress,
    chip: row.typeChip,
    stateLabel: lifecycle.label,
    tone: toneOf(lifecycle),
    section: lifecycle.group,
    lifecycle: lifecycle.key,
    queued: lifecycle.key === 'queued',
    timeLabel: shortDate(time.value),
    timeValue: time.value,
    timeLabelKind: time.kind,
    to: row.to,
    primaryActionLabel: 'Open review',
    guidance,
    details: detailsOf(review, lifecycle, row.typeChip),
  }
}

export interface HomeSections {
  needs: HomeReview[]
  running: HomeReview[]
  /** Newest first, capped. `doneTotal` is how many finished Reviews were loaded. */
  done: HomeReview[]
  doneTotal: number
  /** Loaded Reviews the server did not describe, which Home cannot place. They remain on Reviews. */
  unplaced: number
}

/**
 * Splits loaded Reviews into sections. A failure sorts to the top of Needs you, so it is never
 * buried under routine work. Results are ordered by when they finished.
 */
export function buildHomeSections(reviews: readonly ReviewListItem[]): HomeSections {
  const sections: HomeSections = { needs: [], running: [], done: [], doneTotal: 0, unplaced: 0 }
  const finished: { view: HomeReview; at: number }[] = []
  for (const review of reviews) {
    const view = homeReview(review)
    if (view.section === 'needs') sections.needs.push(view)
    else if (view.section === 'running') sections.running.push(view)
    else if (view.section === 'done') finished.push({ view, at: Date.parse(view.timeValue) || 0 })
    else sections.unplaced += 1
  }
  sections.needs.sort((left, right) => Number(right.tone === 'danger') - Number(left.tone === 'danger'))
  finished.sort((left, right) => right.at - left.at)
  sections.doneTotal = finished.length
  sections.done = finished.slice(0, RECENT_RESULTS_LIMIT).map((entry) => entry.view)
  return sections
}
