import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { apiBaseUrl, createApiClient } from './client'
import type { ChangeActionType, ChangeTargetKind } from './workflow'
import { CHANGE_ACTION_TYPES, CHANGE_TARGET_KINDS } from './workflow'
import {
  asRecord,
  parseApiResult,
  readArray,
  readNumber,
  readOptionalBoolean,
  readOptionalNullableNumber,
  readOptionalNullableString,
  readString,
} from './envelope'

export const REDLINE_EXECUTION_STATUSES = ['COMPLETED', 'FAILED'] as const
export type RedlineExecutionStatus = (typeof REDLINE_EXECUTION_STATUSES)[number] | 'UNKNOWN'

export const REDLINE_OUTCOMES = ['REDLINE_GENERATED', 'NO_CHANGES'] as const
export type RedlineOutcome = (typeof REDLINE_OUTCOMES)[number] | 'UNKNOWN'

export const REDLINE_CHANGE_STATUSES = ['APPLIED', 'FAILED', 'NOT_APPLIED'] as const
export type RedlineChangeStatus = (typeof REDLINE_CHANGE_STATUSES)[number] | 'UNKNOWN'

export const REDLINE_VERIFICATION_STATUSES = ['VERIFIED', 'FAILED'] as const
export type RedlineVerificationStatus =
  | (typeof REDLINE_VERIFICATION_STATUSES)[number]
  | 'UNKNOWN'

export const REDLINE_FAILURE_CODES = [
  'SOURCE_DOCUMENT_UNAVAILABLE',
  'SOURCE_VERSION_MISMATCH',
  'SOURCE_INTEGRITY_MISMATCH',
  'UNSUPPORTED_SOURCE_FORMAT',
  'SOURCE_EXPANSION_LIMIT_EXCEEDED',
  'UNSUPPORTED_CHANGESET_VERSION',
  'PROTECTED_DOCUMENT_UNSUPPORTED',
  'PREEXISTING_REVISIONS_UNSUPPORTED',
  'TARGET_NOT_FOUND',
  'TARGET_AMBIGUOUS',
  'UNSUPPORTED_SOURCE_STRUCTURE',
  'CHANGE_OVERLAP',
  'MISSING_APPROVED_LANGUAGE',
  'INSERT_PLACEMENT_UNRESOLVED',
  'APPROVED_SEMANTICS_DRIFT',
  'DOCX_LOAD_FAILED',
  'DOCX_SERIALIZATION_FAILED',
  'ROUND_TRIP_FAILED',
  'VERIFICATION_FAILED',
] as const
export type RedlineFailureCode = (typeof REDLINE_FAILURE_CODES)[number] | 'UNKNOWN'

export type RedlineExecutionChange = {
  approvedChangeId: string
  ordinal: number
  actionType: ChangeActionType
  targetKind: ChangeTargetKind
  status: RedlineChangeStatus
  verification: RedlineVerificationStatus | null
  failureCode: RedlineFailureCode | null
  placement: string | null
  resolvedTarget: string | null
  paragraphsDeleted: number | null
  paragraphsInserted: number | null
}

export type RedlineExecution = {
  id: string
  reviewId: string
  changeSetId: string
  contractId: string
  contractVersionNumber: number
  changeSetVersion: number
  attempt: number
  status: RedlineExecutionStatus
  outcome: RedlineOutcome | null
  failureCode: RedlineFailureCode | null
  failureDetail: string | null
  rendererVersion: string
  placementStrategyVersion: string
  sourceSha256: string | null
  changeCount: number
  requestedBy: string | null
  startedAt: string
  completedAt: string | null
  artifactFilename: string | null
  artifactSizeBytes: number | null
  artifactSha256: string | null
  downloadAvailable: boolean
  changes: RedlineExecutionChange[]
}

export type RedlineDownload = { blob: Blob; filename: string }

function readStatus<T extends string>(
  source: Record<string, unknown>,
  key: string,
  values: readonly T[],
): T | 'UNKNOWN' {
  const value = source[key]
  if (value === null || value === undefined) return 'UNKNOWN'
  if (typeof value !== 'string' || !(values as readonly string[]).includes(value)) {
    return 'UNKNOWN'
  }
  return value as T
}

