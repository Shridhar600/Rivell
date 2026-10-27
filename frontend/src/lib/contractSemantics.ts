import type { EnrichmentFailureReason } from '../api/semantics'
import { VERIFICATION_LABELS, humanizeCode } from './domainLabels'

export { humanizeCode } from './domainLabels'
import type {
  PartyRole,
  ReviewProfile,
  ReviewProfileInput,
  SemanticFact,
} from '../api/semantics'

export const CONTRACT_TYPES = [
  'MNDA',
  'ONDA',
  'NDA',
  'MSA',
  'SERVICES',
  'SUPPLY',
  'SAAS',
  'ORDER_FORM',
  'SOW',
  'DPA',
  'SWL',
  'RESELLER',
  'AMENDMENT',
  'OTHER',
] as const satisfies readonly NonNullable<
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

export const JURISDICTIONS = [
  'US',
  'UK',
  'AUSTRALIA',
  'CANADA',
  'IRELAND',
  'INDIA',
  'NEW_ZEALAND',
  'SINGAPORE',
  'HONG_KONG',
  'GERMANY',
  'FRANCE',
  'NETHERLANDS',
  'OTHER',
  'UNSPECIFIED',
] as const satisfies readonly NonNullable<ReviewProfileInput['jurisdiction']>[]

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
  GENERATIVE_EXTRACTION: 'Read from the clause text',
}

const RELATIONSHIP_TYPE_VERBS: Record<string, string> = {
  DEPENDS_ON: 'depends on',
  MODIFIES: 'modifies',
  CARVES_OUT: 'carves out',
  SURVIVES: 'survives',
}

const QUALITY_NOTE_LABELS: Record<string, string> = {
  PARSER_WARNINGS_PRESENT: 'Some parts of the document could not be read',
  DUPLICATE_DEFINED_TERM: 'A defined term appears more than once',
  AMBIGUOUS_DEFINITIONS: 'Some definitions could not be matched to a single meaning',
  UNRESOLVED_REFERENCES: 'Some cross-references could not be resolved',
  AMBIGUOUS_PARTY_ROLES: 'Some party roles could not be determined confidently',
  UNCLASSIFIED_REGIONS: 'Some contract regions could not be classified',
  NO_PRIMARY_CONCEPT: 'Some classified regions have no primary topic',
  UNVERIFIED_FACTS: 'Some facts could not be verified',
  UNSUPPORTED_FACT_SHAPE: 'Some facts use an unsupported shape',
  PROVIDER_DEGRADATION: 'Some analysis could not be completed automatically',
  BUDGET_EXHAUSTED: 'Analysis stopped at the configured limit',
}

const ANALYSIS_QUALITY_NOTES = new Set([
  'PROVIDER_DEGRADATION',
  'BUDGET_EXHAUSTED',
  'PARSER_WARNINGS_PRESENT',
])

export const CONTRACT_TYPE_LABELS: Record<string, string> = {
  MNDA: 'Mutual NDA',
  ONDA: 'One-way NDA',
  NDA: 'NDA',
  MSA: 'MSA',
  SERVICES: 'Services',
  SUPPLY: 'Supply',
  SAAS: 'SaaS',
  ORDER_FORM: 'Order form',
  SOW: 'SOW',
  DPA: 'DPA',
  SWL: 'Software licence',
  RESELLER: 'Reseller',
  AMENDMENT: 'Amendment',
  OTHER: 'Other',
}

export const PAPER_SOURCE_LABELS: Record<string, string> = {
  OUR_PAPER: 'Our template',
  COUNTERPARTY_PAPER: 'Their template',
  THIRD_PARTY: 'Third-party template',
}

export const JURISDICTION_LABELS: Record<string, string> = {
  US: 'United States',
  UK: 'United Kingdom',
  AUSTRALIA: 'Australia',
  CANADA: 'Canada',
  IRELAND: 'Ireland',
  INDIA: 'India',
  NEW_ZEALAND: 'New Zealand',
  SINGAPORE: 'Singapore',
  HONG_KONG: 'Hong Kong',
  GERMANY: 'Germany',
  FRANCE: 'France',
  NETHERLANDS: 'Netherlands',
  OTHER: 'Another jurisdiction',
  UNSPECIFIED: 'Not specified',
}

