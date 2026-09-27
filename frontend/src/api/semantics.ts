import { useRef } from 'react'
import {
  useMutation,
  useQuery,
  useQueryClient,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { createApiClient } from './client'
import {
  asRecord,
  parseApiResult,
  readArray,
  readNullableClosedString,
  readNumber,
  readOptionalBoolean,
  readOptionalNullableNumber,
  readOptionalNullableString,
  readOptionalNumber,
  readString,
  readStringArray,
  readStringMap,
} from './envelope'
import type { components } from './schema'

export type ReviewProfileInput = components['schemas']['ReviewProfileInput']
export type PartyRole = NonNullable<ReviewProfileInput['ourSideRole']>
export type EnrichmentStatus =
  | 'NOT_ENRICHED'
  | 'ENRICHING'
  | 'READY'
  | 'FAILED'
  | 'UNKNOWN'

export const ENRICHMENT_FAILURE_REASONS = [
  'NO_PROVIDER_CONFIGURED',
  'PROVIDER_UNAVAILABLE',
  'PROVIDER_TIMEOUT',
  'PROVIDER_REJECTED',
  'PROVIDER_PROTOCOL',
  'UNSUPPORTED_SOURCE',
  'INTERNAL',
] as const

export type EnrichmentFailureReason = (typeof ENRICHMENT_FAILURE_REASONS)[number] | 'UNKNOWN'

const ENRICHMENT_STATUSES: readonly string[] = [
  'NOT_ENRICHED',
  'ENRICHING',
  'READY',
  'FAILED',
]

export type ReviewProfile = {
  contractType: string
  ourSideRole: string | null
  paperSource: string | null
  jurisdiction: string | null
}

export type SemanticMetadataFact = {
  field: string
  value: string
  correctedValue: string | null
  corrected: boolean
  sourceNodeIds: string[]
}

export type SemanticParty = {
  id: string
  name: string
  aliases: string[]
  machineRoles: string[]
  effectiveRoles: string[]
  corrected: boolean
  sourceNodeIds: string[]
}

export type SemanticDefinedTerm = {
  id: string
  term: string
  definition: string
  definingNodeId: string
  referenceNodeIds: string[]
  duplicate: boolean
}

export type SemanticReference = {
  id: string
  referenceText: string
  kind: string
  status: string
  sourceNodeId: string | null
  targetNodeId: string | null
  candidateNodeIds: string[]
}

export type SemanticFact = {
  factSchema: string
  schemaVersion: number
  values: Record<string, string>
  sourceNodeIds: string[]
}

export type SemanticInterpretation = {
  field: string
  kind: string
  proposedValue: string
  specId: string
  sourceNodeIds: string[]
  verification: string
}

export type SemanticClause = {
  id: string
  headingNodeId: string
  regionKind: string
  heading: string
  primaryConcept: string | null
  secondaryConcepts: string[]
  correctedPrimaryConcept: string | null
  machineFacts: SemanticFact[]
  effectiveFacts: SemanticFact[]
  interpretations: SemanticInterpretation[]
  corrected: boolean
}

export type SemanticRelationship = {
  id: string
  sourceRegionNodeId: string
  targetRegionNodeId: string
  type: string
  detector: string
  basis: string
  probability: number
  verification: string
}

export type SemanticObservations = {
  regionCount: number
  decisionExecutionCount: number
  questionCount: number
  generativeExtractionCount: number
  deterministicFactCount: number
  providerDegraded: boolean
  budgetExhausted: boolean
}

export type SemanticContract = {
  contractId: string
  versionNumber: number
  enrichmentStatus: EnrichmentStatus
  failureReason: EnrichmentFailureReason | null
  reviewProfile: ReviewProfile
  metadata: SemanticMetadataFact[]
  parties: SemanticParty[]
  definedTerms: SemanticDefinedTerm[]
  references: SemanticReference[]
  clauses: SemanticClause[]
  structuralRegions: SemanticClause[]
  relationships: SemanticRelationship[]
  quality: string[]
  observations: SemanticObservations | null
}

function parseReviewProfile(value: unknown, what: string): ReviewProfile {
  const profile = asRecord(value, what)
  return {
    contractType: readOptionalNullableString(profile, 'contractType', what) ?? 'OTHER',
    ourSideRole: readOptionalNullableString(profile, 'ourSideRole', what),
    paperSource: readOptionalNullableString(profile, 'paperSource', what),
    jurisdiction: readOptionalNullableString(profile, 'jurisdiction', what),
  }
}

function parseMetadataFact(value: unknown, what: string): SemanticMetadataFact {
  const fact = asRecord(value, what)
  return {
    field: readString(fact, 'field', what),
    value: readString(fact, 'value', what),
    correctedValue: readOptionalNullableString(fact, 'correctedValue', what),
    corrected: readOptionalBoolean(fact, 'corrected', what),
    sourceNodeIds: readStringArray(fact, 'sourceNodeIds', what),
  }
}

function parseParty(value: unknown, what: string): SemanticParty {
  const party = asRecord(value, what)
  return {
    id: readString(party, 'id', what),
    name: readString(party, 'name', what),
    aliases: readStringArray(party, 'aliases', what),
    machineRoles: readStringArray(party, 'machineRoles', what),
    effectiveRoles: readStringArray(party, 'effectiveRoles', what),
    corrected: readOptionalBoolean(party, 'corrected', what),
    sourceNodeIds: readStringArray(party, 'sourceNodeIds', what),
  }
}

function parseDefinedTerm(value: unknown, what: string): SemanticDefinedTerm {
  const term = asRecord(value, what)
  return {
    id: readString(term, 'id', what),
    term: readString(term, 'term', what),
    definition: readString(term, 'definition', what),
    definingNodeId: readString(term, 'definingNodeId', what),
    referenceNodeIds: readStringArray(term, 'referenceNodeIds', what),
    duplicate: readOptionalBoolean(term, 'duplicate', what),
  }
}

function parseReference(value: unknown, what: string): SemanticReference {
  const reference = asRecord(value, what)
  return {
    id: readString(reference, 'id', what),
    referenceText: readString(reference, 'referenceText', what),
    kind: readString(reference, 'kind', what),
    status: readString(reference, 'status', what),
    sourceNodeId: readOptionalNullableString(reference, 'sourceNodeId', what),
    targetNodeId: readOptionalNullableString(reference, 'targetNodeId', what),
    candidateNodeIds: readStringArray(reference, 'candidateNodeIds', what),
  }
}

function parseFact(value: unknown, what: string): SemanticFact {
  const fact = asRecord(value, what)
  return {
    factSchema: readString(fact, 'factSchema', what),
    schemaVersion: readOptionalNumber(fact, 'schemaVersion', what),
    values: readStringMap(fact, 'values', what),
    sourceNodeIds: readStringArray(fact, 'sourceNodeIds', what),
  }
}

function parseInterpretation(value: unknown, what: string): SemanticInterpretation {
  const interpretation = asRecord(value, what)
  return {
    field: readString(interpretation, 'field', what),
    kind: readString(interpretation, 'kind', what),
    proposedValue: readString(interpretation, 'proposedValue', what),
    specId: readOptionalNullableString(interpretation, 'specId', what) ?? '',
    sourceNodeIds: readStringArray(interpretation, 'sourceNodeIds', what),
    verification: readString(interpretation, 'verification', what),
  }
}

function parseClause(value: unknown, what: string): SemanticClause {
  const clause = asRecord(value, what)
  return {
    id: readString(clause, 'id', what),
    headingNodeId: readString(clause, 'headingNodeId', what),
    regionKind: readOptionalNullableString(clause, 'regionKind', what) ?? 'CLAUSE',
    heading: readString(clause, 'heading', what),
    primaryConcept: readOptionalNullableString(clause, 'primaryConcept', what),
    secondaryConcepts: readStringArray(clause, 'secondaryConcepts', what),
    correctedPrimaryConcept: readOptionalNullableString(
      clause,
      'correctedPrimaryConcept',
      what,
    ),
    machineFacts: readArray(clause, 'machineFacts', what, parseFact),
    effectiveFacts: readArray(clause, 'effectiveFacts', what, parseFact),
    interpretations: readArray(clause, 'interpretations', what, parseInterpretation),
    corrected: readOptionalBoolean(clause, 'corrected', what),
  }
}

function parseRelationship(value: unknown, what: string): SemanticRelationship {
  const relationship = asRecord(value, what)
  return {
    id: readString(relationship, 'id', what),
    sourceRegionNodeId: readString(relationship, 'sourceRegionNodeId', what),
    targetRegionNodeId: readString(relationship, 'targetRegionNodeId', what),
    type: readString(relationship, 'type', what),
    detector: readOptionalNullableString(relationship, 'detector', what) ?? 'SEMANTIC',
    basis: readOptionalNullableString(relationship, 'basis', what) ?? '',
    probability: readOptionalNullableNumber(relationship, 'probability', what) ?? 0,
    verification:
      readOptionalNullableString(relationship, 'verification', what) ?? 'NOT_VERIFIED',
  }
}

function parseObservations(value: unknown, what: string): SemanticObservations | null {
  if (value === null || value === undefined) return null
  const observations = asRecord(value, what)
  return {
    regionCount: readOptionalNumber(observations, 'regionCount', what),
    decisionExecutionCount: readOptionalNumber(
      observations,
      'decisionExecutionCount',
      what,
    ),
    questionCount: readOptionalNumber(observations, 'questionCount', what),
    generativeExtractionCount: readOptionalNumber(
      observations,
      'generativeExtractionCount',
      what,
    ),
    deterministicFactCount: readOptionalNumber(
      observations,
      'deterministicFactCount',
      what,
    ),
    providerDegraded: readOptionalBoolean(observations, 'providerDegraded', what),
    budgetExhausted: readOptionalBoolean(observations, 'budgetExhausted', what),
  }
}

export function parseSemanticContract(
  value: unknown,
  what = 'semantics',
): SemanticContract {
  const response = asRecord(value, what)
  const rawStatus = readString(response, 'enrichmentStatus', what)
  const enrichmentStatus = ENRICHMENT_STATUSES.includes(rawStatus)
    ? (rawStatus as EnrichmentStatus)
    : 'UNKNOWN'
  return {
    contractId: readString(response, 'contractId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    enrichmentStatus,
    failureReason: readNullableClosedString(response, 'failureReason', ENRICHMENT_FAILURE_REASONS),
    reviewProfile: parseReviewProfile(response.reviewProfile, what),
    metadata: readArray(response, 'metadata', what, parseMetadataFact),
    parties: readArray(response, 'parties', what, parseParty),
    definedTerms: readArray(response, 'definedTerms', what, parseDefinedTerm),
    references: readArray(response, 'references', what, parseReference),
    clauses: readArray(response, 'clauses', what, parseClause),
    structuralRegions: readArray(response, 'structuralRegions', what, parseClause),
    relationships: readArray(response, 'relationships', what, parseRelationship),
    quality: readStringArray(response, 'quality', what),
    observations: parseObservations(response.observations, what),
  }
}

// Each semantics endpoint has its own failures, so each carries its own messages. One shared table
// produced statements that were false for the endpoint rendering them.
const SEMANTICS_READ_MESSAGES: Record<number, string> = {
  403: 'You do not have permission to read this semantic analysis.',
  404: 'This contract version has no semantic analysis.',
  500: 'The semantic analysis could not be loaded.',
  503: 'The semantic analysis service is unavailable right now.',
}

const ENRICHMENT_MESSAGES: Record<number, string> = {
  400: 'The review profile Rivell received was not valid. Check the contract type and try again.',
  403: 'You do not have permission to run semantic analysis for this contract.',
  404: 'This contract version could not be found.',
  409: 'A semantic analysis is already running for this contract version.',
  500: 'The semantic analysis could not be started.',
  503: 'The semantic analysis service is unavailable right now.',
}

const CORRECTION_MESSAGES: Record<number, string> = {
  400: 'That correction was not valid for this interpretation.',
  403: 'You do not have permission to change this semantic analysis.',
  404: 'That part of the contract could not be found.',
  409: 'The semantic analysis changed and this correction no longer applies.',
  500: 'The correction could not be saved.',
  503: 'The semantic analysis service is unavailable right now.',
}

export class SemanticApiError extends Error {
  readonly status: number

  constructor(status: number, messages: Record<number, string> = SEMANTICS_READ_MESSAGES) {
    super(messages[status] ?? 'The semantic analysis request could not be completed.')
    this.name = 'SemanticApiError'
    this.status = status
  }
}

export function isConflictError(error: unknown): boolean {
  return error instanceof SemanticApiError && error.status === 409
}

export const semanticsKeys = {
  semantics: (contractId: string, versionNumber: number) =>
    ['contract', contractId, 'semantics', versionNumber] as const,
}

export async function fetchSemantics(
  contractId: string,
  versionNumber: number,
): Promise<SemanticContract | null> {
  const client = createApiClient()
  const { data, response } = await client.GET(
    '/api/contracts/{id}/versions/{versionNumber}/semantics',
    { params: { path: { id: contractId, versionNumber } } },
  )

  if (response.status === 404) return null
  if (!data) throw new SemanticApiError(response.status)
  return parseApiResult(data, parseSemanticContract, 'semantics')
}

export function useSemanticsQuery(
  contractId: string,
  versionNumber: number | undefined,
): UseQueryResult<SemanticContract | null, Error> {
  const pollAttempts = useRef(0)
  return useQuery({
    queryKey: semanticsKeys.semantics(contractId, versionNumber ?? 0),
    queryFn: () => {
      if (versionNumber === undefined) {
        throw new Error('Contract version is not available yet')
      }
      return fetchSemantics(contractId, versionNumber)
    },
    enabled: versionNumber !== undefined,
    refetchInterval: (query) => {
      if (query.state.data?.enrichmentStatus !== 'ENRICHING') {
        pollAttempts.current = 0
        return false
      }
      if (pollAttempts.current >= 20) return false
      pollAttempts.current += 1
      return 3000
    },
  })
}

function useSemanticsMutation<TVariables>(
  contractId: string,
  versionNumber: number,
  mutationFn: (variables: TVariables) => Promise<SemanticContract>,
): UseMutationResult<SemanticContract, Error, TVariables> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn,
    onSuccess: (semantics) => {
      queryClient.setQueryData(
        semanticsKeys.semantics(contractId, versionNumber),
        semantics,
      )
    },
  })
}

