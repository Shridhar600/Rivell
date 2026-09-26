import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import type { components } from './schema'
import {
  asRecord,
  malformed,
  parseApiResult,
  readArray,
  readOptionalBoolean,
  readOptionalNullableString,
  readOptionalNumber,
  readString,
} from './envelope'

export const DECISION_ACTIONS = ['ACCEPT', 'EDIT', 'REJECT', 'ESCALATE'] as const
export type DecisionAction = (typeof DECISION_ACTIONS)[number] | 'UNKNOWN'

export const CHANGE_ACTION_TYPES = ['REPLACE', 'INSERT', 'DELETE'] as const
export type ChangeActionType = (typeof CHANGE_ACTION_TYPES)[number] | 'UNKNOWN'

export const CHANGE_TARGET_KINDS = ['CLAUSE', 'ABSENCE'] as const
export type ChangeTargetKind = (typeof CHANGE_TARGET_KINDS)[number] | 'UNKNOWN'

export const CHANGE_LANGUAGE_SOURCES = [
  'APPROVED_DRAFTING',
  'GENERATIVE',
  'HUMAN_EDIT',
  'NONE',
] as const
export type ChangeLanguageSource = (typeof CHANGE_LANGUAGE_SOURCES)[number] | 'UNKNOWN'

export const HUMAN_REVIEW_STATUSES = [
  'NOT_STARTED',
  'IN_PROGRESS',
  'READY_FOR_COMPLETION',
  'COMPLETED',
] as const
export type HumanReviewStatus = (typeof HUMAN_REVIEW_STATUSES)[number] | 'UNKNOWN'

export const MAX_HISTORY_LIMIT = 100

export type HumanDecision = {
  id: string
  findingId: string
  revision: number
  action: DecisionAction
  approvedAction: ChangeActionType | null
  approvedLanguage: string | null
  languageSource: ChangeLanguageSource | null
  approvedDraftingEvidenceId: string | null
  comment: string | null
  actorId: string
  decidedAt: string
  clientRequestId: string | null
  supersedesDecisionId: string | null
}

export type DecisionListResponse = {
  items: HumanDecision[]
  truncated: boolean
}

export type Blocker = {
  code: string
  message: string
  findingIds: string[]
}

export type HumanReview = {
  reviewId: string
  status: HumanReviewStatus
  analysisStatus: string
  actionableFindings: number
  resolvedFindings: number
  remainingFindings: number
  accepted: number
  edited: number
  rejected: number
  escalated: number
  informationalFindings: number
  informationalDecided: number
  readyToFinalize: boolean
  blockers: Blocker[]
  changeSetId: string | null
  finalizedBy: string | null
  finalizedAt: string | null
}

export type ApprovedChange = {
  id: string
  findingId: string
  decisionId: string
  ordinal: number
  actionType: ChangeActionType
  targetKind: ChangeTargetKind
  targetHeadingNodeId: string | null
  absenceConcept: string | null
  approvedLanguage: string | null
  languageSource: ChangeLanguageSource
  approvedDraftingEvidenceId: string | null
}

export type ApprovedChangeSet = {
  id: string
  reviewId: string
  contractId: string
  contractVersionNumber: number
  runId: string
  changeSetVersion: number
  finalizedBy: string
  finalizedAt: string
  changes: ApprovedChange[]
}

export type DecisionInput = {
  findingId: string
  expectedVersion: number
  action: DecisionAction
  approvedAction?: ChangeActionType
  approvedLanguage?: string
  comment?: string
  clientRequestId?: string
}

function parseOptionalStringArray(
  source: Record<string, unknown>,
  key: string,
  what: string,
): string[] {
  const value = source[key]
  if (value === null || value === undefined) return []
  if (!Array.isArray(value) || value.some((item) => typeof item !== 'string')) {
    throw malformed(what)
  }
  return value as string[]
}

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

