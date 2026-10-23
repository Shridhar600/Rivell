import { describe, expect, it } from 'vitest'
import type { ArtifactDetail, Candidate } from '../api/knowledge'
import {
  escalationDiverge,
  formatBoolean,
  hasSupersessionProposal,
  interpretationFor,
  isBulkEligible,
  normalizeJurisdiction,
  positionsDiverge,
  proposedPositions,
  reviewReasonLabel,
  scopeDifferences,
  semanticDraftFrom,
  semanticInputFrom,
  variantRoleDiverge,
  VERIFICATION_LABELS,
} from './semantics'

function candidate(partial: Partial<Candidate> = {}): Candidate {
  return {
    id: 'c1',
    artifactId: 'a1',
    extractionUnitId: 'u1',
    candidateType: 'PLAYBOOK_RULE',
    reviewStatus: 'PENDING',
    rawLabel: 'Rule',
    editedTitle: null,
    title: 'Rule',
    contentText: 'Rule content',
    payloadColumns: {},
    legalConceptKey: 'LIMITATION_OF_LIABILITY',
    effectiveConceptKey: 'LIMITATION_OF_LIABILITY',
    conceptCorrection: null,
    classifiedConcepts: { LIMITATION_OF_LIABILITY: 0.91 },
    positions: ['PREFERRED'],
    variantRole: null,
    escalationTrigger: null,
    requiresApproval: false,
    interpretations: [],
    editedContentText: null,
    editedPayloadColumns: {},
    supersedingCandidateId: null,
    contractPackKey: 'MSA',
    declaredContractTypes: ['MSA'],
    sourceContractTypes: [],
    partyPerspective: 'Customer',
    paperSource: 'Third-party',
    jurisdiction: 'US',
    effectiveFrom: '2026-01-01',
    effectiveTo: null,
    conflictSignals: [],
    reviewCategory: 'ROUTINE',
    reviewReasons: [],
    reviewNote: null,
    reviewedAt: null,
    createdAt: '2026-09-23T05:00:00Z',
    updatedAt: '2026-09-23T05:00:00Z',
    ...partial,
  }
}

const artifact: ArtifactDetail = {
  id: 'a1',
  artifactType: 'PLAYBOOK',
  filename: 'playbook.docx',
  documentTitle: null,
  contentType: 'application/octet-stream',
  sizeBytes: 2048,
  sha256: 'a'.repeat(64),
  storageKey: 'org/knowledge/a1.docx',
  declaredContractTypes: ['MSA'],
  contractPackKey: 'MSA',
  partyPerspective: 'Customer',
  paperSource: 'Third-party',
  jurisdiction: 'US',
  language: null,
  effectiveFrom: '2026-01-01',
  effectiveTo: null,
  manifestNote: null,
  parserVersion: 'rivell-docx-parser-2',
  parseWarnings: [],
  ingestStatus: 'EXTRACTED',
  artifactAssessment: null,
  createdAt: '2026-09-23T05:00:00Z',
}

