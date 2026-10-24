import type { ApprovedChange, ChangeActionType } from '../api/workflow'
import type { RedlineExecution, RedlineExecutionChange, RedlineFailureCode } from '../api/redline'
import type { ConceptLabel } from '../api/taxonomy'
import { changeTargetLabel } from './workflow'

export function redlineExecutionTitle(execution: RedlineExecution): string {
  if (execution.status === 'FAILED') return 'Redline could not be generated'
  if (execution.status === 'UNKNOWN') return 'Redline state unknown'
  switch (execution.outcome) {
    case 'REDLINE_GENERATED':
      return 'Redline generated'
    case 'NO_CHANGES':
      return 'No document changes'
    case 'UNKNOWN':
    case null:
      return 'Redline generated'
    default:
      return 'Redline generated'
  }
}

export function redlineChangeStatusLabel(status: string): string {
  switch (status) {
    case 'APPLIED':
      return 'Applied'
    case 'FAILED':
      return 'Failed'
    case 'NOT_APPLIED':
      return 'Not applied'
    default:
      return 'State unknown'
  }
}

export function redlineVerificationLabel(verification: string | null): string | null {
  if (verification === null) return null
  switch (verification) {
    case 'VERIFIED':
      return 'Verified'
    case 'FAILED':
      return 'Verification failed'
    default:
      return 'Verification state unknown'
  }
}

const FAILURE_TITLES: Record<string, string> = {
  SOURCE_DOCUMENT_UNAVAILABLE:
    'The original document is no longer available, so the redline could not be generated.',
  SOURCE_VERSION_MISMATCH:
    'The original document no longer matches this review’s contract version.',
  SOURCE_INTEGRITY_MISMATCH:
    'The original document failed its integrity check and was not used.',
  UNSUPPORTED_SOURCE_FORMAT:
    'This document format cannot be redlined automatically.',
  SOURCE_EXPANSION_LIMIT_EXCEEDED:
    'This document expands beyond the size Rivell will process, so it was not redlined.',
  UNSUPPORTED_CHANGESET_VERSION:
    'This approved change set version is not supported by Rivell.',
  PROTECTED_DOCUMENT_UNSUPPORTED:
    'The document is protected and cannot be safely redlined automatically.',
  PREEXISTING_REVISIONS_UNSUPPORTED:
    'This document already contains tracked changes and cannot be safely redlined automatically.',
  TARGET_NOT_FOUND:
    'An approved change targets a location that could not be found in the document.',
  TARGET_AMBIGUOUS:
    'An approved change targets a location that is ambiguous in the document.',
  UNSUPPORTED_SOURCE_STRUCTURE:
    'An approved change targets document structure that cannot be safely redlined automatically.',
  CHANGE_OVERLAP:
    'Two approved changes overlap in the document and cannot be applied together.',
  MISSING_APPROVED_LANGUAGE:
    'An approved change is missing its final approved language.',
  INSERT_PLACEMENT_UNRESOLVED:
    'Rivell could not determine a safe insertion location for a missing provision.',
  APPROVED_SEMANTICS_DRIFT:
    'The contract changed after this review was approved, so Rivell placed none of the approved changes. The approved change set is unchanged.',
  DOCX_LOAD_FAILED: 'The original document could not be opened for redlining.',
  DOCX_SERIALIZATION_FAILED: 'The redlined document could not be written.',
  ROUND_TRIP_FAILED: 'The generated document could not be reopened for verification.',
  VERIFICATION_FAILED:
    'The generated document did not match the approved change set during verification.',
}

export function redlineFailureTitle(code: string): string {
  return (
    FAILURE_TITLES[code] ??
    'The redline could not be generated from the approved change set.'
  )
}

/**
 * Retry is offered only for the one recorded failure the frozen inputs do not determine:
 * APPROVED_SEMANTICS_DRIFT, which is raised when the contract's semantics changed after
 * approval. If that later change is reverted, re-executing the same frozen change set can
 * succeed. Every other failure code follows from the frozen inputs, so a rerun would
 * reproduce it and no Retry is offered.
 */
export function redlineRetryAvailable(execution: RedlineExecution): boolean {
  return execution.status === 'FAILED' && execution.failureCode === 'APPROVED_SEMANTICS_DRIFT'
}

