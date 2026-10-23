import { describe, expect, it } from 'vitest'
import type { FindingSummary } from '../api/reviews'
import type { HumanDecision } from '../api/workflow'
import type { StructureNode } from './documentTree'
import {
  acceptEligibilityFor,
  clauseBody,
  clauseExcerpt,
  defaultFinding,
  executableChange,
  finalizeSummary,
  findingState,
  nextOpenFinding,
  orderForReview,
  profileLine,
  proposedChangeType,
  regionBodyText,
  regionText,
  splitOnTerm,
  topLevelNodes,
} from './workspace'

function finding(id: string, overrides: Partial<FindingSummary> = {}): FindingSummary {
  return {
    id,
    subjectKind: 'CLAUSE',
    subjectHeadingNodeId: null,
    absenceConcept: null,
    concept: null,
    secondaryConcept: null,
    issueFamily: 'X',
    observationKind: 'X',
    severity: 'HIGH',
    reviewClassification: 'REVIEW_REQUIRED',
    proposedAction: 'ESCALATE_TO_HUMAN',
    proposedLanguageSource: 'NONE',
    relatedPosition: false,
    decidedBy: null,
    addedBy: null,
    firstReviewCategory: null,
    positionLabel: null,
    createdAt: '2026-10-01T00:00:00Z',
    ...overrides,
  }
}

function decision(findingId: string, overrides: Partial<HumanDecision> = {}): HumanDecision {
  return {
    id: `d-${findingId}`,
    findingId,
    revision: 1,
    action: 'ACCEPT',
    approvedAction: 'REPLACE',
    approvedLanguage: 'New text.',
    languageSource: 'APPROVED_DRAFTING',
    approvedDraftingEvidenceId: null,
    comment: null,
    rejectReason: null,
    approvedBy: null,
    actorId: 'u',
    decidedAt: '2026-10-01T00:00:00Z',
    clientRequestId: null,
    supersedesDecisionId: null,
    ...overrides,
  }
}

function node(id: string, nodeType: string, text: string | null, parentId: string | null, order: number, extra: Partial<StructureNode> = {}): StructureNode {
  return {
    id,
    parentId,
    nodeType,
    text,
    numberingLabel: null,
    headingLevel: nodeType === 'HEADING' ? 1 : null,
    sourceSpan: { bodyIndex: order, paragraphIndex: order },
    ...extra,
  } as StructureNode
}

describe('findingState', () => {
  it('says what each decision is, and never calls an escalation resolved', () => {
    const f = finding('a')
    expect(findingState(f, null)).toEqual({ tone: 'open', label: 'Needs decision' })
    expect(findingState(f, decision('a', { action: 'ACCEPT' })).label).toBe('Accepted')
    expect(findingState(f, decision('a', { action: 'EDIT' })).label).toBe('Edited')
    expect(findingState(f, decision('a', { action: 'REJECT' })).tone).toBe('rejected')
    expect(findingState(f, decision('a', { action: 'REJECT', rejectReason: 'KEEP_AS_DRAFTED' }))).toEqual({ tone: 'resolved', label: 'Kept as drafted' })
    expect(findingState(f, decision('a', { action: 'REJECT', rejectReason: 'FINDING_WRONG' }))).toEqual({ tone: 'rejected', label: 'Dismissed' })
    expect(findingState(f, decision('a', { action: 'RESOLVE', approvedAction: null, approvedLanguage: null, languageSource: null, approvedBy: 'Jane Doe' }))).toEqual({ tone: 'resolved', label: 'Approved by Jane Doe' })
    expect(findingState(f, decision('a', { action: 'ESCALATE' }))).toEqual({ tone: 'escalated', label: 'Escalated · unresolved' })
  })

  it('does not ask for a decision on an informational finding', () => {
    expect(findingState(finding('a', { reviewClassification: 'INFORMATIONAL' }), null).label).toBe('Informational')
  })
})

