import type {
  ApplicabilityScope,
  AuthorityClass,
  EvidenceItem,
  EvidenceSufficiency,
  PolicyCompatibility,
} from '../api/retrieval'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  relationshipTypeVerb,
  roleLabel,
} from './contractSemantics'
import { humanizeCode } from './domainLabels'
import type { ConceptLabel } from '../api/taxonomy'
import { POSITION_LABELS } from './semantics'

type LaneProminence = 'authoritative' | 'subject' | 'historical' | 'optional'

export type EvidenceLane = {
  authority: AuthorityClass
  label: string
  description: string
  emptyState: string
  prominence: LaneProminence
}

const EVIDENCE_LANES: readonly EvidenceLane[] = [
  {
    authority: 'POLICY',
    label: 'Policy',
    description: 'Published organizational policy resolved for this concept and profile.',
    emptyState: 'No published policy applies to this concept and profile.',
    prominence: 'authoritative',
  },
  {
    authority: 'APPROVED_DRAFTING',
    label: 'Approved drafting',
    description: 'Approved reusable wording from the published clause library.',
    emptyState: 'No approved reusable wording exists for this concept.',
    prominence: 'authoritative',
  },
  {
    authority: 'CURRENT_CONTRACT',
    label: 'Current contract',
    description: 'Evidence from the contract under review.',
    emptyState: 'No contract context is available for this clause.',
    prominence: 'subject',
  },
  {
    authority: 'PRECEDENT',
    label: 'Precedents',
    description:
      'Historical comparable agreements, with lower authority than published policy or approved drafting.',
    emptyState: 'No comparable precedent was found for this concept.',
    prominence: 'historical',
  },
  {
    authority: 'EXTERNAL_REFERENCE',
    label: 'External references',
    description: 'External reference material retrieved for this request.',
    emptyState: 'No external references were used in this retrieval.',
    prominence: 'optional',
  },
]

const AUTHORITY_LABELS: Record<AuthorityClass, string> = {
  POLICY: 'Policy',
  APPROVED_DRAFTING: 'Approved drafting',
  PRECEDENT: 'Precedent',
  CURRENT_CONTRACT: 'Current contract',
  EXTERNAL_REFERENCE: 'External reference',
  UNKNOWN: 'Other evidence',
}

export function authorityLabel(authority: AuthorityClass): string {
  return AUTHORITY_LABELS[authority] ?? AUTHORITY_LABELS.UNKNOWN
}

const OTHER_LANE: EvidenceLane = {
  authority: 'UNKNOWN',
  label: 'Other evidence',
  description: 'Evidence with an unrecognised authority class.',
  emptyState: 'No other evidence was returned.',
  prominence: 'optional',
}

type EvidenceLaneGroup = { lane: EvidenceLane; items: EvidenceItem[] }

function laneAlwaysVisible(lane: EvidenceLane, sufficiency: EvidenceSufficiency): boolean {
  if (
    lane.authority === 'POLICY' ||
    lane.authority === 'CURRENT_CONTRACT' ||
    lane.authority === 'PRECEDENT'
  ) {
    return true
  }
  return lane.authority === 'APPROVED_DRAFTING' && sufficiency === 'DRAFTING_MISSING'
}

export function evidenceLanesFor(
  items: EvidenceItem[],
  sufficiency: EvidenceSufficiency,
): EvidenceLaneGroup[] {
  const groups: EvidenceLaneGroup[] = []
  for (const lane of EVIDENCE_LANES) {
    const laneItems = items.filter((item) => item.authorityClass === lane.authority)
    if (laneItems.length > 0 || laneAlwaysVisible(lane, sufficiency)) {
      groups.push({ lane, items: laneItems })
    }
  }
  const otherItems = items.filter((item) => item.authorityClass === 'UNKNOWN')
  if (otherItems.length > 0) {
    groups.push({ lane: OTHER_LANE, items: otherItems })
  }
  return groups
}

export function policyPositionLabels(positions: string[]): string[] {
  const order = Object.keys(POSITION_LABELS)
  const rank = (position: string) => {
    const index = order.indexOf(position)
    return index === -1 ? order.length : index
  }
  return [...positions]
    .sort((a, b) => rank(a) - rank(b))
    .map((position) => POSITION_LABELS[position as keyof typeof POSITION_LABELS] ?? humanizeCode(position))
}

