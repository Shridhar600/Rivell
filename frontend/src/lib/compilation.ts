import type { ArtifactType, Candidate } from '../api/knowledge'
import { humanizeCode } from './domainLabels'
import { isBulkEligible, reviewReasonLabel } from './semantics'

/**
 * The review model behind the Knowledge compilation workspace.
 *
 * Two ideas are kept apart on purpose. A CONDITION explains why a candidate needs attention
 * (a possible conflict, duplicate, supersession, or an uncertain reading). It comes from the
 * server and is never a review outcome. An OUTCOME is what a human decided, and it is the
 * candidate's own review status. Nothing here changes either one. Everything is derived from the
 * candidates the server last returned.
 */

export type Outcome = 'pending' | 'corrected' | 'approved' | 'rejected' | 'published'

export const OUTCOME_LABELS: Record<Outcome, string> = {
  pending: 'To review',
  corrected: 'Corrected',
  approved: 'Approved',
  rejected: 'Rejected',
  published: 'Published',
}

export function outcomeOf(candidate: Candidate): Outcome {
  switch (candidate.reviewStatus) {
    case 'PENDING':
      return 'pending'
    case 'EDITED':
      return 'corrected'
    case 'APPROVED':
      return 'approved'
    case 'REJECTED':
      return 'rejected'
    case 'PUBLISHED':
      return 'published'
  }
}

/** Still waiting for a human decision. A corrected candidate needs its corrected value approved. */
export function isOpen(candidate: Candidate): boolean {
  return candidate.reviewStatus === 'PENDING' || candidate.reviewStatus === 'EDITED'
}

// -------------------------------------------------------------- conditions

export type ConditionKind =
  | 'conflict'
  | 'supersession'
  | 'duplicate'
  | 'uncertain'
  | 'type'
  | 'position'
  | 'scope'
  | 'unavailable'
  | 'other'

export interface Condition {
  kind: ConditionKind
  label: string
  /** What it means and what the reviewer can do. Never a decision. */
  detail: string
}

const CONDITION_ORDER: ConditionKind[] = [
  'conflict',
  'supersession',
  'duplicate',
  'uncertain',
  'type',
  'position',
  'scope',
  'unavailable',
  'other',
]

const REASON_KIND: Record<string, ConditionKind> = {
  CONFLICT_DETECTED: 'conflict',
  SUPERSESSION_PROPOSED: 'supersession',
  DUPLICATE_DETECTED: 'duplicate',
  VERIFICATION_DISPUTED: 'uncertain',
  UNVERIFIED_GENERATIVE_FIELD: 'uncertain',
  LOW_CLASSIFICATION_CONFIDENCE: 'uncertain',
  ARTIFACT_TYPE_MISMATCH: 'type',
  NEW_PROHIBITED_OR_ESCALATION_POSITION: 'position',
  SCOPE_NARROWING_PROPOSED: 'scope',
  SEMANTIC_ANALYSIS_UNAVAILABLE: 'unavailable',
  SEMANTIC_COMPILATION_UNAVAILABLE: 'unavailable',
}

const KIND_COPY: Record<Exclude<ConditionKind, 'other'>, { label: string; detail: string }> = {
  conflict: {
    label: 'Conflicts with published knowledge',
    detail:
      'This may contradict published knowledge on the same topic and scope. Compare the two. Then correct it, reject it, or approve it knowingly.',
  },
  supersession: {
    label: 'Supersession proposed',
    detail:
      'This entry appears to replace published knowledge. Nothing is replaced unless you choose it when you publish, and earlier versions stay as they were.',
  },
  duplicate: {
    label: 'Possible duplicate',
    detail:
      'Published knowledge already holds the same content and scope. A duplicate is not rejected for you. Decide whether to publish it again.',
  },
  uncertain: {
    label: 'Uncertain interpretation',
    detail: 'Rivell could not fully support this reading from the source. Check the source before you approve.',
  },
  type: {
    label: 'Source type may not match',
    detail: 'The source may not be the type you chose. Rivell still treats it as the type you chose.',
  },
  position: {
    label: 'New prohibited or escalation position',
    detail: 'This proposes a prohibited or escalation position, so it needs a closer look before it is published.',
  },
  scope: {
    label: 'Scope narrowed by the source',
    detail: 'The source narrows where this applies, beyond what you declared for the file.',
  },
  unavailable: {
    label: 'Some checks could not run',
    detail: 'Only basic reading was done for this entry. Read it closely.',
  },
}

