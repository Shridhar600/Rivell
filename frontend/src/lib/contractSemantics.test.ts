import { describe, expect, it } from 'vitest'
import {
  conceptLabel,
  describeFact,
  enrichmentFailureNote,
  factDivergence,
  factEditorFields,
  humanizeCode,
  interpretationKindDescription,
  metadataFieldLabel,
  qualityGroups,
  qualityNoteLabel,
  referenceKindLabel,
  referenceStatusLabel,
  relationshipTypeVerb,
  resolveRegionLabel,
  roleLabel,
  verificationLabel,
} from './contractSemantics'
import { ENRICHMENT_FAILURE_REASONS } from '../api/semantics'
import type { EnrichmentFailureReason, SemanticFact } from '../api/semantics'

describe('label maps', () => {
  it('labels known concepts and humanizes unknown ones', () => {
    expect(conceptLabel('LIMITATION_OF_LIABILITY')).toBe('Limitation of liability')
    expect(conceptLabel('TERM_TERMINATION')).toBe('Term and termination')
    expect(conceptLabel('DATA_PROTECTION')).toBe('Data protection')
  })

  it('labels roles, metadata fields, references and verification', () => {
    expect(roleLabel('DISCLOSING_PARTY')).toBe('Disclosing party')
    expect(roleLabel('PARTY_A')).toBe('Party A')
    expect(metadataFieldLabel('EFFECTIVE_DATE')).toBe('Effective date')
    expect(metadataFieldLabel('COMMENCEMENT_DATE')).toBe('Commencement date')
    expect(referenceKindLabel('SECTION')).toBe('Section')
    expect(referenceStatusLabel('RESOLVED')).toBe('Resolved')
    expect(verificationLabel('VERIFIED')).toBe('Supported by source')
    expect(verificationLabel('NOT_VERIFIED')).toBe('Not independently verified')
  })

  it('describes relationship verbs and interpretation sources', () => {
    expect(relationshipTypeVerb('CARVES_OUT')).toBe('carves out')
    expect(relationshipTypeVerb('SURVIVES')).toBe('survives')
    expect(relationshipTypeVerb('NEW_TYPE')).toBe('new type')
    expect(interpretationKindDescription('DETERMINISTIC')).toBe(
      'Taken directly from the contract text',
    )
    expect(interpretationKindDescription('GENERATIVE_EXTRACTION')).toBe(
      'Extracted from the clause text',
    )
  })

  it('humanizes enum-like values', () => {
    expect(humanizeCode('SOME_FUTURE_CODE')).toBe('Some future code')
    expect(humanizeCode('single')).toBe('Single')
  })
})

describe('quality notes', () => {
  it('labels known notes and humanizes unknown codes', () => {
    expect(qualityNoteLabel('PROVIDER_DEGRADATION')).toBe(
      'Some analysis could not be completed automatically',
    )
    expect(qualityNoteLabel('FUTURE_CODE')).toBe('Future code')
  })

  it('groups notes into analysis gaps and unresolved items', () => {
    const groups = qualityGroups([
      'PROVIDER_DEGRADATION',
      'BUDGET_EXHAUSTED',
      'UNCLASSIFIED_REGIONS',
      'UNRESOLVED_REFERENCES',
      'FUTURE_CODE',
    ])

    expect(groups.analysis).toEqual(['PROVIDER_DEGRADATION', 'BUDGET_EXHAUSTED'])
    expect(groups.unresolved).toEqual([
      'UNCLASSIFIED_REGIONS',
      'UNRESOLVED_REFERENCES',
      'FUTURE_CODE',
    ])
  })
})

