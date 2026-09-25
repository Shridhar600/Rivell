import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import type { paths } from './schema'
import {
  asRecord,
  errorMessage,
  malformed,
  parseApiResult,
  readArray,
  readOptionalBoolean,
  readOptionalNullableNumber,
  readOptionalNullableString,
  readOptionalNumber,
  readString,
  readStringArray,
} from './envelope'

export const REVIEW_STATUSES = ['ANALYSIS_QUEUED', 'ANALYZING', 'COMPLETED', 'FAILED'] as const
export type ReviewStatus = (typeof REVIEW_STATUSES)[number] | 'UNKNOWN'

export const RUN_STATUSES = ['RUNNING', 'COMPLETED', 'FAILED'] as const
export type RunStatus = (typeof RUN_STATUSES)[number] | 'UNKNOWN'

export const SEVERITIES = ['HIGH', 'MEDIUM', 'LOW'] as const
export type FindingSeverity = (typeof SEVERITIES)[number] | 'UNKNOWN'

export type ReviewConcept = NonNullable<
  paths['/api/reviews/{id}/findings']['get']['parameters']['query']
>['concept']

export const CLASSIFICATIONS = ['BLOCKING', 'REVIEW_REQUIRED', 'INFORMATIONAL'] as const
export type FindingClassification = (typeof CLASSIFICATIONS)[number] | 'UNKNOWN'

export const MAX_PAGE_LIMIT = 100

export type AnalysisWarning = {
  checkKey: string
  status: string
  reason: string | null
}

export type ReviewRun = {
  id: string
  runNumber: number
  status: RunStatus
}

export type ReviewSummary = {
  id: string
  contractId: string
  contractVersionNumber: number | null
  status: ReviewStatus
  failureReason: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  activeRun: ReviewRun | null
  latestRunStatus: RunStatus | null
  plannedChecks: number
  completedChecks: number
  degradedChecks: number
  failedChecks: number
  signals: number
  findings: number
  contractPackKey: string | null
  packVersion: string | null
  knowledgePinNumber: number | null
  analysisWarnings: AnalysisWarning[]
  analysisWarningsTruncated: boolean
}

export type ReviewProgress = {
  reviewStatus: ReviewStatus
  plannedChecks: number
  pendingChecks: number
  runningChecks: number
  completedChecks: number
  degradedChecks: number
  failedChecks: number
  skippedChecks: number
  signals: number
  findings: number
  latestRunStatus: RunStatus | null
  analysisWarnings: AnalysisWarning[]
  analysisWarningsTruncated: boolean
}

export type ReviewListItem = {
  id: string
  contractId: string
  contractVersionNumber: number | null
  status: ReviewStatus
  createdAt: string
  completedAt: string | null
}

export type ReviewListResponse = {
  items: ReviewListItem[]
  truncated: boolean
}

export type FindingSubjectKind = 'CLAUSE' | 'ABSENCE' | 'UNKNOWN'

export type FindingSummary = {
  id: string
  subjectKind: FindingSubjectKind
  subjectHeadingNodeId: string | null
  absenceConcept: string | null
  concept: string | null
  secondaryConcept: string | null
  issueFamily: string
  observationKind: string
  severity: FindingSeverity
  reviewClassification: FindingClassification
  proposedAction: string
  proposedLanguageSource: string
  createdAt: string
}

export type FindingListResponse = {
  items: FindingSummary[]
  truncated: boolean
}

export type FindingClaim = {
  ordinal: number
  claimKind: string
  text: string
  absence: boolean
  evidenceItemIds: string[]
}

export type FindingQuality = {
  sufficiency: string | null
  verificationOutcome: string | null
  degradationFlags: string[]
}

export type AbsenceBasis = {
  planCheckId: string
  checkKey: string
}

export type FindingDetail = FindingSummary & {
  reviewId: string
  runId: string
  concepts: string[]
  observationText: string
  proposedLanguage: string | null
  proposedLanguageSource: string
  approvedDraftingEvidenceId: string | null
  quality: FindingQuality | null
  claims: FindingClaim[]
  evidenceItemIds: string[]
  signalIds: string[]
  absenceBasis: AbsenceBasis | null
}