/**
 * Why this candidate needs attention, most serious first, from the server's own review reasons.
 * A reason the server adds later still appears, under its own name, instead of being dropped.
 */
export function conditionsOf(candidate: Candidate): Condition[] {
  const found = new Map<ConditionKind, string[]>()
  const note = (kind: ConditionKind, reason: string) => {
    const list = found.get(kind)
    if (list) list.push(reason)
    else found.set(kind, [reason])
  }
  for (const reason of candidate.reviewReasons) note(REASON_KIND[reason] ?? 'other', reason)
  // A signal with no matching reason still counts, so a conflict can never be hidden by a missing reason.
  for (const signal of candidate.conflictSignals) {
    if (signal.conflictType === 'CONTRADICTS_EXISTING' || signal.conflictType === 'AUTHORITY_CONFLICT') {
      if (!found.has('conflict')) note('conflict', 'CONFLICT_DETECTED')
    } else if (signal.conflictType === 'SUPERSEDES_EXISTING') {
      if (!found.has('supersession')) note('supersession', 'SUPERSESSION_PROPOSED')
    } else if (signal.conflictType === 'DUPLICATE') {
      if (!found.has('duplicate')) note('duplicate', 'DUPLICATE_DETECTED')
    }
  }
  return CONDITION_ORDER.flatMap((kind): Condition[] => {
    const reasons = found.get(kind)
    if (!reasons) return []
    if (kind === 'other') {
      return reasons.map((reason) => ({ kind, label: humanizeCode(reason), detail: reviewReasonLabel(reason) }))
    }
    const copy = KIND_COPY[kind]
    return [{ kind, label: copy.label, detail: copy.detail }]
  })
}

export function primaryCondition(candidate: Candidate): Condition | null {
  return conditionsOf(candidate)[0] ?? null
}

/** The server's own wording for each underlying reason, for the reviewer who wants the detail. */
export function reasonLines(candidate: Candidate): string[] {
  return candidate.reviewReasons.map(reviewReasonLabel)
}

/**
 * The server does not stop a conflicted candidate from being approved, because its signals are
 * advisory. The workspace still asks for a deliberate acknowledgement first, so one click can never
 * make a possible contradiction publishable.
 */
export function needsConflictAcknowledgement(candidate: Candidate): boolean {
  return conditionsOf(candidate).some((condition) => condition.kind === 'conflict')
}

// ------------------------------------------------------------------ groups

export interface ReviewGroups {
  /** Open candidates with at least one condition. */
  attention: Candidate[]
  /** Open candidates with no condition that are reviewed one at a time. */
  toReview: Candidate[]
  /** The safe group: exactly the candidates the server would accept for bulk approval. */
  bulk: Candidate[]
  /** Approved or rejected by a human, not yet published. */
  reviewed: Candidate[]
  /** Already part of a publication. Immutable. */
  published: Candidate[]
}

/**
 * Sorts a compilation into the rail's groups. The grouped-review set is the server's own
 * eligibility rule (pending and routine), and nothing is added to it. A reviewer may take a
 * candidate out of the group to review it alone. A group of one is not a group.
 */
export function groupCandidates(candidates: Candidate[], solo: ReadonlySet<string> = new Set()): ReviewGroups {
  const eligible = candidates.filter((candidate) => isBulkEligible(candidate) && !solo.has(candidate.id))
  const bulk = eligible.length >= 2 ? eligible : []
  const inBulk = new Set(bulk.map((candidate) => candidate.id))
  const groups: ReviewGroups = { attention: [], toReview: [], bulk, reviewed: [], published: [] }
  for (const candidate of candidates) {
    if (candidate.reviewStatus === 'PUBLISHED') groups.published.push(candidate)
    else if (candidate.reviewStatus === 'APPROVED' || candidate.reviewStatus === 'REJECTED') groups.reviewed.push(candidate)
    else if (inBulk.has(candidate.id)) continue
    else if (conditionsOf(candidate).length > 0) groups.attention.push(candidate)
    else groups.toReview.push(candidate)
  }
  return groups
}