export function redlinePlacementLabel(placement: string | null, label: ConceptLabel): string | null {
  if (placement === null) return null
  const afterClause = /^AFTER_CLAUSE\[([^\]]+)\]$/.exec(placement)
  if (afterClause) return `After clause: ${label(afterClause[1])}`
  if (placement === 'BEFORE_SIGNATURE_BLOCK') return 'Before the signature block'
  if (placement === 'END_OF_BODY') return 'At the end of the document'
  return 'Placement recorded by Rivell'
}

export type RedlineVerificationOutcome =
  | 'PASSED'
  | 'INCOMPLETE'
  | 'NOT_APPLICABLE'
  | 'UNKNOWN'

export function redlineVerificationOutcome(
  execution: RedlineExecution,
): RedlineVerificationOutcome {
  if (execution.status === 'UNKNOWN') return 'UNKNOWN'
  if (execution.status === 'FAILED') return 'NOT_APPLICABLE'
  if (execution.outcome === 'NO_CHANGES') return 'NOT_APPLICABLE'
  if (execution.outcome !== 'REDLINE_GENERATED') return 'UNKNOWN'
  if (execution.changes.length === 0) return 'INCOMPLETE'
  if (execution.changes.length !== execution.changeCount) return 'INCOMPLETE'
  const allVerified = execution.changes.every(
    (change) => change.status === 'APPLIED' && change.verification === 'VERIFIED',
  )
  return allVerified ? 'PASSED' : 'INCOMPLETE'
}

export function redlineVerificationSentence(outcome: RedlineVerificationOutcome): string {
  switch (outcome) {
    case 'PASSED':
      return 'Rivell reopened the Word file and checked the tracked changes against the approved changes.'
    case 'INCOMPLETE':
      return 'Not every approved change was verified. Review the per-change report before using this document.'
    case 'NOT_APPLICABLE':
      return 'Verification applies only when approved changes are produced.'
    default:
      return 'The check result for this redline is not known.'
  }
}

function paragraphSummary(change: RedlineExecutionChange): string | null {
  const deleted = change.paragraphsDeleted ?? 0
  const inserted = change.paragraphsInserted ?? 0
  const plural = (count: number, verb: string) =>
    `${count} paragraph${count === 1 ? '' : 's'} ${verb}`
  if (change.actionType === 'REPLACE') {
    if (deleted > 0) return plural(deleted, 'replaced')
    if (inserted > 0) return plural(inserted, 'inserted')
    return null
  }
  if (change.actionType === 'DELETE') {
    return deleted > 0 ? plural(deleted, 'deleted') : null
  }
  if (change.actionType === 'INSERT') {
    return inserted > 0 ? plural(inserted, 'inserted') : null
  }
  if (deleted > 0) return plural(deleted, 'removed')
  if (inserted > 0) return plural(inserted, 'inserted')
  return null
}

function resolvedTargetLabel(
  change: RedlineExecutionChange,
  approved: ApprovedChange | undefined,
  regionLabels: Map<string, string>,
  label: ConceptLabel,
): string {
  if (change.actionType === 'INSERT') {
    if (approved) return changeTargetLabel(approved, regionLabels, label)
    if (change.resolvedTarget) {
      return `Missing provision: ${label(change.resolvedTarget)}`
    }
    return 'Target not recorded'
  }
  if (change.resolvedTarget) return change.resolvedTarget
  if (approved) return changeTargetLabel(approved, regionLabels, label)
  return 'Target not recorded'
}

export type RedlineChangeRow = {
  id: string
  ordinal: number
  actionLabel: string
  targetLabel: string
  statusLabel: string
  verificationLabel: string | null
  placementLabel: string | null
  paragraphSummary: string | null
  failureTitle: string | null
  findingId: string | null
}