export type FindingFilters = {
  concept?: ReviewConcept
  classification?: (typeof CLASSIFICATIONS)[number]
  severity?: (typeof SEVERITIES)[number]
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

function parseAnalysisWarning(value: unknown, what: string): AnalysisWarning {
  const warning = asRecord(value, what)
  return {
    checkKey: readOptionalNullableString(warning, 'checkKey', what) ?? 'UNKNOWN',
    status: readOptionalNullableString(warning, 'status', what) ?? 'UNKNOWN',
    reason: readOptionalNullableString(warning, 'reason', what),
  }
}

function parseReviewRun(value: unknown, what: string): ReviewRun {
  const run = asRecord(value, what)
  return {
    id: readString(run, 'id', what),
    runNumber: readOptionalNumber(run, 'runNumber', what),
    status: readStatus(run, 'status', RUN_STATUSES),
  }
}

export function parseReviewSummary(value: unknown, what = 'review summary'): ReviewSummary {
  const summary = asRecord(value, what)
  const rawActiveRun = summary.activeRun
  return {
    id: readString(summary, 'id', what),
    contractId: readString(summary, 'contractId', what),
    contractVersionNumber: readOptionalNullableNumber(summary, 'contractVersionNumber', what),
    status: readStatus(summary, 'status', REVIEW_STATUSES),
    failureReason: readOptionalNullableString(summary, 'failureReason', what),
    createdAt: readString(summary, 'createdAt', what),
    startedAt: readOptionalNullableString(summary, 'startedAt', what),
    completedAt: readOptionalNullableString(summary, 'completedAt', what),
    activeRun:
      rawActiveRun === null || rawActiveRun === undefined
        ? null
        : parseReviewRun(rawActiveRun, what),
    latestRunStatus: readNullableStatus(summary, 'latestRunStatus', RUN_STATUSES),
    plannedChecks: readOptionalNumber(summary, 'plannedChecks', what),
    completedChecks: readOptionalNumber(summary, 'completedChecks', what),
    degradedChecks: readOptionalNumber(summary, 'degradedChecks', what),
    failedChecks: readOptionalNumber(summary, 'failedChecks', what),
    signals: readOptionalNumber(summary, 'signals', what),
    findings: readOptionalNumber(summary, 'findings', what),
    contractPackKey: readOptionalNullableString(summary, 'contractPackKey', what),
    packVersion: readOptionalNullableString(summary, 'packVersion', what),
    knowledgePinNumber: readOptionalNullableNumber(summary, 'knowledgePinNumber', what),
    analysisWarnings: readArray(summary, 'analysisWarnings', what, parseAnalysisWarning),
    analysisWarningsTruncated: readOptionalBoolean(
      summary,
      'analysisWarningsTruncated',
      what,
    ),
  }
}

export function parseReviewProgress(value: unknown, what = 'review progress'): ReviewProgress {
  const progress = asRecord(value, what)
  return {
    reviewStatus: readStatus(progress, 'reviewStatus', REVIEW_STATUSES),
    plannedChecks: readOptionalNumber(progress, 'plannedChecks', what),
    pendingChecks: readOptionalNumber(progress, 'pendingChecks', what),
    runningChecks: readOptionalNumber(progress, 'runningChecks', what),
    completedChecks: readOptionalNumber(progress, 'completedChecks', what),
    degradedChecks: readOptionalNumber(progress, 'degradedChecks', what),
    failedChecks: readOptionalNumber(progress, 'failedChecks', what),
    skippedChecks: readOptionalNumber(progress, 'skippedChecks', what),
    signals: readOptionalNumber(progress, 'signals', what),
    findings: readOptionalNumber(progress, 'findings', what),
    latestRunStatus: readNullableStatus(progress, 'latestRunStatus', RUN_STATUSES),
    analysisWarnings: readArray(progress, 'analysisWarnings', what, parseAnalysisWarning),
    analysisWarningsTruncated: readOptionalBoolean(
      progress,
      'analysisWarningsTruncated',
      what,
    ),
  }
}

function parseReviewListItem(value: unknown, what: string): ReviewListItem {
  const item = asRecord(value, what)
  return {
    id: readString(item, 'id', what),
    contractId: readString(item, 'contractId', what),
    contractVersionNumber: readOptionalNullableNumber(item, 'contractVersionNumber', what),
    status: readStatus(item, 'status', REVIEW_STATUSES),
    createdAt: readString(item, 'createdAt', what),
    completedAt: readOptionalNullableString(item, 'completedAt', what),
  }
}

export function parseReviewList(
  value: unknown,
  what = 'review list',
): ReviewListResponse {
  const list = asRecord(value, what)
  return {
    items: readArray(list, 'items', what, parseReviewListItem),
    truncated: readOptionalBoolean(list, 'truncated', what),
  }
}

function parseFindingSummary(value: unknown, what: string): FindingSummary {
  const finding = asRecord(value, what)
  return {
    id: readString(finding, 'id', what),
    subjectKind: parseSubjectKind(finding),
    subjectHeadingNodeId: readOptionalNullableString(finding, 'subjectHeadingNodeId', what),
    absenceConcept: readOptionalNullableString(finding, 'absenceConcept', what),
    concept: readOptionalNullableString(finding, 'concept', what),
    secondaryConcept: readOptionalNullableString(finding, 'secondaryConcept', what),
    issueFamily: readOptionalNullableString(finding, 'issueFamily', what) ?? 'UNKNOWN',
    observationKind: readOptionalNullableString(finding, 'observationKind', what) ?? 'UNKNOWN',
    severity: readStatus(finding, 'severity', SEVERITIES),
    reviewClassification: readStatus(finding, 'reviewClassification', CLASSIFICATIONS),
    proposedAction: readOptionalNullableString(finding, 'proposedAction', what) ?? 'UNKNOWN',
    proposedLanguageSource:
      readOptionalNullableString(finding, 'proposedLanguageSource', what) ?? 'UNKNOWN',
    createdAt: readOptionalNullableString(finding, 'createdAt', what) ?? '',
  }
}

function parseSubjectKind(source: Record<string, unknown>): FindingSubjectKind {
  const value = source.subjectKind
  if (value === 'CLAUSE' || value === 'ABSENCE') return value
  return 'UNKNOWN'
}

export function parseFindingList(
  value: unknown,
  what = 'finding list',
): FindingListResponse {
  const list = asRecord(value, what)
  return {
    items: readArray(list, 'items', what, parseFindingSummary),
    truncated: readOptionalBoolean(list, 'truncated', what),
  }
}

function parseClaim(value: unknown, what: string): FindingClaim {
  const claim = asRecord(value, what)
  return {
    ordinal: readOptionalNumber(claim, 'ordinal', what),
    claimKind: readOptionalNullableString(claim, 'claimKind', what) ?? 'UNKNOWN',
    text: readString(claim, 'text', what),
    absence: readOptionalBoolean(claim, 'absence', what),
    evidenceItemIds: parseOptionalStringArray(claim, 'evidenceItemIds', what),
  }
}

function parseQuality(value: unknown, what: string): FindingQuality | null {
  if (value === null || value === undefined) return null
  const quality = asRecord(value, what)
  return {
    sufficiency: readOptionalNullableString(quality, 'sufficiency', what),
    verificationOutcome: readOptionalNullableString(quality, 'verificationOutcome', what),
    degradationFlags: parseOptionalStringArray(quality, 'degradationFlags', what),
  }
}

function parseAbsenceBasis(value: unknown, what: string): AbsenceBasis | null {
  if (value === null || value === undefined) return null
  const basis = asRecord(value, what)
  return {
    planCheckId: readOptionalNullableString(basis, 'planCheckId', what) ?? '',
    checkKey: readOptionalNullableString(basis, 'checkKey', what) ?? 'UNKNOWN',
  }
}

export function parseFindingDetail(value: unknown, what = 'finding detail'): FindingDetail {
  const finding = asRecord(value, what)
  return {
    ...parseFindingSummary(finding, what),
    reviewId: readString(finding, 'reviewId', what),
    runId: readOptionalNullableString(finding, 'runId', what) ?? '',
    concepts: parseOptionalStringArray(finding, 'concepts', what),
    observationText: readOptionalNullableString(finding, 'observationText', what) ?? '',
    proposedLanguage: readOptionalNullableString(finding, 'proposedLanguage', what),
    proposedLanguageSource:
      readOptionalNullableString(finding, 'proposedLanguageSource', what) ?? 'UNKNOWN',
    approvedDraftingEvidenceId: readOptionalNullableString(
      finding,
      'approvedDraftingEvidenceId',
      what,
    ),
    quality: parseQuality(finding.quality, what),
    claims: readArray(finding, 'claims', what, parseClaim),
    evidenceItemIds: parseOptionalStringArray(finding, 'evidenceItemIds', what),
    signalIds: readStringArray(finding, 'signalIds', what),
    absenceBasis: parseAbsenceBasis(finding.absenceBasis, what),
  }
}

export function isReviewTerminal(status: ReviewStatus | undefined | null): boolean {
  return status === 'COMPLETED' || status === 'FAILED'
}

export function isReviewPending(status: ReviewStatus | undefined | null): boolean {
  return status === 'ANALYSIS_QUEUED' || status === 'ANALYZING'
}

export function reviewPollInterval(status: ReviewStatus | undefined | null): number | false {
  return isReviewPending(status) ? 2500 : false
}

class ReviewApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ReviewApiError'
    this.status = status
  }
}