// --------------------------------------------------------------- readiness

export interface Readiness {
  total: number
  /** Pending, or corrected and not yet approved. */
  awaiting: number
  approved: number
  rejected: number
  published: number
  /** Awaiting candidates that carry a condition. */
  attention: number
  /** Whether the next publication could contain something and nothing is left undecided. */
  canPublish: boolean
  /** Why not, when it cannot. */
  blocker: string | null
}

/**
 * What the server's last answer says about publishing. It is computed from candidate statuses the
 * server returned, never from what is on screen. The publish request itself is still checked by
 * the server, and anything it refuses is shown.
 */
export function readiness(candidates: Candidate[]): Readiness {
  let awaiting = 0
  let approved = 0
  let rejected = 0
  let published = 0
  let attention = 0
  for (const candidate of candidates) {
    if (candidate.reviewStatus === 'APPROVED') approved += 1
    else if (candidate.reviewStatus === 'REJECTED') rejected += 1
    else if (candidate.reviewStatus === 'PUBLISHED') published += 1
    else {
      awaiting += 1
      if (conditionsOf(candidate).length > 0) attention += 1
    }
  }
  const total = candidates.length
  let blocker: string | null = null
  if (total === 0) blocker = 'No entries were produced from this source.'
  else if (awaiting > 0) blocker = `${awaiting} ${awaiting === 1 ? 'entry is' : 'entries are'} still to review.`
  else if (approved === 0 && published > 0) blocker = 'Everything approved here is already published.'
  else if (approved === 0) blocker = 'Every entry was rejected, so there is nothing to publish.'
  return { total, awaiting, approved, rejected, published, attention, canPublish: blocker === null, blocker }
}

/** A human has decided it, so it counts toward "reviewed". */
export function reviewedCount(candidates: Candidate[]): number {
  return candidates.filter((candidate) => !isOpen(candidate)).length
}

/** Fully resolved and published: nothing left to decide or publish, and something went out. */
export function isPublishedCompilation(candidates: Candidate[]): boolean {
  const state = readiness(candidates)
  return state.published > 0 && state.awaiting === 0 && state.approved === 0
}

export type NextTarget = { kind: 'candidate'; id: string } | { kind: 'bulk' } | null

/** Where the reviewer should go after deciding one: attention first, then the next to review, then the group. */
export function nextTarget(candidates: Candidate[], after: string, solo: ReadonlySet<string> = new Set()): NextTarget {
  const groups = groupCandidates(candidates, solo)
  const open = [...groups.attention, ...groups.toReview].filter((candidate) => candidate.id !== after)
  const next = open.find((candidate) => groups.attention.includes(candidate)) ?? open[0]
  if (next) return { kind: 'candidate', id: next.id }
  if (groups.bulk.length > 0) return { kind: 'bulk' }
  return null
}

// ----------------------------------------------------------- presentation

export const ARTIFACT_KIND_LABELS: Partial<Record<ArtifactType, string>> = {
  PLAYBOOK: 'Playbook',
  CLAUSE_LIBRARY: 'Clause library',
}

export function kindLabel(type: ArtifactType): string {
  return ARTIFACT_KIND_LABELS[type] ?? humanizeCode(type)
}

/** Where "Knowledge" leads back to: the list this kind of knowledge lives in. */
export function backTarget(type: ArtifactType | null): string {
  if (type === 'CLAUSE_LIBRARY') return '/knowledge/clauses'
  if (type === 'PLAYBOOK') return '/knowledge/playbooks'
  return '/knowledge'
}

/** Where a source's published knowledge is read. A playbook has its own page; clauses live in the one library. */
export function publishedDestination(artifact: { id: string; artifactType: ArtifactType }): string {
  return artifact.artifactType === 'CLAUSE_LIBRARY' ? '/knowledge/clauses' : `/knowledge/playbooks/${artifact.id}`
}

