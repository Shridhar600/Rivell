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
  readArrayValue,
  readEnum,
  readEnumArray,
  readNullableBoolean,
  readNullableEnum,
  readNullableNumber,
  readNullableString,
  readNumber,
  readNumberMap,
  readString,
  readStringArray,
  readStringMap,
} from './envelope'
import type { components, paths } from './schema'
import { UploadApiError } from './uploadError'
import { boundedErrorMessage } from './errorMessage'

type SchemaArtifactSummary = components['schemas']['KnowledgeArtifactSummaryResponse']
type SchemaArtifactType = NonNullable<SchemaArtifactSummary['artifactType']>
type SchemaIngestStatus = NonNullable<SchemaArtifactSummary['ingestStatus']>
type SchemaCandidate = components['schemas']['CandidateView']
type SchemaUnitKind = NonNullable<components['schemas']['ExtractionUnitView']['kind']>
type SchemaConflictType = NonNullable<
  components['schemas']['ConflictSignalView']['conflictType']
>
type SchemaPublicationAction = NonNullable<components['schemas']['ItemView']['action']>
type SchemaPositionRole = NonNullable<SchemaCandidate['positions']>[number]
type SchemaReviewCategory = NonNullable<SchemaCandidate['reviewCategory']>
type SchemaReviewReason = NonNullable<SchemaCandidate['reviewReasons']>[number]
type SchemaVerification = NonNullable<
  components['schemas']['InterpretationView']['verification']
>
type SchemaAssessmentState = NonNullable<
  components['schemas']['ArtifactAssessmentView']['state']
>

export const ARTIFACT_TYPES = [
  'PLAYBOOK',
  'CLAUSE_LIBRARY',
  'TEMPLATE',
  'EXECUTED_PRECEDENT',
  'NEGOTIATION_HISTORY',
  'EXTERNAL_REFERENCE',
] as const satisfies readonly SchemaArtifactType[]

export const INGEST_STATUSES = [
  'RECEIVED',
  'EXTRACTING',
  'EXTRACTED',
  'FAILED',
] as const satisfies readonly SchemaIngestStatus[]

export const CANDIDATE_TYPES = [
  'PLAYBOOK_RULE',
  'CLAUSE_VARIANT',
  'TEMPLATE_CLAUSE',
  'PRECEDENT',
  'REFERENCE',
] as const satisfies readonly NonNullable<SchemaCandidate['candidateType']>[]

export const REVIEW_STATUSES = [
  'PENDING',
  'APPROVED',
  'EDITED',
  'REJECTED',
  'PUBLISHED',
] as const satisfies readonly NonNullable<SchemaCandidate['reviewStatus']>[]

export const UNIT_KINDS = [
  'HEADING_SECTION',
  'TABLE',
  'SPREADSHEET_ROW',
] as const satisfies readonly SchemaUnitKind[]

export const CONFLICT_TYPES = [
  'DUPLICATE',
  'CONTRADICTS_EXISTING',
  'SUPERSEDES_EXISTING',
  'OVERLAPPING_EFFECTIVE_PERIOD',
  'JURISDICTION_MISMATCH',
  'AUTHORITY_CONFLICT',
  'UNKNOWN',
] as const satisfies readonly SchemaConflictType[]

export const PUBLICATION_ACTIONS = ['ADD', 'SUPERSEDE'] as const satisfies readonly SchemaPublicationAction[]

export const POSITION_ROLES = [
  'PREFERRED',
  'FALLBACK',
  'PROHIBITED',
  'ESCALATION',
  'COMMENTARY',
  'UNSPECIFIED',
] as const satisfies readonly SchemaPositionRole[]

export const REVIEW_CATEGORIES = [
  'ROUTINE',
  'ELEVATED',
  'HIGH',
] as const satisfies readonly SchemaReviewCategory[]

export const REVIEW_REASONS = [
  'DUPLICATE_DETECTED',
  'CONFLICT_DETECTED',
  'SUPERSESSION_PROPOSED',
  'ARTIFACT_TYPE_MISMATCH',
  'VERIFICATION_DISPUTED',
  'UNVERIFIED_GENERATIVE_FIELD',
  'LOW_CLASSIFICATION_CONFIDENCE',
  'NEW_PROHIBITED_OR_ESCALATION_POSITION',
  'SCOPE_NARROWING_PROPOSED',
  'SEMANTIC_ANALYSIS_UNAVAILABLE',
  'SEMANTIC_COMPILATION_UNAVAILABLE',
] as const satisfies readonly SchemaReviewReason[]

