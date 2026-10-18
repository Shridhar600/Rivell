import type { ReviewSummary } from '../api/reviews'
import type { RedlineExecution } from '../api/redline'
import type { ApprovedChangeSet, HumanReview } from '../api/workflow'
import { redlineVerificationOutcome } from './redline'

/**
 * Where a Review is in its life, written in the canonical vocabulary of `rivell-reviews-v1.html`.
 *
 * This is a reading of facts the server already owns, never a second lifecycle: analysis status,
 * human-review progress and readiness, the frozen change set, and the persisted redline execution.
 * `rl_running` is the one exception in spirit, and only in the browser: redline execution is a
 * single synchronous request, so "running" means this page has that request in flight.
 */
export type LifecycleKey =
  | 'queued'
  | 'analyzing'
  | 'failed'
  | 'incomplete'
  | 'degraded'
  | 'completed'
  | 'progress'
  | 'blocked'
  | 'ready'
  | 'finalized'
  | 'finalized_zero'
  | 'rl_running'
  | 'rl_verified'
  | 'rl_failed'
  | 'rl_unverified'
  | 'unknown'

export type StatusVariant = 'neutral' | 'ok' | 'am' | 'red' | 'dk'
export type StatusGlyphKind = 'none' | 'spin' | 'lock'

export interface LifecycleStatus {
  label: string
  variant: StatusVariant
  glyph: StatusGlyphKind
}

/** The canonical `ST` table, plus the three states the canonical file has no word for. */
const STATUS: Record<LifecycleKey, LifecycleStatus> = {
  queued: { label: 'Queued', variant: 'neutral', glyph: 'none' },
  analyzing: { label: 'Analyzing', variant: 'neutral', glyph: 'spin' },
  failed: { label: 'Analysis failed', variant: 'red', glyph: 'none' },
  incomplete: { label: 'Analysis complete', variant: 'neutral', glyph: 'none' },
  degraded: { label: 'Analysis degraded', variant: 'am', glyph: 'none' },
  completed: { label: 'Ready for review', variant: 'am', glyph: 'none' },
  progress: { label: 'In review', variant: 'ok', glyph: 'none' },
  blocked: { label: 'Escalation blocking', variant: 'am', glyph: 'none' },
  ready: { label: 'Ready to finalize', variant: 'ok', glyph: 'none' },
  finalized: { label: 'Finalized', variant: 'dk', glyph: 'lock' },
  finalized_zero: { label: 'Review completed', variant: 'dk', glyph: 'lock' },
  rl_running: { label: 'Generating redline', variant: 'neutral', glyph: 'spin' },
  rl_verified: { label: 'Verified redline', variant: 'ok', glyph: 'none' },
  rl_failed: { label: 'Redline failed', variant: 'red', glyph: 'none' },
  rl_unverified: { label: 'Redline not verified', variant: 'red', glyph: 'none' },
  unknown: { label: 'Status unknown', variant: 'neutral', glyph: 'none' },
}

export function lifecycleStatus(key: LifecycleKey): LifecycleStatus {
  return STATUS[key]
}

export interface LifecycleFacts {
  summary: ReviewSummary
  /** Read once analysis has completed. Null until then, or while the read has not answered. */
  humanReview: HumanReview | null
  /** Read once the review is finalized. Null until then, or while the read has not answered. */
  changeSet: ApprovedChangeSet | null
  /** The persisted execution. Null when none exists yet. */
  redline: RedlineExecution | null
  /** True only while this page's own redline request is in flight. */
  redlineRunning: boolean
}

/**
 * Null means "the facts needed to decide have not arrived yet". The page shows a loading or retry
 * state for that, and never guesses a state in the meantime.
 */
