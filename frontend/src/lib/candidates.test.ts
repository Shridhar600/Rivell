import { describe, expect, it } from 'vitest'
import type { Candidate, ExtractionUnit, KnowledgeNode } from '../api/knowledge'
import { effectiveContentText, effectivePayloadColumns, resolveCandidateSource } from './candidates'

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
    effectiveConceptKey: null,
    conceptCorrection: null,
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
    classifiedConcepts: {},
    positions: [],
    variantRole: null,
    escalationTrigger: null,
    requiresApproval: null,
    interpretations: [],
    reviewCategory: null,
    reviewReasons: [],
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
    recordKind: null,
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

    const untouched = candidate({ id: 'p' })
    expect(effectiveContentText(untouched)).toBe('Original text')
    expect(effectivePayloadColumns(untouched)).toEqual({})
  })

  it('renders a structured-only correction in source order when no wording was typed', () => {
    const corrected = candidate({
      id: 'c',
      contentText: 'Preferred | Fallback | Escalate',
      editedContentText: null,
      payloadColumns: { Position: 'Preferred', Alternative: 'Fallback', Trigger: 'Escalate' },
      editedPayloadColumns: {
        Position: 'Preferred',
        Alternative: 'Fallback',
        Trigger: 'Escalate to the board',
      },
    })

    expect(effectiveContentText(corrected)).toBe('Preferred | Fallback | Escalate to the board')
    expect(effectivePayloadColumns(corrected)).toEqual({
      Position: 'Preferred',
      Alternative: 'Fallback',
      Trigger: 'Escalate to the board',
    })
  })

  it('renders corrected columns in source order even when the columns arrive out of order', () => {
    const corrected = candidate({
      id: 'c',
      contentText: 'Liability cap | Notification',
      editedContentText: null,
      payloadColumns: { Cap: 'Liability cap', Notice: 'Notification' },
      editedPayloadColumns: { Notice: 'Notification within 5 days', Cap: 'Liability cap' },
    })

    expect(effectiveContentText(corrected)).toBe('Liability cap | Notification within 5 days')
  })

  it('keeps explicit human wording ahead of corrected columns', () => {
    const both = candidate({
      id: 'b',
      contentText: 'Preferred | Fallback',
      editedContentText: 'Counsel agreed wording',
      payloadColumns: { Position: 'Preferred', Alternative: 'Fallback' },
      editedPayloadColumns: { Position: 'Prohibited', Alternative: 'Fallback' },
    })

    expect(effectiveContentText(both)).toBe('Counsel agreed wording')
  })

  it('composes sequential structured corrections instead of dropping the earlier one', () => {
    const firstCorrection = candidate({
      id: 's',
      contentText: 'A1 | B1 | C1',
      editedContentText: null,
      payloadColumns: { One: 'A1', Two: 'B1', Three: 'C1' },
      editedPayloadColumns: { One: 'A1 edited', Two: 'B1', Three: 'C1' },
    })
    expect(effectiveContentText(firstCorrection)).toBe('A1 edited | B1 | C1')

    const secondCorrection = candidate({
      ...firstCorrection,
      editedPayloadColumns: { One: 'A1 edited', Two: 'B1 edited', Three: 'C1' },
    })
    expect(effectiveContentText(secondCorrection)).toBe('A1 edited | B1 edited | C1')
  })

  it('falls back to machine wording when no correction was made', () => {
    const untouched = candidate({
      id: 'u',
      contentText: 'Machine wording',
      editedContentText: null,
      payloadColumns: { Clause: 'Machine' },
      editedPayloadColumns: {},
    })

    expect(effectiveContentText(untouched)).toBe('Machine wording')
  })

  it('does not let a blank correction hide valid machine wording', () => {
    const blank = candidate({
      id: 'k',
      contentText: 'Machine wording',
      editedContentText: '   ',
      payloadColumns: { Clause: 'Machine' },
      editedPayloadColumns: { Clause: '   ' },
    })

    expect(effectiveContentText(blank)).toBe('Machine wording')
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
