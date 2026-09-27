import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import { parseEvidenceItem } from './retrieval'
import type { EvidenceItem } from './retrieval'
import type { components, paths } from './schema'
import { boundedErrorMessage } from './errorMessage'
import {
  asRecord,
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
  reasonCode: string
  message: string
}

export type ReviewFailure = {
  code: string
  message: string
}

export type ReviewSnapshotProfile = {
  contractType: string | null
  ourSideRole: string | null
  paperSource: string | null
  jurisdiction: string | null
  contractPackKey: string | null
}

export type ReviewSnapshot = {
  profile: ReviewSnapshotProfile | null
  packVersion: string | null
  knowledgePinNumber: number | null
  effectiveOn: string | null
}

export const COVERAGE_STATES = [
  'PRESENT_PRIMARY',
  'PRESENT_SECONDARY_ONLY',
  'ABSENT',
  'UNKNOWN',
] as const
export type CoverageState = (typeof COVERAGE_STATES)[number] | 'UNKNOWN'

export type ReviewCoverage = {
  concept: string
  expectation: string
  coverage: CoverageState
}

export type ReviewPack = {
  key: string
  displayName: string
  summary: string
  version: string
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
  failure: ReviewFailure | null
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
  snapshot: ReviewSnapshot | null
  coverage: ReviewCoverage[]
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
  evidenceItems: EvidenceItem[]
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
    reasonCode: readOptionalNullableString(warning, 'reasonCode', what) ?? 'UNKNOWN',
    message: readOptionalNullableString(warning, 'message', what) ?? '',
  }
}

function parseFailure(value: unknown, what: string): ReviewFailure | null {
  if (value === null || value === undefined) return null
  const failure = asRecord(value, what)
  return {
    code: readOptionalNullableString(failure, 'code', what) ?? 'UNKNOWN',
    message: readOptionalNullableString(failure, 'message', what) ?? '',
  }
}

function parseSnapshotProfile(value: unknown, what: string): ReviewSnapshotProfile | null {
  if (value === null || value === undefined) return null
  const profile = asRecord(value, what)
  return {
    contractType: readOptionalNullableString(profile, 'contractType', what),
    ourSideRole: readOptionalNullableString(profile, 'ourSideRole', what),
    paperSource: readOptionalNullableString(profile, 'paperSource', what),
    jurisdiction: readOptionalNullableString(profile, 'jurisdiction', what),
    contractPackKey: readOptionalNullableString(profile, 'contractPackKey', what),
  }
}

function parseSnapshot(value: unknown, what: string): ReviewSnapshot | null {
  if (value === null || value === undefined) return null
  const snapshot = asRecord(value, what)
  return {
    profile: parseSnapshotProfile(snapshot.profile, what),
    packVersion: readOptionalNullableString(snapshot, 'packVersion', what),
    knowledgePinNumber: readOptionalNullableNumber(snapshot, 'knowledgePinNumber', what),
    effectiveOn: readOptionalNullableString(snapshot, 'effectiveOn', what),
  }
}

function parseCoverage(value: unknown, what: string): ReviewCoverage {
  const coverage = asRecord(value, what)
  const state = readOptionalNullableString(coverage, 'coverage', what)
  return {
    concept: readOptionalNullableString(coverage, 'concept', what) ?? 'UNKNOWN',
    expectation: readOptionalNullableString(coverage, 'expectation', what) ?? 'UNKNOWN',
    coverage:
      state && (COVERAGE_STATES as readonly string[]).includes(state)
        ? (state as CoverageState)
        : 'UNKNOWN',
  }
}

