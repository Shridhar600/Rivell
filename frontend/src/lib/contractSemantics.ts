import type { components } from '../api/schema'
import type { EnrichmentFailureReason } from '../api/semantics'
import {
  CONCEPT_LABELS,
  VERIFICATION_LABELS,
  humanizeCode,
} from './domainLabels'

export { humanizeCode } from './domainLabels'
import type {
  PartyRole,
  ReviewProfile,
  ReviewProfileInput,
  SemanticFact,
} from '../api/semantics'

export const TAXONOMY_CONCEPTS = [
  'CONFIDENTIALITY',
  'TERM_TERMINATION',
  'LIMITATION_OF_LIABILITY',
  'INDEMNIFICATION',
  'GOVERNING_LAW',
] as const satisfies readonly NonNullable<
  components['schemas']['ConceptView']['concept']
>[]

export const CONTRACT_TYPES = ['NDA', 'MSA', 'OTHER'] as const satisfies readonly NonNullable<
  ReviewProfileInput['contractType']
>[]

export const PARTY_ROLES = [
  'CUSTOMER',
  'SUPPLIER',
  'DISCLOSING_PARTY',
  'RECEIVING_PARTY',
  'LICENSOR',
  'LICENSEE',
  'PARTY_A',
  'PARTY_B',
] as const satisfies readonly PartyRole[]

export const PAPER_SOURCES = [
  'OUR_PAPER',
  'COUNTERPARTY_PAPER',
  'THIRD_PARTY',
] as const satisfies readonly NonNullable<ReviewProfileInput['paperSource']>[]

export const JURISDICTIONS = ['US', 'UK', 'UNSPECIFIED'] as const satisfies readonly NonNullable<
  ReviewProfileInput['jurisdiction']
>[]

const ROLE_LABELS: Record<string, string> = {
  CUSTOMER: 'Customer',
  SUPPLIER: 'Supplier',
  DISCLOSING_PARTY: 'Disclosing party',
  RECEIVING_PARTY: 'Receiving party',
  LICENSOR: 'Licensor',
  LICENSEE: 'Licensee',
  PARTY_A: 'Party A',
  PARTY_B: 'Party B',
}

const METADATA_FIELD_LABELS: Record<string, string> = {
  AGREEMENT_TITLE: 'Agreement title',
  CONTRACT_TYPE: 'Contract type',
  EFFECTIVE_DATE: 'Effective date',
  GOVERNING_LAW: 'Governing law',
  TERM: 'Term',
}

const REFERENCE_KIND_LABELS: Record<string, string> = {
  SECTION: 'Section',
  SCHEDULE: 'Schedule',
  EXHIBIT: 'Exhibit',
  ANNEX: 'Annex',
  EXTERNAL: 'External document',
}

const REFERENCE_STATUS_LABELS: Record<string, string> = {
  RESOLVED: 'Resolved',
  AMBIGUOUS: 'Ambiguous',
  BROKEN: 'Broken',
  EXTERNAL: 'Outside this document',
}

const INTERPRETATION_KIND_DESCRIPTIONS: Record<string, string> = {
  DETERMINISTIC: 'Taken directly from the contract text',
  BOUNDED_CLASSIFICATION: 'Classified from the clause text',
  GENERATIVE_EXTRACTION: 'Extracted from the clause text',
}

const RELATIONSHIP_TYPE_VERBS: Record<string, string> = {
  DEPENDS_ON: 'depends on',
  MODIFIES: 'modifies',
  CARVES_OUT: 'carves out',
  SURVIVES: 'survives',
}

const QUALITY_NOTE_LABELS: Record<string, string> = {
  PARSER_WARNINGS_PRESENT: 'The document parser reported warnings',
  DUPLICATE_DEFINED_TERM: 'A defined term appears more than once',
  AMBIGUOUS_DEFINITIONS: 'Some definitions could not be matched to a single meaning',
  UNRESOLVED_REFERENCES: 'Some cross-references could not be resolved',
  AMBIGUOUS_PARTY_ROLES: 'Some party roles could not be determined confidently',
  UNCLASSIFIED_REGIONS: 'Some contract regions could not be classified',
  NO_PRIMARY_CONCEPT: 'Some classified regions have no primary concept',
  UNVERIFIED_FACTS: 'Some extracted facts could not be verified',
  UNSUPPORTED_FACT_SHAPE: 'Some extracted facts use an unsupported shape',
  PROVIDER_DEGRADATION: 'Some analysis could not be completed automatically',
  BUDGET_EXHAUSTED: 'Analysis stopped at the configured limit',
}