export const VERIFICATION_STATES = [
  'NOT_VERIFIED',
  'VERIFIED',
  'UNCERTAIN',
  'DISPUTED',
] as const satisfies readonly SchemaVerification[]

export const ASSESSMENT_STATES = [
  'MATCH',
  'POSSIBLE_MISMATCH',
  'AMBIGUOUS',
] as const satisfies readonly SchemaAssessmentState[]

export type ArtifactType = (typeof ARTIFACT_TYPES)[number]
export type IngestStatus = (typeof INGEST_STATUSES)[number]
export type CandidateType = (typeof CANDIDATE_TYPES)[number]
export type ReviewStatus = (typeof REVIEW_STATUSES)[number]
export type UnitKind = (typeof UNIT_KINDS)[number]
export type ConflictType = (typeof CONFLICT_TYPES)[number]
export type PublicationAction = (typeof PUBLICATION_ACTIONS)[number]
export type PositionRole = (typeof POSITION_ROLES)[number]
export type ReviewCategory = (typeof REVIEW_CATEGORIES)[number]
export type ReviewReason = (typeof REVIEW_REASONS)[number]
export type VerificationState = (typeof VERIFICATION_STATES)[number]
export type AssessmentState = (typeof ASSESSMENT_STATES)[number]

export interface ArtifactSummary {
  id: string
  artifactType: ArtifactType
  filename: string
  contentType: string
  sizeBytes: number
  sha256: string
  ingestStatus: IngestStatus
  createdAt: string
}

export interface ArtifactAssessment {
  assessedType: string
  state: AssessmentState
  specId: string
  specVersion: number
  warnings: string[]
}

export interface ArtifactDetail extends ArtifactSummary {
  storageKey: string
  declaredContractTypes: string[]
  contractPackKey: string | null
  partyPerspective: string | null
  paperSource: string | null
  jurisdiction: string | null
  language: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  manifestNote: string | null
  parserVersion: string | null
  parseWarnings: string[]
  artifactAssessment: ArtifactAssessment | null
}

export interface MergedRegion {
  firstRow: number
  lastRow: number
  firstColumn: number
  lastColumn: number
}

export interface KnowledgeNode {
  id: string
  artifactId: string
  parentId: string | null
  nodeType: string
  ordinal: number
  text: string | null
  headingLevel: number | null
  numberingLabel: string | null
  partPath: string
  bodyIndex: number
  paragraphIndex: number | null
  tableIndex: number | null
  rowIndex: number | null
  columnIndex: number | null
  runStart: number | null
  runEnd: number | null
  sheetName: string | null
  mergedRegion: MergedRegion | null
  rawStyleId: string | null
}

export interface ExtractionUnit {
  id: string
  artifactId: string
  ordinal: number
  kind: UnitKind
  title: string | null
  contentText: string | null
  sourceNodeIds: string[]
}

export interface ConflictSignal {
  conflictType: ConflictType
  message: string
  publishedCandidateId: string | null
}

export interface Interpretation {
  field: string
  proposedValue: string
  kind: string
  specId: string
  specVersion: number
  supportingSourceNodes: number
  verification: VerificationState
}