export function evidenceTextCollapsible(item: EvidenceItem): boolean {
  if (item.authorityClass === 'PRECEDENT') return true
  if (item.authorityClass === 'CURRENT_CONTRACT') return item.text.length > 280
  return item.text.length > 600
}

type SufficiencyPresentation = {
  state: EvidenceSufficiency
  label: string
  description: string
}

const SUFFICIENCY_PRESENTATIONS: Record<
  Exclude<EvidenceSufficiency, 'UNKNOWN'>,
  { label: string; description: string }
> = {
  SUFFICIENT: {
    label: 'Evidence complete',
    description: 'The evidence set is complete for this review.',
  },
  PARTIAL: {
    label: 'Evidence partially complete',
    description: 'Some evidence could not be retrieved. Available evidence is shown.',
  },
  POLICY_MISSING: {
    label: 'Policy evidence missing',
    description: 'No published policy applies to this concept and profile.',
  },
  AMBIGUOUS_POLICY: {
    label: 'Policy evidence ambiguous',
    description: 'More than one published policy applies; none was chosen.',
  },
  DRAFTING_MISSING: {
    label: 'Approved drafting missing',
    description: 'No approved reusable wording exists for this concept.',
  },
  PRECEDENT_WEAK: {
    label: 'No comparable past agreement found',
    description: 'No comparable past agreement was found for this concept. Precedent is evidence only and does not set policy.',
  },
  CONTRACT_CONTEXT_INCOMPLETE: {
    label: 'Contract context incomplete',
    description: 'Some contract context could not be resolved.',
  },
  SOURCE_CONFLICT: {
    label: 'Policy source conflict',
    description: 'An applicable published policy records a conflict signal.',
  },
}

const UNKNOWN_SUFFICIENCY = {
  label: 'Evidence state unavailable',
  description: 'The completeness of this evidence set is not recognised.',
}

export function sufficiencyPresentation(state: EvidenceSufficiency): SufficiencyPresentation {
  return {
    state,
    ...(SUFFICIENCY_PRESENTATIONS[state as Exclude<EvidenceSufficiency, 'UNKNOWN'>] ??
      UNKNOWN_SUFFICIENCY),
  }
}

export const POLICY_MISSING_PRECEDENT_NOTE =
  'Historical precedent does not establish organizational policy.'

type ApplicabilityDimension = { key: string; label: string; value: string }

const SCOPE_DIMENSION_COUNT = 6

export function contractPackLabel(key: string): string {
  if (key.endsWith('_PACK')) return `${key.slice(0, -'_PACK'.length)} pack`
  return key
}

export function applicabilityDimensions(
  scope: ApplicabilityScope,
): ApplicabilityDimension[] {
  const dimensions: ApplicabilityDimension[] = []
  if (scope.declaredContractTypes.length > 0) {
    dimensions.push({
      key: 'contractTypes',
      label: 'Contract types',
      value: scope.declaredContractTypes
        .map((type) => CONTRACT_TYPE_LABELS[type] ?? humanizeCode(type))
        .join(', '),
    })
  }
  if (scope.contractPackKey) {
    dimensions.push({
      key: 'pack',
      label: 'Contract pack',
      value: contractPackLabel(scope.contractPackKey),
    })
  }
  if (scope.partyPerspective) {
    dimensions.push({
      key: 'perspective',
      label: 'Perspective',
      value: roleLabel(scope.partyPerspective),
    })
  }
  if (scope.paperSource) {
    dimensions.push({
      key: 'paperSource',
      label: 'Paper source',
      value: PAPER_SOURCE_LABELS[scope.paperSource] ?? humanizeCode(scope.paperSource),
    })
  }
  if (scope.jurisdiction) {
    dimensions.push({
      key: 'jurisdiction',
      label: 'Jurisdiction',
      value: JURISDICTION_LABELS[scope.jurisdiction] ?? scope.jurisdiction,
    })
  }
  if (scope.effectiveFrom && scope.effectiveTo) {
    dimensions.push({
      key: 'effective',
      label: 'Effective period',
      value: `${scope.effectiveFrom} – ${scope.effectiveTo}`,
    })
  } else if (scope.effectiveFrom) {
    dimensions.push({
      key: 'effective',
      label: 'Effective from',
      value: scope.effectiveFrom,
    })
  } else if (scope.effectiveTo) {
    dimensions.push({
      key: 'effective',
      label: 'Effective until',
      value: scope.effectiveTo,
    })
  }
  return dimensions
}

