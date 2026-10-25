import { useRef } from 'react'
import { skipToken, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type {
  InfiniteData,
  UseInfiniteQueryResult,
  UseMutationResult,
  UseQueryResult,
} from '@tanstack/react-query'
import { createApiClient } from './client'
import { parseContractFact, parseEvidenceItem } from './retrieval'
import type { EvidenceContractFact, EvidenceItem } from './retrieval'
import type { components, paths } from './schema'
import { boundedErrorMessage } from './errorMessage'
import {
  asRecord,
  malformed,
  parseApiResult,
  readArray,
  readNumber,
  readNumberMap,
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

/**
 * How many rows Home asks for. Bounded on purpose: Home is a work queue, not an archive, and the
 * backend rejects anything above its own maximum. Reviews the page does not show are reachable
 * from Reviews, and Home says so when the response carries a continuation cursor.
 */
export const HOME_PAGE_LIMIT = 20

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

/** A published playbook or clause library the review froze at creation. */
export type PlaybookUsed = {
  artifactId: string
  artifactType: string
  name: string
  publicationNumber: number
}

export type ReviewSnapshot = {
  profile: ReviewSnapshotProfile | null
  packVersion: string | null
  knowledgePinNumber: number | null
  effectiveOn: string | null
  /** The review engine the review is frozen on: 'V1' (one review) or 'V2' (a second opinion when the first review is unsure). */
  engine: string | null
  /** Null on a review created before the freeze existed; empty means none applied. */
  playbooksUsed: PlaybookUsed[] | null
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

export type ReviewDocument = {
  contractFilename: string
  uploadedAt: string
  sizeBytes: number
}

/**
 * Rivell's own proposals for the counted generation. These are orientation before anyone
 * decides, never a count of approved changes, and the three kinds are not equivalent:
 * `replace` carries replacement wording, `insert` is a detected gap Rivell proposes no
 * wording for, and `delete` is always zero because deletion is a human decision only.
 */
export type ProposedChangeCounts = {
  replace: number
  insert: number
  delete: number
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
  proposedChanges: ProposedChangeCounts
  document: ReviewDocument | null
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

/** The human-declared context this review froze at creation. Never inferred by the client. */
export type ReviewListContext = {
  contractType: string | null
  ourSideRole: string | null
  paperSource: string | null
  /** The real `UNSPECIFIED` constant when none was declared. */
  jurisdiction: string | null
}

/** Named and counted exactly as the review's own human-review read. Readiness is the server's. */
export type ReviewListHumanReview = {
  status: string
  actionableFindings: number
  resolvedFindings: number
  remainingFindings: number
  escalated: number
  readyToFinalize: boolean
  /** Server-owned reason codes. `ANALYSIS_NOT_COMPLETE` is one: it is not human attention. */
  blockers: string[]
}

/** Present only once the review is finalized. */
export type ReviewListFinalization = {
  finalizedAt: string
  approvedChanges: number
}

/** Present only once a redline was requested. A failed execution carries no verified count. */
export type ReviewListRedline = {
  status: string
  outcome: string | null
  verifiedChangeCount: number | null
  downloadAvailable: boolean
  completedAt: string | null
}

export type ReviewListItem = {
  id: string
  contractId: string
  contractVersionNumber: number | null
  /** The uploaded file's name. Null only when the server sends no filename. */
  contractFilename: string | null
  /** When the pinned contract version was uploaded. Not the review's creation time. */
  uploadedAt: string | null
  status: ReviewStatus
  failure: ReviewFailure | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  plannedChecks: number
  completedChecks: number
  degradedChecks: number
  failedChecks: number
  findings: number
  context: ReviewListContext | null
  humanReview: ReviewListHumanReview | null
  finalization: ReviewListFinalization | null
  redline: ReviewListRedline | null
}

export type ReviewListResponse = {
  items: ReviewListItem[]
  truncated: boolean
  /** Non-null exactly when more rows exist beyond this page. */
  nextCursor: string | null
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
  /** True when the position governs a topic the clause only touches: a LOW note, not a breach. */
  relatedPosition: boolean
  /** Who decided: 'JEV' (Rivell's first review), 'SECOND_OPINION' or 'NEEDS_YOUR_LOOK' (the user decides). Null for other findings. */
  decidedBy: string | null
  createdAt: string
  /** The user who added this finding because Rivell missed it; null for Rivell's own findings. */
  addedBy: string | null
  /** A "needs your look" item's first-review answer (a comparison category); null otherwise. */
  firstReviewCategory: string | null
  /** The missing playbook position's own label on an omission finding; null otherwise. */
  positionLabel: string | null
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
  /** The policy comparison's code-owned category. Null for findings from other checks. */
  category: string | null
  /** The model's summed flag probability on a 0–1 scale. An estimate, never a certainty. */
  flagProbability: number | null
  /** The per-option probabilities the flag probability was summed from. */
  probabilities: Record<string, number>
  /** True when the position governs a topic the clause only touches, not its own subject. */
  secondaryTopic: boolean
  /** The uncovered-clause triage's probability on the same 0–1 scale. An estimate too. */
  uncoveredProbability: number | null
  /** Who decided: 'JEV' (Rivell's first review), 'SECOND_OPINION' or 'NEEDS_YOUR_LOOK'. */
  route: string | null
  /** The first review's own answer on a "needs your look" item (a comparison category). */
  jevCategory: string | null
  /** AGREED, DISAGREED, UNAVAILABLE, INVALID or OVER_BUDGET. */
  secondOpinionOutcome: string | null
  secondOpinionCategory: string | null
  /** The clause words the second opinion rests on: model output, shown as a quote. */
  secondOpinionQuote: string | null
  secondOpinionReason: string | null
  /** Why Rivell left the item to the user: playbook_silent, omission_unsure, second_opinion_unavailable. */
  uncertaintyReason: string | null
  /** 'SEARCH' when the position was found by searching the playbook for the clause's text. */
  foundBy: string | null
  /** The fit check's verdict on a search-found rule: FITS, DOES_NOT_FIT or UNKNOWN. */
  fitVerdict: string | null
  /** The fit check's code-owned reason for a search-found rule. */
  fitReason: string | null
  /** The whole-contract read's own severity (HIGH or MEDIUM) and reason for a playbook-silent pick. */
  spottedSeverity: string | null
  spottedReason: string | null
  /** The fit check's verdict on the library wording offered: FITS, DOES_NOT_FIT or UNKNOWN. */
  wordingFitVerdict: string | null
  /** The fit check's code-owned reason for that verdict. */
  wordingFitReason: string | null
  /** Omission check: the estimate that the playbook position requires the provision, 0–1. */
  presenceProbability: number | null
  /** The typed approval rule behind an escalation; null when no rule applies. */
  escalationApprover?: string | null
  /** The rule's own condition, shown as "Escalate if". */
  escalationCondition?: string | null
  /** Where the rule was stated: ENTRY, MATRIX or TABLE. */
  escalationSource?: string | null
  /** The stance the rule was read for: FALLBACK or PROHIBITED. */
  escalationStance?: string | null
  /** A lower-level rule naming a different approver for the same stance. */
  escalationConflictApprover?: string | null
  escalationConflictSource?: string | null
}

export type AbsenceBasis = {
  planCheckId: string
  checkKey: string
}

/**
 * The finding's own positions, grouped by the server into the slots the Workspace compares. Each
 * slot lists every cited item that holds the role, in the finding's frozen evidence order, and each
 * carries the evidence id that opens its source.
 */
export type ContractPositionSlot = {
  evidenceItemId: string
  authorityClass: string
  legalConcept: string | null
  /** Typed facts extracted from the frozen clause. There is no sentence to read instead. */
  facts: EvidenceContractFact[]
}

export type PolicyPositionSlot = {
  evidenceItemId: string
  authorityClass: string
  legalConcept: string | null
  publicationNumber: number | null
  /** The authored wording, verbatim. */
  text: string
}

export type EscalationSlot = {
  evidenceItemId: string
  authorityClass: string
  publicationNumber: number | null
  trigger: string
}

export type PositionComparison = {
  contract: ContractPositionSlot[]
  preferred: PolicyPositionSlot[]
  fallback: PolicyPositionSlot[]
  walkAway: PolicyPositionSlot[]
  escalation: EscalationSlot[]
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
  /** Null when the server found no position data in the finding's evidence. */
  positionComparison: PositionComparison | null
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

function parsePlaybooksUsed(value: unknown, what: string): PlaybookUsed[] | null {
  if (value === null || value === undefined) return null
  if (!Array.isArray(value)) throw malformed(what)
  return value.map((entry, index) => {
    const item = asRecord(entry, `${what}.playbooksUsed[${index}]`)
    return {
      artifactId: readString(item, 'artifactId', what),
      artifactType: readString(item, 'artifactType', what),
      name: readString(item, 'name', what),
      publicationNumber: readNumber(item, 'publicationNumber', what),
    }
  })
}

function parseSnapshot(value: unknown, what: string): ReviewSnapshot | null {
  if (value === null || value === undefined) return null
  const snapshot = asRecord(value, what)
  return {
    profile: parseSnapshotProfile(snapshot.profile, what),
    packVersion: readOptionalNullableString(snapshot, 'packVersion', what),
    knowledgePinNumber: readOptionalNullableNumber(snapshot, 'knowledgePinNumber', what),
    effectiveOn: readOptionalNullableString(snapshot, 'effectiveOn', what),
    engine: readOptionalNullableString(snapshot, 'engine', what),
    playbooksUsed: parsePlaybooksUsed(snapshot.playbooksUsed, what),
  }
}

function parseProposedChangeCounts(value: unknown, what: string): ProposedChangeCounts {
  const counts = value === null || value === undefined ? {} : asRecord(value, what)
  return {
    replace: readOptionalNumber(counts, 'replace', what),
    insert: readOptionalNumber(counts, 'insert', what),
    delete: readOptionalNumber(counts, 'delete', what),
  }
}

function parseReviewDocument(value: unknown, what: string): ReviewDocument | null {
  if (value === null || value === undefined) return null
  const document = asRecord(value, what)
  return {
    contractFilename: readString(document, 'contractFilename', what),
    uploadedAt: readString(document, 'uploadedAt', what),
    sizeBytes: readNumber(document, 'sizeBytes', what),
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
    proposedChanges: parseProposedChangeCounts(summary.proposedChanges, what),
    document: parseReviewDocument(summary.document, what),
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

function parseListContext(value: unknown, what: string): ReviewListContext | null {
  if (value === null || value === undefined) return null
  const context = asRecord(value, what)
  return {
    contractType: readOptionalNullableString(context, 'contractType', what),
    ourSideRole: readOptionalNullableString(context, 'ourSideRole', what),
    paperSource: readOptionalNullableString(context, 'paperSource', what),
    jurisdiction: readOptionalNullableString(context, 'jurisdiction', what),
  }
}

function parseListHumanReview(value: unknown, what: string): ReviewListHumanReview | null {
  if (value === null || value === undefined) return null
  const human = asRecord(value, what)
  return {
    status: readString(human, 'status', what),
    actionableFindings: readOptionalNumber(human, 'actionableFindings', what),
    resolvedFindings: readOptionalNumber(human, 'resolvedFindings', what),
    remainingFindings: readOptionalNumber(human, 'remainingFindings', what),
    escalated: readOptionalNumber(human, 'escalated', what),
    // A missing flag is never read as ready.
    readyToFinalize: readOptionalBoolean(human, 'readyToFinalize', what),
    blockers: human.blockers === undefined || human.blockers === null ? [] : readStringArray(human, 'blockers', what),
  }
}

function parseListFinalization(value: unknown, what: string): ReviewListFinalization | null {
  if (value === null || value === undefined) return null
  const finalization = asRecord(value, what)
  return {
    finalizedAt: readString(finalization, 'finalizedAt', what),
    approvedChanges: readOptionalNumber(finalization, 'approvedChanges', what),
  }
}

function parseListRedline(value: unknown, what: string): ReviewListRedline | null {
  if (value === null || value === undefined) return null
  const redline = asRecord(value, what)
  return {
    status: readString(redline, 'status', what),
    outcome: readOptionalNullableString(redline, 'outcome', what),
    verifiedChangeCount: readOptionalNullableNumber(redline, 'verifiedChangeCount', what),
    downloadAvailable: readOptionalBoolean(redline, 'downloadAvailable', what),
    completedAt: readOptionalNullableString(redline, 'completedAt', what),
  }
}

function parseReviewListItem(value: unknown, what: string): ReviewListItem {
  const item = asRecord(value, what)
  return {
    id: readString(item, 'id', what),
    contractId: readString(item, 'contractId', what),
    contractVersionNumber: readOptionalNullableNumber(item, 'contractVersionNumber', what),
    contractFilename: readOptionalNullableString(item, 'contractFilename', what),
    uploadedAt: readOptionalNullableString(item, 'uploadedAt', what),
    status: readStatus(item, 'status', REVIEW_STATUSES),
    failure: parseFailure(item.failure, what),
    createdAt: readString(item, 'createdAt', what),
    startedAt: readOptionalNullableString(item, 'startedAt', what),
    completedAt: readOptionalNullableString(item, 'completedAt', what),
    plannedChecks: readOptionalNumber(item, 'plannedChecks', what),
    completedChecks: readOptionalNumber(item, 'completedChecks', what),
    degradedChecks: readOptionalNumber(item, 'degradedChecks', what),
    failedChecks: readOptionalNumber(item, 'failedChecks', what),
    findings: readOptionalNumber(item, 'findings', what),
    context: parseListContext(item.context, what),
    humanReview: parseListHumanReview(item.humanReview, what),
    finalization: parseListFinalization(item.finalization, what),
    redline: parseListRedline(item.redline, what),
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
    nextCursor: readOptionalNullableString(list, 'nextCursor', what),
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
    relatedPosition: readOptionalBoolean(finding, 'relatedPosition', what),
    decidedBy: readOptionalNullableString(finding, 'decidedBy', what),
    createdAt: readOptionalNullableString(finding, 'createdAt', what) ?? '',
    addedBy: readOptionalNullableString(finding, 'addedBy', what),
    firstReviewCategory: readOptionalNullableString(finding, 'firstReviewCategory', what),
    positionLabel: readOptionalNullableString(finding, 'positionLabel', what),
  }
}

const CLAUSE_MARKS = ['FLAGGED', 'CHECKED', 'NOT_COVERED', 'NOT_ANALYSED'] as const

/** What a review did with one planned clause (spec §9). */
export type ClauseMark = (typeof CLAUSE_MARKS)[number]

export type ClauseMarksResponse = {
  /** Null while no analysis has completed: there is nothing to mark yet. */
  runId: string | null
  clauses: { headingNodeId: string; mark: ClauseMark }[]
}

function parseClauseMarks(value: unknown, what: string): ClauseMarksResponse {
  const record = asRecord(value, what)
  return {
    runId: readOptionalNullableString(record, 'runId', what),
    clauses: readArray(record, 'clauses', what, (entry, entryWhat) => {
      const clause = asRecord(entry, entryWhat)
      const mark = readStatus(clause, 'mark', CLAUSE_MARKS)
      return {
        headingNodeId: readString(clause, 'headingNodeId', entryWhat),
        // an unknown mark is shown as not analysed: never as checked
        mark: mark === 'UNKNOWN' ? 'NOT_ANALYSED' : mark,
      }
    }),
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
  const flagProbability = readOptionalNullableNumber(quality, 'flagProbability', what)
  const uncoveredProbability = readOptionalNullableNumber(quality, 'uncoveredProbability', what)
  const presenceProbability = readOptionalNullableNumber(quality, 'presenceProbability', what)
  return {
    sufficiency: readOptionalNullableString(quality, 'sufficiency', what),
    verificationOutcome: readOptionalNullableString(quality, 'verificationOutcome', what),
    degradationFlags: parseOptionalStringArray(quality, 'degradationFlags', what),
    category: readOptionalNullableString(quality, 'category', what),
    // A probability outside 0–1 is not on the scale the comparison uses, so it is not shown.
    flagProbability:
      flagProbability !== null && flagProbability >= 0 && flagProbability <= 1
        ? flagProbability
        : null,
    probabilities: readNumberMap(quality, 'probabilities', what),
    secondaryTopic: readOptionalBoolean(quality, 'secondaryTopic', what),
    uncoveredProbability:
      uncoveredProbability !== null && uncoveredProbability >= 0 && uncoveredProbability <= 1
        ? uncoveredProbability
        : null,
    route: readOptionalNullableString(quality, 'route', what),
    jevCategory: readOptionalNullableString(quality, 'jevCategory', what),
    secondOpinionOutcome: readOptionalNullableString(quality, 'secondOpinionOutcome', what),
    secondOpinionCategory: readOptionalNullableString(quality, 'secondOpinionCategory', what),
    secondOpinionQuote: readOptionalNullableString(quality, 'secondOpinionQuote', what),
    secondOpinionReason: readOptionalNullableString(quality, 'secondOpinionReason', what),
    uncertaintyReason: readOptionalNullableString(quality, 'uncertaintyReason', what),
    foundBy: readOptionalNullableString(quality, 'foundBy', what),
    fitVerdict: readOptionalNullableString(quality, 'fitVerdict', what),
    fitReason: readOptionalNullableString(quality, 'fitReason', what),
    spottedSeverity: readOptionalNullableString(quality, 'spottedSeverity', what),
    spottedReason: readOptionalNullableString(quality, 'spottedReason', what),
    wordingFitVerdict: readOptionalNullableString(quality, 'wordingFitVerdict', what),
    wordingFitReason: readOptionalNullableString(quality, 'wordingFitReason', what),
    presenceProbability:
      presenceProbability !== null && presenceProbability >= 0 && presenceProbability <= 1
        ? presenceProbability
        : null,
    escalationApprover: readOptionalNullableString(quality, 'escalationApprover', what),
    escalationCondition: readOptionalNullableString(quality, 'escalationCondition', what),
    escalationSource: readOptionalNullableString(quality, 'escalationSource', what),
    escalationStance: readOptionalNullableString(quality, 'escalationStance', what),
    escalationConflictApprover: readOptionalNullableString(
      quality,
      'escalationConflictApprover',
      what,
    ),
    escalationConflictSource: readOptionalNullableString(
      quality,
      'escalationConflictSource',
      what,
    ),
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

// A slot list the server leaves out is an empty slot. Nothing is filled in for it.
function readSlots<T>(
  source: Record<string, unknown>,
  key: string,
  what: string,
  parseItem: (item: unknown, what: string) => T,
): T[] {
  return source[key] === undefined || source[key] === null ? [] : readArray(source, key, what, parseItem)
}

function parsePositionComparison(value: unknown, what: string): PositionComparison | null {
  if (value === null || value === undefined) return null
  const comparison = asRecord(value, what)
  return {
    contract: readSlots(comparison, 'contract', what, (item, label) => {
      const slot = asRecord(item, label)
      return {
        evidenceItemId: readString(slot, 'evidenceItemId', label),
        authorityClass: readOptionalNullableString(slot, 'authorityClass', label) ?? 'UNKNOWN',
        legalConcept: readOptionalNullableString(slot, 'legalConcept', label),
        facts: readSlots(slot, 'facts', label, parseContractFact),
      }
    }),
    preferred: readSlots(comparison, 'preferred', what, parsePolicyPositionSlot),
    fallback: readSlots(comparison, 'fallback', what, parsePolicyPositionSlot),
    walkAway: readSlots(comparison, 'walkAway', what, parsePolicyPositionSlot),
    escalation: readSlots(comparison, 'escalation', what, (item, label) => {
      const slot = asRecord(item, label)
      return {
        evidenceItemId: readString(slot, 'evidenceItemId', label),
        authorityClass: readOptionalNullableString(slot, 'authorityClass', label) ?? 'UNKNOWN',
        publicationNumber: readOptionalNullableNumber(slot, 'publicationNumber', label),
        trigger: readOptionalNullableString(slot, 'trigger', label) ?? '',
      }
    }),
  }
}

function parsePolicyPositionSlot(item: unknown, what: string): PolicyPositionSlot {
  const slot = asRecord(item, what)
  return {
    evidenceItemId: readString(slot, 'evidenceItemId', what),
    authorityClass: readOptionalNullableString(slot, 'authorityClass', what) ?? 'UNKNOWN',
    legalConcept: readOptionalNullableString(slot, 'legalConcept', what),
    publicationNumber: readOptionalNullableNumber(slot, 'publicationNumber', what),
    text: readOptionalNullableString(slot, 'text', what) ?? '',
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
    positionComparison: parsePositionComparison(finding.positionComparison, what),
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

// A review whose background worker is not running stays queued forever, so the poll is bounded
// rather than running for the life of the page. The review read is the only way to notice a
// completion, so this is a cap on patience, not a limit on correctness.
//
// The budget has to cover the project's own reference happy path: scripts/demo-reproduction.sh
// waits 90 x 4s = 360s for the same run. A shorter cap let the UI give up while the reference path
// was still working, and the pending states carry no refresh control, so the page then sat on a
// stale "analysing" view until a full reload.
export const REVIEW_POLL_INTERVAL_MS = 2500
export const POLL_BUDGET_MS = 360_000
export const MAX_POLL_ATTEMPTS = Math.ceil(POLL_BUDGET_MS / REVIEW_POLL_INTERVAL_MS)

export function reviewPollInterval(status: ReviewStatus | undefined | null): number | false {
  return isReviewPending(status) ? REVIEW_POLL_INTERVAL_MS : false
}

const REVIEW_MESSAGES: Partial<Record<number, string>> = {
  403: 'You do not have permission to read this review.',
  404: 'That review could not be found.',
  409: 'That review request does not apply in the current state.',
  500: 'The review could not be loaded.',
  503: 'The review service is unavailable right now.',
}

const REVIEW_PACK_MESSAGES: Partial<Record<number, string>> = {
  500: 'The review pack catalog could not be loaded.',
  503: 'The review pack catalog service is unavailable right now.',
}

class ReviewApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'ReviewApiError'
    this.status = status
  }
}

export function isReviewNotFoundError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 404
}

export function isReviewForbiddenError(error: unknown): boolean {
  return error instanceof ReviewApiError && error.status === 403
}

export function reviewErrorStatus(error: unknown): number | null {
  return error instanceof ReviewApiError ? error.status : null
}

function reviewRetry(failureCount: number, error: Error): boolean {
  // A permission denial or a missing resource cannot become true on a second identical request.
  return failureCount < 1 && !isReviewNotFoundError(error) && !isReviewForbiddenError(error)
}

export const reviewKeys = {
  packs: () => ['reviews', 'packs'] as const,
  list: (contractId: string) => ['reviews', 'list', contractId] as const,
  organization: () => ['reviews', 'organization'] as const,
  history: () => ['reviews', 'history'] as const,
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
  clauseMarks: (reviewId: string) => ['reviews', 'clause-marks', reviewId] as const,
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

/** One page of the newest reviews across the whole organization. */
export async function fetchOrganizationReviewList(): Promise<ReviewListResponse> {
  const client = createApiClient()
  // contractId is deliberately omitted: Home shows all review work in one read. Enumerating
  // contracts first and then reading reviews per contract is the N+1 this endpoint removes.
  const { data, response } = await client.GET('/api/reviews', {
    params: { query: { limit: HOME_PAGE_LIMIT } },
  })
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Review list unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseReviewList, 'review list')
}

/**
 * Page size for the Reviews history. Larger than Home's queue because this page is the archive,
 * and still well under the backend maximum.
 */
export const REVIEW_HISTORY_PAGE_LIMIT = 25

async function fetchReviewHistoryPage(cursor: string | undefined): Promise<ReviewListResponse> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews', {
    params: { query: { limit: REVIEW_HISTORY_PAGE_LIMIT, ...(cursor ? { cursor } : {}) } },
  })
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Review list unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseReviewList, 'review list')
}

/**
 * Every review in the organization, newest first, one cursor page at a time. Nothing is fetched
 * ahead of the reviewer: more pages load only when they ask for them.
 */
export function useReviewHistoryQuery(): UseInfiniteQueryResult<
  InfiniteData<ReviewListResponse, string | undefined>
> {
  return useInfiniteQuery({
    queryKey: reviewKeys.history(),
    queryFn: ({ pageParam }) => fetchReviewHistoryPage(pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    staleTime: 15_000,
    retry: reviewRetry,
  })
}

export function useOrganizationReviewsQuery(): UseQueryResult<ReviewListResponse> {
  return useQuery({
    queryKey: reviewKeys.organization(),
    queryFn: fetchOrganizationReviewList,
    staleTime: 15_000,
    retry: reviewRetry,
  })
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

export async function fetchClauseMarks(reviewId: string): Promise<ClauseMarksResponse> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/reviews/{id}/clause-marks', {
    params: { path: { id: reviewId } },
  })
  if (!data) {
    throw new ReviewApiError(
      boundedErrorMessage(response.status, REVIEW_MESSAGES, 'Clause marks unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseClauseMarks, 'clause marks')
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
    // The create panel classifies this by status, so this message is never rendered. It exists only
    // so the thrown error is still readable in a log or a test.
    throw new ReviewApiError('Review creation failed.', response.status)
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
  const pollAttempts = useRef(0)
  return useQuery({
    queryKey: reviewKeys.summary(reviewId ?? ''),
    queryFn: reviewId ? () => fetchReviewSummary(reviewId) : skipToken,
    enabled: Boolean(reviewId),
    retry: reviewRetry,
    refetchInterval: (query) => {
      if (query.state.data?.status !== 'ANALYSIS_QUEUED' && query.state.data?.status !== 'ANALYZING') {
        pollAttempts.current = 0
        return false
      }
      if (pollAttempts.current >= MAX_POLL_ATTEMPTS) return false
      pollAttempts.current += 1
      return reviewPollInterval(query.state.data?.status)
    },
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

/** What the active run did with each planned clause; refetched when a finding is added. */
export function useClauseMarksQuery(
  reviewId: string | undefined,
  enabled: boolean,
): UseQueryResult<ClauseMarksResponse> {
  return useQuery({
    queryKey: reviewKeys.clauseMarks(reviewId ?? ''),
    queryFn: reviewId ? () => fetchClauseMarks(reviewId) : skipToken,
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