export interface Candidate {
  id: string
  artifactId: string
  extractionUnitId: string
  candidateType: CandidateType
  reviewStatus: ReviewStatus
  rawLabel: string
  contentText: string | null
  payloadColumns: Record<string, string>
  legalConceptKey: string | null
  /** The human correction when one exists, otherwise the classified concept. Absent when neither is set. */
  effectiveConceptKey: string | null
  /** A reviewer's taxonomy-key correction. An empty string means the reviewer cleared the concept. */
  conceptCorrection: string | null
  classifiedConcepts: Record<string, number>
  positions: PositionRole[]
  variantRole: PositionRole | null
  escalationTrigger: string | null
  requiresApproval: boolean | null
  interpretations: Interpretation[]
  editedContentText: string | null
  editedPayloadColumns: Record<string, string>
  supersedingCandidateId: string | null
  contractPackKey: string | null
  declaredContractTypes: string[]
  partyPerspective: string | null
  paperSource: string | null
  jurisdiction: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  conflictSignals: ConflictSignal[]
  reviewCategory: ReviewCategory | null
  reviewReasons: string[]
  reviewNote: string | null
  reviewedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface PublicationItem {
  id: string
  candidateId: string
  supersededCandidateId: string | null
  action: PublicationAction
}

export interface Publication {
  id: string
  organizationId: string
  publicationNumber: number
  note: string | null
  items: PublicationItem[]
  createdAt: string
}

export interface ArtifactManifestInput {
  artifactType: ArtifactType
  declaredContractTypes: string[]
  contractPackKey?: string
  partyPerspective?: string
  paperSource?: string
  jurisdiction?: string
  language?: string
  effectiveFrom?: string
  effectiveTo?: string
  note?: string
}

function parseMergedRegion(value: unknown, what: string): MergedRegion {
  const record = asRecord(value, what)
  return {
    firstRow: readNumber(record, 'firstRow', what),
    lastRow: readNumber(record, 'lastRow', what),
    firstColumn: readNumber(record, 'firstColumn', what),
    lastColumn: readNumber(record, 'lastColumn', what),
  }
}

export function parseArtifactSummary(
  value: unknown,
  what = 'knowledge source',
): ArtifactSummary {
  const record = asRecord(value, what)
  return {
    id: readString(record, 'id', what),
    artifactType: readEnum(record, 'artifactType', what, ARTIFACT_TYPES),
    filename: readString(record, 'filename', what),
    contentType: readString(record, 'contentType', what),
    sizeBytes: readNumber(record, 'sizeBytes', what),
    sha256: readString(record, 'sha256', what),
    ingestStatus: readEnum(record, 'ingestStatus', what, INGEST_STATUSES),
    createdAt: readString(record, 'createdAt', what),
  }
}

export function parseArtifactDetail(
  value: unknown,
  what = 'knowledge source',
): ArtifactDetail {
  const record = asRecord(value, what)
  const assessment = record.artifactAssessment
  return {
    ...parseArtifactSummary(record, what),
    storageKey: readString(record, 'storageKey', what),
    declaredContractTypes: readStringArray(record, 'declaredContractTypes', what),
    contractPackKey: readNullableString(record, 'contractPackKey', what),
    partyPerspective: readNullableString(record, 'partyPerspective', what),
    paperSource: readNullableString(record, 'paperSource', what),
    jurisdiction: readNullableString(record, 'jurisdiction', what),
    language: readNullableString(record, 'language', what),
    effectiveFrom: readNullableString(record, 'effectiveFrom', what),
    effectiveTo: readNullableString(record, 'effectiveTo', what),
    manifestNote: readNullableString(record, 'manifestNote', what),
    parserVersion: readNullableString(record, 'parserVersion', what),
    parseWarnings: readStringArray(record, 'parseWarnings', what),
    artifactAssessment:
      assessment === null || assessment === undefined
        ? null
        : parseArtifactAssessment(assessment, what),
  }
}

export function parseArtifactAssessment(
  value: unknown,
  what = 'artifact assessment',
): ArtifactAssessment {
  const record = asRecord(value, what)
  return {
    assessedType: readString(record, 'assessedType', what),
    state: readEnum(record, 'state', what, ASSESSMENT_STATES),
    specId: readString(record, 'specId', what),
    specVersion: readNumber(record, 'specVersion', what),
    warnings: readStringArray(record, 'warnings', what),
  }
}

export function parseKnowledgeNode(
  value: unknown,
  what = 'knowledge structure',
): KnowledgeNode {
  const record = asRecord(value, what)
  const merged = record.mergedRegion
  return {
    id: readString(record, 'id', what),
    artifactId: readString(record, 'artifactId', what),
    parentId: readNullableString(record, 'parentId', what),
    nodeType: readString(record, 'nodeType', what),
    ordinal: readNumber(record, 'ordinal', what),
    text: readNullableString(record, 'text', what),
    headingLevel: readNullableNumber(record, 'headingLevel', what),
    numberingLabel: readNullableString(record, 'numberingLabel', what),
    partPath: readString(record, 'partPath', what),
    bodyIndex: readNumber(record, 'bodyIndex', what),
    paragraphIndex: readNullableNumber(record, 'paragraphIndex', what),
    tableIndex: readNullableNumber(record, 'tableIndex', what),
    rowIndex: readNullableNumber(record, 'rowIndex', what),
    columnIndex: readNullableNumber(record, 'columnIndex', what),
    runStart: readNullableNumber(record, 'runStart', what),
    runEnd: readNullableNumber(record, 'runEnd', what),
    sheetName: readNullableString(record, 'sheetName', what),
    mergedRegion: merged === null ? null : parseMergedRegion(merged, what),
    rawStyleId: readNullableString(record, 'rawStyleId', what),
  }
}

export function parseExtractionUnit(
  value: unknown,
  what = 'extraction unit',
): ExtractionUnit {
  const record = asRecord(value, what)
  return {
    id: readString(record, 'id', what),
    artifactId: readString(record, 'artifactId', what),
    ordinal: readNumber(record, 'ordinal', what),
    kind: readEnum(record, 'kind', what, UNIT_KINDS),
    title: readNullableString(record, 'title', what),
    contentText: readNullableString(record, 'contentText', what),
    sourceNodeIds: readStringArray(record, 'sourceNodeIds', what),
  }
}

export function parseConflictSignal(
  value: unknown,
  what = 'conflict signal',
): ConflictSignal {
  const record = asRecord(value, what)
  const rawType = record.conflictType
  const conflictType =
    typeof rawType === 'string' && (CONFLICT_TYPES as readonly string[]).includes(rawType)
      ? (rawType as ConflictType)
      : 'UNKNOWN'
  return {
    conflictType,
    message: readString(record, 'message', what),
    publishedCandidateId: readNullableString(record, 'publishedCandidateId', what),
  }
}

export function parseInterpretation(
  value: unknown,
  what = 'interpretation',
): Interpretation {
  const record = asRecord(value, what)
  return {
    field: readString(record, 'field', what),
    proposedValue: readString(record, 'proposedValue', what),
    kind: readString(record, 'kind', what),
    specId: readString(record, 'specId', what),
    specVersion: readNumber(record, 'specVersion', what),
    supportingSourceNodes: readNumber(record, 'supportingSourceNodes', what),
    verification: readEnum(record, 'verification', what, VERIFICATION_STATES),
  }
}

export function parseCandidate(value: unknown, what = 'candidate'): Candidate {
  const record = asRecord(value, what)
  return {
    id: readString(record, 'id', what),
    artifactId: readString(record, 'artifactId', what),
    extractionUnitId: readString(record, 'extractionUnitId', what),
    candidateType: readEnum(record, 'candidateType', what, CANDIDATE_TYPES),
    reviewStatus: readEnum(record, 'reviewStatus', what, REVIEW_STATUSES),
    rawLabel: readString(record, 'rawLabel', what),
    contentText: readNullableString(record, 'contentText', what),
    payloadColumns: readStringMap(record, 'payloadColumns', what),
    legalConceptKey: readNullableString(record, 'legalConceptKey', what),
    effectiveConceptKey: readNullableString(record, 'effectiveConceptKey', what),
    conceptCorrection: readNullableString(record, 'conceptCorrection', what),
    classifiedConcepts: readNumberMap(record, 'classifiedConcepts', what),
    positions: readEnumArray(record, 'positions', what, POSITION_ROLES),
    variantRole: readNullableEnum(record, 'variantRole', what, POSITION_ROLES),
    escalationTrigger: readNullableString(record, 'escalationTrigger', what),
    requiresApproval: readNullableBoolean(record, 'requiresApproval', what),
    interpretations: readArray(record, 'interpretations', what, parseInterpretation),
    editedContentText: readNullableString(record, 'editedContentText', what),
    editedPayloadColumns: readStringMap(record, 'editedPayloadColumns', what),
    supersedingCandidateId: readNullableString(record, 'supersedingCandidateId', what),
    contractPackKey: readNullableString(record, 'contractPackKey', what),
    declaredContractTypes: readStringArray(record, 'declaredContractTypes', what),
    partyPerspective: readNullableString(record, 'partyPerspective', what),
    paperSource: readNullableString(record, 'paperSource', what),
    jurisdiction: readNullableString(record, 'jurisdiction', what),
    effectiveFrom: readNullableString(record, 'effectiveFrom', what),
    effectiveTo: readNullableString(record, 'effectiveTo', what),
    conflictSignals: readArray(record, 'conflictSignals', what, parseConflictSignal),
    reviewCategory: readNullableEnum(record, 'reviewCategory', what, REVIEW_CATEGORIES),
    reviewReasons: readStringArray(record, 'reviewReasons', what),
    reviewNote: readNullableString(record, 'reviewNote', what),
    reviewedAt: readNullableString(record, 'reviewedAt', what),
    createdAt: readString(record, 'createdAt', what),
    updatedAt: readString(record, 'updatedAt', what),
  }
}

export function parsePublicationItem(
  value: unknown,
  what = 'publication item',
): PublicationItem {
  const record = asRecord(value, what)
  return {
    id: readString(record, 'id', what),
    candidateId: readString(record, 'candidateId', what),
    supersededCandidateId: readNullableString(record, 'supersededCandidateId', what),
    action: readEnum(record, 'action', what, PUBLICATION_ACTIONS),
  }
}

export function parsePublication(value: unknown, what = 'publication'): Publication {
  const record = asRecord(value, what)
  return {
    id: readString(record, 'id', what),
    organizationId: readString(record, 'organizationId', what),
    publicationNumber: readNumber(record, 'publicationNumber', what),
    note: readNullableString(record, 'note', what),
    items: readArray(record, 'items', what, parsePublicationItem),
    createdAt: readString(record, 'createdAt', what),
  }
}

const PUBLISH_MESSAGES: Partial<Record<number, string>> = {
  404: 'That knowledge source could not be found.',
  409: 'Only candidates that have been approved can be published. Review the candidate states, then try again.',
  500: 'The knowledge version could not be published.',
  503: 'The knowledge service is unavailable right now.',
}

export function knowledgeRetry(failureCount: number, error: Error): boolean {
  // A denial or a missing resource cannot become true on a second identical request, and these
  // queries otherwise inherit the app-wide single retry.
  return failureCount < 1 && !isKnowledgeForbiddenError(error) && !(error instanceof KnowledgeApiError && error.status === 404)
}

export class KnowledgeApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'KnowledgeApiError'
    this.status = status
  }
}