function readNullableStatus<T extends string>(
  source: Record<string, unknown>,
  key: string,
  values: readonly T[],
): T | 'UNKNOWN' | null {
  const value = source[key]
  if (value === null || value === undefined) return null
  if (typeof value !== 'string' || !(values as readonly string[]).includes(value)) {
    return 'UNKNOWN'
  }
  return value as T
}

function parseRedlineChange(value: unknown, what: string): RedlineExecutionChange {
  const change = asRecord(value, what)
  return {
    approvedChangeId: readString(change, 'approvedChangeId', what),
    ordinal: readNumber(change, 'ordinal', what),
    actionType: readStatus(change, 'actionType', CHANGE_ACTION_TYPES),
    targetKind: readStatus(change, 'targetKind', CHANGE_TARGET_KINDS),
    status: readStatus(change, 'status', REDLINE_CHANGE_STATUSES),
    verification: readNullableStatus(change, 'verification', REDLINE_VERIFICATION_STATUSES),
    failureCode: readNullableStatus(change, 'failureCode', REDLINE_FAILURE_CODES),
    placement: readOptionalNullableString(change, 'placement', what),
    resolvedTarget: readOptionalNullableString(change, 'resolvedTarget', what),
    paragraphsDeleted: readOptionalNullableNumber(change, 'paragraphsDeleted', what),
    paragraphsInserted: readOptionalNullableNumber(change, 'paragraphsInserted', what),
  }
}

export function parseRedlineExecution(
  value: unknown,
  what = 'redline execution',
): RedlineExecution {
  const execution = asRecord(value, what)
  return {
    id: readString(execution, 'id', what),
    reviewId: readString(execution, 'reviewId', what),
    changeSetId: readString(execution, 'changeSetId', what),
    contractId: readString(execution, 'contractId', what),
    contractVersionNumber: readNumber(execution, 'contractVersionNumber', what),
    changeSetVersion: readNumber(execution, 'changeSetVersion', what),
    attempt: readNumber(execution, 'attempt', what),
    status: readStatus(execution, 'status', REDLINE_EXECUTION_STATUSES),
    outcome: readNullableStatus(execution, 'outcome', REDLINE_OUTCOMES),
    failureCode: readNullableStatus(execution, 'failureCode', REDLINE_FAILURE_CODES),
    failureDetail: readOptionalNullableString(execution, 'failureDetail', what),
    rendererVersion: readOptionalNullableString(execution, 'rendererVersion', what) ?? '',
    placementStrategyVersion:
      readOptionalNullableString(execution, 'placementStrategyVersion', what) ?? '',
    sourceSha256: readOptionalNullableString(execution, 'sourceSha256', what),
    changeCount: readNumber(execution, 'changeCount', what),
    requestedBy: readOptionalNullableString(execution, 'requestedBy', what),
    startedAt: readOptionalNullableString(execution, 'startedAt', what) ?? '',
    completedAt: readOptionalNullableString(execution, 'completedAt', what),
    artifactFilename: readOptionalNullableString(execution, 'artifactFilename', what),
    artifactSizeBytes: readOptionalNullableNumber(execution, 'artifactSizeBytes', what),
    artifactSha256: readOptionalNullableString(execution, 'artifactSha256', what),
    downloadAvailable: readOptionalBoolean(execution, 'downloadAvailable', what),
    changes: readArray(execution, 'changes', what, parseRedlineChange),
  }
}

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  400: 'The redline request was not valid.',
  403: 'You do not have permission to generate or download redlines for this review.',
  404: 'This review or its redline could not be found.',
  409: 'The redline is not available in its current state.',
  500: 'Redlining is unavailable right now.',
  503: 'Document storage is unavailable, so the redline was not produced.',
}

export class RedlineApiError extends Error {
  readonly status: number
  /** The server's typed failure code on a 409, when it sent one. */
  readonly failureCode: RedlineFailureCode | null
  /** A 503 from a saturated document-work limit carries this, unlike a storage outage. */
  readonly retryAfter: string | null

  constructor(
    status: number,
    failureCode: RedlineFailureCode | null = null,
    retryAfter: string | null = null,
  ) {
    super(SAFE_ERROR_MESSAGES[status] ?? 'The redline request failed.')
    this.name = 'RedlineApiError'
    this.status = status
    this.failureCode = failureCode
    this.retryAfter = retryAfter
  }
}