export function applicabilityNote(scope: ApplicabilityScope): string | null {
  const present = applicabilityDimensions(scope).length
  if (present === 0 || present >= SCOPE_DIMENSION_COUNT) return null
  return 'Unlisted dimensions are not constrained by this source.'
}

export function laneEmptyState(
  lane: EvidenceLane,
  sufficiency: EvidenceSufficiency,
): string {
  if (lane.authority === 'POLICY') {
    return sufficiency === 'POLICY_MISSING'
      ? 'No published policy applies to this concept and profile.'
      : 'Policy evidence was not resolved for this retrieval.'
  }
  if (lane.authority === 'PRECEDENT') {
    return sufficiency === 'PARTIAL'
      ? 'Precedent retrieval was incomplete; no comparable precedent was returned.'
      : 'No comparable precedent was found for this concept.'
  }
  return lane.emptyState
}

export function staleClassificationNote(
  builtConcept: string | null,
  currentConcept: string | null,
  label: ConceptLabel,
): string | null {
  if (builtConcept === null || builtConcept === currentConcept) return null
  const current = currentConcept === null ? 'not set' : label(currentConcept)
  return `This evidence was built with the classification ${label(
    builtConcept,
  )}. The contract's current classification is ${current}; rebuild to refresh the evidence.`
}

const DEGRADATION_MESSAGES: Record<string, string> = {
  VECTOR_LANE_UNAVAILABLE: 'Vector evidence was unavailable for this retrieval.',
  VECTOR_LANE_UNCONFIGURED: 'Vector evidence is not configured in this environment.',
  RERANK_UNAVAILABLE:
    'Semantic reranking was unavailable; precedents are shown in fusion order.',
  COMPATIBILITY_UNAVAILABLE: 'Policy compatibility could not be assessed.',
  STRUCTURED_LANE_TRUNCATED:
    'Structured evidence exceeded the bounded retrieval window; results may be incomplete.',
  INDEX_INCOMPLETE: 'Some index entries are not currently searchable.',
  VECTOR_INDEX_INCOMPLETE: 'Some index entries can be found by their words only, not by their meaning.',
}

export function degradationMessage(code: string): string {
  return DEGRADATION_MESSAGES[code] ?? humanizeCode(code)
}

const COMPATIBILITY_LABELS: Record<PolicyCompatibility, string> = {
  COMPATIBLE: 'Compatible with current published policy',
  INCOMPATIBLE: 'Not compatible with current published policy',
  UNKNOWN: 'Policy compatibility unknown',
  NOT_ASSESSED: 'Policy compatibility not assessed',
}

export function policyCompatibilityLabel(value: PolicyCompatibility | null): string | null {
  if (value === null) return null
  return COMPATIBILITY_LABELS[value] ?? COMPATIBILITY_LABELS.UNKNOWN
}

const SOURCE_TYPE_LABELS: Record<string, string> = {
  PUBLISHED_PLAYBOOK_RULE: 'Playbook rule',
  PUBLISHED_CLAUSE_VARIANT: 'Clause library variant',
  PUBLISHED_PRECEDENT: 'Executed precedent',
  CURRENT_CONTRACT_CLAUSE: 'Contract clause',
  CURRENT_CONTRACT_DEFINED_TERM: 'Defined term',
  DEFINED_TERM: 'Defined term',
}

export function sourceTypeLabel(code: string): string {
  return SOURCE_TYPE_LABELS[code] ?? humanizeCode(code)
}

const RETRIEVAL_REASON_LABELS: Record<string, string> = {
  STRUCTURED: 'Structured lookup',
  CANONICAL_STRUCTURE: 'Contract structure',
  LEXICAL: 'Lexical match',
  VECTOR: 'Semantic similarity match',
  FUSION: 'Lexical + semantic match',
  DEFINED_TERM: 'Referenced defined term',
  RESOLVED_REFERENCE: 'Resolved cross-reference',
  SIBLING_CONCEPT: 'Related clause (same concept)',
}

export function retrievalReasonLabel(code: string): string {
  const prefix = 'SEMANTIC_RELATIONSHIP:'
  if (code.startsWith(prefix)) {
    return `Semantic relationship: ${relationshipTypeVerb(code.slice(prefix.length))}`
  }
  return RETRIEVAL_REASON_LABELS[code] ?? humanizeCode(code)
}

