import { skipToken, useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import { REDLINE_FAILURE_CODES } from './redline'
import { REVIEW_STATUSES, RUN_STATUSES } from './reviews'
import {
  asRecord,
  parseApiResult,
  readArray,
  readNullableClosedString,
  readClosedString,
  readNumber,
  readOptionalBoolean,
  readOptionalNullableBoolean,
  readOptionalNullableNumber,
  readOptionalNullableString,
  readOptionalNumber,
  readString,
} from './envelope'

export const RUN_TRIGGERS = ['INITIAL', 'RECOMPUTE'] as const
export type RunTrigger = (typeof RUN_TRIGGERS)[number] | 'UNKNOWN'

export const CHECK_STATUSES = [
  'PENDING',
  'RUNNING',
  'COMPLETED',
  'DEGRADED',
  'FAILED',
  'SKIPPED',
] as const
export type DiagnosticsCheckStatus = (typeof CHECK_STATUSES)[number] | 'UNKNOWN'

export const DIAGNOSTICS_REDLINE_STATUSES = ['COMPLETED', 'FAILED'] as const
export const DIAGNOSTICS_REDLINE_OUTCOMES = ['REDLINE_GENERATED', 'NO_CHANGES'] as const

export type DiagnosticsStatus = (typeof DIAGNOSTICS_REDLINE_STATUSES)[number] | 'UNKNOWN'
export type DiagnosticsRedlineOutcome = (typeof DIAGNOSTICS_REDLINE_OUTCOMES)[number] | 'UNKNOWN'

/**
 * The diagnostics response carries three free-text fields that hold backend internals: a review
 * failure string, an analysis failure string, and a per-check degraded reason. Those are reduced to
 * a presence flag here and the text is discarded, so no component can render it.
 */
export type InternalDetail = 'NONE' | 'RECORDED'

export type DiagnosticsCheck = {
  key: string
  status: DiagnosticsCheckStatus
  signalCount: number | null
  degradedReason: InternalDetail
  startedAt: string | null
  completedAt: string | null
  durationMillis: number | null
}

export type DiagnosticsRun = {
  runId: string
  runNumber: number
  status: (typeof RUN_STATUSES)[number] | 'UNKNOWN'
  trigger: RunTrigger
  pipelineVersion: string
  startedAt: string | null
  completedAt: string | null
  runDurationSeconds: number | null
  failureDetail: InternalDetail
  semanticsMatchFrozenSnapshot: boolean | null
  checks: DiagnosticsCheck[]
}

export type DiagnosticsFindingCounts = {
  total: number
  blocking: number
  reviewRequired: number
  informational: number
}

export type DiagnosticsDecisionCounts = {
  accepted: number
  edited: number
  rejected: number
  escalated: number
}

export type DiagnosticsFinalization = {
  finalized: boolean
  changeSetId: string | null
  finalizedAt: string | null
  approvedChanges: number
}

export type DiagnosticsRedline = {
  status: DiagnosticsStatus
  outcome: DiagnosticsRedlineOutcome | null
  failureCode: (typeof REDLINE_FAILURE_CODES)[number] | 'UNKNOWN' | null
  changeCount: number
  downloadAvailable: boolean
  ageSeconds: number | null
}

export type ReviewDiagnostics = {
  reviewId: string
  contractId: string
  contractVersionNumber: number
  reviewStatus: (typeof REVIEW_STATUSES)[number] | 'UNKNOWN'
  reviewFailureDetail: InternalDetail
  backgroundWorkerEnabled: boolean | null
  analysis: DiagnosticsRun | null
  findings: DiagnosticsFindingCounts
  decisions: DiagnosticsDecisionCounts
  finalization: DiagnosticsFinalization
  redline: DiagnosticsRedline | null
  stalled: string[]
}

const MAX_STALLED_ENTRIES = 4
const MAX_STALLED_LENGTH = 200

function readInternalDetail(
  source: Record<string, unknown>,
  key: string,
  what: string,
): InternalDetail {
  return readOptionalNullableString(source, key, what) === null ? 'NONE' : 'RECORDED'
}

function readStalled(source: Record<string, unknown>): string[] {
  const value = source['stalled']
  if (!Array.isArray(value)) return []
  return value
    .filter((entry): entry is string => typeof entry === 'string')
    .slice(0, MAX_STALLED_ENTRIES)
    .map((entry) => entry.slice(0, MAX_STALLED_LENGTH))
}

function parseCheck(value: unknown, what: string): DiagnosticsCheck {
  const check = asRecord(value, what)
  return {
    key: readString(check, 'key', what),
    status: readClosedString(check, 'status', CHECK_STATUSES),
    signalCount: readOptionalNullableNumber(check, 'signalCount', what),
    degradedReason: readInternalDetail(check, 'degradedReason', what),
    startedAt: readOptionalNullableString(check, 'startedAt', what),
    completedAt: readOptionalNullableString(check, 'completedAt', what),
    durationMillis: readOptionalNullableNumber(check, 'durationMillis', what),
  }
}

function parseRun(value: unknown, what: string): DiagnosticsRun {
  const run = asRecord(value, what)
  return {
    runId: readString(run, 'runId', what),
    runNumber: readNumber(run, 'runNumber', what),
    status: readClosedString(run, 'status', RUN_STATUSES),
    trigger: readClosedString(run, 'trigger', RUN_TRIGGERS),
    pipelineVersion: readString(run, 'pipelineVersion', what),
    startedAt: readOptionalNullableString(run, 'startedAt', what),
    completedAt: readOptionalNullableString(run, 'completedAt', what),
    runDurationSeconds: readOptionalNullableNumber(run, 'ageSeconds', what),
    failureDetail: readInternalDetail(run, 'failureReason', what),
    semanticsMatchFrozenSnapshot: readOptionalNullableBoolean(
      run,
      'environmentMatchesSnapshot',
      what,
    ),
    checks: readArray(run, 'checks', what, parseCheck),
  }
}

function parseFindingCounts(value: unknown, what: string): DiagnosticsFindingCounts {
  const counts = asRecord(value, what)
  return {
    total: readOptionalNumber(counts, 'total', what),
    blocking: readOptionalNumber(counts, 'blocking', what),
    reviewRequired: readOptionalNumber(counts, 'reviewRequired', what),
    informational: readOptionalNumber(counts, 'informational', what),
  }
}

function parseDecisionCounts(value: unknown, what: string): DiagnosticsDecisionCounts {
  const counts = asRecord(value, what)
  return {
    accepted: readOptionalNumber(counts, 'accepted', what),
    edited: readOptionalNumber(counts, 'edited', what),
    rejected: readOptionalNumber(counts, 'rejected', what),
    escalated: readOptionalNumber(counts, 'escalated', what),
  }
}

function parseFinalization(value: unknown, what: string): DiagnosticsFinalization {
  const finalization = asRecord(value, what)
  return {
    finalized: readOptionalBoolean(finalization, 'finalized', what),
    changeSetId: readOptionalNullableString(finalization, 'changeSetId', what),
    finalizedAt: readOptionalNullableString(finalization, 'finalizedAt', what),
    approvedChanges: readOptionalNumber(finalization, 'approvedChanges', what),
  }
}

function parseRedline(value: unknown, what: string): DiagnosticsRedline {
  const redline = asRecord(value, what)
  return {
    status: readClosedString(redline, 'status', DIAGNOSTICS_REDLINE_STATUSES),
    outcome: readNullableClosedString(redline, 'outcome', DIAGNOSTICS_REDLINE_OUTCOMES),
    failureCode: readNullableClosedString(redline, 'failureCode', REDLINE_FAILURE_CODES),
    changeCount: readOptionalNumber(redline, 'changeCount', what),
    downloadAvailable: readOptionalBoolean(redline, 'downloadAvailable', what),
    ageSeconds: readOptionalNullableNumber(redline, 'ageSeconds', what),
  }
}

export function parseReviewDiagnostics(value: unknown, what = 'review diagnostics'): ReviewDiagnostics {
  const source = asRecord(value, what)
  return {
    reviewId: readString(source, 'reviewId', what),
    contractId: readString(source, 'contractId', what),
    contractVersionNumber: readNumber(source, 'contractVersionNumber', what),
    reviewStatus: readClosedString(source, 'reviewStatus', REVIEW_STATUSES),
    reviewFailureDetail: readInternalDetail(source, 'reviewFailure', what),
    backgroundWorkerEnabled: readOptionalNullableBoolean(
      source,
      'backgroundWorkerEnabled',
      what,
    ),
    analysis: source['analysis'] === null || source['analysis'] === undefined
      ? null
      : parseRun(source['analysis'], what),
    findings: parseFindingCounts(source['findings'] ?? {}, what),
    decisions: parseDecisionCounts(source['decisions'] ?? {}, what),
    finalization: parseFinalization(source['finalization'] ?? {}, what),
    redline:
      source['redline'] === null || source['redline'] === undefined
        ? null
        : parseRedline(source['redline'], what),
    stalled: readStalled(source),
  }
}

const DIAGNOSTICS_ERROR_MESSAGES: Record<number, string> = {
  403: 'You do not have permission to read the diagnostics for this review.',
  404: 'The diagnostics for this review could not be found.',
  500: 'The review diagnostics could not be loaded.',
  503: 'The diagnostics service is unavailable right now.',
}

export class ReviewDiagnosticsApiError extends Error {
  readonly status: number

  constructor(status: number) {
    super(DIAGNOSTICS_ERROR_MESSAGES[status] ?? 'The review diagnostics could not be loaded.')
    this.name = 'ReviewDiagnosticsApiError'
    this.status = status
  }
}

export function isReviewDiagnosticsForbiddenError(error: unknown): boolean {
  return error instanceof ReviewDiagnosticsApiError && error.status === 403
}

export function isReviewDiagnosticsNotFoundError(error: unknown): boolean {
  return error instanceof ReviewDiagnosticsApiError && error.status === 404
}

export const diagnosticsKeys = {
  forReview: (reviewId: string) => ['reviews', 'diagnostics', reviewId] as const,
}

export async function fetchReviewDiagnostics(reviewId: string): Promise<ReviewDiagnostics> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}/diagnostics', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw new ReviewDiagnosticsApiError(response.status)
  return parseApiResult(data, parseReviewDiagnostics, 'review diagnostics')
}

export function useReviewDiagnosticsQuery(
  reviewId: string | undefined,
  enabled: boolean,
): UseQueryResult<ReviewDiagnostics> {
  return useQuery({
    queryKey: diagnosticsKeys.forReview(reviewId ?? ''),
    queryFn: reviewId ? () => fetchReviewDiagnostics(reviewId) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: false,
  })
}
