import { describe, expect, it } from 'vitest'
import type { EvidenceItem, EvidenceSufficiency } from '../api/retrieval'
import {
  POLICY_MISSING_PRECEDENT_NOTE,
  degradationMessage,
  evidenceLanesFor,
  policyCompatibilityLabel,
  retrievalReasonLabel,
  sourceTypeLabel,
  sufficiencyPresentation,
  traceRequestEntries,
  traceStageLabel,
  traceTimingLabel,
} from './evidence'

function item(partial: Partial<EvidenceItem> & { authorityClass: EvidenceItem['authorityClass'] }): EvidenceItem {
  return {
    evidenceId: 'e',
    sourceType: 'PUBLISHED_PLAYBOOK_RULE',
    sourceArtifactId: null,
    sourceCandidateId: null,
    sourceContractVersionId: null,
    sourceClauseId: null,
    legalConcept: 'LIMITATION_OF_LIABILITY',
    text: 'text',
    policyFacts: null,
    contractFacts: [],
    retrievalReason: 'STRUCTURED',
    laneInfo: { lexicalRank: null, vectorRank: null, fusedRank: null, fusedScore: null },
    semanticAssessment: null,
    policyCompatibility: null,
    provenanceNodeIds: [],
    publicationId: null,
    publicationNumber: null,
    ...partial,
  }
}

const items = [
  item({ evidenceId: 'e-p2', authorityClass: 'PRECEDENT', sourceType: 'PUBLISHED_PRECEDENT' }),
  item({ evidenceId: 'e-p1', authorityClass: 'POLICY' }),
  item({ evidenceId: 'e-c1', authorityClass: 'CURRENT_CONTRACT', sourceType: 'CURRENT_CONTRACT_CLAUSE' }),
  item({ evidenceId: 'e-p3', authorityClass: 'PRECEDENT', sourceType: 'PUBLISHED_PRECEDENT' }),
]

describe('evidenceLanesFor', () => {
  it('groups items into the fixed authority order, preserving item order per lane', () => {
    const lanes = evidenceLanesFor(items, 'SUFFICIENT')
    const authorities = lanes.map((entry) => entry.lane.authority)

    expect(authorities).toEqual(['POLICY', 'CURRENT_CONTRACT', 'PRECEDENT'])
    const precedent = lanes.find((entry) => entry.lane.authority === 'PRECEDENT')
    expect(precedent?.items.map((entry) => entry.evidenceId)).toEqual(['e-p2', 'e-p3'])
    expect(precedent?.lane.prominence).toBe('historical')
    expect(precedent?.lane.description).toMatch(/lower authority/i)
  })

  it('keeps policy visible when empty and drafting visible only when missing', () => {
    const empty = evidenceLanesFor([], 'POLICY_MISSING')
    expect(empty.map((entry) => entry.lane.authority)).toEqual([
      'POLICY',
      'CURRENT_CONTRACT',
      'PRECEDENT',
    ])
    expect(empty[0].lane.emptyState).toMatch(/no published policy/i)

    const draftingMissing = evidenceLanesFor([], 'DRAFTING_MISSING')
    expect(draftingMissing.map((entry) => entry.lane.authority)).toContain(
      'APPROVED_DRAFTING',
    )

    const sufficient = evidenceLanesFor([], 'SUFFICIENT')
    expect(sufficient.map((entry) => entry.lane.authority)).not.toContain(
      'APPROVED_DRAFTING',
    )
  })

  it('shows external references and unknown authority items only when present', () => {
    const withExternal = evidenceLanesFor(
      [
        item({ authorityClass: 'EXTERNAL_REFERENCE', sourceType: 'EXTERNAL' }),
        item({ authorityClass: 'UNKNOWN', sourceType: 'FUTURE_SOURCE' }),
      ],
      'PARTIAL',
    )
    const authorities = withExternal.map((entry) => entry.lane.authority)
    expect(authorities).toContain('EXTERNAL_REFERENCE')
    expect(authorities).toContain('UNKNOWN')
    expect(authorities.indexOf('UNKNOWN')).toBe(authorities.length - 1)
    expect(withExternal.at(-1)?.lane.label).toMatch(/other evidence/i)
  })
})

describe('sufficiencyPresentation', () => {
  const states: EvidenceSufficiency[] = [
    'SUFFICIENT',
    'PARTIAL',
    'POLICY_MISSING',
    'AMBIGUOUS_POLICY',
    'DRAFTING_MISSING',
    'PRECEDENT_WEAK',
    'CONTRACT_CONTEXT_INCOMPLETE',
    'SOURCE_CONFLICT',
    'UNKNOWN',
  ]

  it('describes evidence completeness without legal-risk language', () => {
    for (const state of states) {
      const presentation = sufficiencyPresentation(state)
      expect(presentation.label.length).toBeGreaterThan(0)
      expect(presentation.description.length).toBeGreaterThan(0)
      expect(presentation.description).not.toMatch(
        /risk|accept|reject|unsafe|recommend|should|\bapprove\b/i,
      )
    }
    expect(sufficiencyPresentation('POLICY_MISSING').description).toMatch(
      /no published policy/i,
    )
    expect(sufficiencyPresentation('AMBIGUOUS_POLICY').description).toMatch(
      /more than one published policy/i,
    )
    expect(sufficiencyPresentation('FUTURE' as EvidenceSufficiency).label).toMatch(
      /unavailable/i,
    )
  })

  it('states that precedent does not establish policy', () => {
    expect(POLICY_MISSING_PRECEDENT_NOTE).toMatch(/does not establish/i)
  })
})

