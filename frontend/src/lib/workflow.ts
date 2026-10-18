import type {
  ChangeActionType,
  ChangeLanguageSource,
  DecisionAction,
  HumanDecision,
  HumanReview,
  HumanReviewStatus,
} from '../api/workflow'
import type { ConceptLabel } from '../api/taxonomy'

export function decisionActionLabel(action: DecisionAction): string {
  switch (action) {
    case 'ACCEPT':
      return 'Accept recommendation'
    case 'EDIT':
      return 'Edit change'
    case 'REJECT':
      return 'Reject recommendation'
    case 'ESCALATE':
      return 'Escalate for legal review'
    default:
      return 'Unknown action'
  }
}

export function decisionStateLabel(decision: HumanDecision | null | undefined): string {
  if (!decision) return 'No decision'
  switch (decision.action) {
    case 'ACCEPT':
      return 'Accepted'
    case 'EDIT':
      return 'Edited'
    case 'REJECT':
      return 'Rejected'
    case 'ESCALATE':
      return 'Escalated (unresolved)'
    default:
      return 'Decision recorded'
  }
}

export function humanStatusLabel(status: HumanReviewStatus): string {
  switch (status) {
    case 'NOT_STARTED':
      return 'Review not started'
    case 'IN_PROGRESS':
      return 'Review in progress'
    case 'READY_FOR_COMPLETION':
      return 'Ready to finalize'
    case 'COMPLETED':
      return 'Review completed'
    default:
      return 'Review state unknown'
  }
}

// `blocker.message` is rendered verbatim by HumanReviewProgress, and is exempt from the rule the
// API layer follows for the same reason as `AnalysisWarning.message` and `ReviewFailure.message`:
// the backend builds every blocker message from a fixed literal plus an integer count, so it carries
// no internals. Keep this in step with the note in `lib/reviews.ts` if that ever changes.
const BLOCKER_LABELS: Record<string, string> = {
  ANALYSIS_NOT_COMPLETE: 'The automated analysis is not complete.',
  ANALYSIS_DEGRADED: 'The analysis produced no trustworthy result.',
  UNRESOLVED_BLOCKING_FINDINGS: 'Blocking findings still need a decision.',
  UNRESOLVED_REVIEW_REQUIRED_FINDINGS:
    'Findings that require review still need a decision.',
  ACTIVE_ESCALATION: 'A finding is escalated for legal review and must be resolved.',
  APPROVED_CHANGE_CONFLICT:
    'Some approved changes target the same contract location and must be resolved before finalization.',
  MISSING_APPROVED_LANGUAGE: 'An approved change is missing its final language.',
  INVALID_SOURCE_TARGET:
    'An approved change targets a clause that is missing from the reviewed contract version.',
}

export function blockerLabel(code: string): string {
  return BLOCKER_LABELS[code] ?? 'The review cannot be finalized yet.'
}

export function changeActionLabel(actionType: ChangeActionType | null | undefined): string {
  switch (actionType) {
    case 'REPLACE':
      return 'Replace clause'
    case 'INSERT':
      return 'Insert provision'
    case 'DELETE':
      return 'Delete clause'
    default:
      return 'Contract change'
  }
}

export function changeLanguageProvenanceLabel(
  source: ChangeLanguageSource | null | undefined,
): string {
  switch (source) {
    case 'APPROVED_DRAFTING':
      return 'Approved organizational language'
    case 'GENERATIVE':
      return 'Proposed by Rivell, approved by you'
    case 'HUMAN_EDIT':
      return 'Written by you'
    case 'NONE':
      return 'No replacement text'
    default:
      return 'Language source unavailable'
  }
}

const EXECUTABLE_PROPOSED_ACTIONS = new Set([
  'USE_APPROVED_FALLBACK',
  'CONSIDER_GENERATED_LANGUAGE',
])

export type AcceptEligibility = { eligible: true } | { eligible: false; reason: string }

export function acceptEligibility(finding: {
  proposedAction: string
  proposedLanguage: string | null
}): AcceptEligibility {
  if (!EXECUTABLE_PROPOSED_ACTIONS.has(finding.proposedAction)) {
    return {
      eligible: false,
      reason:
        'This recommendation cannot be accepted as-is. Use Edit to author the change, or Reject or Escalate it.',
    }
  }
  if (!finding.proposedLanguage || finding.proposedLanguage.trim().length === 0) {
    return {
      eligible: false,
      reason:
        'This recommendation has no wording to apply. Use Edit to author the change, or Reject or Escalate it.',
    }
  }
  return { eligible: true }
}

export function editActionsForSubject(subjectKind: string): ChangeActionType[] {
  if (subjectKind === 'CLAUSE') return ['REPLACE', 'DELETE']
  if (subjectKind === 'ABSENCE') return ['INSERT']
  return []
}