const TRACE_STAGE_LABELS: Record<string, string> = {
  policy: 'Policy lookup',
  drafting: 'Approved drafting lookup',
  structured: 'Structured lookup',
  lexical: 'Lexical candidates',
  vector: 'Vector candidates',
  fusion: 'Rank fusion',
  rerank: 'Semantic reranking',
  compatibility: 'Policy compatibility',
  currentContract: 'Current contract',
}

export function traceStageLabel(key: string): string {
  return TRACE_STAGE_LABELS[key] ?? humanizeCode(key)
}

const TRACE_TIMING_LABELS: Record<string, string> = {
  pinResolution: 'Knowledge pin resolution',
  structured: 'Structured lookup',
  precedent: 'Precedent retrieval',
  currentContract: 'Current contract',
  totalMs: 'Total',
}

export function traceTimingLabel(key: string): string {
  return TRACE_TIMING_LABELS[key] ?? humanizeCode(key)
}

type TraceRequestEntry = {
  key: string
  label: string
  value: string
  long: boolean
}

const TRACE_REQUEST_KEYS = new Set([
  'purpose',
  'legalConcept',
  'effectiveOn',
  'knowledgePin',
  'resolvedAsOfPublication',
  'contractId',
  'versionNumber',
  'clauseId',
  'clauseText',
  'contractType',
  'ourSideRole',
  'paperSource',
  'jurisdiction',
])

const TRACE_PROFILE_KEYS = new Set([
  'contractType',
  'ourSideRole',
  'paperSource',
  'jurisdiction',
])

const REQUEST_KEY_LABELS: Record<string, string> = {
  purpose: 'Purpose',
  legalConcept: 'Legal concept',
  effectiveOn: 'Effective on',
  knowledgePin: 'Knowledge pin',
  resolvedAsOfPublication: 'Resolved publication',
  contractId: 'Contract',
  versionNumber: 'Version',
  clauseId: 'Clause',
  contractType: 'Contract type',
  ourSideRole: 'Our side',
  paperSource: 'Paper source',
  jurisdiction: 'Jurisdiction',
  clauseText: 'Subject clause text',
}

function entryLabel(key: string): string {
  return REQUEST_KEY_LABELS[key] ?? humanizeCode(key)
}

function displayValue(key: string, value: string, label: ConceptLabel): string {
  switch (key) {
    case 'legalConcept':
      return label(value)
    case 'purpose':
      return humanizeCode(value)
    case 'contractType':
      return CONTRACT_TYPE_LABELS[value] ?? value
    case 'ourSideRole':
      return roleLabel(value)
    case 'paperSource':
      return PAPER_SOURCE_LABELS[value] ?? value
    case 'jurisdiction':
      return JURISDICTION_LABELS[value] ?? value
    default:
      return value
  }
}

function scalarEntry(
  entryKey: string,
  valueKey: string,
  value: string | number | boolean,
  label: ConceptLabel,
): TraceRequestEntry {
  if (typeof value === 'string') {
    return {
      key: entryKey,
      label: entryLabel(valueKey),
      value: displayValue(valueKey, value, label),
      long: value.length > 200,
    }
  }
  return { key: entryKey, label: entryLabel(valueKey), value: String(value), long: false }
}

export function traceRequestEntries(
  request: Record<string, unknown>,
  label: ConceptLabel,
): TraceRequestEntry[] {
  const entries: TraceRequestEntry[] = []
  for (const [key, value] of Object.entries(request)) {
    if (value === null || value === undefined) continue
    if (key === 'reviewProfile' && typeof value === 'object' && !Array.isArray(value)) {
      for (const [nestedKey, nestedValue] of Object.entries(value)) {
        if (
          TRACE_PROFILE_KEYS.has(nestedKey) &&
          (typeof nestedValue === 'string' ||
            typeof nestedValue === 'number' ||
            typeof nestedValue === 'boolean')
        ) {
          entries.push(scalarEntry(`reviewProfile.${nestedKey}`, nestedKey, nestedValue, label))
        }
      }
      continue
    }
    if (!TRACE_REQUEST_KEYS.has(key)) continue
    if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
      entries.push(scalarEntry(key, key, value, label))
    }
  }
  return entries
}