export function isReviewBadRequestError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 400
}

export function isReviewConflictError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 409
}

export function isReviewNotFoundError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 404
}

export function isReviewForbiddenError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 403
}

function reviewRetry(failureCount: number, error: Error): boolean {
  return failureCount < 1 && !isReviewNotFoundError(error)
}

export const reviewKeys = {
  list: (contractId: string) => ['reviews', 'list', contractId] as const,
  summary: (reviewId: string) => ['reviews', 'summary', reviewId] as const,
  progress: (reviewId: string) => ['reviews', 'progress', reviewId] as const,
  findings: (reviewId: string, filters: FindingFilters) =>
    [
      'reviews',
      'findings',
      reviewId,
      filters.concept ?? '',
      filters.classification ?? '',
      filters.severity ?? '',
    ] as const,
  finding: (reviewId: string, findingId: string) =>
    ['reviews', 'finding', reviewId, findingId] as const,
}

export async function fetchReviewList(contractId: string): Promise<ReviewListResponse> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/reviews', {
    params: { query: { contractId, limit: MAX_PAGE_LIMIT } },
  })
  if (!data) {
    throw new ReviewApiError(errorMessage(error, 'Review list unavailable'), response.status)
  }
  return parseApiResult(data, parseReviewList, 'review list')
}