export function isKnowledgeForbiddenError(error: unknown): boolean {
  return error instanceof KnowledgeApiError && error.status === 403
}

/**
 * A failed extraction is stored as a failed source, so an upload that fails on the server may still
 * have left one behind. These do not claim that nothing was changed.
 */
const KNOWLEDGE_UPLOAD_MESSAGES: Partial<Record<number, string>> = {
  400: 'The file was not accepted. Use a .docx or .xlsx file.',
  500: 'Rivell could not process this file. If it was stored, it appears in Sources as a failed source. Nothing was published.',
}

const CONCEPT_MESSAGES: Partial<Record<number, string>> = {
  400: 'That legal concept is not one Rivell knows.',
  404: 'That candidate could not be found.',
  409: 'A published or rejected candidate cannot be corrected.',
  500: 'The legal concept could not be corrected.',
  503: 'The knowledge service is unavailable right now.',
}

const KNOWLEDGE_MESSAGES: Partial<Record<number, string>> = {
  404: 'That knowledge source or candidate could not be found.',
  409: 'That knowledge request does not apply in the current state.',
  413: 'The submitted content is larger than this Rivell deployment accepts.',
  500: 'The knowledge request could not be completed.',
  503: 'The knowledge service is unavailable right now.',
}

/**
 * Bounded so Home and the legacy lists cannot request an unbounded page. The backend caps both
 * knowledge lists at 100. Neither list has a cursor yet, so a page is not the whole set; the
 * remaining GAP-C items are recorded in DEFERRED_WORK.md.
 */
