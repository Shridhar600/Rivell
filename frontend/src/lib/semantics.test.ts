import { describe, expect, it } from 'vitest'
import type { ArtifactDetail, Candidate } from '../api/knowledge'
import {
  escalationDiverge,
  hasSupersessionProposal,
  interpretationFor,
  isBulkEligible,
  positionsDiverge,
  proposedPositions,
  reviewReasonLabel,
  scopeDifferences,
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
    expect(VERIFICATION_LABELS.VERIFIED).toMatch(/supported by source/i)
    expect(VERIFICATION_LABELS.DISPUTED).toMatch(/disputed/i)
    expect(VERIFICATION_LABELS.NOT_VERIFIED).toMatch(/not independently verified/i)
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

  it('shows bulk eligibility from server fields only', () => {
    expect(isBulkEligible(candidate())).toBe(true)
    expect(isBulkEligible(candidate({ reviewCategory: 'ELEVATED' }))).toBe(false)
    expect(isBulkEligible(candidate({ reviewStatus: 'APPROVED' }))).toBe(false)
    expect(isBulkEligible(candidate({ reviewCategory: null }))).toBe(false)
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