export async function fetchReviewSummary(reviewId: string): Promise<ReviewSummary> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/reviews/{id}', {
    params: { path: { id: reviewId } },
  })
  if (!data) {
    throw new ReviewApiError(errorMessage(error, 'Review unavailable'), response.status)
  }
  return parseApiResult(data, parseReviewSummary, 'review summary')
}

export async function fetchReviewProgress(reviewId: string): Promise<ReviewProgress> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/reviews/{id}/progress', {
    params: { path: { id: reviewId } },
  })
  if (!data) {
    throw new ReviewApiError(errorMessage(error, 'Review progress unavailable'), response.status)
  }
  return parseApiResult(data, parseReviewProgress, 'review progress')
}

export async function fetchFindingList(
  reviewId: string,
  filters: FindingFilters,
): Promise<FindingListResponse> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/reviews/{id}/findings', {
    params: {
      path: { id: reviewId },
      query: {
        limit: MAX_PAGE_LIMIT,
        ...(filters.concept ? { concept: filters.concept } : {}),
        ...(filters.classification ? { classification: filters.classification } : {}),
        ...(filters.severity ? { severity: filters.severity } : {}),
      },
    },
  })
  if (!data) {
    throw new ReviewApiError(errorMessage(error, 'Findings unavailable'), response.status)
  }
  return parseApiResult(data, parseFindingList, 'finding list')
}