export const KNOWLEDGE_PAGE_LIMIT = 20

/** The backend's own cap on both lists. The read surfaces ask for all of it. */
export const KNOWLEDGE_READ_LIMIT = 100

export const knowledgeKeys = {
  artifacts: ['knowledge', 'artifacts'] as const,
  artifact: (id: string) => ['knowledge', 'artifacts', id] as const,
  structure: (id: string) => ['knowledge', 'artifacts', id, 'structure'] as const,
  units: (id: string) => ['knowledge', 'artifacts', id, 'units'] as const,
  candidates: (id: string) => ['knowledge', 'artifacts', id, 'candidates'] as const,
  candidate: (id: string) => ['knowledge', 'candidates', id] as const,
  publications: ['knowledge', 'publications'] as const,
  publication: (id: string) => ['knowledge', 'publications', id] as const,
}

export function isProcessingArtifact(status: IngestStatus): boolean {
  return status === 'RECEIVED' || status === 'EXTRACTING'
}

export async function fetchArtifacts(limit = KNOWLEDGE_PAGE_LIMIT): Promise<ArtifactSummary[]> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/artifacts', {
    params: { query: { limit } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Knowledge sources unavailable'))
  }
  return parseApiResult(
    data,
    (raw, what) => readArrayValue(raw, what, parseArtifactSummary),
    'knowledge sources',
  )
}

export async function fetchArtifact(artifactId: string): Promise<ArtifactDetail> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/artifacts/{id}', {
    params: { path: { id: artifactId } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Knowledge source unavailable'))
  }
  return parseApiResult(data, parseArtifactDetail, 'knowledge source')
}