describe('fact presentation', () => {
  const liability: SemanticFact = {
    factSchema: 'liability-facts',
    schemaVersion: 1,
    sourceNodeIds: [],
    values: {
      liabilityCapMultiple: '3',
      capBasis: 'PRECEDING_12_MONTH_FEES',
      consequentialDamagesExcluded: 'true',
      dataBreachCarveout: 'true',
    },
  }

  it('renders liability facts as domain rows', () => {
    const rows = describeFact(liability)

    expect(rows).toEqual([
      { field: 'liabilityCapMultiple', label: 'Liability cap', value: '3×' },
      { field: 'capBasis', label: 'Cap basis', value: 'Preceding 12-month fees' },
      { field: 'consequentialDamagesExcluded', label: 'Consequential damages', value: 'Excluded' },
      { field: 'dataBreachCarveout', label: 'Data-breach carve-out', value: 'Present' },
    ])
  })

  it('keeps an unspecified cap basis honest', () => {
    const rows = describeFact({
      ...liability,
      values: { liabilityCapMultiple: '2', capBasis: 'UNSPECIFIED' },
    })

    expect(rows).toEqual([
      { field: 'liabilityCapMultiple', label: 'Liability cap', value: '2×' },
      { field: 'capBasis', label: 'Cap basis', value: 'Not specified' },
    ])
  })

  it('renders termination facts as domain rows', () => {
    const rows = describeFact({
      factSchema: 'termination-facts',
      schemaVersion: 1,
    sourceNodeIds: [],
      values: {
        noticePeriodDays: '60',
        availableTo: 'ANY_PARTY',
        terminationCharge: 'false',
        feesDueOnTermination: 'true',
      },
    })

    expect(rows).toEqual([
      { field: 'noticePeriodDays', label: 'Termination notice', value: '60 days' },
      { field: 'availableTo', label: 'Available to', value: 'Either party' },
      { field: 'terminationCharge', label: 'Termination charge', value: 'Does not apply' },
      { field: 'feesDueOnTermination', label: 'Fees due on termination', value: 'Yes' },
    ])
  })

  it('falls back honestly for unknown schemas and fields', () => {
    const rows = describeFact({
      factSchema: 'privacy-facts',
      schemaVersion: 1,
    sourceNodeIds: [],
      values: { retentionDays: '30' },
    })

    expect(rows).toEqual([
      { field: 'retentionDays', label: 'Retention days', value: '30' },
    ])
  })

  it('offers typed editor fields only for known schemas', () => {
    const liabilityFields = factEditorFields('liability-facts')
    expect(liabilityFields?.map((field) => field.field)).toEqual([
      'liabilityCapMultiple',
      'capBasis',
      'consequentialDamagesExcluded',
      'dataBreachCarveout',
    ])
    expect(liabilityFields?.find((field) => field.field === 'capBasis')?.options).toEqual([
      'PRECEDING_12_MONTH_FEES',
      'ANNUAL_FEES',
      'UNSPECIFIED',
    ])
    expect(factEditorFields('privacy-facts')).toBeNull()
  })
})

describe('machine vs effective facts', () => {
  it('reports diverging fields with both values', () => {
    const divergence = factDivergence(
      [
        {
          factSchema: 'termination-facts',
          schemaVersion: 1,
    sourceNodeIds: [],
          values: { noticePeriodDays: '30', availableTo: 'ANY_PARTY' },
        },
      ],
      [
        {
          factSchema: 'termination-facts',
          schemaVersion: 1,
    sourceNodeIds: [],
          values: { noticePeriodDays: '60', availableTo: 'ANY_PARTY' },
        },
      ],
    )

    expect(divergence).toEqual([
      {
        factSchema: 'termination-facts',
        field: 'noticePeriodDays',
        machineValue: '30',
        effectiveValue: '60',
      },
    ])
  })

  it('returns nothing when the machine values are effective', () => {
    expect(
      factDivergence(
        [
          {
            factSchema: 'liability-facts',
            schemaVersion: 1,
    sourceNodeIds: [],
            values: { liabilityCapMultiple: '3' },
          },
        ],
        [
          {
            factSchema: 'liability-facts',
            schemaVersion: 1,
    sourceNodeIds: [],
            values: { liabilityCapMultiple: '3' },
          },
        ],
      ),
    ).toEqual([])
  })
})