function labelFrom(map: Record<string, string>, value: string): string {
  return map[value] ?? humanizeCode(value)
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

/**
 * A clause heading alone, never the sub-clause body a heading label can carry with it.
 *
 * The backend sometimes sends "9. Intellectual Property and Infringement Indemnity. 9.1 The
 * Supplier and its licensors retain..." as the heading. Cut where a sub-clause number starts
 * ("9.1 "). Without one, cut at a sentence end only when the label is too long to be a heading,
 * so "Section 4. Payment" and "U.S. Export Controls" stay whole.
 */
export function clauseHeadingTitle(label: string): string {
  const text = label.replace(/\s+/g, ' ').trim()
  const subClause = /\.\s+(?=\d+(?:\.\d+)+\.?\s)/.exec(text)
  if (subClause && subClause.index > 0) return text.slice(0, subClause.index).trim()
  if (text.length <= HEADING_MAX_LENGTH) return text
  // the first sentence end that leaves at least two words of heading
  const sentence = /\.\s+(?=[A-Z])/g
  for (let match = sentence.exec(text); match; match = sentence.exec(text)) {
    const heading = text.slice(0, match.index).trim()
    if (heading.replace(/^\d+(?:\.\d+)*\.?\s*/, '').split(' ').length >= 2) return heading
  }
  return text
}

/** Longer than this, a label is a heading with its body attached. */
const HEADING_MAX_LENGTH = 100

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
  for (const clause of clauses) labels.set(clause.headingNodeId, clauseHeadingTitle(clause.heading))
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

/**
 * What the tenant is told about a failed enrichment.
 *
 * <p>Only the sentence. There is deliberately no "retryable" or "retry available" flag, because a
 * re-run control is offered for every reason — the previous implementation had a boolean that gated
 * nothing but was named as though it withheld the control, and a second claim attached to it that a
 * retry was worthwhile. Neither was true, and two of the five reasons collapsed into
 * `SERVICE_UNAVAILABLE` can never succeed on a retry, so the frontend cannot honestly assert
 * suitability: the diagnosis that would decide it is exactly what the tenant projection withholds.
 *
 * <p>Whether a retry should be offered at all for this class, and whether the tenant needs a recovery
 * classification of its own, is an open product decision and is escalated rather than answered here.
 * What this surface does is state what happened, without a cause, a timeframe or an outcome.
 */
export type EnrichmentFailureNote = { summary: string }

/**
 * Copy for the tenant-facing enrichment failure taxonomy.
 *
 * The five provider-level reasons this replaces were disclosed to tenants even though none of them
 * is actionable by a tenant, and `NO_PROVIDER_CONFIGURED` in particular described the deployment's
 * credential state. The operator's precise diagnosis stays in server state, where it is queryable
 * and safe.
 *
 * The copy is strictly subtractive: it states what happened and nothing else. `SERVICE_UNAVAILABLE`
 * and `INTERNAL` deliberately share a sentence, because after the narrowing the tenant cannot
 * honestly be told which of the two occurred, and the only difference the wire could show was a
 * claim that no diagnosis had been recorded — which is untrue for both.
 */
const ENRICHMENT_FAILURE_NOTES: Record<
  Exclude<EnrichmentFailureReason, 'UNKNOWN'>,
  EnrichmentFailureNote
> = {
  SERVICE_UNAVAILABLE: {
    summary: 'Rivell could not complete the analysis of this contract version.',
  },
  UNSUPPORTED_SOURCE: {
    summary: 'This document cannot be analysed as it is supplied.',
  },
  INTERNAL: {
    summary: 'Rivell could not complete the analysis of this contract version.',
  },
}

export function enrichmentFailureNote(
  reason: EnrichmentFailureReason | null,
): EnrichmentFailureNote {
  if (reason === null) {
    return {
      summary:
        'Rivell could not complete the analysis of this contract version and did not record a reason.',
    }
  }
  // The record is keyed by the real reasons so a typo is a compile error, mirroring the backend's
  // exhaustive switch. 'UNKNOWN' is the parser's sentinel for an unrecognised wire value rather than
  // a reason, so it is excluded from the key type and falls through to the fallback sentence.
  const known = ENRICHMENT_FAILURE_NOTES[reason as Exclude<EnrichmentFailureReason, 'UNKNOWN'>];
  return (
    known ?? {
      summary:
        'Rivell could not complete the analysis of this contract version and recorded a reason Rivell does not recognise.',
    }
  )
}