describe('executableChange', () => {
  it('exists only for an accepted or edited decision with a concrete change type', () => {
    expect(executableChange(decision('a'))).toEqual({ type: 'REPLACE', language: 'New text.' })
    expect(executableChange(decision('a', { action: 'EDIT', approvedAction: 'DELETE', approvedLanguage: null }))).toEqual({ type: 'DELETE', language: null })
    expect(executableChange(decision('a', { action: 'REJECT' }))).toBeNull()
    expect(executableChange(decision('a', { action: 'RESOLVE', approvedBy: 'Jane Doe' }))).toBeNull()
    expect(executableChange(decision('a', { action: 'ESCALATE' }))).toBeNull()
    expect(executableChange(decision('a', { approvedAction: null }))).toBeNull()
    expect(executableChange(null)).toBeNull()
  })
})

describe('proposedChangeType', () => {
  it('names a change only when the recommendation makes one', () => {
    expect(proposedChangeType({ subjectKind: 'CLAUSE', proposedAction: 'USE_APPROVED_FALLBACK' })).toBe('REPLACE')
    expect(proposedChangeType({ subjectKind: 'ABSENCE', proposedAction: 'ADD_REQUIRED_PROVISION' })).toBe('INSERT')
    expect(proposedChangeType({ subjectKind: 'ABSENCE', proposedAction: 'CONSIDER_GENERATED_LANGUAGE' })).toBe('INSERT')
    expect(proposedChangeType({ subjectKind: 'CLAUSE', proposedAction: 'CONSIDER_GENERATED_LANGUAGE' })).toBe('REPLACE')
    expect(proposedChangeType({ subjectKind: 'CLAUSE', proposedAction: 'ESCALATE_TO_HUMAN' })).toBeNull()
    expect(proposedChangeType({ subjectKind: 'CLAUSE', proposedAction: 'NO_ACTION' })).toBeNull()
  })
})

describe('profileLine', () => {
  it('describes the frozen profile only, and is silent without one', () => {
    const snapshot = {
      profile: { contractType: 'MSA', ourSideRole: 'CUSTOMER', paperSource: 'COUNTERPARTY_PAPER', jurisdiction: 'US', contractPackKey: null },
      packVersion: null,
      knowledgePinNumber: null,
      effectiveOn: null,
      engine: null,
      playbooksUsed: null,
    }
    expect(profileLine(snapshot)).toBe('MSA, counterparty paper, our side customer')
    expect(profileLine({ ...snapshot, profile: null })).toBeNull()
    expect(profileLine(null)).toBeNull()
  })
})

describe('moving between findings', () => {
  const list = [finding('a'), finding('b', { reviewClassification: 'INFORMATIONAL' }), finding('c'), finding('d')]

  it('lands on the first finding that needs a decision, skipping informational and decided ones', () => {
    const decisions = new Map([['a', decision('a')]])
    expect(defaultFinding(list, decisions)?.id).toBe('c')
    expect(defaultFinding(list, new Map())?.id).toBe('a')
  })

  it('falls back to the first finding when none needs a decision', () => {
    const decisions = new Map(list.map((f) => [f.id, decision(f.id)]))
    expect(defaultFinding(list, decisions)?.id).toBe('a')
    expect(defaultFinding([], new Map())).toBeNull()
  })

  it('advances after a decision, wraps around, and treats an escalation as still open', () => {
    const decided = new Map([
      ['a', decision('a')],
      ['c', decision('c', { action: 'ESCALATE' })],
    ])
    expect(nextOpenFinding(list, decided, 'a')?.id).toBe('c')
    expect(nextOpenFinding(list, decided, 'c')?.id).toBe('d')
    expect(nextOpenFinding(list, decided, 'd')?.id).toBe('c')
    const allDone = new Map(list.map((f) => [f.id, decision(f.id)]))
    expect(nextOpenFinding(list, allDone, 'a')).toBeNull()
  })
})