export function buildRedlineChangeRows(
  execution: RedlineExecution,
  context: { approvedChanges: ApprovedChange[]; regionLabels: Map<string, string>; label: ConceptLabel },
): RedlineChangeRow[] {
  const approvedById = new Map(context.approvedChanges.map((change) => [change.id, change]))
  return execution.changes.map((change) => {
    const approved = approvedById.get(change.approvedChangeId)
    return {
      id: change.approvedChangeId,
      ordinal: change.ordinal,
      actionLabel: actionLabel(change.actionType),
      targetLabel: resolvedTargetLabel(change, approved, context.regionLabels, context.label),
      statusLabel: redlineChangeStatusLabel(change.status),
      verificationLabel: redlineVerificationLabel(change.verification),
      placementLabel:
        change.actionType === 'INSERT' ? redlinePlacementLabel(change.placement, context.label) : null,
      paragraphSummary: paragraphSummary(change),
      failureTitle: change.failureCode ? redlineFailureTitle(change.failureCode) : null,
      findingId: approved?.findingId ?? null,
    }
  })
}

function actionLabel(actionType: ChangeActionType): string {
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

export type RedlineTriggerErrorKind =
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'FAILED_EXECUTION'
  | 'NOT_AVAILABLE'
  | 'SOURCE_UNAVAILABLE'
  | 'UNCONFIRMED'
  | 'ENGINE_UNAVAILABLE'
  | 'BUSY'
  | 'STORAGE_UNAVAILABLE'
  | 'TRANSPORT'

/** The 409 codes that say the frozen source itself cannot be used. */
const SOURCE_FAILURE_CODES: ReadonlySet<RedlineFailureCode> = new Set([
  'SOURCE_DOCUMENT_UNAVAILABLE',
  'SOURCE_VERSION_MISMATCH',
  'SOURCE_INTEGRITY_MISMATCH',
])

export function classifyTriggerError(input: {
  status: number | null
  execution: RedlineExecution | null
  executionStateKnown: boolean
  /** The typed code from the error body, when the server sent one. */
  failureCode?: RedlineFailureCode | null
  /** A 503 carries this only when the document-work limit refused the request. */
  retryAfter?: string | null
}): RedlineTriggerErrorKind {
  if (input.status === 403) return 'FORBIDDEN'
  if (input.status === 404) return 'NOT_FOUND'
  if (input.status === 409) {
    // The server's typed source failure is the most specific fact on the wire; a stale execution
    // state or a still-loading read must not hide it.
    if (input.failureCode && SOURCE_FAILURE_CODES.has(input.failureCode)) {
      return 'SOURCE_UNAVAILABLE'
    }
    if (!input.executionStateKnown) return 'UNCONFIRMED'
    if (input.execution?.status === 'FAILED') return 'FAILED_EXECUTION'
    return 'NOT_AVAILABLE'
  }
  if (input.status === 503) return input.retryAfter ? 'BUSY' : 'STORAGE_UNAVAILABLE'
  if (input.status === 500) return 'ENGINE_UNAVAILABLE'
  return 'TRANSPORT'
}

const TRIGGER_ERROR_NOTICES: Record<RedlineTriggerErrorKind, string> = {
  FORBIDDEN: 'You do not have permission to generate redlines for this review.',
  NOT_FOUND:
    'This review could not be found. It may not exist, or you may not have access to it.',
  FAILED_EXECUTION:
    'The redline failed. The report below shows what happened; the approved changes are unchanged.',
  NOT_AVAILABLE:
    'Rivell could not generate the redline from this review’s original document. The approved changes are unchanged.',
  SOURCE_UNAVAILABLE:
    'The original document could not be opened, so the redline was not generated. The approved changes are unchanged.',
  UNCONFIRMED:
    'The redline request could not be confirmed. Reopen this review to check whether a redline was created.',
  ENGINE_UNAVAILABLE:
    'Rivell cannot generate a redline right now. The approved changes are unchanged; try again later.',
  BUSY:
    'Rivell is handling as many files as it can right now. The redline was not generated; try again in a moment.',
  STORAGE_UNAVAILABLE:
    'The generated redline could not be stored, so no document was produced. This review and its approved changes are unchanged; you can generate the redline again once storage is available.',
  TRANSPORT:
    'The redline could not be generated. Check your connection and try again.',
}

/**
 * The sentence for a failed request. A typed source code has its own exact wording; every other
 * kind uses its fixed notice.
 */
export function redlineTriggerErrorNotice(
  kind: RedlineTriggerErrorKind,
  failureCode: RedlineFailureCode | null = null,
): string {
  if (kind === 'SOURCE_UNAVAILABLE' && failureCode) return redlineFailureTitle(failureCode)
  return TRIGGER_ERROR_NOTICES[kind]
}
