import { describe, expect, it } from 'vitest'
import type { Candidate, ExtractionUnit, KnowledgeNode } from '../api/knowledge'
import {
  approvedCandidates,
  countByReviewStatus,
  effectiveContentText,
  effectivePayloadColumns,
  filterCandidates,
  hasConflicts,
  isEdited,
  isReviewable,
  resolveCandidateSource,
} from './candidates'

function candidate(
  partial: Partial<Candidate> & { id: string },
): Candidate {
  return {
    artifactId: 'a1',
    extractionUnitId: 'u1',
    candidateType: 'PLAYBOOK_RULE',
    reviewStatus: 'PENDING',
    rawLabel: 'Rule',
    contentText: 'Original text',
    payloadColumns: {},
    legalConceptKey: null,
    editedContentText: null,
    editedPayloadColumns: {},
    supersedingCandidateId: null,
    contractPackKey: null,
    declaredContractTypes: [],
    partyPerspective: null,
    paperSource: null,
    jurisdiction: null,
    effectiveFrom: null,
    effectiveTo: null,
    conflictSignals: [],
    reviewNote: null,
    reviewedAt: null,
    createdAt: '2026-09-23T05:00:00Z',
    updatedAt: '2026-09-23T05:00:00Z',
    ...partial,
  }
}

function unit(partial: Partial<ExtractionUnit> & { id: string }): ExtractionUnit {
  return {
    artifactId: 'a1',
    ordinal: 0,
    kind: 'HEADING_SECTION',
    title: null,
    contentText: null,
    sourceNodeIds: [],
    ...partial,
  }
}

function node(partial: Partial<KnowledgeNode> & { id: string }): KnowledgeNode {
  return {
    artifactId: 'a1',
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
    ...partial,
  }
}

describe('candidate review helpers', () => {
  const candidates = [
    candidate({ id: 'p', reviewStatus: 'PENDING' }),
    candidate({ id: 'a', reviewStatus: 'APPROVED' }),
    candidate({
      id: 'e',
      reviewStatus: 'EDITED',
      conflictSignals: [
        { conflictType: 'DUPLICATE', message: 'Duplicate', publishedCandidateId: null },
      ],
    }),
    candidate({ id: 'r', reviewStatus: 'REJECTED' }),
    candidate({ id: 'x', reviewStatus: 'PUBLISHED' }),
  ]

  it('counts candidates per review status', () => {
    const counts = countByReviewStatus(candidates)

    expect(counts).toEqual({
      total: 5,
      PENDING: 1,
      APPROVED: 1,
      EDITED: 1,
      REJECTED: 1,
      PUBLISHED: 1,
    })
  })

  it('filters by status and conflicts', () => {
    expect(filterCandidates(candidates, { status: 'EDITED', conflictsOnly: false })).toHaveLength(1)
    expect(filterCandidates(candidates, { status: 'ALL', conflictsOnly: true })).toEqual([
      candidates[2],
    ])
    expect(
      filterCandidates(candidates, { status: 'APPROVED', conflictsOnly: true }),
    ).toHaveLength(0)
    expect(hasConflicts(candidates[0])).toBe(false)
    expect(hasConflicts(candidates[2])).toBe(true)
  })

  it('prefers the human edit over the machine proposal', () => {
    const edited = candidate({
      id: 'e',
      contentText: 'Machine text',
      editedContentText: 'Human text',
      payloadColumns: { Clause: 'Machine' },
      editedPayloadColumns: { Clause: 'Human' },
    })

    expect(effectiveContentText(edited)).toBe('Human text')
    expect(effectivePayloadColumns(edited)).toEqual({ Clause: 'Human' })
    expect(isEdited(edited)).toBe(true)

    const untouched = candidate({ id: 'p' })
    expect(effectiveContentText(untouched)).toBe('Original text')
    expect(effectivePayloadColumns(untouched)).toEqual({})
    expect(isEdited(untouched)).toBe(false)
  })

  it('treats rejected and published candidates as not reviewable', () => {
    expect(isReviewable(candidate({ id: 'p' }))).toBe(true)
    expect(isReviewable(candidate({ id: 'r', reviewStatus: 'REJECTED' }))).toBe(false)
    expect(isReviewable(candidate({ id: 'x', reviewStatus: 'PUBLISHED' }))).toBe(false)
    expect(approvedCandidates(candidates).map((entry) => entry.id)).toEqual(['a'])
  })

  it('resolves the source unit and its provenance nodes', () => {
    const units = [unit({ id: 'u1', sourceNodeIds: ['h1', 'p1', 'missing'] })]
    const nodes = [
      node({ id: 'h1', nodeType: 'HEADING', text: 'Liability' }),
      node({ id: 'p1', text: 'Caps below 1x are preferred.' }),
    ]

    const resolved = resolveCandidateSource(candidates[0], units, nodes)

    expect(resolved.unit?.id).toBe('u1')
    expect(resolved.nodes.map((entry) => entry.id)).toEqual(['h1', 'p1'])

    const orphaned = resolveCandidateSource(
      candidate({ id: 'z', extractionUnitId: 'missing' }),
      units,
      nodes,
    )
    expect(orphaned.unit).toBeNull()
    expect(orphaned.nodes).toEqual([])
  })
})