export async function fetchKnowledgeStructure(
  artifactId: string,
): Promise<KnowledgeNode[]> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/artifacts/{id}/structure', {
    params: { path: { id: artifactId } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Knowledge structure unavailable'))
  }
  return parseApiResult(
    data,
    (raw, what) => readArrayValue(raw, what, parseKnowledgeNode),
    'knowledge structure',
  )
}

export async function fetchExtractionUnits(
  artifactId: string,
): Promise<ExtractionUnit[]> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/artifacts/{id}/units', {
    params: { path: { id: artifactId } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Extraction units unavailable'))
  }
  return parseApiResult(
    data,
    (raw, what) => readArrayValue(raw, what, parseExtractionUnit),
    'extraction units',
  )
}

export async function fetchCandidates(artifactId: string): Promise<Candidate[]> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/artifacts/{id}/candidates', {
    params: { path: { id: artifactId } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Knowledge candidates unavailable'))
  }
  return parseApiResult(
    data,
    (raw, what) => readArrayValue(raw, what, parseCandidate),
    'knowledge candidates',
  )
}

export async function fetchCandidate(candidateId: string): Promise<Candidate> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/candidates/{id}', {
    params: { path: { id: candidateId } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Candidate unavailable'))
  }
  return parseApiResult(data, parseCandidate, 'candidate')
}

export async function fetchPublications(limit = KNOWLEDGE_PAGE_LIMIT): Promise<Publication[]> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/publications', {
    params: { query: { limit } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Publications unavailable'))
  }
  return parseApiResult(
    data,
    (raw, what) => readArrayValue(raw, what, parsePublication),
    'publications',
  )
}

export async function fetchPublication(publicationId: string): Promise<Publication> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/knowledge/publications/{id}', {
    params: { path: { id: publicationId } },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Publication unavailable'))
  }
  return parseApiResult(data, parsePublication, 'publication')
}

function manifestQuery(
  manifest: ArtifactManifestInput,
): paths['/api/knowledge/artifacts']['post']['parameters']['query'] {
  const query: paths['/api/knowledge/artifacts']['post']['parameters']['query'] = {
    artifactType: manifest.artifactType,
  }
  const declared = manifest.declaredContractTypes.join(',')
  if (declared.length > 0) query.declaredContractTypes = declared
  for (const key of [
    'contractPackKey',
    'partyPerspective',
    'paperSource',
    'jurisdiction',
    'language',
    'effectiveFrom',
    'effectiveTo',
    'note',
  ] as const) {
    const value = manifest[key]
    if (value !== undefined && value.trim().length > 0) query[key] = value.trim()
  }
  return query
}

export async function uploadKnowledge(
  file: File,
  manifest: ArtifactManifestInput,
): Promise<ArtifactDetail> {
  const client = createApiClient()
  const formData = new FormData()
  formData.append('file', file)

  const { data, response } = await client.POST('/api/knowledge/artifacts', {
    params: { query: manifestQuery(manifest) },
    body: { file: file.name },
    bodySerializer: () => formData,
  })

  if (!data) throw new UploadApiError(response.status, KNOWLEDGE_UPLOAD_MESSAGES)
  return parseApiResult(data, parseArtifactDetail, 'knowledge source')
}

