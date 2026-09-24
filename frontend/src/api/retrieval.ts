import { skipToken, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import {
  asRecord,
  errorMessage,
  malformed,
  parseApiResult,
  readArray,
  readBooleanMap,
  readNumber,
  readNumberMap,
  readOptionalBoolean,
  readOptionalNullableNumber,
  readOptionalNullableString,
  readOptionalNumber,
  readString,
  readStringArray,
  readStringMap,
} from './envelope'

export type AuthorityClass =
  | 'POLICY'
  | 'APPROVED_DRAFTING'
  | 'PRECEDENT'
  | 'CURRENT_CONTRACT'
  | 'EXTERNAL_REFERENCE'
  | 'UNKNOWN'

export type EvidenceSufficiency =
  | 'SUFFICIENT'
  | 'PARTIAL'
  | 'POLICY_MISSING'
  | 'AMBIGUOUS_POLICY'
  | 'DRAFTING_MISSING'
  | 'PRECEDENT_WEAK'
  | 'CONTRACT_CONTEXT_INCOMPLETE'
  | 'SOURCE_CONFLICT'
  | 'UNKNOWN'

export type PolicyCompatibility =
  | 'COMPATIBLE'
  | 'INCOMPATIBLE'
  | 'UNKNOWN'
  | 'NOT_ASSESSED'

const AUTHORITY_CLASSES: readonly string[] = [
  'POLICY',
  'APPROVED_DRAFTING',
  'PRECEDENT',
  'CURRENT_CONTRACT',
  'EXTERNAL_REFERENCE',
]

const SUFFICIENCY_STATES: readonly string[] = [
  'SUFFICIENT',
  'PARTIAL',
  'POLICY_MISSING',
  'AMBIGUOUS_POLICY',
  'DRAFTING_MISSING',
  'PRECEDENT_WEAK',
  'CONTRACT_CONTEXT_INCOMPLETE',
  'SOURCE_CONFLICT',
]

const COMPATIBILITY_STATES: readonly string[] = [
  'COMPATIBLE',
  'INCOMPATIBLE',
  'UNKNOWN',
  'NOT_ASSESSED',
]

export type PolicyFacts = {
  positions: string[]
  requiresApproval: boolean
  escalationTrigger: string | null
}

export type EvidenceContractFact = {
  factSchema: string
  schemaVersion: number
  values: Record<string, string>
}

export type LaneInfo = {
  lexicalRank: number | null
  vectorRank: number | null
  fusedRank: number | null
  fusedScore: number | null
}

export type SemanticAssessment = {
  judgments: Record<string, boolean>
  decisionSpecId: string | null
  decisionSpecVersion: number
}

export type EvidenceItem = {
  evidenceId: string
  authorityClass: AuthorityClass
  sourceType: string
  sourceArtifactId: string | null
  sourceCandidateId: string | null
  sourceContractVersionId: string | null
  sourceClauseId: string | null
  legalConcept: string | null
  text: string
  policyFacts: PolicyFacts | null
  contractFacts: EvidenceContractFact[]
  retrievalReason: string
  laneInfo: LaneInfo
  semanticAssessment: SemanticAssessment | null
  policyCompatibility: PolicyCompatibility | null
  provenanceNodeIds: string[]
  publicationId: string | null
  publicationNumber: number | null
}

export type EvidenceRequestSummary = {
  purpose: string
  legalConcept: string | null
  contractId: string
  versionNumber: number
  clauseId: string
  effectiveOn: string
  knowledgePinPublicationNumber: number | null
}

export type EvidenceBundle = {
  bundleId: string
  request: EvidenceRequestSummary
  items: EvidenceItem[]
  sufficiency: EvidenceSufficiency
  traceId: string
  degradationWarnings: string[]
}

export type RetrievalStage = {
  stage: string
  candidateCount: number
  candidateIds: string[]
  candidateRanks: number[]
  executionIds: string[]
}

export type RejectedEvidence = {
  candidateId: string
  stage: string
  reason: string
}

export type RetrievalTrace = {
  id: string
  purpose: string
  request: Record<string, unknown>
  stages: Record<string, RetrievalStage>
  selectedEvidenceIds: string[]
  rejectedEvidence: RejectedEvidence[]
  degradation: string[]
  stageTimings: Record<string, number>
  modelVersions: Record<string, string>
  knowledgePinPublicationNumber: number | null
}

function parsePolicyFacts(value: unknown, what: string): PolicyFacts | null {
  if (value === null || value === undefined) return null
  const facts = asRecord(value, what)
  return {
    positions: readStringArray(facts, 'positions', what),
    requiresApproval: readOptionalBoolean(facts, 'requiresApproval', what),
    escalationTrigger: readOptionalNullableString(facts, 'escalationTrigger', what),
  }
}

function parseContractFact(value: unknown, what: string): EvidenceContractFact {
  const fact = asRecord(value, what)
  return {
    factSchema: readString(fact, 'factSchema', what),
    schemaVersion: readOptionalNumber(fact, 'schemaVersion', what),
    values: readStringMap(fact, 'values', what),
  }
}

function parseLaneInfo(value: unknown, what: string): LaneInfo {
  if (value === null || value === undefined) {
    return { lexicalRank: null, vectorRank: null, fusedRank: null, fusedScore: null }
  }
  const lane = asRecord(value, what)
  return {
    lexicalRank: readOptionalNullableNumber(lane, 'lexicalRank', what),
    vectorRank: readOptionalNullableNumber(lane, 'vectorRank', what),
    fusedRank: readOptionalNullableNumber(lane, 'fusedRank', what),
    fusedScore: readOptionalNullableNumber(lane, 'fusedScore', what),
  }
}

function parseSemanticAssessment(value: unknown, what: string): SemanticAssessment | null {
  if (value === null || value === undefined) return null
  const assessment = asRecord(value, what)
  return {
    judgments: readBooleanMap(assessment, 'judgments', what),
    decisionSpecId: readOptionalNullableString(assessment, 'decisionSpecId', what),
    decisionSpecVersion: readOptionalNumber(assessment, 'decisionSpecVersion', what),
  }
}

function parseEvidenceItem(value: unknown, what: string): EvidenceItem {
  const item = asRecord(value, what)
  const rawAuthority = readOptionalNullableString(item, 'authorityClass', what)
  const rawCompatibility = readOptionalNullableString(item, 'policyCompatibility', what)
  return {
    evidenceId: readString(item, 'evidenceId', what),
    authorityClass:
      rawAuthority && AUTHORITY_CLASSES.includes(rawAuthority)
        ? (rawAuthority as AuthorityClass)
        : 'UNKNOWN',
    sourceType: readOptionalNullableString(item, 'sourceType', what) ?? 'UNKNOWN',
    sourceArtifactId: readOptionalNullableString(item, 'sourceArtifactId', what),
    sourceCandidateId: readOptionalNullableString(item, 'sourceCandidateId', what),
    sourceContractVersionId: readOptionalNullableString(
      item,
      'sourceContractVersionId',
      what,
    ),
    sourceClauseId: readOptionalNullableString(item, 'sourceClauseId', what),
    legalConcept: readOptionalNullableString(item, 'legalConcept', what),
    text: readOptionalNullableString(item, 'text', what) ?? '',
    policyFacts: parsePolicyFacts(item.policyFacts, what),
    contractFacts: readArray(item, 'contractFacts', what, parseContractFact),
    retrievalReason: readOptionalNullableString(item, 'retrievalReason', what) ?? 'UNKNOWN',
    laneInfo: parseLaneInfo(item.laneInfo, what),
    semanticAssessment: parseSemanticAssessment(item.semanticAssessment, what),
    policyCompatibility:
      rawCompatibility === null
        ? null
        : COMPATIBILITY_STATES.includes(rawCompatibility)
          ? (rawCompatibility as PolicyCompatibility)
          : 'UNKNOWN',
    provenanceNodeIds: readStringArray(item, 'provenanceNodeIds', what),
    publicationId: readOptionalNullableString(item, 'publicationId', what),
    publicationNumber: readOptionalNullableNumber(item, 'publicationNumber', what),
  }
}

function parseEvidenceRequestSummary(value: unknown, what: string): EvidenceRequestSummary {
  const request = asRecord(value, what)
  return {
    purpose: readOptionalNullableString(request, 'purpose', what) ?? 'UNKNOWN',
    legalConcept: readOptionalNullableString(request, 'legalConcept', what),
    contractId: readString(request, 'contractId', what),
    versionNumber: readNumber(request, 'versionNumber', what),
    clauseId: readString(request, 'clauseId', what),
    effectiveOn: readOptionalNullableString(request, 'effectiveOn', what) ?? '',
    knowledgePinPublicationNumber: readOptionalNullableNumber(
      request,
      'knowledgePinPublicationNumber',
      what,
    ),
  }
}

export function parseEvidenceBundle(value: unknown, what = 'evidence bundle'): EvidenceBundle {
  const bundle = asRecord(value, what)
  const rawSufficiency = readOptionalNullableString(bundle, 'sufficiency', what)
  return {
    bundleId: readString(bundle, 'bundleId', what),
    request: parseEvidenceRequestSummary(bundle.request, what),
    items: readArray(bundle, 'items', what, parseEvidenceItem),
    sufficiency:
      rawSufficiency && SUFFICIENCY_STATES.includes(rawSufficiency)
        ? (rawSufficiency as EvidenceSufficiency)
        : 'UNKNOWN',
    traceId: readString(bundle, 'traceId', what),
    degradationWarnings: readStringArray(bundle, 'degradationWarnings', what),
  }
}

function parseRetrievalStage(value: unknown, what: string): RetrievalStage {
  const stage = asRecord(value, what)
  return {
    stage: readOptionalNullableString(stage, 'stage', what) ?? 'UNKNOWN',
    candidateCount: readOptionalNumber(stage, 'candidateCount', what),
    candidateIds: readStringArray(stage, 'candidateIds', what),
    candidateRanks: readArray(stage, 'candidateRanks', what, (rank, stageWhat) => {
      if (typeof rank !== 'number' || !Number.isFinite(rank)) throw malformed(stageWhat)
      return rank
    }),
    executionIds: readStringArray(stage, 'executionIds', what),
  }
}

function parseRejectedEvidence(value: unknown, what: string): RejectedEvidence {
  const rejected = asRecord(value, what)
  return {
    candidateId: readOptionalNullableString(rejected, 'candidateId', what) ?? '',
    stage: readOptionalNullableString(rejected, 'stage', what) ?? 'UNKNOWN',
    reason: readOptionalNullableString(rejected, 'reason', what) ?? '',
  }
}

function parseTraceRequest(value: unknown, what: string): Record<string, unknown> {
  if (value === null || value === undefined) return {}
  return asRecord(value, what)
}

export function parseRetrievalTrace(value: unknown, what = 'retrieval trace'): RetrievalTrace {
  const trace = asRecord(value, what)
  const rawStages = trace.stages
  if (rawStages !== null && rawStages !== undefined) {
    if (typeof rawStages !== 'object' || Array.isArray(rawStages)) throw malformed(what)
  }
  const stages: Record<string, RetrievalStage> = {}
  for (const [key, stage] of Object.entries(rawStages ?? {})) {
    stages[key] = parseRetrievalStage(stage, what)
  }
  return {
    id: readString(trace, 'id', what),
    purpose: readOptionalNullableString(trace, 'purpose', what) ?? 'UNKNOWN',
    request: parseTraceRequest(trace.request, what),
    stages,
    selectedEvidenceIds: readStringArray(trace, 'selectedEvidenceIds', what),
    rejectedEvidence: readArray(trace, 'rejectedEvidence', what, parseRejectedEvidence),
    degradation: readStringArray(trace, 'degradation', what),
    stageTimings: readNumberMap(trace, 'stageTimings', what),
    modelVersions: readStringMap(trace, 'modelVersions', what),
    knowledgePinPublicationNumber: readOptionalNullableNumber(
      trace,
      'knowledgePinPublicationNumber',
      what,
    ),
  }
}

export class RetrievalApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
    this.name = 'RetrievalApiError'
    this.status = status
  }
}