describe('clauses from the real, flat structure', () => {
  // The parser keeps every passage directly under BODY. A clause is a heading plus what follows it.
  const nodes = [
    node('body', 'BODY', null, null, 0),
    node('t', 'TITLE', 'Master Services Agreement', 'body', 1),
    node('h1', 'HEADING', 'Fees', 'body', 2, { numberingLabel: '3.' }),
    node('p1', 'PARAGRAPH', '3.1 Pay in thirty days.', 'body', 3),
    node('p2', 'PARAGRAPH', '3.2 Late interest applies.', 'body', 4),
    node('h2', 'HEADING', 'Term', 'body', 5, { numberingLabel: '4.' }),
    node('p3', 'PARAGRAPH', '4.1 Two years.', 'body', 6),
  ]

  it('cuts clauses at the BODY level, as the backend segmenter does', () => {
    const top = topLevelNodes(nodes)
    expect(top.map((n) => n.id)).toEqual(['t', 'h1', 'p1', 'p2', 'h2', 'p3'])
    expect(clauseBody(top, 1).map((n) => n.id)).toEqual(['p1', 'p2'])
    expect(clauseBody(top, 4).map((n) => n.id)).toEqual(['p3'])
  })

  it('reads a clause as received: heading, then body, one passage per line', () => {
    expect(regionText(nodes, 'h1')).toBe('3. Fees\n3.1 Pay in thirty days.\n3.2 Late interest applies.')
    expect(regionText(nodes, 'h2')).toBe('4. Term\n4.1 Two years.')
    expect(regionText(nodes, 'missing')).toBeNull()
    expect(regionBodyText(nodes, 'h1')).toBe('3.1 Pay in thirty days.\n3.2 Late interest applies.')
    expect(regionBodyText(nodes, 'h2')).toBe('4.1 Two years.')
    expect(regionBodyText(nodes, 'missing')).toBeNull()
  })
})

describe('clauseExcerpt', () => {
  it('keeps about two lines and marks that more follows', () => {
    expect(clauseExcerpt('3. Fees\n3.1 Pay in thirty days.\n3.2 Late interest applies.')).toBe(
      '3. Fees\n3.1 Pay in thirty days.…',
    )
  })

  it('truncates one very long passage within the character budget', () => {
    const long = 'x'.repeat(400)
    const excerpt = clauseExcerpt(`3. Fees\n${long}`)
    expect(excerpt.length).toBeLessThan(long.length)
    expect(excerpt.endsWith('…')).toBe(true)
  })

  it('keeps some clause body even when the first passage exhausts the budget', () => {
    const long = 'x'.repeat(400)
    const excerpt = clauseExcerpt(long)
    expect(excerpt.length).toBeLessThanOrEqual(180)
    expect(excerpt.endsWith('…')).toBe(true)
    expect(excerpt).not.toContain('\n')
  })

  it('still shows the start of the body when the heading and body together exceed the budget', () => {
    const excerpt = clauseExcerpt(`6. Limitation of Liability\n${'y'.repeat(400)}`)
    expect(excerpt.startsWith('6. Limitation of Liability\n')).toBe(true)
    expect(excerpt.split('\n')).toHaveLength(2)
    expect(excerpt.endsWith('…')).toBe(true)
    expect(excerpt.length).toBeLessThanOrEqual(181)
  })

  it('returns a short clause unchanged, with no invented ellipsis', () => {
    expect(clauseExcerpt('4. Term\n4.1 Two years.')).toBe('4. Term\n4.1 Two years.')
  })
})

describe('finalizeSummary', () => {
  const review = { accepted: 1, edited: 2, rejected: 1 }

  it('states the server counts, and splits by change type only when every change is accounted for', () => {
    const loaded = [{ type: 'REPLACE' as const }, { type: 'REPLACE' as const }, { type: 'DELETE' as const }]
    expect(finalizeSummary(review, loaded)).toEqual([
      '3 approved changes (1 accepted, 2 edited): 2 replace, 0 insert, 1 delete',
      '1 finding with no approved change',
    ])
  })

  it('names resolved escalations in the final summary', () => {
    expect(
      finalizeSummary({ accepted: 1, edited: 1, rejected: 1 }, null, 1),
    ).toEqual([
      '2 approved changes (1 accepted, 1 edited)',
      '1 finding with no approved change',
      '1 escalation resolved with an approver name; no approved change created',
    ])
  })

  it('leaves the split out when the loaded decisions do not match the server', () => {
    expect(finalizeSummary(review, [{ type: 'REPLACE' }])[0]).toBe('3 approved changes (1 accepted, 2 edited)')
    expect(finalizeSummary(review, null)[0]).toBe('3 approved changes (1 accepted, 2 edited)')
  })
})