async function approveCandidate(candidateId: string, note?: string): Promise<Candidate> {
  const client = createApiClient()
  const { data, response } = await client.POST('/api/knowledge/candidates/{id}/approve', {
    params: { path: { id: candidateId } },
    body: note && note.trim().length > 0 ? { note } : {},
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Approval failed'))
  }
  return parseApiResult(data, parseCandidate, 'candidate')
}

export interface ScopeEditInput {
  contractPackKey?: string
  declaredContractTypes: string[]
  partyPerspective?: string
  paperSource?: string
  jurisdiction?: string
  effectiveFrom?: string
  effectiveTo?: string
}

export interface SemanticFieldsEditInput {
  positions?: PositionRole[]
  requiresApproval?: boolean
  escalationTrigger?: string
  variantRole?: PositionRole
}

export interface EditCandidateInput {
  candidateId: string
  contentText?: string
  payloadColumns?: Record<string, string>
  scope?: ScopeEditInput
  semanticFields?: SemanticFieldsEditInput
  note?: string
}

type EditRequestBody = NonNullable<
  paths['/api/knowledge/candidates/{id}/edit']['post']['requestBody']
>['content']['application/json']

async function editCandidate(input: EditCandidateInput): Promise<Candidate> {
  const client = createApiClient()
  const body: EditRequestBody = {}
  if (input.contentText !== undefined) body.contentText = input.contentText
  if (input.payloadColumns !== undefined) body.payloadColumns = input.payloadColumns
  if (input.scope !== undefined) body.scope = input.scope
  if (input.semanticFields !== undefined) body.semanticFields = input.semanticFields
  if (input.note !== undefined && input.note.trim().length > 0) body.note = input.note

  const { data, response } = await client.POST('/api/knowledge/candidates/{id}/edit', {
    params: { path: { id: input.candidateId } },
    body,
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Edit failed'))
  }
  return parseApiResult(data, parseCandidate, 'candidate')
}

async function rejectCandidate(candidateId: string, note?: string): Promise<Candidate> {
  const client = createApiClient()
  const { data, response } = await client.POST('/api/knowledge/candidates/{id}/reject', {
    params: { path: { id: candidateId } },
    body: note && note.trim().length > 0 ? { note } : {},
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Rejection failed'))
  }
  return parseApiResult(data, parseCandidate, 'candidate')
}

async function correctConcept(candidateId: string, primary: string): Promise<Candidate> {
  const client = createApiClient()
  const { data, response } = await client.POST('/api/knowledge/candidates/{id}/concept', {
    params: { path: { id: candidateId } },
    body: { primary },
  })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, CONCEPT_MESSAGES, 'The legal concept could not be corrected.'))
  }
  return parseApiResult(data, parseCandidate, 'candidate')
}

export interface BulkApprovalResult {
  approvedIds: string[]
  skipped: Record<string, string>
}

export function parseBulkApprovalResult(
  value: unknown,
  what = 'bulk approval',
): BulkApprovalResult {
  const record = asRecord(value, what)
  return {
    approvedIds: readStringArray(record, 'approvedIds', what),
    skipped: readStringMap(record, 'skipped', what),
  }
}

async function bulkApproveCandidates(
  candidateIds: string[],
): Promise<BulkApprovalResult> {
  const client = createApiClient()
  const { data, response } = await client.POST(
    '/api/knowledge/candidates/bulk-approve',
    { body: { candidateIds } },
  )

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, KNOWLEDGE_MESSAGES, 'Bulk approval failed'))
  }
  return parseApiResult(data, parseBulkApprovalResult, 'bulk approval')
}

export async function publishKnowledge(
  candidateIds: string[],
  supersededCandidates: Record<string, string> | undefined,
  note?: string,
): Promise<Publication> {
  const client = createApiClient()
  const body: {
    candidateIds: string[]
    supersededCandidates?: Record<string, string>
    note?: string
  } = { candidateIds }
  if (supersededCandidates && Object.keys(supersededCandidates).length > 0) {
    body.supersededCandidates = supersededCandidates
  }
  if (note && note.trim().length > 0) body.note = note

  const { data, response } = await client.POST('/api/knowledge/publications', { body })

  if (!data) {
    throw new KnowledgeApiError(response.status, boundedErrorMessage(response.status, PUBLISH_MESSAGES, 'The knowledge version could not be published.'))
  }
  return parseApiResult(data, parsePublication, 'publication')
}

export function useArtifactsQuery(): UseQueryResult<ArtifactSummary[], Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.artifacts,
    queryFn: () => fetchArtifacts(),
    refetchInterval: (query) =>
      query.state.data?.some((artifact) => isProcessingArtifact(artifact.ingestStatus))
        ? 3000
        : false,
  })
}

export function useArtifactQuery(
  artifactId: string,
): UseQueryResult<ArtifactDetail, Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.artifact(artifactId),
    queryFn: () => fetchArtifact(artifactId),
    refetchInterval: (query) =>
      query.state.data && isProcessingArtifact(query.state.data.ingestStatus) ? 3000 : false,
  })
}

export function useKnowledgeStructureQuery(
  artifactId: string,
): UseQueryResult<KnowledgeNode[], Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.structure(artifactId),
    queryFn: () => fetchKnowledgeStructure(artifactId),
  })
}

export function useExtractionUnitsQuery(
  artifactId: string,
): UseQueryResult<ExtractionUnit[], Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.units(artifactId),
    queryFn: () => fetchExtractionUnits(artifactId),
  })
}

export function useCandidatesQuery(
  artifactId: string,
): UseQueryResult<Candidate[], Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.candidates(artifactId),
    queryFn: () => fetchCandidates(artifactId),
  })
}

export function useCandidateQuery(
  candidateId: string | undefined,
): UseQueryResult<Candidate, Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.candidate(candidateId ?? ''),
    queryFn: () => {
      if (!candidateId) throw new Error('Candidate id is required')
      return fetchCandidate(candidateId)
    },
    enabled: Boolean(candidateId),
  })
}