export function parseDecision(value: unknown, what = 'decision'): HumanDecision {
  const decision = asRecord(value, what)
  return {
    id: readString(decision, 'id', what),
    findingId: readString(decision, 'findingId', what),
    revision: readOptionalNumber(decision, 'revision', what),
    action: readStatus(decision, 'action', DECISION_ACTIONS),
    approvedAction: readNullableStatus(decision, 'approvedAction', CHANGE_ACTION_TYPES),
    approvedLanguage: readOptionalNullableString(decision, 'approvedLanguage', what),
    languageSource: readNullableStatus(decision, 'languageSource', CHANGE_LANGUAGE_SOURCES),
    approvedDraftingEvidenceId: readOptionalNullableString(
      decision,
      'approvedDraftingEvidenceId',
      what,
    ),
    comment: readOptionalNullableString(decision, 'comment', what),
    actorId: readOptionalNullableString(decision, 'actorId', what) ?? '',
    decidedAt: readOptionalNullableString(decision, 'decidedAt', what) ?? '',
    clientRequestId: readOptionalNullableString(decision, 'clientRequestId', what),
    supersedesDecisionId: readOptionalNullableString(decision, 'supersedesDecisionId', what),
  }
}

export function parseDecisionList(value: unknown, what = 'decision list'): DecisionListResponse {
  const list = asRecord(value, what)
  return {
    items: readArray(list, 'items', what, parseDecision),
    truncated: readOptionalBoolean(list, 'truncated', what),
  }
}

function parseBlocker(value: unknown, what: string): Blocker {
  const blocker = asRecord(value, what)
  return {
    code: readOptionalNullableString(blocker, 'code', what) ?? 'UNKNOWN',
    message: readOptionalNullableString(blocker, 'message', what) ?? '',
    findingIds: parseOptionalStringArray(blocker, 'findingIds', what),
  }
}

export function parseHumanReview(value: unknown, what = 'human review'): HumanReview {
  const review = asRecord(value, what)
  return {
    reviewId: readOptionalNullableString(review, 'reviewId', what) ?? '',
    status: readStatus(review, 'status', HUMAN_REVIEW_STATUSES),
    analysisStatus: readOptionalNullableString(review, 'analysisStatus', what) ?? 'UNKNOWN',
    actionableFindings: readOptionalNumber(review, 'actionableFindings', what),
    resolvedFindings: readOptionalNumber(review, 'resolvedFindings', what),
    remainingFindings: readOptionalNumber(review, 'remainingFindings', what),
    accepted: readOptionalNumber(review, 'accepted', what),
    edited: readOptionalNumber(review, 'edited', what),
    rejected: readOptionalNumber(review, 'rejected', what),
    escalated: readOptionalNumber(review, 'escalated', what),
    informationalFindings: readOptionalNumber(review, 'informationalFindings', what),
    informationalDecided: readOptionalNumber(review, 'informationalDecided', what),
    readyToFinalize: readOptionalBoolean(review, 'readyToFinalize', what),
    blockers: readArray(review, 'blockers', what, parseBlocker),
    changeSetId: readOptionalNullableString(review, 'changeSetId', what),
    finalizedBy: readOptionalNullableString(review, 'finalizedBy', what),
    finalizedAt: readOptionalNullableString(review, 'finalizedAt', what),
  }
}

function parseApprovedChange(value: unknown, what: string): ApprovedChange {
  const change = asRecord(value, what)
  return {
    id: readString(change, 'id', what),
    findingId: readString(change, 'findingId', what),
    decisionId: readString(change, 'decisionId', what),
    ordinal: readOptionalNumber(change, 'ordinal', what),
    actionType: readStatus(change, 'actionType', CHANGE_ACTION_TYPES),
    targetKind: readStatus(change, 'targetKind', CHANGE_TARGET_KINDS),
    targetHeadingNodeId: readOptionalNullableString(change, 'targetHeadingNodeId', what),
    absenceConcept: readOptionalNullableString(change, 'absenceConcept', what),
    approvedLanguage: readOptionalNullableString(change, 'approvedLanguage', what),
    languageSource: readStatus(change, 'languageSource', CHANGE_LANGUAGE_SOURCES),
    approvedDraftingEvidenceId: readOptionalNullableString(
      change,
      'approvedDraftingEvidenceId',
      what,
    ),
  }
}