export function lifecycleKey(facts: LifecycleFacts): LifecycleKey | null {
  const { summary, humanReview, changeSet, redline, redlineRunning } = facts
  switch (summary.status) {
    case 'ANALYSIS_QUEUED':
      return 'queued'
    case 'ANALYZING':
      return 'analyzing'
    case 'FAILED':
      return 'failed'
    case 'COMPLETED':
      break
    default:
      return 'unknown'
  }

  if (!humanReview) return null
  if (humanReview.status === 'COMPLETED') {
    if (!changeSet) return null
    if (redlineRunning) return 'rl_running'
    return finalizedKey(changeSet, redline)
  }

  // Machine work comes first: a review the analysis has not finished is never human attention,
  // whatever else the server lists as a blocker.
  const codes = humanReview.blockers.map((blocker) => blocker.code)
  if (codes.includes('ANALYSIS_NOT_COMPLETE')) return 'incomplete'
  if (codes.includes('ANALYSIS_DEGRADED')) return 'degraded'
  if (codes.includes('ACTIVE_ESCALATION')) return 'blocked'
  if (humanReview.readyToFinalize) return 'ready'
  if (humanReview.status === 'NOT_STARTED') return 'completed'
  if (humanReview.status === 'UNKNOWN') return 'unknown'
  return 'progress'
}

function finalizedKey(changeSet: ApprovedChangeSet, redline: RedlineExecution | null): LifecycleKey {
  // An empty frozen change set needs no artifact, whether or not an execution was ever recorded.
  if (changeSet.changes.length === 0) return 'finalized_zero'
  if (!redline) return 'finalized'
  if (redline.status === 'FAILED') return 'rl_failed'
  if (redline.status !== 'COMPLETED') return 'unknown'
  if (redline.outcome === 'NO_CHANGES') return 'finalized_zero'
  // Fail closed: a verified result needs every change applied and verified and an artifact to
  // download. Anything less is never shown as success.
  if (
    redline.outcome === 'REDLINE_GENERATED' &&
    redline.downloadAvailable &&
    redlineVerificationOutcome(redline) === 'PASSED'
  ) {
    return 'rl_verified'
  }
  return 'rl_unverified'
}

/** States that send the reviewer back into the Review Workspace. */
const WORKSPACE_STATES: readonly LifecycleKey[] = ['completed', 'progress', 'blocked', 'ready']

export function opensWorkspace(key: LifecycleKey): boolean {
  return WORKSPACE_STATES.includes(key)
}

/** The states with a Redline / Approved changes / report tab bar. */
const POST_STATES: readonly LifecycleKey[] = [
  'finalized',
  'finalized_zero',
  'rl_running',
  'rl_verified',
  'rl_failed',
  'rl_unverified',
]

export function isPostFinalize(key: LifecycleKey): boolean {
  return POST_STATES.includes(key)
}

export type LifecycleTab = 'redline' | 'changes' | 'report'

export function lifecycleTabs(key: LifecycleKey): { id: LifecycleTab; label: string }[] {
  if (!isPostFinalize(key)) return []
  const tabs: { id: LifecycleTab; label: string }[] = [
    { id: 'redline', label: 'Redline' },
    { id: 'changes', label: 'Approved changes' },
  ]
  // The report exists only once an execution is recorded.
  if (key === 'rl_verified') tabs.push({ id: 'report', label: 'Change report' })
  if (key === 'rl_failed' || key === 'rl_unverified') {
    tabs.push({ id: 'report', label: 'Diagnostic report' })
  }
  return tabs
}

/** An unknown or unavailable tab falls back to Redline, as the canonical hub does. */
export function resolveTab(key: LifecycleKey, requested: string | undefined): LifecycleTab {
  const tabs = lifecycleTabs(key)
  return tabs.find((tab) => tab.id === requested)?.id ?? 'redline'
}

export type JourneyTone = 'i' | 'a' | 'd' | 'w' | 'x'

export interface JourneyStep {
  title: string
  detail: string
  tone: JourneyTone
}

/**
 * The four-step strip above every state: Analysis, Review, Finalize, Redline. A port of the
 * canonical `journey()`. Only the Review step carries a count, and only the server's own.
 */