export function useEnrichContract(
  contractId: string,
  versionNumber: number,
): UseMutationResult<SemanticContract, Error, ReviewProfileInput> {
  return useSemanticsMutation(contractId, versionNumber, async (profile) => {
    const client = createApiClient()
    const { data, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/enrichment',
      { params: { path: { id: contractId, versionNumber } }, body: profile },
    )
    if (!data) {
      throw new SemanticApiError(response.status, ENRICHMENT_MESSAGES)
    }
    return parseApiResult(data, parseSemanticContract, 'semantics')
  })
}

export function useCorrectPartyRole(
  contractId: string,
  versionNumber: number,
): UseMutationResult<
  SemanticContract,
  Error,
  { partyId: string; roles: PartyRole[] }
> {
  return useSemanticsMutation(contractId, versionNumber, async ({ partyId, roles }) => {
    const client = createApiClient()
    const { data, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/semantics/parties/{partyId}/role',
      {
        params: { path: { id: contractId, versionNumber, partyId } },
        body: { roles },
      },
    )
    if (!data) {
      throw new SemanticApiError(response.status, CORRECTION_MESSAGES)
    }
    return parseApiResult(data, parseSemanticContract, 'semantics')
  })
}

export function useCorrectClauseConcept(
  contractId: string,
  versionNumber: number,
): UseMutationResult<
  SemanticContract,
  Error,
  { clauseId: string; primary: string }
> {
  return useSemanticsMutation(contractId, versionNumber, async ({ clauseId, primary }) => {
    const client = createApiClient()
    const { data, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/concepts',
      {
        params: { path: { id: contractId, versionNumber, clauseId } },
        body: { primary },
      },
    )
    if (!data) {
      throw new SemanticApiError(response.status, CORRECTION_MESSAGES)
    }
    return parseApiResult(data, parseSemanticContract, 'semantics')
  })
}

export function useCorrectClauseFacts(
  contractId: string,
  versionNumber: number,
): UseMutationResult<
  SemanticContract,
  Error,
  { clauseId: string; values: Record<string, string> }
> {
  return useSemanticsMutation(contractId, versionNumber, async ({ clauseId, values }) => {
    const client = createApiClient()
    const { data, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/facts',
      {
        params: { path: { id: contractId, versionNumber, clauseId } },
        body: { values },
      },
    )
    if (!data) {
      throw new SemanticApiError(response.status, CORRECTION_MESSAGES)
    }
    return parseApiResult(data, parseSemanticContract, 'semantics')
  })
}