describe('labels', () => {
  it('maps degradation flags to retrieval-quality notes with a fallback', () => {
    expect(degradationMessage('VECTOR_LANE_UNAVAILABLE')).toMatch(/vector evidence/i)
    expect(degradationMessage('INDEX_INCOMPLETE')).toMatch(/not currently searchable/i)
    expect(degradationMessage('FUTURE_FLAG')).toBe('Future flag')
  })

  it('labels policy compatibility with backend semantics only', () => {
    expect(policyCompatibilityLabel('COMPATIBLE')).toBe(
      'Compatible with current published policy',
    )
    expect(policyCompatibilityLabel('INCOMPATIBLE')).toBe(
      'Not compatible with current published policy',
    )
    expect(policyCompatibilityLabel('UNKNOWN')).toBe('Policy compatibility unknown')
    expect(policyCompatibilityLabel('NOT_ASSESSED')).toBe(
      'Policy compatibility not assessed',
    )
    expect(policyCompatibilityLabel(null)).toBeNull()
    for (const value of ['COMPATIBLE', 'INCOMPATIBLE', 'UNKNOWN', 'NOT_ASSESSED'] as const) {
      expect(policyCompatibilityLabel(value)).not.toMatch(/safe|unsafe|acceptable/i)
    }
  })

  it('labels source types and retrieval reasons, including relationship reasons', () => {
    expect(sourceTypeLabel('PUBLISHED_PLAYBOOK_RULE')).toBe('Playbook rule')
    expect(sourceTypeLabel('PUBLISHED_CLAUSE_VARIANT')).toBe('Clause library variant')
    expect(sourceTypeLabel('PUBLISHED_PRECEDENT')).toBe('Executed precedent')
    expect(sourceTypeLabel('CURRENT_CONTRACT_DEFINED_TERM')).toBe('Defined term')
    expect(sourceTypeLabel('FUTURE_SOURCE')).toBe('Future source')

    expect(retrievalReasonLabel('CANONICAL_STRUCTURE')).toBe('Contract structure')
    expect(retrievalReasonLabel('FUSION')).toBe('Lexical + semantic match')
    expect(retrievalReasonLabel('RESOLVED_REFERENCE')).toMatch(/cross-reference/i)
    expect(retrievalReasonLabel('SIBLING_CONCEPT')).toMatch(/related clause/i)
    expect(retrievalReasonLabel('SEMANTIC_RELATIONSHIP:DEPENDS_ON')).toBe(
      'Semantic relationship: depends on',
    )
    expect(retrievalReasonLabel('FUTURE_REASON')).toBe('Future reason')
  })

  it('labels trace stages and timings with a fallback', () => {
    expect(traceStageLabel('fusion')).toBe('Rank fusion')
    expect(traceStageLabel('rerank')).toBe('Semantic reranking')
    expect(traceStageLabel('policy')).toBe('Policy lookup')
    expect(traceStageLabel('futureStage')).toBe('Future stage')
    expect(traceTimingLabel('pinResolution')).toBe('Knowledge pin resolution')
    expect(traceTimingLabel('currentContract')).toBe('Current contract')
    expect(traceTimingLabel('total')).toBe('Total')
    expect(traceTimingLabel('futureTiming')).toBe('Future timing')
  })
})

describe('traceRequestEntries', () => {
  it('flattens the request echo into labelled scalar entries without raw dumps', () => {
    const entries = traceRequestEntries({
      legalConcept: 'LIMITATION_OF_LIABILITY',
      effectiveOn: '2026-09-24',
      reviewProfile: {
        contractType: 'MSA',
        ourSideRole: 'CUSTOMER',
        paperSource: 'COUNTERPARTY_PAPER',
        jurisdiction: 'UK',
      },
      subjectClauseText: 'x'.repeat(500),
      nested: { provider: { secret: 'nope' } },
    })

    const labels = entries.map((entry) => entry.label)
    expect(labels).toContain('Legal concept')
    expect(labels).toContain('Effective on')
    expect(labels).toContain('Contract type')
    expect(labels).toContain('Our side')
    expect(entries.find((entry) => entry.label === 'Legal concept')?.value).toBe(
      'Limitation of liability',
    )
    expect(entries.find((entry) => entry.label === 'Subject clause text')?.long).toBe(true)
    expect(JSON.stringify(entries)).not.toMatch(/nope/)
  })
})
