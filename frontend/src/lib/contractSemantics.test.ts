import { describe, expect, it } from 'vitest'
import {
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
  it('labels roles, metadata fields, references and verification', () => {
    expect(roleLabel('DISCLOSING_PARTY')).toBe('Disclosing party')
    expect(roleLabel('PARTY_A')).toBe('Party A')
    expect(metadataFieldLabel('EFFECTIVE_DATE')).toBe('Effective date')
    expect(metadataFieldLabel('COMMENCEMENT_DATE')).toBe('Commencement date')
    expect(referenceKindLabel('SECTION')).toBe('Section')
    expect(referenceStatusLabel('RESOLVED')).toBe('Resolved')
    expect(verificationLabel('VERIFIED')).toBe('Matches the source')
    expect(verificationLabel('NOT_VERIFIED')).toBe('Not checked against a second source')
  })

  it('describes relationship verbs and interpretation sources', () => {
    expect(relationshipTypeVerb('CARVES_OUT')).toBe('carves out')
    expect(relationshipTypeVerb('SURVIVES')).toBe('survives')
    expect(relationshipTypeVerb('NEW_TYPE')).toBe('new type')
    expect(interpretationKindDescription('DETERMINISTIC')).toBe(
      'Taken directly from the contract text',
    )
    expect(interpretationKindDescription('GENERATIVE_EXTRACTION')).toBe(
      'Read from the clause text',
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

  it('gives every code a non-technical sentence, distinguishing them only where it can be truthful', () => {
    const summaries = codes.map((code) => enrichmentFailureNote(code).summary)

    for (const summary of summaries) {
      expect(summary.length).toBeGreaterThan(20)
      expect(summary).not.toMatch(/Exception|com\.rivell|api key|credential|token|secret/i)
    }
    // The document-attributable reason is separable from the two service-side ones, because that
    // difference is real and the tenant can act on it.
    expect(new Set(summaries).size).toBeGreaterThanOrEqual(2)
    expect(enrichmentFailureNote('UNSUPPORTED_SOURCE').summary).not.toBe(
      enrichmentFailureNote('INTERNAL').summary,
    )
    // SERVICE_UNAVAILABLE and INTERNAL deliberately share a sentence. After DW-041 the tenant cannot
    // honestly be told which of the two occurred, and distinct wording here would assert a
    // distinction this surface does not have. There is no second signal to fall back on either: the
    // note carries a sentence and nothing else, because the boolean this view model used to carry
    // gated no control and named a claim the surface cannot support.
    expect(enrichmentFailureNote('SERVICE_UNAVAILABLE').summary).toBe(
      enrichmentFailureNote('INTERNAL').summary,
    )
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
    // Defence in depth at the note layer. A stale response, a rolled-back backend or a proxied older
    // deployment could put one of these on the wire, and this function must still degrade rather
    // than render it.
    //
    // This is NOT the mechanism that actually protects the UI today: the API parser closes the
    // vocabulary first and rewrites anything unrecognised to 'UNKNOWN', so this function is never
    // reached with a provider reason. The reachable path is covered by
    // `semantics.test.ts`, which drives the parser with a provider value. What this test guards is
    // that the note layer stays safe if the parser's behaviour ever changes.
    for (const provider of providerReasons) {
      const note = enrichmentFailureNote(provider as EnrichmentFailureReason)

      expect(note.summary).not.toContain(provider)
      expect(note.summary).not.toMatch(/provider|credential|quota|configured/i)
      expect(note.summary).toMatch(/does not recognise/i)
    }
  })

  it('states an unrecognised code instead of hiding it', () => {
    expect(enrichmentFailureNote('UNKNOWN').summary).toMatch(/does not recognise/i)
  })

  it('admits the missing reason when none was recorded', () => {
    expect(enrichmentFailureNote(null).summary).toMatch(/did not record a reason/i)
  })

  /**
   * M10A.1 corrective (DW-041): the narrowing collapsed five provider reasons into
   * SERVICE_UNAVAILABLE, two of which can never succeed on a retry — the deployment has no usable
   * provider, or the provider refused on auth/quota. The copy used to tell the tenant to "try again
   * in a moment", which asserts a transient, time-bounded condition this surface cannot know about,
   * because the diagnosis that would decide it is exactly what was redacted.
   *
   * So the copy may state what happened and nothing else: no cause, no timeframe, no outcome, and
   * no instruction that presumes a retry is the remedy. This asserts the absence of those claims for
   * every tenant-facing code rather than for the one code that happens to occur today, because a
   * promise added to the next code would be just as false.
   */
  it('promises no cause, no timeframe and no outcome for any tenant-facing reason', () => {
    const forbiddenClaim =
      /in a moment|just now|shortly|later|again|retry|eventually|will (?:succeed|work|resolve)|should (?:succeed|work)|temporary|transient/i

    for (const code of [...ENRICHMENT_FAILURE_REASONS, 'UNKNOWN', null] as (
      | EnrichmentFailureReason
      | null
    )[]) {
      const { summary } = enrichmentFailureNote(code as EnrichmentFailureReason)
      expect(summary).not.toMatch(forbiddenClaim)
    }
  })

  /**
   * The view model must carry no claim about recoverability at all.
   *
   * <p>It previously carried two. The first, `retryable`, asserted that a retry was appropriate,
   * which this surface cannot know once the diagnosis is redacted. Replacing it with
   * `retryAvailable` looked like a fix and was not: the re-run control is rendered unconditionally,
   * so the boolean selected a sentence and withheld nothing, while the name and the accompanying
   * comment claimed it withheld the control. A test asserting the field's shape is what makes that
   * class of fiction visible — the field is now gone, and this fails if any claim-shaped field
   * returns.
   */
  it('carries a sentence and no claim about whether a retry is appropriate or available', () => {
    for (const code of [...ENRICHMENT_FAILURE_REASONS, 'UNKNOWN', null] as (
      | EnrichmentFailureReason
      | null
    )[]) {
      const note = enrichmentFailureNote(code as EnrichmentFailureReason)
      expect(Object.keys(note)).toEqual(['summary'])
      expect(Object.keys(note).join()).not.toMatch(/retry|recover|available|worthwhile/i)
    }
  })
})
