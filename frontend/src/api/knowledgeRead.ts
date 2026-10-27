import { useCallback, useMemo } from 'react'
import { useQueries, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import {
  fetchArtifacts,
  fetchCandidates,
  fetchPublications,
  isKnowledgeForbiddenError,
  isProcessingArtifact,
  knowledgeKeys,
  knowledgeRetry,
  KNOWLEDGE_READ_LIMIT,
  type Candidate,
} from './knowledge'
import { buildCatalog, type Catalog } from '../lib/knowledgeCatalog'

/**
 * These ask for the backend's full page, where the legacy Home and Compilation lists ask for a
 * short one, so they cannot share a cache entry. They sit under the legacy key prefixes on
 * purpose: approving or publishing in the compilation workspace invalidates those prefixes, and
 * the read surfaces must then refetch instead of showing the old published set for 30 seconds.
 * 'catalog' can never collide with a source or publication id.
 */
export const readKeys = {
  artifacts: [...knowledgeKeys.artifacts, 'catalog'] as const,
  publications: [...knowledgeKeys.publications, 'catalog'] as const,
}

/** Every publication the backend returns (it caps the list at 100), under the shared read key. */
export function useAllPublicationsQuery(enabled = true) {
  return useQuery({
    retry: knowledgeRetry,
    queryKey: readKeys.publications,
    queryFn: () => fetchPublications(KNOWLEDGE_READ_LIMIT),
    enabled,
  })
}

export interface KnowledgeCatalogState {
  status: 'pending' | 'error' | 'ready'
  catalog: Catalog | null
  error: Error | null
  /** The reader may not view Knowledge. That is a permission boundary, not a failure. */
  forbidden: boolean
  /** A list reached the backend's cap, so older entries may be missing. */
  truncated: boolean
  refetch: () => void
}

/**
 * Published Knowledge for the read surfaces.
 *
 * There is no per-artifact published projection yet, so this reads the three real lists
 * and joins them by id in `buildCatalog`: sources, each source's candidates, and publications.
 * That is one request per extracted source. It is correct, and it is the cost a per-artifact
 * projection would remove.
 */
export function useKnowledgeCatalog(): KnowledgeCatalogState {
  const artifacts = useQuery({
    retry: knowledgeRetry,
    queryKey: readKeys.artifacts,
    queryFn: () => fetchArtifacts(KNOWLEDGE_READ_LIMIT),
    refetchInterval: (query) =>
      query.state.data?.some((artifact) => isProcessingArtifact(artifact.ingestStatus)) ? 3000 : false,
  })
  const publications = useAllPublicationsQuery()

  // Only extracted sources have candidates. A source still extracting, or one that failed, has none yet.
  const extracted = useMemo(
    () => (artifacts.data ?? []).filter((artifact) => artifact.ingestStatus === 'EXTRACTED'),
    [artifacts.data],
  )
  const combine = useCallback(
    (reads: UseQueryResult<Candidate[], Error>[]) => ({
      settled: reads.every((read) => !read.isPending),
      byArtifact: extracted.map((artifact, position) => [artifact.id, reads[position]?.data] as const),
    }),
    [extracted],
  )
  const { settled, byArtifact } = useQueries({
    queries: extracted.map((artifact) => ({
      retry: knowledgeRetry,
      queryKey: knowledgeKeys.candidates(artifact.id),
      queryFn: () => fetchCandidates(artifact.id),
    })),
    combine,
  })

  const catalog = useMemo(() => {
    if (!artifacts.data || !publications.data || !settled) return null
    const candidates = new Map<string, Candidate[]>()
    for (const [id, data] of byArtifact) if (data) candidates.set(id, data)
    return buildCatalog({ artifacts: artifacts.data, publications: publications.data, candidates })
  }, [artifacts.data, publications.data, settled, byArtifact])

  const queryClient = useQueryClient()

  const error = artifacts.error ?? publications.error
  const status = error ? 'error' : catalog ? 'ready' : 'pending'

  return {
    status,
    catalog,
    error,
    forbidden: isKnowledgeForbiddenError(error),
    truncated:
      (artifacts.data?.length ?? 0) >= KNOWLEDGE_READ_LIMIT || (publications.data?.length ?? 0) >= KNOWLEDGE_READ_LIMIT,
    refetch: () => {
      void artifacts.refetch()
      void publications.refetch()
      for (const artifact of extracted) {
        void queryClient.invalidateQueries({ queryKey: knowledgeKeys.candidates(artifact.id) })
      }
    },
  }
}
