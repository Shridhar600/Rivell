import { describe, expect, it } from 'vitest'
import {
  fixtureFailedRedlineExecution,
  fixtureRedlineChange,
  fixtureRedlineExecution,
} from '../components/review/redlineFixtures'
import {
  fixtureAbsenceFindingSummaryForChangeSet,
  fixtureAcceptableFinding,
  fixtureChangeSet,
  fixtureDecision,
  fixtureHumanReview,
} from '../components/review/workflowFixtures'
import { fixtureNodes } from '../components/contract/testFixtures'
import type { DocumentNode } from '../api/contracts'
import { humanizeCode } from './domainLabels'
import {
  changeLocation,
  clauseBodyText,
  changeOutcome,
  changeTitle,
  classificationCounts,
  contextRows,
  decisionSegments,
  failureNote,
  findingTitle,
  nextUnresolvedTitle,
  overallResult,
  pluralise,
  reportLocation,
  reportResult,
  sideChip,
  typeChip,
} from './lifecycleView'

describe('findings and progress', () => {
  it('names a Finding by its concept, or by the missing provision', () => {
    expect(findingTitle(fixtureAcceptableFinding, humanizeCode)).toBe('Limitation of liability')
    expect(findingTitle(fixtureAbsenceFindingSummaryForChangeSet, humanizeCode)).toBe('Missing: Confidentiality obligations')
    expect(findingTitle(undefined, humanizeCode)).toBeNull()
  })

  it('counts Findings by their server classification', () => {
    const counts = classificationCounts([
      fixtureAcceptableFinding,
      { ...fixtureAcceptableFinding, id: 'f-9', reviewClassification: 'BLOCKING' },
      { ...fixtureAcceptableFinding, id: 'f-8', reviewClassification: 'INFORMATIONAL' },
    ])
    expect(counts).toEqual([
      { label: 'Blocking', count: 1 },
      { label: 'Review required', count: 1 },
      { label: 'Informational', count: 1 },
    ])
  })

  it('names the next unresolved Finding only when the server names it and the list holds it', () => {
    const review = fixtureHumanReview()
    expect(nextUnresolvedTitle(review, [fixtureAcceptableFinding], humanizeCode)).toBe('Limitation of liability')
    expect(nextUnresolvedTitle(review, [], humanizeCode)).toBeNull()
    expect(nextUnresolvedTitle(fixtureHumanReview({ blockers: [] }), [fixtureAcceptableFinding], humanizeCode)).toBeNull()
  })

  it('draws one segment per Finding, filled for each one decided, with no percentage', () => {
    expect(decisionSegments(fixtureHumanReview({ actionableFindings: 8, resolvedFindings: 5 }))).toEqual([
      true, true, true, true, true, false, false, false,
    ])
    expect(decisionSegments(fixtureHumanReview({ actionableFindings: 0, resolvedFindings: 0 }))).toEqual([])
    // A count that exceeds the total cannot overfill the bar.
    expect(decisionSegments(fixtureHumanReview({ actionableFindings: 2, resolvedFindings: 5 }))).toEqual([true, true])
  })

  it('pluralises', () => {
    expect(pluralise(1, 'Finding')).toBe('1 Finding')
    expect(pluralise(2, 'Finding')).toBe('2 Findings')
    expect(pluralise(0, 'approved change')).toBe('0 approved changes')
  })
})

describe('approved changes', () => {
  const change = fixtureChangeSet().changes[0]
  const labels = new Map([['node-6', '8. Limitation of Liability']])

  it('titles a change by its Finding, falling back to the target', () => {
    expect(changeTitle(change, fixtureAcceptableFinding, labels, humanizeCode)).toBe('Limitation of liability')
    expect(changeTitle(change, undefined, labels, humanizeCode)).toBe('8. Limitation of Liability')
  })

  it('does not invent a place for an insertion', () => {
    const insert = { ...change, actionType: 'INSERT' as const, targetKind: 'ABSENCE' as const, targetHeadingNodeId: null, absenceConcept: 'CONFIDENTIALITY_OBLIGATIONS' }
    expect(changeLocation(insert, labels, humanizeCode)).toBe('Placement resolved during redline execution')
    expect(changeLocation(change, labels, humanizeCode)).toBe('8. Limitation of Liability')
  })

  it('reports the human decision as the outcome', () => {
    expect(changeOutcome(fixtureDecision({ action: 'EDIT' }))).toBe('Edited')
    expect(changeOutcome(fixtureDecision({ action: 'ACCEPT' }))).toBe('Accepted')
    expect(changeOutcome(undefined)).toBe('Approved')
  })
})