export function isEnrichmentNotReadyError(error: unknown): boolean {
  return error instanceof RetrievalApiError && error.status === 409
}

export function isNotFoundError(error: unknown): boolean {
  return error instanceof RetrievalApiError && error.status === 404
}

export function isForbiddenError(error: unknown): boolean {
  return error instanceof RetrievalApiError && error.status === 403
}

export const retrievalKeys = {
  bundle: (contractId: string, versionNumber: number, clauseId: string) =>
    ['retrieval', 'bundle', contractId, versionNumber, clauseId] as const,
  trace: (traceId: string) => ['retrieval', 'trace', traceId] as const,
}

export type EvidenceBuildInput = {
  contractId: string
  versionNumber: number
  clauseId: string
}

export async function buildEvidence(input: EvidenceBuildInput): Promise<EvidenceBundle> {
  const client = createApiClient()
  const { data, error, response } = await client.POST('/api/retrieval/evidence', {
    body: {
      contractId: input.contractId,
      versionNumber: input.versionNumber,
      clauseId: input.clauseId,
    },
  })
  if (!data) {
    throw new RetrievalApiError(
      errorMessage(error, 'Evidence construction failed'),
      response.status,
    )
  }
  return parseApiResult(data, parseEvidenceBundle, 'evidence bundle')
}