describe('region labels', () => {
  const clauses = [
    { headingNodeId: 'node-6', heading: '6. Limitation of Liability' },
  ]
  const nodes = [
    { id: 'node-9', text: 'Schedule A — Services', numberingLabel: null },
    { id: 'node-5', text: '5. Termination', numberingLabel: null },
  ]

  it('prefers the clause heading, then the structure node text', () => {
    expect(resolveRegionLabel('node-6', clauses, nodes)).toBe(
      '6. Limitation of Liability',
    )
    expect(resolveRegionLabel('node-9', clauses, nodes)).toBe('Schedule A — Services')
  })

  it('returns null for unknown regions', () => {
    expect(resolveRegionLabel('missing', clauses, nodes)).toBeNull()
  })
})

describe('enrichment failure reasons', () => {
  // The tenant-facing taxonomy. The provider-level reasons the backend used to send are absent on
  // purpose: see the DW-041 note on ENRICHMENT_FAILURE_REASONS.
  const codes: EnrichmentFailureReason[] = ['SERVICE_UNAVAILABLE', 'UNSUPPORTED_SOURCE', 'INTERNAL']

  const providerReasons = [
    'NO_PROVIDER_CONFIGURED',
    'PROVIDER_UNAVAILABLE',
    'PROVIDER_TIMEOUT',
    'PROVIDER_REJECTED',
    'PROVIDER_PROTOCOL',
  ]

  it('accepts only the tenant-facing taxonomy', () => {
    expect([...ENRICHMENT_FAILURE_REASONS].sort()).toEqual([...codes].sort())
    for (const provider of providerReasons) {
      expect(ENRICHMENT_FAILURE_REASONS as readonly string[]).not.toContain(provider)
    }
  })

  it('does not blame the contract for a reason the server cannot attribute', () => {
    for (const code of ['SERVICE_UNAVAILABLE', 'INTERNAL'] as const) {
      expect(enrichmentFailureNote(code).summary).not.toMatch(
        /this document|this contract version cannot|cannot be analysed as it is supplied/i,
      )
    }
  })

  it('gives every code a distinct, non-technical sentence', () => {
    const summaries = codes.map((code) => enrichmentFailureNote(code).summary)

    expect(new Set(summaries).size).toBe(codes.length)
    for (const summary of summaries) {
      expect(summary.length).toBeGreaterThan(20)
      expect(summary).not.toMatch(/Exception|com\.rivell|api key|credential|token|secret/i)
    }
  })

  it('never puts provider or configuration vocabulary in front of a tenant', () => {
    for (const code of [...ENRICHMENT_FAILURE_REASONS, 'UNKNOWN'] as EnrichmentFailureReason[]) {
      const { summary } = enrichmentFailureNote(code)
      expect(summary).not.toMatch(
        /provider|credential|api key|quota|not set up|configured|timeout|timed out/i,
      )
    }
  })

  it('renders nothing from a provider reason that arrives anyway', () => {
    // A stale response, a rolled-back backend or a proxied older deployment can still put one of
    // these on the wire. It must degrade to the unrecognised-reason sentence, never to itself.
    for (const provider of providerReasons) {
      const note = enrichmentFailureNote(provider as EnrichmentFailureReason)

      expect(note.summary).not.toContain(provider)
      expect(note.summary).not.toMatch(/provider|credential|quota|configured/i)
      expect(note.summary).toMatch(/does not recognise/i)
    }
  })

  it('leaves a retry available for the reason that can clear on its own', () => {
    expect(codes.filter((code) => enrichmentFailureNote(code).retryable)).toEqual([
      'SERVICE_UNAVAILABLE',
    ])
  })

  it('states an unrecognised code instead of hiding it, without assuming a retry is futile', () => {
    const note = enrichmentFailureNote('UNKNOWN')

    expect(note.summary).toMatch(/does not recognise/i)
    expect(note.retryable).toBe(true)
  })

  it('keeps the retry available but admits the missing reason when none was recorded', () => {
    const note = enrichmentFailureNote(null)

    expect(note.summary).toMatch(/did not record a reason/i)
    expect(note.retryable).toBe(true)
  })
})