describe('redline report: no partial success is ever shown as success', () => {
  const applied = fixtureRedlineChange()

  it('marks a change Applied · Verified only inside a completed execution', () => {
    expect(reportResult(fixtureRedlineExecution(), applied)).toEqual({ label: 'Applied · Verified', variant: 'ok' })
  })

  it('a change applied before an overall failure is neutral, never the success colour', () => {
    const execution = fixtureFailedRedlineExecution()
    const result = reportResult(execution, fixtureRedlineChange({ status: 'APPLIED', verification: null }))
    expect(result).toEqual({ label: 'Applied before overall failure', variant: 'neutral' })
  })

  it('shows a failed or unapplied change as such', () => {
    const execution = fixtureFailedRedlineExecution()
    expect(reportResult(execution, fixtureRedlineChange({ status: 'FAILED', verification: null }))).toEqual({
      label: 'Execution failed',
      variant: 'red',
    })
    expect(reportResult(execution, fixtureRedlineChange({ status: 'NOT_APPLIED', verification: null })).variant).toBe('neutral')
  })

  it('does not call an applied but unverified change verified', () => {
    const execution = fixtureRedlineExecution()
    expect(reportResult(execution, fixtureRedlineChange({ verification: 'FAILED' })).variant).toBe('red')
    expect(reportResult(execution, fixtureRedlineChange({ verification: null })).label).toBe('Applied · Not verified')
  })

  it('states the overall result from the execution record', () => {
    expect(overallResult(fixtureRedlineExecution())).toMatchObject({ word: 'Verified', tone: 'ok' })
    expect(overallResult(fixtureFailedRedlineExecution())).toMatchObject({ word: 'Failed', tone: 'red' })
    expect(overallResult(fixtureRedlineExecution({ downloadAvailable: true, changes: [fixtureRedlineChange({ verification: 'FAILED' })] }))).toMatchObject({
      word: 'Not verified',
      tone: 'red',
    })
    expect(overallResult(fixtureFailedRedlineExecution()).sentence).toContain('No file is offered')
  })

  it('names where an insertion landed, or says it was not resolved', () => {
    const insert = fixtureRedlineChange({ actionType: 'INSERT', placement: 'AFTER_CLAUSE[CONFIDENTIALITY_OBLIGATIONS]', resolvedTarget: 'CONFIDENTIALITY_OBLIGATIONS' })
    expect(reportLocation(insert, undefined, new Map(), false, humanizeCode)).toBe('After clause: Confidentiality obligations')
    expect(reportLocation({ ...insert, status: 'FAILED' }, undefined, new Map(), true, humanizeCode)).toBe('Not resolved safely')
  })

  it('shows only a fixed, user-safe sentence for a failure, never the server detail', () => {
    const note = failureNote(fixtureRedlineChange({ failureCode: 'TARGET_NOT_FOUND' }))
    expect(note).toBe('An approved change targets a location that could not be found in the frozen document.')
    expect(failureNote(fixtureRedlineChange({ failureCode: null }))).toBeNull()
  })
})

describe('review context', () => {
  const profile = {
    contractType: 'MSA',
    ourSideRole: 'SUPPLIER',
    paperSource: 'COUNTERPARTY_PAPER',
    jurisdiction: 'UK',
    contractPackKey: 'MSA_PACK',
  }

  it('reads the context frozen on the Review, never anything newer', () => {
    expect(contextRows(profile, 'MSA pack')).toEqual([
      { label: 'Type', value: 'MSA' },
      { label: 'Our side', value: 'Supplier' },
      { label: 'Paper', value: 'Counterparty paper' },
      { label: 'Jurisdiction', value: 'United Kingdom' },
      { label: 'Pack', value: 'MSA pack' },
    ])
  })

  it('says Not specified for an undeclared value, and None for no pack', () => {
    const rows = contextRows({ ...profile, ourSideRole: null, contractPackKey: null }, null)
    expect(rows.find((row) => row.label === 'Our side')?.value).toBe('Not specified')
    expect(rows.find((row) => row.label === 'Pack')?.value).toBe('None')
  })

  it('has no chips when nothing was declared', () => {
    expect(sideChip(null)).toBeNull()
    expect(typeChip(null)).toBeNull()
    expect(sideChip(profile)).toBe('Supplier side')
  })
})

describe('clauseBodyText: the clause a REPLACE or DELETE acts on', () => {
  const span = (bodyIndex: number) => ({ bodyIndex, paragraphIndex: bodyIndex, rowIndex: null, cellIndex: null }) as DocumentNode['sourceSpan']
  const node = (id: string, nodeType: DocumentNode['nodeType'], bodyIndex: number, text: string, headingLevel: number | null = null) =>
    ({ id, parentId: 'body', nodeType, ordinal: bodyIndex, text, headingLevel, numberingLabel: null, sourceSpan: span(bodyIndex) }) as DocumentNode

  it('is the paragraphs after the heading, up to the next heading of the same level', () => {
    const nodes = [
      node('h1', 'HEADING', 1, '1. Definitions', 1),
      node('p1', 'PARAGRAPH', 2, '1.1 First.'),
      node('p2', 'PARAGRAPH', 3, '1.2 Second.'),
      node('h2', 'HEADING', 4, '2. Services', 1),
      node('p3', 'PARAGRAPH', 5, '2.1 Other clause.'),
    ]
    expect(clauseBodyText(nodes, 'h1')).toBe('1.1 First.\n\n1.2 Second.')
    expect(clauseBodyText(nodes, 'h2')).toBe('2.1 Other clause.')
  })

  it('reads in document order whatever order the nodes arrive in, and keeps a deeper heading inside the clause', () => {
    const nodes = [
      node('p2', 'PARAGRAPH', 4, 'Detail.'),
      node('h1', 'HEADING', 1, '1. Terms', 1),
      node('sub', 'HEADING', 3, '1.1 Sub', 2),
      node('p1', 'PARAGRAPH', 2, 'Lead-in.'),
    ]
    expect(clauseBodyText(nodes, 'h1')).toBe('Lead-in.\n\n1.1 Sub\n\nDetail.')
  })

  it('is null for an unknown heading, or a heading with no body, so no empty before is shown as a deletion', () => {
    const nodes = [node('h1', 'HEADING', 1, '1. Empty', 1), node('h2', 'HEADING', 2, '2. Next', 1)]
    expect(clauseBodyText(nodes, 'h1')).toBeNull()
    expect(clauseBodyText(nodes, 'missing')).toBeNull()
  })

  it('reads the pinned fixture contract', () => {
    expect(clauseBodyText(fixtureNodes as unknown as DocumentNode[], 'node-6')).toContain('6.3 The exclusions in Section 6.2')
  })
})
