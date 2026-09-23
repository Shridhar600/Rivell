import {
  useMutation,
  useQuery,
  type UseMutationResult,
  type UseQueryResult,
} from '@tanstack/react-query'
import { apiBaseUrl, createApiClient } from './client'
import {
  asRecord,
  errorMessage,
  parseApiResult,
  readArray,
  readEnum,
  readNullableNumber,
  readNullableString,
  readNumber,
  readString,
  readStringArray,
} from './envelope'
import type { components } from './schema'

export type IngestResponse = components['schemas']['IngestResponse']
export type ContractSummary = components['schemas']['ContractSummaryResponse']
export type StructureResponse = components['schemas']['ContractStructureResponse']
export type DocumentNode = components['schemas']['NodeView']
export type SourceSpan = components['schemas']['SourceSpanView']
export type ParseStatus = IngestResponse['parseStatus']

const PARSE_STATUSES: readonly ParseStatus[] = ['PARSING', 'OK', 'FAILED']

function parseSourceSpan(value: unknown, what: string): SourceSpan {
  const span = asRecord(value, what)
  return {
    partPath: readString(span, 'partPath', what),
    bodyIndex: readNumber(span, 'bodyIndex', what),
    paragraphIndex: readNullableNumber(span, 'paragraphIndex', what),
    tableIndex: readNullableNumber(span, 'tableIndex', what),
    rowIndex: readNullableNumber(span, 'rowIndex', what),
    cellIndex: readNullableNumber(span, 'cellIndex', what),
    runStart: readNullableNumber(span, 'runStart', what),
    runEnd: readNullableNumber(span, 'runEnd', what),
  }
}

function parseNode(value: unknown, what: string): DocumentNode {
  const node = asRecord(value, what)
  return {
    id: readString(node, 'id', what),
    parentId: readNullableString(node, 'parentId', what),
    nodeType: readString(node, 'nodeType', what) as DocumentNode['nodeType'],
    ordinal: readNumber(node, 'ordinal', what),
    text: readNullableString(node, 'text', what),
    headingLevel: readNullableNumber(node, 'headingLevel', what),
    numberingLabel: readNullableString(node, 'numberingLabel', what),
    rawStyleId: readNullableString(node, 'rawStyleId', what),
    sourceSpan: parseSourceSpan(node.sourceSpan, what),
  }
}

export function parseIngestResponse(value: unknown, what = 'upload'): IngestResponse {
  const response = asRecord(value, what)
  return {
    contractId: readString(response, 'contractId', what),
    contractVersionId: readString(response, 'contractVersionId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    parseStatus: readEnum(response, 'parseStatus', what, PARSE_STATUSES),
    parseWarnings: readStringArray(response, 'parseWarnings', what),
    filename: readString(response, 'filename', what),
    sha256: readString(response, 'sha256', what),
    nodeCount: readNumber(response, 'nodeCount', what),
  }
}

export function parseContractSummary(value: unknown, what = 'contract'): ContractSummary {
  const response = asRecord(value, what)
  const latest = asRecord(response.latestVersion, what)
  return {
    id: readString(response, 'id', what),
    title: readNullableString(response, 'title', what),
    latestVersion: {
      versionNumber: readNumber(latest, 'versionNumber', what),
      parseStatus: readEnum(latest, 'parseStatus', what, PARSE_STATUSES),
      parseWarnings: readStringArray(latest, 'parseWarnings', what),
      parserVersion: readNullableString(latest, 'parserVersion', what),
      sha256: readString(latest, 'sha256', what),
      filename: readString(latest, 'filename', what),
      nodeCount: readNumber(latest, 'nodeCount', what),
    },
  }
}

export function parseStructureResponse(
  value: unknown,
  what = 'structure',
): StructureResponse {
  const response = asRecord(value, what)
  return {
    contractId: readString(response, 'contractId', what),
    versionNumber: readNumber(response, 'versionNumber', what),
    parserVersion: readNullableString(response, 'parserVersion', what),
    parseStatus: readEnum(response, 'parseStatus', what, PARSE_STATUSES),
    parseWarnings: readStringArray(response, 'parseWarnings', what),
    nodeCount: readNumber(response, 'nodeCount', what),
    nodes: readArray(response, 'nodes', what, parseNode),
  }
}

export async function fetchContract(contractId: string): Promise<ContractSummary> {
  const client = createApiClient()
  const { data, error } = await client.GET('/api/contracts/{id}', {
    params: { path: { id: contractId } },
  })

  if (!data) {
    throw new Error(errorMessage(error, 'Contract unavailable'))
  }
  return parseApiResult(data, parseContractSummary, 'contract')
}

export async function fetchContractStructure(
  contractId: string,
  versionNumber: number,
): Promise<StructureResponse> {
  const client = createApiClient()
  const { data, error } = await client.GET(
    '/api/contracts/{id}/versions/{versionNumber}/structure',
    { params: { path: { id: contractId, versionNumber } } },
  )

  if (!data) {
    throw new Error(errorMessage(error, 'Structure unavailable'))
  }
  return parseApiResult(data, parseStructureResponse, 'structure')
}

export function createUploadFormData(file: File): FormData {
  const formData = new FormData()
  formData.append('file', file)
  return formData
}

export async function uploadContract(file: File): Promise<IngestResponse> {
  const client = createApiClient()
  const { data, error } = await client.POST('/api/contracts', {
    body: { file: file.name },
    bodySerializer: () => createUploadFormData(file),
  })

  if (!data) {
    throw new Error(errorMessage(error, 'Upload failed'))
  }
  return parseApiResult(data, parseIngestResponse, 'upload')
}

export function useContractQuery(
  contractId: string,
): UseQueryResult<ContractSummary, Error> {
  return useQuery({
    queryKey: ['contract', contractId],
    queryFn: () => fetchContract(contractId),
  })
}

export function useContractStructureQuery(
  contractId: string,
  versionNumber: number | undefined,
): UseQueryResult<StructureResponse, Error> {
  return useQuery({
    queryKey: ['contract', contractId, 'structure', versionNumber],
    queryFn: () => {
      if (versionNumber === undefined) {
        throw new Error('Contract version is not available yet')
      }
      return fetchContractStructure(contractId, versionNumber)
    },
    enabled: versionNumber !== undefined,
  })
}

export function useUploadContract(): UseMutationResult<
  IngestResponse,
  Error,
  File
> {
  return useMutation({ mutationFn: uploadContract })
}

export function originalDocumentUrl(
  contractId: string,
  versionNumber: number,
): string {
  return `${apiBaseUrl}/api/contracts/${contractId}/versions/${versionNumber}/original`
}
