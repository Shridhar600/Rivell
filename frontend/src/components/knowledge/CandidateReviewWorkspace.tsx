import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import {
  useCandidatesQuery,
  useExtractionUnitsQuery,
  useKnowledgeStructureQuery,
  type ArtifactDetail,
} from '../../api/knowledge'
import type { ReviewStatusFilter } from '../../lib/candidates'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'
import { CandidateQueue } from './CandidateQueue'
import { CandidateReviewPanel } from './CandidateReviewPanel'

export function CandidateReviewWorkspace({
  artifact,
}: {
  artifact: ArtifactDetail
}) {
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = searchParams.get('candidate')
  const candidatesQuery = useCandidatesQuery(artifact.id)
  const unitsQuery = useExtractionUnitsQuery(artifact.id)
  const structureQuery = useKnowledgeStructureQuery(artifact.id)
  const [statusFilter, setStatusFilter] = useState<ReviewStatusFilter>('ALL')
  const [conflictsOnly, setConflictsOnly] = useState(false)
  const panelRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!selectedId) return
    const element = panelRef.current
    if (element && typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ block: 'nearest' })
    }
  }, [selectedId])

  function selectCandidate(candidateId: string) {
    if (candidateId === selectedId) return
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous)
      next.set('candidate', candidateId)
      return next
    })
  }

  if (candidatesQuery.isPending) {
    return <LoadingState label="Loading candidates…" />
  }

  if (candidatesQuery.isError) {
    return (
      <ErrorState
        message={candidatesQuery.error.message || 'Knowledge candidates unavailable.'}
        onRetry={() => void candidatesQuery.refetch()}
      />
    )
  }

  const candidates = candidatesQuery.data

  if (candidates.length === 0) {
    return (
      <EmptyState
        title="No candidates compiled"
        message="Rivell did not compile any knowledge candidates from this source."
      />
    )
  }

  const selected =
    candidates.find((candidate) => candidate.id === selectedId) ?? null
  const sourceContextPending = unitsQuery.isPending || structureQuery.isPending

  return (
    <section className="review-workspace" aria-label="Candidate review">
      <CandidateQueue
        candidates={candidates}
        selectedId={selectedId}
        onSelect={selectCandidate}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
        conflictsOnly={conflictsOnly}
        onConflictsOnlyChange={setConflictsOnly}
      />
      <div className="review-workspace__panel" ref={panelRef}>
        {selected ? (
          <CandidateReviewPanel
            key={selected.id}
            candidate={selected}
            artifact={artifact}
            units={unitsQuery.data ?? []}
            nodes={structureQuery.data ?? []}
            sourceContextPending={sourceContextPending}
          />
        ) : selectedId ? (
          <p className="state" role="status">
            The selected candidate is not part of this knowledge source.
          </p>
        ) : (
          <EmptyState
            title="Select a candidate"
            message="Choose a candidate from the queue to review its source, extracted values, conflicts, and provenance."
          />
        )}
      </div>
    </section>
  )
}
