import { describe, expect, it } from 'vitest'
import type { EvidenceItem, EvidenceSufficiency } from '../api/retrieval'
import {
  POLICY_MISSING_PRECEDENT_NOTE,
  applicabilityDimensions,
  applicabilityNote,
  authorityLabel,
  contractPackLabel,
  degradationMessage,
  evidenceLanesFor,
  evidenceTextCollapsible,
  laneEmptyState,
  policyPositionLabels,
  policyCompatibilityLabel,
  retrievalReasonLabel,
  sourceTypeLabel,
  staleClassificationNote,
  sufficiencyPresentation,
  traceRequestEntries,
  traceStageLabel,
  traceTimingLabel,
} from './evidence'
import { humanizeCode } from './domainLabels'

function item(partial: Partial<EvidenceItem> & { authorityClass: EvidenceItem['authorityClass'] }): EvidenceItem {
  return {
    evidenceId: 'e',
    applicabilityScope: null,
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
    expect(precedent?.lane.description).toMatch(/less weight/i)
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

describe('authority and positions helpers', () => {
  it('labels authority classes and orders policy positions canonically', () => {
    expect(authorityLabel('POLICY')).toBe('Policy')
    expect(authorityLabel('PRECEDENT')).toBe('Precedent')
    expect(authorityLabel('UNKNOWN')).toBe('Other evidence')
    expect(policyPositionLabels(['FALLBACK', 'PREFERRED'])).toEqual([
      'Preferred',
      'Fallback',
    ])
    expect(policyPositionLabels(['UNKNOWN_ROLE', 'PREFERRED'])).toEqual([
      'Preferred',
      'Unknown role',
    ])
  })

  it('collapses precedent and long contract text, keeps authoritative wording visible', () => {
    expect(evidenceTextCollapsible(item({ authorityClass: 'PRECEDENT', text: 'short' }))).toBe(
      true,
    )
    expect(
      evidenceTextCollapsible(item({ authorityClass: 'CURRENT_CONTRACT', text: 'short' })),
    ).toBe(false)
    expect(
      evidenceTextCollapsible(
        item({ authorityClass: 'CURRENT_CONTRACT', text: 'x'.repeat(400) }),
      ),
    ).toBe(true)
    expect(evidenceTextCollapsible(item({ authorityClass: 'POLICY', text: 'short' }))).toBe(
      false,
    )
    expect(
      evidenceTextCollapsible(item({ authorityClass: 'POLICY', text: 'x'.repeat(700) })),
    ).toBe(true)
  })
})

describe('applicability scope presentation', () => {
  it('renders only populated dimensions with product labels', () => {
    const scope = {
      contractPackKey: 'MSA_PACK',
      declaredContractTypes: ['MSA'],
      partyPerspective: 'CUSTOMER',
      paperSource: 'THIRD_PARTY',
      jurisdiction: 'US',
      effectiveFrom: '2026-01-01',
      effectiveTo: '2026-12-31',
      sourceContractTypes: [],
    }

    expect(
      applicabilityDimensions(scope).map((d) => `${d.label}: ${d.value}`),
    ).toEqual([
      'Contract types: MSA',
      'Review pack: MSA pack',
      'Perspective: Customer',
      'Whose template: Third-party template',
      'Jurisdiction: United States',
      'Effective period: 2026-01-01 – 2026-12-31',
    ])
    expect(applicabilityNote(scope)).toBeNull()
  })

  it('omits unconstrained dimensions and states the wildcard semantics', () => {
    const scope = {
      contractPackKey: 'MSA_PACK',
      declaredContractTypes: [],
      partyPerspective: null,
      paperSource: null,
      jurisdiction: null,
      effectiveFrom: null,
      effectiveTo: null,
      sourceContractTypes: [],
    }

    expect(applicabilityDimensions(scope)).toEqual([
      { key: 'pack', label: 'Review pack', value: 'MSA pack' },
    ])
    expect(applicabilityNote(scope)).toBe(
      'Fields not listed do not limit this source.',
    )

    const empty = { ...scope, contractPackKey: null }
    expect(applicabilityDimensions(empty)).toEqual([])
    expect(applicabilityNote(empty)).toBeNull()
  })

  it('keeps unknown codes honest and handles single effective bounds', () => {
    expect(contractPackLabel('NDA_PACK')).toBe('NDA pack')
    expect(contractPackLabel('CUSTOMER_MSA')).toBe('CUSTOMER_MSA')

    const partial = {
      contractPackKey: null,
      declaredContractTypes: ['SERVICES_AGREEMENT'],
      partyPerspective: 'LICENSOR',
      paperSource: null,
      jurisdiction: 'DE',
      effectiveFrom: null,
      effectiveTo: '2025-06-30',
      sourceContractTypes: [],
    }
    expect(applicabilityDimensions(partial)).toEqual([
      { key: 'contractTypes', label: 'Contract types', value: 'Services agreement' },
      { key: 'perspective', label: 'Perspective', value: 'Licensor' },
      { key: 'jurisdiction', label: 'Jurisdiction', value: 'DE' },
      { key: 'effective', label: 'Effective until', value: '2025-06-30' },
    ])
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

  it('qualifies empty-lane copy under degraded or unknown states', () => {
    const policyLane = evidenceLanesFor([], 'POLICY_MISSING')[0].lane
    expect(laneEmptyState(policyLane, 'POLICY_MISSING')).toMatch(/no published policy/i)
    expect(laneEmptyState(policyLane, 'PARTIAL')).toMatch(/was not resolved/i)
    expect(laneEmptyState(policyLane, 'UNKNOWN')).toMatch(/was not resolved/i)

    const precedentLane = evidenceLanesFor([], 'PARTIAL').find(
      (group) => group.lane.authority === 'PRECEDENT',
    )!.lane
    expect(laneEmptyState(precedentLane, 'PARTIAL')).toMatch(/incomplete/i)
    expect(laneEmptyState(precedentLane, 'PRECEDENT_WEAK')).toMatch(
      /no comparable precedent/i,
    )
  })

  it('states that precedent does not establish policy', () => {
    expect(POLICY_MISSING_PRECEDENT_NOTE).toMatch(/does not establish/i)
  })

  it('describes a changed classification after the build', () => {
    expect(staleClassificationNote(null, 'TERM', humanizeCode)).toBeNull()
    expect(staleClassificationNote('TERM', 'TERM', humanizeCode)).toBeNull()
    expect(staleClassificationNote('TERM', 'CONFIDENTIALITY_OBLIGATIONS', humanizeCode)).toBe(
      'This source was compared as Term. The contract is now classified as Confidentiality obligations. Rebuild to update.',
    )
    expect(staleClassificationNote('TERM', null, humanizeCode)).toMatch(/classified as not set/)
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
    expect(sourceTypeLabel('PUBLISHED_PRECEDENT')).toBe('Signed precedent')
    expect(sourceTypeLabel('CURRENT_CONTRACT_DEFINED_TERM')).toBe('Defined term')
    expect(sourceTypeLabel('FUTURE_SOURCE')).toBe('Future source')

    expect(retrievalReasonLabel('CANONICAL_STRUCTURE')).toBe('Contract structure')
    expect(retrievalReasonLabel('FUSION')).toBe('Lexical + similarity match')
    expect(retrievalReasonLabel('RESOLVED_REFERENCE')).toMatch(/cross-reference/i)
    expect(retrievalReasonLabel('SIBLING_CONCEPT')).toMatch(/related clause/i)
    expect(retrievalReasonLabel('SEMANTIC_RELATIONSHIP:DEPENDS_ON')).toBe(
      'Related clause: depends on',
    )
    expect(retrievalReasonLabel('FUTURE_REASON')).toBe('Future reason')
  })

  it('labels trace stages and timings with a fallback', () => {
    expect(traceStageLabel('fusion')).toBe('Rank fusion')
    expect(traceStageLabel('rerank')).toBe('Similarity ranking')
    expect(traceStageLabel('policy')).toBe('Policy lookup')
    expect(traceStageLabel('currentContract')).toBe('Current contract')
    expect(traceStageLabel('futureStage')).toBe('Future stage')
    expect(traceTimingLabel('pinResolution')).toBe('Knowledge version lookup')
    expect(traceTimingLabel('currentContract')).toBe('Current contract')
    expect(traceTimingLabel('totalMs')).toBe('Total')
    expect(traceTimingLabel('futureTiming')).toBe('Future timing')
  })
})

describe('traceRequestEntries', () => {
  it('flattens the request echo into labelled scalar entries without raw dumps', () => {
    const entries = traceRequestEntries({
      purpose: 'CLAUSE_REVIEW',
      legalConcept: 'LIMITATION_OF_LIABILITY',
      effectiveOn: '2026-09-24',
      knowledgePin: 2,
      resolvedAsOfPublication: 2,
      reviewProfile: {
        contractType: 'MSA',
        ourSideRole: 'CUSTOMER',
        paperSource: 'COUNTERPARTY_PAPER',
        jurisdiction: 'UK',
      },
      clauseText: 'x'.repeat(500),
      providerPayload: { apiKey: 'nope2' },
      nested: { provider: { secret: 'nope' } },
    }, humanizeCode)

    const labels = entries.map((entry) => entry.label)
    expect(labels).toContain('Topic')
    expect(labels).toContain('Effective on')
    expect(labels).toContain('Contract type')
    expect(labels).toContain('Party you represent')
    expect(labels).toContain('Knowledge version')
    expect(labels).toContain('Knowledge version used')
    expect(entries.find((entry) => entry.label === 'Topic')?.value).toBe(
      'Limitation of liability',
    )
    expect(entries.find((entry) => entry.label === 'Purpose')?.value).toBe('Clause review')
    expect(entries.find((entry) => entry.label === 'Party you represent')?.value).toBe('Customer')
    expect(entries.find((entry) => entry.label === 'Whose template')?.value).toBe(
      'Their template',
    )
    expect(entries.find((entry) => entry.label === 'Subject clause text')?.long).toBe(true)
    expect(JSON.stringify(entries)).not.toMatch(/nope/)
  })
})