export function parseApprovedChangeSet(
  value: unknown,
  what = 'approved change set',
): ApprovedChangeSet {
  const changeSet = asRecord(value, what)
  return {
    id: readString(changeSet, 'id', what),
    reviewId: readString(changeSet, 'reviewId', what),
    contractId: readString(changeSet, 'contractId', what),
    contractVersionNumber: readOptionalNumber(changeSet, 'contractVersionNumber', what),
    runId: readOptionalNullableString(changeSet, 'runId', what) ?? '',
    changeSetVersion: readOptionalNumber(changeSet, 'changeSetVersion', what),
    finalizedBy: readOptionalNullableString(changeSet, 'finalizedBy', what) ?? '',
    finalizedAt: readOptionalNullableString(changeSet, 'finalizedAt', what) ?? '',
    changes: readArray(changeSet, 'changes', what, parseApprovedChange),
  }
}

const SAFE_ERROR_MESSAGES: Record<number, string> = {
  400: 'The decision could not be sent. Check the form and try again.',
  403: 'You do not have permission to record decisions on this review.',
  404: 'This review could not be found. It may not exist, or you may not have access to it.',
  409: 'The review changed or the action is no longer available.',
  500: 'The review workflow is unavailable right now.',
  503: 'The review workflow is unavailable right now.',
}

export class WorkflowApiError extends Error {
  readonly status: number

  constructor(status: number) {
    super(SAFE_ERROR_MESSAGES[status] ?? 'The review workflow request failed.')
    this.name = 'WorkflowApiError'
    this.status = status
  }
}

export function isWorkflowStatusError(error: unknown, status: number): boolean {
  return error instanceof WorkflowApiError && error.status === status
}

export function isWorkflowBadRequestError(error: unknown): boolean {
  return isWorkflowStatusError(error, 400)
}

export function isWorkflowForbiddenError(error: unknown): boolean {
  return isWorkflowStatusError(error, 403)
}

export function isWorkflowNotFoundError(error: unknown): boolean {
  return isWorkflowStatusError(error, 404)
}

export function isWorkflowConflictError(error: unknown): boolean {
  return isWorkflowStatusError(error, 409)
}

function workflowRetry(failureCount: number, error: Error): boolean {
  return failureCount < 1 && !isWorkflowNotFoundError(error)
}

export const workflowKeys = {
  decisions: (reviewId: string) => ['reviews', 'decisions', reviewId] as const,
  humanReview: (reviewId: string) => ['reviews', 'human-review', reviewId] as const,
  decisionHistory: (reviewId: string, findingId: string) =>
    ['reviews', 'decision-history', reviewId, findingId] as const,
  changeSet: (reviewId: string) => ['reviews', 'change-set', reviewId] as const,
}

export async function fetchCurrentDecisions(reviewId: string): Promise<DecisionListResponse> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/reviews/{id}/decisions', {
    params: { path: { id: reviewId }, query: { limit: MAX_HISTORY_LIMIT } },
  })
  if (!data) throw new WorkflowApiError(response.status)
  void error
  return parseApiResult(data, parseDecisionList, 'decision list')
}

export async function fetchDecisionHistory(
  reviewId: string,
  findingId: string,
): Promise<DecisionListResponse> {
  const client = createApiClient()
  const { data, response } = await client.GET(
    '/api/reviews/{id}/findings/{findingId}/decisions',
    { params: { path: { id: reviewId, findingId }, query: { limit: MAX_HISTORY_LIMIT } } },
  )
  if (!data) throw new WorkflowApiError(response.status)
  return parseApiResult(data, parseDecisionList, 'decision history')
}

export async function fetchHumanReview(reviewId: string): Promise<HumanReview> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}/human-review', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw new WorkflowApiError(response.status)
  return parseApiResult(data, parseHumanReview, 'human review')
}

export async function fetchApprovedChangeSet(reviewId: string): Promise<ApprovedChangeSet> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}/approved-change-set', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw new WorkflowApiError(response.status)
  return parseApiResult(data, parseApprovedChangeSet, 'approved change set')
}

