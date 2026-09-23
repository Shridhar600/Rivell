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
  errorMessage,
  parseApiResult,
  readArray,
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
  sourceNodeCount: number
}

export type SemanticParty = {
  id: string
  name: string
  aliases: string[]
  machineRoles: string[]
  effectiveRoles: string[]
  corrected: boolean
  sourceNodeCount: number
}

export type SemanticDefinedTerm = {
  id: string
  term: string
  definition: string
  referenceCount: number
  duplicate: boolean
}

export type SemanticReference = {
  id: string
  referenceText: string
  kind: string
  status: string
  targetNodeId: string | null
  candidateCount: number
}

export type SemanticFact = {
  factSchema: string
  schemaVersion: number
  values: Record<string, string>
}

export type SemanticInterpretation = {
  field: string
  kind: string
  proposedValue: string
  specId: string
  sourceNodeCount: number
  verification: string
}

export type SemanticClause = {
  id: string
  headingNodeId: string
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
  reviewProfile: ReviewProfile
  metadata: SemanticMetadataFact[]
  parties: SemanticParty[]
  definedTerms: SemanticDefinedTerm[]
  references: SemanticReference[]
  clauses: SemanticClause[]
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
    sourceNodeCount: readOptionalNumber(fact, 'sourceNodeCount', what),
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
    sourceNodeCount: readOptionalNumber(party, 'sourceNodeCount', what),
  }
}

function parseDefinedTerm(value: unknown, what: string): SemanticDefinedTerm {
  const term = asRecord(value, what)
  return {
    id: readString(term, 'id', what),
    term: readString(term, 'term', what),
    definition: readString(term, 'definition', what),
    referenceCount: readOptionalNumber(term, 'referenceCount', what),
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
    targetNodeId: readOptionalNullableString(reference, 'targetNodeId', what),
    candidateCount: readOptionalNumber(reference, 'candidateCount', what),
  }
}

function parseFact(value: unknown, what: string): SemanticFact {
  const fact = asRecord(value, what)
  return {
    factSchema: readString(fact, 'factSchema', what),
    schemaVersion: readOptionalNumber(fact, 'schemaVersion', what),
    values: readStringMap(fact, 'values', what),
  }
}

function parseInterpretation(value: unknown, what: string): SemanticInterpretation {
  const interpretation = asRecord(value, what)
  return {
    field: readString(interpretation, 'field', what),
    kind: readString(interpretation, 'kind', what),
    proposedValue: readString(interpretation, 'proposedValue', what),
    specId: readOptionalNullableString(interpretation, 'specId', what) ?? '',
    sourceNodeCount: readOptionalNumber(interpretation, 'sourceNodeCount', what),
    verification: readString(interpretation, 'verification', what),
  }
}

function parseClause(value: unknown, what: string): SemanticClause {
  const clause = asRecord(value, what)
  return {
    id: readString(clause, 'id', what),
    headingNodeId: readString(clause, 'headingNodeId', what),
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
    reviewProfile: parseReviewProfile(response.reviewProfile, what),
    metadata: readArray(response, 'metadata', what, parseMetadataFact),
    parties: readArray(response, 'parties', what, parseParty),
    definedTerms: readArray(response, 'definedTerms', what, parseDefinedTerm),
    references: readArray(response, 'references', what, parseReference),
    clauses: readArray(response, 'clauses', what, parseClause),
    relationships: readArray(response, 'relationships', what, parseRelationship),
    quality: readStringArray(response, 'quality', what),
    observations: parseObservations(response.observations, what),
  }
}

export class SemanticApiError extends Error {
  readonly status: number

  constructor(message: string, status: number) {
    super(message)
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
  const { data, error, response } = await client.GET(
    '/api/contracts/{id}/versions/{versionNumber}/semantics',
    { params: { path: { id: contractId, versionNumber } } },
  )

  if (response.status === 404) return null
  if (!data) {
    throw new SemanticApiError(
      errorMessage(error, 'Semantic analysis unavailable'),
      response.status,
    )
  }
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
    const { data, error, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/enrichment',
      { params: { path: { id: contractId, versionNumber } }, body: profile },
    )
    if (!data) {
      throw new SemanticApiError(
        errorMessage(error, 'Semantic analysis failed'),
        response.status,
      )
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
    const { data, error, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/semantics/parties/{partyId}/role',
      {
        params: { path: { id: contractId, versionNumber, partyId } },
        body: { roles },
      },
    )
    if (!data) {
      throw new SemanticApiError(
        errorMessage(error, 'Role correction failed'),
        response.status,
      )
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
    const { data, error, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/concepts',
      {
        params: { path: { id: contractId, versionNumber, clauseId } },
        body: { primary },
      },
    )
    if (!data) {
      throw new SemanticApiError(
        errorMessage(error, 'Classification correction failed'),
        response.status,
      )
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
    const { data, error, response } = await client.POST(
      '/api/contracts/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/facts',
      {
        params: { path: { id: contractId, versionNumber, clauseId } },
        body: { values },
      },
    )
    if (!data) {
      throw new SemanticApiError(
        errorMessage(error, 'Fact correction failed'),
        response.status,
      )
    }
    return parseApiResult(data, parseSemanticContract, 'semantics')
  })
}