export async function fetchFindingDetail(
  reviewId: string,
  findingId: string,
): Promise<FindingDetail> {
  const client = createApiClient()
  const { data, error, response } = await client.GET(
    '/api/reviews/{id}/findings/{findingId}',
    { params: { path: { id: reviewId, findingId } } },
  )
  if (!data) {
    throw new ReviewApiError(errorMessage(error, 'Finding unavailable'), response.status)
  }
  return parseApiResult(data, parseFindingDetail, 'finding detail')
}

export async function createReview(
  contractId: string,
  versionNumber: number,
  contractPackKey?: string | null,
): Promise<ReviewSummary> {
  const client = createApiClient()
  const { data, error, response } = await client.POST(
    '/api/contracts/{contractId}/versions/{versionNumber}/reviews',
    {
      params: { path: { contractId, versionNumber } },
      body: contractPackKey ? { contractPackKey } : {},
    },
  )
  if (!data) {
    throw new ReviewApiError(errorMessage(error, 'Review creation failed'), response.status)
  }
  return parseApiResult(data, parseReviewSummary, 'review summary')
}

export function useContractReviewsQuery(contractId: string): UseQueryResult<ReviewListResponse> {
  return useQuery({
    queryKey: reviewKeys.list(contractId),
    queryFn: () => fetchReviewList(contractId),
    enabled: Boolean(contractId),
    retry: reviewRetry,
  })
}

export function useReviewSummaryQuery(reviewId: string | undefined): UseQueryResult<ReviewSummary> {
  return useQuery({
    queryKey: reviewKeys.summary(reviewId ?? ''),
    queryFn: reviewId ? () => fetchReviewSummary(reviewId) : skipToken,
    enabled: Boolean(reviewId),
    retry: reviewRetry,
    refetchInterval: (query) => reviewPollInterval(query.state.data?.status),
  })
}

export function useReviewProgressQuery(
  reviewId: string | undefined,
  enabled: boolean,
  poll = false,
): UseQueryResult<ReviewProgress> {
  return useQuery({
    queryKey: reviewKeys.progress(reviewId ?? ''),
    queryFn: reviewId ? () => fetchReviewProgress(reviewId) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: false,
    refetchInterval: poll && enabled ? 2500 : false,
  })
}

export function useFindingsQuery(
  reviewId: string | undefined,
  filters: FindingFilters,
  enabled: boolean,
): UseQueryResult<FindingListResponse> {
  return useQuery({
    queryKey: reviewKeys.findings(reviewId ?? '', filters),
    queryFn: reviewId ? () => fetchFindingList(reviewId, filters) : skipToken,
    enabled: Boolean(reviewId) && enabled,
    retry: reviewRetry,
  })
}

export function useFindingQuery(
  reviewId: string | undefined,
  findingId: string | null,
): UseQueryResult<FindingDetail> {
  return useQuery({
    queryKey: reviewKeys.finding(reviewId ?? '', findingId ?? ''),
    queryFn: reviewId && findingId ? () => fetchFindingDetail(reviewId, findingId) : skipToken,
    enabled: Boolean(reviewId) && Boolean(findingId),
    retry: false,
  })
}

export function useCreateReview(
  contractId: string,
  versionNumber: number,
): UseMutationResult<ReviewSummary, Error, { contractPackKey?: string | null }> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ contractPackKey }) => createReview(contractId, versionNumber, contractPackKey),
    retry: false,
    networkMode: 'always',
    onSuccess: (summary) => {
      queryClient.setQueryData(reviewKeys.summary(summary.id), summary)
      void queryClient.invalidateQueries({ queryKey: reviewKeys.list(contractId) })
    },
  })
}