export function defaultEditAction(subjectKind: string): ChangeActionType {
  return subjectKind === 'ABSENCE' ? 'INSERT' : 'REPLACE'
}

export function editLanguageRequired(action: ChangeActionType | undefined): boolean {
  return action === 'REPLACE' || action === 'INSERT'
}

export type DecisionFormValues = {
  action: DecisionAction
  approvedAction?: ChangeActionType
  approvedLanguage?: string
  comment?: string
}

export type DecisionFormErrors = {
  comment?: string
  approvedLanguage?: string
}

export function validateDecisionForm(values: DecisionFormValues): DecisionFormErrors {
  const errors: DecisionFormErrors = {}
  const comment = values.comment ?? ''
  const language = values.approvedLanguage ?? ''
  if ((values.action === 'REJECT' || values.action === 'ESCALATE') && comment.trim() === '') {
    errors.comment =
      values.action === 'REJECT'
        ? 'A comment is required to reject a recommendation.'
        : 'A comment is required to escalate for legal review.'
  } else if (comment.length > 2000) {
    errors.comment = 'Keep the comment under 2000 characters.'
  }
  if (values.action === 'EDIT' && editLanguageRequired(values.approvedAction)) {
    if (language.trim() === '') {
      errors.approvedLanguage = 'Enter the final approved language for this change.'
    } else if (language.length > 20000) {
      errors.approvedLanguage = 'Keep the approved language under 20000 characters.'
    }
  }
  return errors
}

export type DecisionConflictKind =
  | 'STALE'
  | 'FINALIZED'
  | 'ANALYSIS_INCOMPLETE'
  | 'NOT_ACTIONABLE'
  | 'UNKNOWN'

export function classifyDecisionConflict(input: {
  attemptedAction: DecisionAction
  expectedVersion: number
  currentDecision: HumanDecision | null | undefined
  decisionStateKnown: boolean
  humanReview: HumanReview | null | undefined
  analysisComplete: boolean
}): DecisionConflictKind {
  if (input.humanReview?.status === 'COMPLETED') return 'FINALIZED'
  if (
    input.currentDecision &&
    input.currentDecision.revision !== input.expectedVersion
  ) {
    return 'STALE'
  }
  if (!input.decisionStateKnown) return 'UNKNOWN'
  if (!input.analysisComplete) return 'ANALYSIS_INCOMPLETE'
  if (input.attemptedAction === 'ACCEPT') return 'NOT_ACTIONABLE'
  return 'UNKNOWN'
}

const CONFLICT_NOTICES: Record<DecisionConflictKind, string> = {
  STALE:
    'This decision was changed elsewhere while you were working. Your change was not saved; the current decision is shown.',
  FINALIZED:
    'This review was finalized. Decisions are locked and cannot be changed.',
  ANALYSIS_INCOMPLETE:
    'The automated analysis is not complete yet, so decisions cannot be recorded.',
  NOT_ACTIONABLE:
    'The recommendation cannot be accepted as-is. Use Edit to author the change, or Reject or Escalate it.',
  UNKNOWN:
    'Your decision was not saved. The review may have changed; the current state is shown below.',
}

export function decisionConflictNotice(kind: DecisionConflictKind): string {
  return CONFLICT_NOTICES[kind]
}

export function changeSetCounts(
  changes: { actionType: ChangeActionType }[],
): { total: number; replace: number; insert: number; delete: number } {
  let replace = 0
  let insert = 0
  let deleteCount = 0
  for (const change of changes) {
    if (change.actionType === 'REPLACE') replace += 1
    else if (change.actionType === 'INSERT') insert += 1
    else if (change.actionType === 'DELETE') deleteCount += 1
  }
  return { total: changes.length, replace, insert, delete: deleteCount }
}

export function changeTargetLabel(
  change: {
    targetKind: string
    targetHeadingNodeId: string | null
    absenceConcept: string | null
  },
  regionLabels: Map<string, string>,
  label: ConceptLabel,
): string {
  if (change.targetKind === 'ABSENCE') {
    return change.absenceConcept
      ? `Missing provision: ${label(change.absenceConcept)}`
      : 'Missing provision'
  }
  const nodeId = change.targetHeadingNodeId
  return (nodeId ? regionLabels.get(nodeId) : undefined) ?? 'Clause'
}

export function humanReviewCountRows(
  review: HumanReview,
): { label: string; value: number }[] {
  return [
    { label: 'Needing a decision', value: review.actionableFindings },
    { label: 'Decided', value: review.resolvedFindings },
    { label: 'Still open', value: review.remainingFindings },
    { label: 'Accepted', value: review.accepted },
    { label: 'Edited', value: review.edited },
    { label: 'Rejected', value: review.rejected },
    { label: 'Escalated', value: review.escalated },
  ]
}