function parseReviewPack(value: unknown, what: string): ReviewPack {
  const pack = asRecord(value, what)
  return {
    key: readOptionalNullableString(pack, 'key', what) ?? '',
    displayName: readOptionalNullableString(pack, 'displayName', what) ?? '',
    summary: readOptionalNullableString(pack, 'summary', what) ?? '',
    version: readOptionalNullableString(pack, 'version', what) ?? '',
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
    failure: parseFailure(summary.failure, what),
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
    snapshot: parseSnapshot(summary.snapshot, what),
    coverage: readArray(summary, 'coverage', what, parseCoverage),
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
    evidenceItems: readArray(finding, 'evidenceItems', what, parseEvidenceItem),
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

const REVIEW_MESSAGES: Partial<Record<number, string>> = {
  404: 'That review could not be found.',
  409: 'That review request does not apply in the current state.',
  500: 'The review could not be loaded.',
  503: 'The review service is unavailable right now.',
}

const REVIEW_PACK_MESSAGES: Partial<Record<number, string>> = {
  500: 'The review pack catalog could not be loaded.',
  503: 'The review pack catalog service is unavailable right now.',
}

const REVIEW_CREATE_MESSAGES: Partial<Record<number, string>> = {
  400: 'The review could not be started with the selected options.',
  403: 'You do not have permission to start a review.',
  404: 'This contract version is not available for review.',
  409: 'This contract version is not ready for an automated review yet.',
  500: 'The review could not be started.',
  503: 'The review service is unavailable right now.',
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

export function isReviewPayloadTooLargeError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 413
}

export function reviewErrorStatus(error: unknown): number | null {
  return error instanceof ReviewApiError ? error.status : null
}

function reviewRetry(failureCount: number, error: Error): boolean {
  return failureCount < 1 && !isReviewNotFoundError(error)
}

export const reviewKeys = {
  packs: () => ['reviews', 'packs'] as const,
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

export async function fetchReviewPacks(): Promise<ReviewPack[]> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/packs')
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_PACK_MESSAGES, 'Review pack catalog unavailable'),
      response.status,
    )
  }
  return parseApiResult(
    data,
    (value, what) => readArray(asRecord(value, what), 'packs', what, parseReviewPack),
    'review pack catalog',
  )
}

export function useReviewPacksQuery(): UseQueryResult<ReviewPack[]> {
  return useQuery({
    queryKey: reviewKeys.packs(),
    queryFn: fetchReviewPacks,
    staleTime: 5 * 60_000,
    retry: reviewRetry,
  })
}

export async function fetchReviewList(contractId: string): Promise<ReviewListResponse> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews', {
    params: { query: { contractId, limit: MAX_PAGE_LIMIT } },
  })
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Review list unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseReviewList, 'review list')
}

export async function fetchReviewSummary(reviewId: string): Promise<ReviewSummary> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}', {
    params: { path: { id: reviewId } },
  })
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Review unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseReviewSummary, 'review summary')
}

export async function fetchReviewProgress(reviewId: string): Promise<ReviewProgress> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}/progress', {
    params: { path: { id: reviewId } },
  })
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Review progress unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseReviewProgress, 'review progress')
}

export async function fetchFindingList(
  reviewId: string,
  filters: FindingFilters,
): Promise<FindingListResponse> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}/findings', {
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
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Findings unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseFindingList, 'finding list')
}

export async function fetchFindingDetail(
  reviewId: string,
  findingId: string,
): Promise<FindingDetail> {
  const client = createApiClient()
  const { data, response } = await client.GET(
    '/api/reviews/{id}/findings/{findingId}',
    { params: { path: { id: reviewId, findingId } } },
  )
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Finding unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseFindingDetail, 'finding detail')
}

export async function createReview(
  contractId: string,
  versionNumber: number,
  contractPackKey?: string | null,
  clientRequestId?: string | null,
): Promise<ReviewSummary> {
  const client = createApiClient()
  const body: components['schemas']['CreateReviewRequest'] = {}
  if (contractPackKey) body.contractPackKey = contractPackKey
  if (clientRequestId) body.clientRequestId = clientRequestId
  const { data, response } = await client.POST(
    '/api/contracts/{contractId}/versions/{versionNumber}/reviews',
    {
      params: { path: { contractId, versionNumber } },
      body,
    },
  )
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_CREATE_MESSAGES, 'Review creation failed'),
      response.status,
    )
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
): UseMutationResult<
  ReviewSummary,
  Error,
  { contractPackKey?: string | null; clientRequestId?: string | null }
> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ contractPackKey, clientRequestId }) =>
      createReview(contractId, versionNumber, contractPackKey, clientRequestId),
    retry: false,
    networkMode: 'always',
    onSuccess: (summary) => {
      queryClient.setQueryData(reviewKeys.summary(summary.id), summary)
      void queryClient.invalidateQueries({ queryKey: reviewKeys.list(contractId) })
    },
  })
}