export function journey(key: LifecycleKey, humanReview: HumanReview | null): JourneyStep[] {
  const steps: JourneyStep[] = [
    { title: 'Analysis', detail: 'Queued', tone: 'i' },
    { title: 'Review', detail: '', tone: 'i' },
    { title: 'Finalize', detail: '', tone: 'i' },
    { title: 'Redline', detail: '', tone: 'i' },
  ]
  const set = (index: number, tone: JourneyTone, detail?: string) => {
    steps[index] = { ...steps[index], tone, detail: detail ?? steps[index].detail }
  }

  if (key === 'queued') set(0, 'a', 'Queued')
  else if (key === 'analyzing') set(0, 'a', 'Analyzing')
  else if (key === 'failed') set(0, 'x', 'Failed')
  else if (key === 'unknown') set(0, 'i', 'Unknown')
  else if (key === 'degraded') set(0, 'w', 'Degraded')
  else {
    set(0, 'd', 'Completed')
    if (key === 'completed') set(1, 'w', 'Ready to start')
    else if (key === 'progress') {
      set(1, 'a', humanReview ? `${humanReview.resolvedFindings} of ${humanReview.actionableFindings} decided` : 'In progress')
    } else if (key === 'blocked') set(1, 'w', 'Escalation unresolved')
    else if (key === 'ready') {
      set(1, 'd', 'Resolved')
      set(2, 'a', 'Ready to finalize')
    } else if (isPostFinalize(key)) {
      set(1, 'd', 'Resolved')
      set(2, 'd', 'Frozen')
      if (key === 'finalized') set(3, 'a', 'Not generated')
      else if (key === 'finalized_zero') set(3, 'd', 'No changes required')
      else if (key === 'rl_running') set(3, 'a', 'Executing')
      else if (key === 'rl_verified') set(3, 'd', 'Verified')
      else if (key === 'rl_unverified') set(3, 'x', 'Not verified')
      else set(3, 'x', 'Failed')
    }
  }
  return steps.map((step) => ({ ...step, detail: step.detail || 'Not started' }))
}

/** What the reviewer sees as the headline of the human-review card. */
export function decidedLine(humanReview: HumanReview): string {
  return `${humanReview.resolvedFindings} of ${humanReview.actionableFindings} Findings decided`
}

export interface ActivityEvent {
  label: string
  time: string
  /** Failure events use the red marker. */
  failed: boolean
}

/**
 * Lifecycle activity for the side rail. Only moments the server recorded a timestamp for appear:
 * there is no event for "human review opened" because nothing stores when that happened.
 */
export function activityEvents(facts: LifecycleFacts): ActivityEvent[] {
  const { summary, humanReview, changeSet, redline } = facts
  const events: ActivityEvent[] = [{ label: 'Review created', time: summary.createdAt, failed: false }]
  if (summary.startedAt) events.push({ label: 'Analysis started', time: summary.startedAt, failed: false })
  if (summary.completedAt) {
    events.push(
      summary.status === 'FAILED'
        ? { label: 'Analysis failed', time: summary.completedAt, failed: true }
        : { label: 'Analysis completed', time: summary.completedAt, failed: false },
    )
  }
  const finalizedAt = changeSet?.finalizedAt ?? humanReview?.finalizedAt ?? null
  if (humanReview?.status === 'COMPLETED' && finalizedAt) {
    events.push({ label: 'Review finalized; approved changes frozen', time: finalizedAt, failed: false })
  }
  if (redline) {
    if (redline.startedAt) events.push({ label: 'Redline execution started', time: redline.startedAt, failed: false })
    if (redline.completedAt) {
      events.push(
        redline.status === 'FAILED'
          ? { label: 'Redline failed', time: redline.completedAt, failed: true }
          : { label: 'Redline completed', time: redline.completedAt, failed: false },
      )
    }
  }
  return events
}

/** The route a "Start another review" action points at. One place, so it moves in one edit. */
export const START_REVIEW_PATH = '/reviews/new'
export const REVIEWS_PATH = '/reviews'

export function workspacePath(contractId: string, reviewId: string): string {
  return `/reviews/${contractId}/analysis/${reviewId}`
}

export function lifecyclePath(contractId: string, reviewId: string, tab?: LifecycleTab): string {
  const base = `/reviews/${contractId}/overview/${reviewId}`
  return tab && tab !== 'redline' ? `${base}/${tab}` : base
}
