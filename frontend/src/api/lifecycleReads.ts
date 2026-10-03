import { skipToken, useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import {
  fetchContractStructure,
  type StructureResponse,
} from './contracts'
import { fetchReviewList, reviewKeys, type ReviewListResponse } from './reviews'
import { fetchSemantics, semanticsKeys, type SemanticContract } from './semantics'

/**
 * Reads the lifecycle surface needs only in some states. The shared hooks for these always fire,
 * which would load a whole document structure for a review that has not even started analysing, so
 * these gate on `enabled`. They use the shared query keys, so a cached read is reused either way.
 */
export function useSemanticsWhen(
  contractId: string,
  versionNumber: number | undefined,
  enabled: boolean,
): UseQueryResult<SemanticContract | null, Error> {
  return useQuery({
    queryKey: semanticsKeys.semantics(contractId, versionNumber ?? 0),
    queryFn: versionNumber === undefined ? skipToken : () => fetchSemantics(contractId, versionNumber),
    enabled: enabled && versionNumber !== undefined && Boolean(contractId),
  })
}

export function useStructureWhen(
  contractId: string,
  versionNumber: number | undefined,
  enabled: boolean,
): UseQueryResult<StructureResponse, Error> {
  return useQuery({
    queryKey: ['contract', contractId, 'structure', versionNumber],
    queryFn: versionNumber === undefined ? skipToken : () => fetchContractStructure(contractId, versionNumber),
    enabled: enabled && versionNumber !== undefined && Boolean(contractId),
  })
}

/**
 * The contract's review list, read only to learn the file name of the version a Review pins when
 * that is not the latest version. The Review itself does not carry a file name.
 */
export function usePinnedFilenameList(
  contractId: string,
  enabled: boolean,
): UseQueryResult<ReviewListResponse, Error> {
  return useQuery({
    queryKey: reviewKeys.list(contractId),
    queryFn: () => fetchReviewList(contractId),
    enabled: enabled && Boolean(contractId),
  })
}