/** Reads the typed code from an error body without ever surfacing the body's text. */
async function redlineApiError(response: Response, parsed: unknown): Promise<RedlineApiError> {
  let body = parsed
  if (body === undefined) {
    try {
      body = await response.clone().json()
    } catch {
      // An error body is optional; the status alone still describes the failure.
    }
  }
  let failureCode: RedlineFailureCode | null = null
  if (typeof body === 'object' && body !== null && 'failureCode' in body) {
    const value = (body as Record<string, unknown>)['failureCode']
    if (typeof value === 'string' && (REDLINE_FAILURE_CODES as readonly string[]).includes(value)) {
      failureCode = value as RedlineFailureCode
    }
  }
  return new RedlineApiError(response.status, failureCode,
    response.headers.get('Retry-After'))
}

export function isRedlineForbiddenError(error: unknown): boolean {
  return error instanceof RedlineApiError && error.status === 403
}

export function isRedlineNotFoundError(error: unknown): boolean {
  return error instanceof RedlineApiError && error.status === 404
}

export const redlineKeys = {
  execution: (reviewId: string) => ['reviews', 'redline', reviewId] as const,
}

function redlineRetry(failureCount: number, error: Error): boolean {
  return (
    failureCount < 1 && !isRedlineNotFoundError(error) && !isRedlineForbiddenError(error)
  )
}

export async function fetchRedlineExecution(reviewId: string): Promise<RedlineExecution> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/reviews/{id}/redline', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw await redlineApiError(response, error)
  return parseApiResult(data, parseRedlineExecution, 'redline execution')
}

export async function triggerRedline(reviewId: string): Promise<RedlineExecution> {
  const client = createApiClient()
  const { data, error, response } = await client.POST('/api/reviews/{id}/redline', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw await redlineApiError(response, error)
  return parseApiResult(data, parseRedlineExecution, 'redline execution')
}

/**
 * Re-runs a persisted FAILED attempt against the same frozen ApprovedChangeSet. The prior
 * attempt is kept; the server appends the next numbered attempt.
 */
export async function retryRedline(reviewId: string): Promise<RedlineExecution> {
  const client = createApiClient()
  const { data, error, response } = await client.POST('/api/reviews/{id}/redline/retry', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw await redlineApiError(response, error)
  return parseApiResult(data, parseRedlineExecution, 'redline execution')
}

function filenameFromDisposition(header: string | null): string | null {
  if (!header) return null
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      return null
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1] : null
}

const DOCX_MEDIA_TYPE =
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document'

export class RedlineDownloadIntegrityError extends Error {
  constructor() {
    super('The downloaded file did not match the verified redline.')
    this.name = 'RedlineDownloadIntegrityError'
  }
}

export async function downloadRedline(reviewId: string): Promise<RedlineDownload> {
  const response = await fetch(`${apiBaseUrl}/api/reviews/${reviewId}/redline/download`, {
    headers: { Accept: DOCX_MEDIA_TYPE },
  })
  if (!response.ok) throw await redlineApiError(response, undefined)
  const contentType = response.headers.get('Content-Type')
  const blob = await response.blob()
  if (blob.size === 0) throw new RedlineDownloadIntegrityError()
  if (contentType && !contentType.toLowerCase().startsWith(DOCX_MEDIA_TYPE)) {
    throw new RedlineDownloadIntegrityError()
  }
  const filename =
    filenameFromDisposition(response.headers.get('Content-Disposition')) ?? 'redline.docx'
  return { blob, filename }
}

export function useRedlineExecutionQuery(
  reviewId: string | undefined,
  enabled: boolean,
): UseQueryResult<RedlineExecution> {
  return useQuery({
    queryKey: redlineKeys.execution(reviewId ?? ''),
    queryFn: reviewId ? () => fetchRedlineExecution(reviewId) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: redlineRetry,
  })
}

export function useTriggerRedline(
  reviewId: string,
): UseMutationResult<RedlineExecution, Error, void> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => triggerRedline(reviewId),
    retry: false,
    networkMode: 'always',
    onSuccess: (execution) => {
      queryClient.setQueryData(redlineKeys.execution(reviewId), execution)
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: redlineKeys.execution(reviewId) })
    },
  })
}

export function useRetryRedline(
  reviewId: string,
): UseMutationResult<RedlineExecution, Error, void> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => retryRedline(reviewId),
    retry: false,
    networkMode: 'always',
    onSuccess: (execution) => {
      queryClient.setQueryData(redlineKeys.execution(reviewId), execution)
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: redlineKeys.execution(reviewId) })
    },
  })
}
