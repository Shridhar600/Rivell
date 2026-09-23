import { useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import {
  useCandidatesQuery,
  useExtractionUnitsQuery,
  useKnowledgeStructureQuery,
  usePublicationsQuery,
  type ArtifactDetail,
} from '../../api/knowledge'
import {
  approvedCandidates,
  type ReviewStatusFilter,
} from '../../lib/candidates'
import { isBulkEligible } from '../../lib/semantics'
import { EmptyState } from '../EmptyState'
import { ErrorState } from '../ErrorState'
import { LoadingState } from '../LoadingState'
import { BulkReviewToolbar } from './BulkReviewToolbar'
import { CandidateQueue } from './CandidateQueue'
import { CandidateReviewPanel } from './CandidateReviewPanel'
import { PublicationDialog } from './PublicationDialog'

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
  const [publishOpen, setPublishOpen] = useState(false)
  const [supersessionChoices, setSupersessionChoices] = useState<
    Record<string, string>
  >({})
  const [selectedForBulk, setSelectedForBulk] = useState<Set<string>>(new Set())
  const panelRef = useRef<HTMLDivElement>(null)
  const panelHeadingRef = useRef<HTMLHeadingElement>(null)
  const skipPanelFocus = useRef(true)

  const selectedCandidateId = selectedId
  const publishedSelected = candidatesQuery.data?.find(
    (candidate) => candidate.id === selectedCandidateId,
  )
  const publicationsQuery = usePublicationsQuery(
    publishedSelected?.reviewStatus === 'PUBLISHED',
  )

  useEffect(() => {
    if (!selectedId) {
      skipPanelFocus.current = false
      return
    }
    const heading = panelHeadingRef.current
    if (heading && !skipPanelFocus.current && typeof heading.focus === 'function') {
      heading.focus({ preventScroll: true })
    }
    skipPanelFocus.current = false
    const element = panelRef.current
    if (element && typeof element.scrollIntoView === 'function') {
      element.scrollIntoView({ block: 'nearest' })
    }
  }, [selectedId])

  function setSupersessionChoice(candidateId: string, peerId: string | null) {
    setSupersessionChoices((previous) => {
      const next = { ...previous }
      if (peerId === null) {
        delete next[candidateId]
      } else {
        next[candidateId] = peerId
      }
      return next
    })
  }

  function toggleBulkSelection(candidateId: string) {
    setSelectedForBulk((previous) => {
      const next = new Set(previous)
      if (next.has(candidateId)) {
        next.delete(candidateId)
      } else {
        next.add(candidateId)
      }
      return next
    })
  }

  function toggleAllEligible() {
    setSelectedForBulk((previous) => {
      const eligible = candidatesQuery.data?.filter(isBulkEligible) ?? []
      const allSelected =
        eligible.length > 0 && eligible.every((candidate) => previous.has(candidate.id))
      return allSelected ? new Set() : new Set(eligible.map((candidate) => candidate.id))
    })
  }

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
  const approved = approvedCandidates(candidates)
  const publishedIn =
    selected && selected.reviewStatus === 'PUBLISHED'
      ? (publicationsQuery.data?.find((publication) =>
          publication.items.some((item) => item.candidateId === selected.id),
        ) ?? null)
      : null

  return (
    <section className="review-workspace" aria-label="Candidate review">
      <BulkReviewToolbar
        candidates={candidates}
        selectedIds={selectedForBulk}
        onToggleAll={toggleAllEligible}
        onClear={() => setSelectedForBulk(new Set())}
        artifactId={artifact.id}
      />
      <CandidateQueue
        candidates={candidates}
        selectedId={selectedId}
        onSelect={selectCandidate}
        statusFilter={statusFilter}
        onStatusFilterChange={setStatusFilter}
        conflictsOnly={conflictsOnly}
        onConflictsOnlyChange={setConflictsOnly}
        publishAction={
          <button
            type="button"
            className="button button--primary"
            disabled={approved.length === 0}
            onClick={() => setPublishOpen(true)}
          >
            Publish approved ({approved.length})
          </button>
        }
        selectedForBulk={selectedForBulk}
        onToggleBulk={toggleBulkSelection}
      />
      <div className="review-workspace__panel" ref={panelRef}>
        {selected ? (
          <CandidateReviewPanel
            key={selected.id}
            headingRef={panelHeadingRef}
            candidate={selected}
            artifact={artifact}
            units={unitsQuery.data ?? []}
            nodes={structureQuery.data ?? []}
            sourceContextPending={sourceContextPending}
            publishedIn={publishedIn}
            supersessionChoice={supersessionChoices[selected.id] ?? null}
            onSupersessionChange={(peerId) =>
              setSupersessionChoice(selected.id, peerId)
            }
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
      {publishOpen ? (
        <PublicationDialog
          artifact={artifact}
          approved={approved}
          supersessionChoices={supersessionChoices}
          onClose={() => setPublishOpen(false)}
        />
      ) : null}
    </section>
  )
}