const ANALYSIS_QUALITY_NOTES = new Set([
  'PROVIDER_DEGRADATION',
  'BUDGET_EXHAUSTED',
  'PARSER_WARNINGS_PRESENT',
])

export const CONTRACT_TYPE_LABELS: Record<string, string> = {
  NDA: 'NDA',
  MSA: 'MSA',
  OTHER: 'Other',
}

export const PAPER_SOURCE_LABELS: Record<string, string> = {
  OUR_PAPER: 'Our paper',
  COUNTERPARTY_PAPER: 'Counterparty paper',
  THIRD_PARTY: 'Third-party paper',
}

export const JURISDICTION_LABELS: Record<string, string> = {
  US: 'United States',
  UK: 'United Kingdom',
  UNSPECIFIED: 'Not specified',
}

function labelFrom(map: Record<string, string>, value: string): string {
  return map[value] ?? humanizeCode(value)
}

export function conceptLabel(value: string): string {
  return labelFrom(CONCEPT_LABELS, value)
}

export function roleLabel(value: string): string {
  return labelFrom(ROLE_LABELS, value)
}

export function metadataFieldLabel(value: string): string {
  return labelFrom(METADATA_FIELD_LABELS, value)
}

export function referenceKindLabel(value: string): string {
  return labelFrom(REFERENCE_KIND_LABELS, value)
}

export function referenceStatusLabel(value: string): string {
  return labelFrom(REFERENCE_STATUS_LABELS, value)
}

export function verificationLabel(value: string): string {
  return labelFrom(VERIFICATION_LABELS, value)
}

export function interpretationKindDescription(value: string): string {
  return INTERPRETATION_KIND_DESCRIPTIONS[value] ?? humanizeCode(value)
}

export function relationshipTypeVerb(value: string): string {
  return RELATIONSHIP_TYPE_VERBS[value] ?? humanizeCode(value).toLowerCase()
}

export function qualityNoteLabel(value: string): string {
  return labelFrom(QUALITY_NOTE_LABELS, value)
}

export function qualityGroups(notes: string[]): {
  analysis: string[]
  unresolved: string[]
} {
  const analysis: string[] = []
  const unresolved: string[] = []
  for (const note of notes) {
    if (ANALYSIS_QUALITY_NOTES.has(note)) analysis.push(note)
    else unresolved.push(note)
  }
  return { analysis, unresolved }
}

export type FactRow = { field: string; label: string; value: string }

const CAP_BASIS_LABELS: Record<string, string> = {
  PRECEDING_12_MONTH_FEES: 'Preceding 12-month fees',
  ANNUAL_FEES: 'Annual fees',
}

const AVAILABLE_TO_LABELS: Record<string, string> = {
  ANY_PARTY: 'Either party',
  CUSTOMER: 'Customer',
  SUPPLIER: 'Supplier',
  UNSPECIFIED: 'Not specified',
}

function booleanLabel(value: string, whenTrue: string, whenFalse: string): string {
  return value === 'true' ? whenTrue : whenFalse
}

function formatMultiple(value: string): string {
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return `${number}×`
}