export function usePublicationsQuery(
  enabled = true,
): UseQueryResult<Publication[], Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.publications,
    queryFn: () => fetchPublications(),
    enabled,
  })
}

export function usePublicationQuery(
  publicationId: string,
): UseQueryResult<Publication, Error> {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: knowledgeKeys.publication(publicationId),
    queryFn: () => fetchPublication(publicationId),
  })
}

export interface UploadKnowledgeVariables {
  file: File
  manifest: ArtifactManifestInput
}

export function useUploadKnowledge(): UseMutationResult<
  ArtifactDetail,
  Error,
  UploadKnowledgeVariables
> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ file, manifest }: UploadKnowledgeVariables) =>
      uploadKnowledge(file, manifest),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: knowledgeKeys.artifacts })
    },
  })
}

function useCandidateReviewInvalidation() {
  const queryClient = useQueryClient()
  return (candidate: Candidate) => {
    void queryClient.invalidateQueries({ queryKey: knowledgeKeys.candidate(candidate.id) })
    void queryClient.invalidateQueries({
      queryKey: knowledgeKeys.candidates(candidate.artifactId),
    })
  }
}

export interface ConceptCorrectionVariables {
  candidateId: string
  /** A taxonomy key, or an empty string to clear the concept. */
  primary: string
}

export function useCorrectCandidateConcept(): UseMutationResult<Candidate, Error, ConceptCorrectionVariables> {
  const invalidate = useCandidateReviewInvalidation()
  return useMutation({
    mutationFn: ({ candidateId, primary }: ConceptCorrectionVariables) => correctConcept(candidateId, primary),
    onSuccess: invalidate,
  })
}

export interface CandidateReviewVariables {
  candidateId: string
  note?: string
}

export function useApproveCandidate(): UseMutationResult<
  Candidate,
  Error,
  CandidateReviewVariables
> {
  const invalidate = useCandidateReviewInvalidation()
  return useMutation({
    mutationFn: ({ candidateId, note }: CandidateReviewVariables) =>
      approveCandidate(candidateId, note),
    onSuccess: invalidate,
  })
}

export function useEditCandidate(): UseMutationResult<Candidate, Error, EditCandidateInput> {
  const invalidate = useCandidateReviewInvalidation()
  return useMutation({ mutationFn: editCandidate, onSuccess: invalidate })
}

export function useRejectCandidate(): UseMutationResult<
  Candidate,
  Error,
  CandidateReviewVariables
> {
  const invalidate = useCandidateReviewInvalidation()
  return useMutation({
    mutationFn: ({ candidateId, note }: CandidateReviewVariables) =>
      rejectCandidate(candidateId, note),
    onSuccess: invalidate,
  })
}

export interface BulkApproveVariables {
  candidateIds: string[]
  artifactId: string
}

export function useBulkApproveCandidates(): UseMutationResult<
  BulkApprovalResult,
  Error,
  BulkApproveVariables
> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ candidateIds }: BulkApproveVariables) =>
      bulkApproveCandidates(candidateIds),
    onSuccess: (_result, variables) => {
      void queryClient.invalidateQueries({
        queryKey: knowledgeKeys.candidates(variables.artifactId),
      })
      for (const candidateId of variables.candidateIds) {
        void queryClient.invalidateQueries({
          queryKey: knowledgeKeys.candidate(candidateId),
        })
      }
    },
  })
}

export interface PublishKnowledgeVariables {
  candidateIds: string[]
  supersededCandidates?: Record<string, string>
  note?: string
  artifactIds: string[]
}

export function usePublishKnowledge(): UseMutationResult<
  Publication,
  Error,
  PublishKnowledgeVariables
> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ candidateIds, supersededCandidates, note }: PublishKnowledgeVariables) =>
      publishKnowledge(candidateIds, supersededCandidates, note),
    onSuccess: (publication, variables) => {
      void queryClient.invalidateQueries({ queryKey: knowledgeKeys.publications })
      void queryClient.invalidateQueries({
        queryKey: knowledgeKeys.publication(publication.id),
      })
      for (const artifactId of variables.artifactIds) {
        void queryClient.invalidateQueries({ queryKey: knowledgeKeys.candidates(artifactId) })
      }
      for (const item of publication.items) {
        void queryClient.invalidateQueries({ queryKey: knowledgeKeys.candidate(item.candidateId) })
      }
    },
  })
}

