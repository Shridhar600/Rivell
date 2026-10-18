import { useMutation, useQueryClient, type UseMutationResult } from '@tanstack/react-query'
import {
  fetchCandidates,
  knowledgeKeys,
  publishKnowledge,
  type Candidate,
  type Publication,
} from './knowledge'
import { readiness, type Readiness } from '../lib/compilation'
import { hasHumanCorrection } from '../lib/compilationFields'

/**
 * Raised when the server's current answer says there is nothing publishable, or something is still
 * undecided. It is thrown before any publish request is sent.
 */
export class PublicationNotReadyError extends Error {
  readonly readiness: Readiness
  constructor(state: Readiness) {
    super(state.blocker ?? 'This draft is not ready to publish.')
    this.name = 'PublicationNotReadyError'
    this.readiness = state
  }
}

export interface PublishCompilationVariables {
  note?: string
  /** New candidate id to the published candidate it replaces. Only for candidates being published. */
  supersede: Record<string, string>
}

export interface PublishedCompilation {
  publication: Publication
  /** What was decided, from the server's own candidates at the moment of publishing. */
  published: number
  corrected: number
  rejected: number
}

function approved(candidates: Candidate[]): Candidate[] {
  return candidates.filter((candidate) => candidate.reviewStatus === 'APPROVED')
}

/**
 * Publishes the approved candidates of one compilation.
 *
 * The set sent is not taken from what is on screen. The candidates are read again first, and
 * the request is sent only if the server's own statuses say nothing is undecided and something is
 * approved. The server still checks every id, and anything it refuses is raised as an error.
 * Replacements are sent only for candidates that are in the set being published.
 */
export function usePublishCompilation(artifactId: string): UseMutationResult<PublishedCompilation, Error, PublishCompilationVariables> {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ note, supersede }: PublishCompilationVariables) => {
      const fresh = await fetchCandidates(artifactId)
      queryClient.setQueryData(knowledgeKeys.candidates(artifactId), fresh)
      const state = readiness(fresh)
      if (!state.canPublish) throw new PublicationNotReadyError(state)
      const ready = approved(fresh)
      const ids = new Set(ready.map((candidate) => candidate.id))
      const supersededCandidates = Object.fromEntries(Object.entries(supersede).filter(([id]) => ids.has(id)))
      const publication = await publishKnowledge(
        ready.map((candidate) => candidate.id),
        supersededCandidates,
        note,
      )
      return {
        publication,
        published: ready.length,
        corrected: ready.filter((candidate) => hasHumanCorrection(candidate)).length,
        rejected: state.rejected,
      }
    },
    onSuccess: async () => {
      // Refetch the publication records now, including the ones no open page is watching, so the page
      // the reviewer is taken to shows the new publication at once and never the old published set.
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: knowledgeKeys.publications, refetchType: 'all' }),
        queryClient.invalidateQueries({ queryKey: knowledgeKeys.candidates(artifactId) }),
      ])
    },
  })
}