function humanizeField(field: string): string {
  const spaced = field
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .toLowerCase()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

export function describeFact(fact: SemanticFact): FactRow[] {
  const { values } = fact
  if (fact.factSchema === 'liability-facts') {
    const rows: FactRow[] = []
    const multiple = values.liabilityCapMultiple
    const basis = values.capBasis
    if (multiple !== undefined) {
      rows.push({
        field: 'liabilityCapMultiple',
        label: 'Liability cap',
        value: formatMultiple(multiple),
      })
    }
    if (basis !== undefined) {
      rows.push({
        field: 'capBasis',
        label: 'Cap basis',
        value:
          CAP_BASIS_LABELS[basis] ??
          (basis === 'UNSPECIFIED' ? 'Not specified' : humanizeCode(basis)),
      })
    }
    if (values.consequentialDamagesExcluded !== undefined) {
      rows.push({
        field: 'consequentialDamagesExcluded',
        label: 'Consequential damages',
        value: booleanLabel(
          values.consequentialDamagesExcluded,
          'Excluded',
          'Not excluded',
        ),
      })
    }
    if (values.dataBreachCarveout !== undefined) {
      rows.push({
        field: 'dataBreachCarveout',
        label: 'Data-breach carve-out',
        value: booleanLabel(values.dataBreachCarveout, 'Present', 'Not present'),
      })
    }
    return rows
  }

  if (fact.factSchema === 'termination-facts') {
    const rows: FactRow[] = []
    if (values.noticePeriodDays !== undefined) {
      const days = Number(values.noticePeriodDays)
      rows.push({
        field: 'noticePeriodDays',
        label: 'Termination notice',
        value: Number.isFinite(days)
          ? `${days} day${days === 1 ? '' : 's'}`
          : values.noticePeriodDays,
      })
    }
    if (values.availableTo !== undefined) {
      rows.push({
        field: 'availableTo',
        label: 'Available to',
        value:
          AVAILABLE_TO_LABELS[values.availableTo] ?? humanizeCode(values.availableTo),
      })
    }
    if (values.terminationCharge !== undefined) {
      rows.push({
        field: 'terminationCharge',
        label: 'Termination charge',
        value: booleanLabel(values.terminationCharge, 'Applies', 'Does not apply'),
      })
    }
    if (values.feesDueOnTermination !== undefined) {
      rows.push({
        field: 'feesDueOnTermination',
        label: 'Fees due on termination',
        value: booleanLabel(values.feesDueOnTermination, 'Yes', 'No'),
      })
    }
    return rows
  }

  return Object.entries(values).map(([field, value]) => ({
    field,
    label: humanizeField(field),
    value,
  }))
}

export type FactEditorField = {
  field: string
  label: string
  kind: 'decimal' | 'integer' | 'boolean' | 'enum'
  options?: string[]
  optionLabels?: Record<string, string>
  booleanLabels?: { whenTrue: string; whenFalse: string }
}

const CAP_BASIS_OPTION_LABELS: Record<string, string> = {
  PRECEDING_12_MONTH_FEES: 'Preceding 12-month fees',
  ANNUAL_FEES: 'Annual fees',
  UNSPECIFIED: 'Not specified',
}

const AVAILABLE_TO_OPTION_LABELS: Record<string, string> = {
  ANY_PARTY: 'Either party',
  CUSTOMER: 'Customer',
  SUPPLIER: 'Supplier',
  UNSPECIFIED: 'Not specified',
}

const FACT_SCHEMA_LABELS: Record<string, string> = {
  'liability-facts': 'Liability facts',
  'termination-facts': 'Termination facts',
}

export function factSchemaLabel(factSchema: string): string {
  return FACT_SCHEMA_LABELS[factSchema] ?? humanizeCode(factSchema)
}

const FACT_EDITOR_FIELDS: Record<string, FactEditorField[]> = {
  'liability-facts': [
    { field: 'liabilityCapMultiple', label: 'Liability cap multiple', kind: 'decimal' },
    {
      field: 'capBasis',
      label: 'Cap basis',
      kind: 'enum',
      options: ['PRECEDING_12_MONTH_FEES', 'ANNUAL_FEES', 'UNSPECIFIED'],
      optionLabels: CAP_BASIS_OPTION_LABELS,
    },
    {
      field: 'consequentialDamagesExcluded',
      label: 'Consequential damages',
      kind: 'boolean',
      booleanLabels: { whenTrue: 'Excluded', whenFalse: 'Not excluded' },
    },
    {
      field: 'dataBreachCarveout',
      label: 'Data-breach carve-out',
      kind: 'boolean',
      booleanLabels: { whenTrue: 'Present', whenFalse: 'Not present' },
    },
  ],
  'termination-facts': [
    { field: 'noticePeriodDays', label: 'Notice period (days)', kind: 'integer' },
    {
      field: 'availableTo',
      label: 'Available to',
      kind: 'enum',
      options: ['ANY_PARTY', 'CUSTOMER', 'SUPPLIER', 'UNSPECIFIED'],
      optionLabels: AVAILABLE_TO_OPTION_LABELS,
    },
    {
      field: 'terminationCharge',
      label: 'Termination charge',
      kind: 'boolean',
      booleanLabels: { whenTrue: 'Applies', whenFalse: 'Does not apply' },
    },
    {
      field: 'feesDueOnTermination',
      label: 'Fees due on termination',
      kind: 'boolean',
      booleanLabels: { whenTrue: 'Yes', whenFalse: 'No' },
    },
  ],
}

export function factEditorFields(factSchema: string): FactEditorField[] | null {
  return FACT_EDITOR_FIELDS[factSchema] ?? null
}

export type FactDivergence = {
  factSchema: string
  field: string
  machineValue: string
  effectiveValue: string
}

export function factDivergence(
  machineFacts: SemanticFact[],
  effectiveFacts: SemanticFact[],
): FactDivergence[] {
  const divergences: FactDivergence[] = []
  for (const effective of effectiveFacts) {
    const machine = machineFacts.find(
      (candidate) => candidate.factSchema === effective.factSchema,
    )
    if (!machine) continue
    for (const [field, effectiveValue] of Object.entries(effective.values)) {
      const machineValue = machine.values[field]
      if (machineValue !== undefined && machineValue !== effectiveValue) {
        divergences.push({
          factSchema: effective.factSchema,
          field,
          machineValue,
          effectiveValue,
        })
      }
    }
  }
  return divergences
}

export type ConceptCarrier = {
  primaryConcept: string | null
  correctedPrimaryConcept: string | null
}

export function effectivePrimaryConcept(clause: ConceptCarrier): string | null {
  return clause.correctedPrimaryConcept ?? clause.primaryConcept
}

export function machinePrimaryConcept(clause: ConceptCarrier): string | null {
  if (clause.correctedPrimaryConcept === null) return null
  if (clause.correctedPrimaryConcept === clause.primaryConcept) return null
  return clause.primaryConcept
}

export type RegionLabelSource = { headingNodeId: string; heading: string }
export type RegionLabelNode = {
  id: string
  text: string | null
  numberingLabel: string | null
}

const REGION_LABEL_MAX_LENGTH = 60

function truncateLabel(label: string): string {
  if (label.length <= REGION_LABEL_MAX_LENGTH) return label
  return `${label.slice(0, REGION_LABEL_MAX_LENGTH - 1).trimEnd()}…`
}

export function buildRegionLabelMap(
  clauses: RegionLabelSource[],
  nodes: RegionLabelNode[],
): Map<string, string> {
  const labels = new Map<string, string>()
  for (const node of nodes) {
    const label =
      node.numberingLabel && node.text
        ? `${node.numberingLabel} ${node.text}`
        : node.numberingLabel ?? node.text
    if (label) labels.set(node.id, truncateLabel(label))
  }
  for (const clause of clauses) labels.set(clause.headingNodeId, clause.heading)
  return labels
}

export function resolveRegionLabel(
  nodeId: string,
  clauses: RegionLabelSource[],
  nodes: RegionLabelNode[],
): string | null {
  return buildRegionLabelMap(clauses, nodes).get(nodeId) ?? null
}

const REGION_KIND_LABELS: Record<string, string> = {
  CLAUSE: 'Clause',
  SCHEDULE: 'Schedule',
  SIGNATURE_BLOCK: 'Signature block',
}

export function regionKindLabel(value: string): string {
  return REGION_KIND_LABELS[value] ?? humanizeCode(value)
}

export function profileKey(profile: ReviewProfile | null): string {
  if (!profile) return 'none'
  return [
    profile.contractType,
    profile.ourSideRole,
    profile.paperSource,
    profile.jurisdiction,
  ].join('|')
}

export type EnrichmentFailureNote = { summary: string; retryable: boolean }

const ENRICHMENT_FAILURE_NOTES: Record<string, EnrichmentFailureNote> = {
  NO_PROVIDER_CONFIGURED: {
    summary: 'Rivell is not set up to analyse contract semantics in this environment right now.',
    retryable: false,
  },
  PROVIDER_UNAVAILABLE: {
    summary: 'The semantic analysis service did not respond, so the analysis did not complete.',
    retryable: true,
  },
  PROVIDER_TIMEOUT: {
    summary: 'The semantic analysis took too long and did not complete.',
    retryable: true,
  },
  PROVIDER_REJECTED: {
    // The backend's cause is auth, quota, or a request it considered invalid, so this must not
    // blame the document. Retrying is left available because a quota or credential problem can
    // clear; the copy does not tell the user which it was.
    summary: 'Rivell could not obtain a semantic analysis for this contract version.',
    retryable: true,
  },
  PROVIDER_PROTOCOL: {
    summary:
      'The semantic analysis service returned a result Rivell could not use, so the analysis did not complete.',
    retryable: true,
  },
  UNSUPPORTED_SOURCE: {
    summary: 'This document cannot be analysed for semantics as it is supplied.',
    retryable: false,
  },
  INTERNAL: {
    summary: 'Rivell could not complete the semantic analysis of this contract version.',
    retryable: false,
  },
}

export function enrichmentFailureNote(
  reason: EnrichmentFailureReason | null,
): EnrichmentFailureNote {
  if (reason === null) {
    return {
      summary:
        'Rivell could not complete the semantic analysis of this contract version and did not record a reason.',
      retryable: true,
    }
  }
  return (
    ENRICHMENT_FAILURE_NOTES[reason] ?? {
      summary:
        'Rivell could not complete the semantic analysis of this contract version and recorded a reason Rivell does not recognise.',
      retryable: false,
    }
  )
}