describe('splitOnTerm', () => {
  it('marks every literal, case-insensitive match', () => {
    expect(splitOnTerm('Net 30 and net 60', 'net')).toEqual([
      { text: 'Net', match: true },
      { text: ' 30 and ', match: false },
      { text: 'net', match: true },
      { text: ' 60', match: false },
    ])
  })

  it('treats the term as text, not a pattern, and returns the text whole when nothing matches', () => {
    expect(splitOnTerm('a.b', '.')).toEqual([
      { text: 'a', match: false },
      { text: '.', match: true },
      { text: 'b', match: false },
    ])
    expect(splitOnTerm('abc', 'x')).toEqual([{ text: 'abc', match: false }])
    expect(splitOnTerm('abc', '')).toEqual([{ text: 'abc', match: false }])
  })
})

describe('approved wording', () => {
  const detail = (over: Record<string, unknown> = {}) =>
    ({
      proposedAction: 'USE_APPROVED_FALLBACK',
      proposedLanguage: 'Cap is 2x fees.',
      proposedLanguageSource: 'APPROVED_DRAFTING',
      approvedDraftingEvidenceId: 'e-1',
      evidenceItems: [{ evidenceId: 'e-1', authorityClass: 'APPROVED_DRAFTING' }],
      ...over,
    }) as never

  it('can be accepted only when the evidence it cites is part of the finding', () => {
    expect(acceptEligibilityFor(detail()).eligible).toBe(true)
    expect(acceptEligibilityFor(detail({ approvedDraftingEvidenceId: null })).eligible).toBe(false)
    expect(acceptEligibilityFor(detail({ approvedDraftingEvidenceId: 'e-missing' })).eligible).toBe(false)
    expect(acceptEligibilityFor(detail({ evidenceItems: [{ evidenceId: 'e-1', authorityClass: 'POLICY' }] })).eligible).toBe(false)
  })

  it('leaves machine-proposed wording to the ordinary eligibility rule', () => {
    expect(acceptEligibilityFor(detail({ proposedLanguageSource: 'GENERATIVE', proposedAction: 'CONSIDER_GENERATED_LANGUAGE', approvedDraftingEvidenceId: null })).eligible).toBe(true)
  })
})

describe('orderForReview', () => {
  it('puts "needs your look" items after the others and keeps each group in server order', () => {
    const findings = [
      { id: 'a', decidedBy: 'NEEDS_YOUR_LOOK' },
      { id: 'b', decidedBy: 'JEV' },
      { id: 'c', decidedBy: null },
      { id: 'd', decidedBy: 'NEEDS_YOUR_LOOK' },
    ]
    expect(orderForReview(findings).map((finding) => finding.id)).toEqual(['b', 'c', 'a', 'd'])
  })

  it('puts walk-away items first inside "needs your look", the rest in server order', () => {
    const findings = [
      { id: 'a', decidedBy: 'NEEDS_YOUR_LOOK', firstReviewCategory: 'BEYOND_FALLBACK' },
      { id: 'b', decidedBy: 'JEV', firstReviewCategory: null },
      { id: 'c', decidedBy: 'NEEDS_YOUR_LOOK', firstReviewCategory: null },
      { id: 'd', decidedBy: 'NEEDS_YOUR_LOOK', firstReviewCategory: 'WALK_AWAY' },
      { id: 'e', decidedBy: 'NEEDS_YOUR_LOOK', firstReviewCategory: 'WALK_AWAY' },
    ]
    expect(orderForReview(findings).map((finding) => finding.id)).toEqual(['b', 'd', 'e', 'a', 'c'])
  })
})