describe('semantic display helpers', () => {
  it('labels review reasons and falls back to the raw code', () => {
    expect(reviewReasonLabel('VERIFICATION_DISPUTED')).toMatch(/not sufficiently supported/i)
    expect(reviewReasonLabel('FUTURE_REASON')).toBe('FUTURE_REASON')
  })

  it('labels verification states', () => {
    expect(VERIFICATION_LABELS.VERIFIED).toMatch(/matches the source/i)
    expect(VERIFICATION_LABELS.DISPUTED).toMatch(/disputed/i)
    expect(VERIFICATION_LABELS.NOT_VERIFIED).toMatch(/not checked against a second source/i)
  })

  it('finds an interpretation by field', () => {
    const withInterpretation = candidate({
      interpretations: [
        {
          field: 'positions',
          proposedValue: 'FALLBACK,PREFERRED',
          kind: 'BOUNDED_CLASSIFICATION',
          specId: 'playbook-position',
          specVersion: 1,
          supportingSourceNodes: 2,
          verification: 'NOT_VERIFIED',
        },
      ],
    })

    expect(interpretationFor(withInterpretation, 'positions')?.proposedValue).toBe(
      'FALLBACK,PREFERRED',
    )
    expect(interpretationFor(withInterpretation, 'scope')).toBeNull()
  })

  it('parses machine position proposals and detects divergence', () => {
    const diverging = candidate({
      positions: ['PREFERRED'],
      interpretations: [
        {
          field: 'positions',
          proposedValue: 'FALLBACK,PREFERRED',
          kind: 'BOUNDED_CLASSIFICATION',
          specId: 'playbook-position',
          specVersion: 1,
          supportingSourceNodes: 2,
          verification: 'NOT_VERIFIED',
        },
      ],
    })

    expect(proposedPositions(diverging)).toEqual(['FALLBACK', 'PREFERRED'])
    expect(positionsDiverge(diverging)).toBe(true)

    const aligned = candidate({
      positions: ['FALLBACK', 'PREFERRED'],
      interpretations: diverging.interpretations,
    })
    expect(positionsDiverge(aligned)).toBe(false)
    expect(positionsDiverge(candidate())).toBe(false)
  })

  it('detects variant-role and escalation divergence', () => {
    const edited = candidate({
      candidateType: 'CLAUSE_VARIANT',
      variantRole: 'FALLBACK',
      escalationTrigger: 'Anything above 2x requires GC approval.',
      interpretations: [
        {
          field: 'variantRole',
          proposedValue: 'PREFERRED',
          kind: 'BOUNDED_CLASSIFICATION',
          specId: 'clause-variant-role',
          specVersion: 1,
          supportingSourceNodes: 1,
          verification: 'NOT_VERIFIED',
        },
        {
          field: 'escalationTrigger',
          proposedValue: 'Above 2x',
          kind: 'GENERATIVE_EXTRACTION',
          specId: 'escalation-trigger-extraction',
          specVersion: 1,
          supportingSourceNodes: 3,
          verification: 'VERIFIED',
        },
      ],
    })

    expect(variantRoleDiverge(edited)).toBe(true)
    expect(escalationDiverge(edited)).toBe(true)
  })

  it('compares effective scope with the artifact manifest', () => {
    const narrowed = candidate({ jurisdiction: 'UK' })
    expect(scopeDifferences(narrowed, artifact)).toEqual([
      { label: 'Jurisdiction', candidateValue: 'UK', manifestValue: 'US' },
    ])
    expect(scopeDifferences(candidate(), artifact)).toEqual([])
  })

  it('compares jurisdictions with the shared normalisation', () => {
    expect(normalizeJurisdiction('United Kingdom')).toBe('uk')
    expect(normalizeJurisdiction('UK')).toBe('uk')
    expect(normalizeJurisdiction('United States')).toBe('us')
    expect(normalizeJurisdiction('US')).toBe('us')
    expect(normalizeJurisdiction('New Zealand')).toBe('newzealand')
    expect(normalizeJurisdiction('NEW_ZEALAND')).toBe('newzealand')

    expect(
      scopeDifferences(candidate({ jurisdiction: 'New Zealand' }), {
        ...artifact,
        jurisdiction: 'NEW_ZEALAND',
      }),
    ).toEqual([])
    expect(
      scopeDifferences(candidate({ jurisdiction: 'United Kingdom' }), {
        ...artifact,
        jurisdiction: 'UK',
      }),
    ).toEqual([])
    expect(
      scopeDifferences(candidate({ jurisdiction: 'United States' }), {
        ...artifact,
        jurisdiction: 'US',
      }),
    ).toEqual([])
    expect(
      scopeDifferences(candidate({ jurisdiction: 'United Kingdom' }), {
        ...artifact,
        jurisdiction: 'US',
      }),
    ).toEqual([{ label: 'Jurisdiction', candidateValue: 'United Kingdom', manifestValue: 'US' }])
    expect(
      scopeDifferences(candidate({ jurisdiction: '' }), { ...artifact, jurisdiction: null }),
    ).toEqual([])
  })

  it('shows bulk eligibility from server fields only', () => {
    expect(isBulkEligible(candidate())).toBe(true)
    expect(isBulkEligible(candidate({ reviewCategory: 'ELEVATED' }))).toBe(false)
    expect(isBulkEligible(candidate({ reviewStatus: 'APPROVED' }))).toBe(false)
    expect(isBulkEligible(candidate({ reviewCategory: null }))).toBe(false)
  })

  it('sends an explicit null when counsel approval is set back to not specified', () => {
    const current = candidate({ requiresApproval: true, positions: ['PREFERRED'], escalationTrigger: 'Above 2x' })
    const draft = semanticDraftFrom(current)
    expect(draft.requiresApproval).toBe('true')

    const unset = semanticInputFrom({ ...draft, requiresApproval: 'unset' }, current)

    expect(unset).toEqual({ positions: ['PREFERRED'], requiresApproval: null, escalationTrigger: 'Above 2x' })
    expect(formatBoolean(null)).toBe('Not specified')
  })

  it('sends no approval field when approval did not change', () => {
    const current = candidate({ requiresApproval: null })
    const input = semanticInputFrom(semanticDraftFrom(current), current)
    expect(input).toBeUndefined()
  })

  it('detects supersession proposals from conflict signals', () => {
    expect(
      hasSupersessionProposal(
        candidate({
          conflictSignals: [
            {
              conflictType: 'SUPERSEDES_EXISTING',
              message: 'Appears intended to replace a published position (p=0.9)',
              publishedCandidateId: 'p1',
            },
          ],
        }),
      ),
    ).toBe(true)
    expect(hasSupersessionProposal(candidate())).toBe(false)
  })
})