export function useBuildEvidence(
  contractId: string,
  versionNumber: number,
): UseMutationResult<EvidenceBundle, Error, { clauseId: string }> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ clauseId }) => buildEvidence({ contractId, versionNumber, clauseId }),
    onSuccess: (bundle) => {
      queryClient.setQueryData(
        retrievalKeys.bundle(contractId, versionNumber, bundle.request.clauseId),
        bundle,
      )
    },
  })
}

export function useEvidenceBundle(
  contractId: string,
  versionNumber: number,
  clauseId: string | null,
): UseQueryResult<EvidenceBundle, Error> {
  return useQuery({
    queryKey: retrievalKeys.bundle(contractId, versionNumber, clauseId ?? ''),
    queryFn: skipToken,
  })
}

export async function fetchRetrievalTrace(traceId: string): Promise<RetrievalTrace> {
  const client = createApiClient()
  const { data, error, response } = await client.GET('/api/retrieval/traces/{id}', {
    params: { path: { id: traceId } },
  })
  if (!data) {
    throw new RetrievalApiError(
      errorMessage(error, 'Retrieval trace unavailable'),
      response.status,
    )
  }
  return parseApiResult(data, parseRetrievalTrace, 'retrieval trace')
}

export function useRetrievalTraceQuery(
  traceId: string | null,
  enabled: boolean,
): UseQueryResult<RetrievalTrace, Error> {
  return useQuery({
    queryKey: retrievalKeys.trace(traceId ?? ''),
    queryFn: traceId ? () => fetchRetrievalTrace(traceId) : skipToken,
    enabled: enabled && traceId !== null,
  })
}