export async function submitDecision(
  reviewId: string,
  input: DecisionInput,
): Promise<HumanDecision> {
  const client = createApiClient()
  const body: components['schemas']['DecisionRequest'] = {
    expectedVersion: input.expectedVersion,
    action: input.action as components['schemas']['DecisionRequest']['action'],
  }
  if (input.clientRequestId) body.clientRequestId = input.clientRequestId
  if (input.approvedAction) {
    body.approvedAction =
      input.approvedAction as components['schemas']['DecisionRequest']['approvedAction']
  }
  if (input.approvedLanguage !== undefined) body.approvedLanguage = input.approvedLanguage
  if (input.comment !== undefined) body.comment = input.comment
  const { data, response } = await client.PUT(
    '/api/reviews/{id}/findings/{findingId}/decision',
    {
      params: { path: { id: reviewId, findingId: input.findingId } },
      body,
    },
  )
  if (!data) throw new WorkflowApiError(response.status)
  return parseApiResult(data, parseDecision, 'decision')
}

export async function finalizeReview(reviewId: string): Promise<ApprovedChangeSet> {
  const client = createApiClient()
  const { data, response } = await client.POST('/api/reviews/{id}/finalize', {
    params: { path: { id: reviewId } },
  })
  if (!data) throw new WorkflowApiError(response.status)
  return parseApiResult(data, parseApprovedChangeSet, 'approved change set')
}

export function useCurrentDecisionsQuery(
  reviewId: string | undefined,
  enabled: boolean,
): UseQueryResult<DecisionListResponse> {
  return useQuery({
    queryKey: workflowKeys.decisions(reviewId ?? ''),
    queryFn: reviewId ? () => fetchCurrentDecisions(reviewId) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: workflowRetry,
  })
}

export function useHumanReviewQuery(
  reviewId: string | undefined,
  enabled: boolean,
): UseQueryResult<HumanReview> {
  return useQuery({
    queryKey: workflowKeys.humanReview(reviewId ?? ''),
    queryFn: reviewId ? () => fetchHumanReview(reviewId) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: workflowRetry,
  })
}

export function useApprovedChangeSetQuery(
  reviewId: string | undefined,
  enabled: boolean,
): UseQueryResult<ApprovedChangeSet> {
  return useQuery({
    queryKey: workflowKeys.changeSet(reviewId ?? ''),
    queryFn: reviewId ? () => fetchApprovedChangeSet(reviewId) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: workflowRetry,
  })
}

export function useDecisionHistoryQuery(
  reviewId: string | undefined,
  findingId: string | undefined,
  enabled: boolean,
): UseQueryResult<DecisionListResponse> {
  return useQuery({
    queryKey: workflowKeys.decisionHistory(reviewId ?? '', findingId ?? ''),
    queryFn:
      reviewId && findingId ? () => fetchDecisionHistory(reviewId, findingId) : skipToken,
    enabled: Boolean(reviewId) && Boolean(findingId) && enabled,
    retry: workflowRetry,
  })
}

export function useSubmitDecision(
  reviewId: string,
): UseMutationResult<HumanDecision, Error, DecisionInput> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: DecisionInput) => submitDecision(reviewId, input),
    retry: false,
    networkMode: 'always',
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.decisions(reviewId) })
      void queryClient.invalidateQueries({ queryKey: workflowKeys.humanReview(reviewId) })
    },
    onError: (error) => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.decisions(reviewId) })
      void queryClient.invalidateQueries({ queryKey: workflowKeys.humanReview(reviewId) })
      if (isWorkflowConflictError(error)) {
        void queryClient.invalidateQueries({ queryKey: workflowKeys.changeSet(reviewId) })
      }
    },
  })
}

export function useFinalizeReview(
  reviewId: string,
): UseMutationResult<ApprovedChangeSet, Error, void> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => finalizeReview(reviewId),
    retry: false,
    networkMode: 'always',
    onSuccess: (changeSet) => {
      queryClient.setQueryData(workflowKeys.changeSet(reviewId), changeSet)
      void queryClient.invalidateQueries({ queryKey: workflowKeys.decisions(reviewId) })
      void queryClient.invalidateQueries({ queryKey: workflowKeys.humanReview(reviewId) })
    },
    onError: () => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.humanReview(reviewId) })
      void queryClient.invalidateQueries({ queryKey: workflowKeys.decisions(reviewId) })
    },
  })
}
