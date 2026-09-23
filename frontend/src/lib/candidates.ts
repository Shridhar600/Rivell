import type {
  Candidate,
  ExtractionUnit,
  KnowledgeNode,
  ReviewStatus,
} from '../api/knowledge'

export type ReviewStatusFilter = ReviewStatus | 'ALL'

export interface CandidateFilter {
  status: ReviewStatusFilter
  conflictsOnly: boolean
}

export type ReviewStatusCounts = Record<ReviewStatus, number> & { total: number }

export function countByReviewStatus(candidates: Candidate[]): ReviewStatusCounts {
  const counts: ReviewStatusCounts = {
    total: candidates.length,
    PENDING: 0,
    APPROVED: 0,
    EDITED: 0,
    REJECTED: 0,
    PUBLISHED: 0,
  }
  for (const candidate of candidates) {
    counts[candidate.reviewStatus] += 1
  }
  return counts
}

export function hasConflicts(candidate: Candidate): boolean {
  return candidate.conflictSignals.length > 0
}

export function filterCandidates(
  candidates: Candidate[],
  filter: CandidateFilter,
): Candidate[] {
  return candidates.filter((candidate) => {
    if (filter.status !== 'ALL' && candidate.reviewStatus !== filter.status) {
      return false
    }
    if (filter.conflictsOnly && !hasConflicts(candidate)) return false
    return true
  })
}

export function effectiveContentText(candidate: Candidate): string | null {
  return candidate.editedContentText ?? candidate.contentText
}

export function effectivePayloadColumns(
  candidate: Candidate,
): Record<string, string> {
  return Object.keys(candidate.editedPayloadColumns).length > 0
    ? candidate.editedPayloadColumns
    : candidate.payloadColumns
}

export function isEdited(candidate: Candidate): boolean {
  return (
    candidate.editedContentText !== null ||
    Object.keys(candidate.editedPayloadColumns).length > 0
  )
}

export function isReviewable(candidate: Candidate): boolean {
  return (
    candidate.reviewStatus !== 'PUBLISHED' && candidate.reviewStatus !== 'REJECTED'
  )
}

export function approvedCandidates(candidates: Candidate[]): Candidate[] {
  return candidates.filter((candidate) => candidate.reviewStatus === 'APPROVED')
}

export function resolveCandidateSource(
  candidate: Candidate,
  units: ExtractionUnit[],
  nodes: KnowledgeNode[],
): { unit: ExtractionUnit | null; nodes: KnowledgeNode[] } {
  const unit =
    units.find((candidateUnit) => candidateUnit.id === candidate.extractionUnitId) ??
    null
  if (!unit) return { unit: null, nodes: [] }

  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const sourceNodes = unit.sourceNodeIds
    .map((nodeId) => nodeById.get(nodeId))
    .filter((node): node is KnowledgeNode => node !== undefined)

  return { unit, nodes: sourceNodes }
}
