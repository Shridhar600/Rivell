import type {
  ArtifactDetail,
  AssessmentState,
  AuthorityLevel,
  AuthorityRule,
  Candidate,
  Interpretation,
  PositionRole,
  ReviewCategory,
  ReviewReason,
  ScopeEditInput,
  SemanticFieldsEditInput,
} from '../api/knowledge'
export { VERIFICATION_LABELS, humanizeCode } from './domainLabels'

export const POSITION_LABELS: Record<PositionRole, string> = {
  PREFERRED: 'Preferred',
  FALLBACK: 'Fallback',
  PROHIBITED: 'Walk-away',
  ESCALATION: 'Escalation',
  COMMENTARY: 'Commentary',
  UNSPECIFIED: 'Unspecified',
}

export const REVIEW_CATEGORY_LABELS: Record<ReviewCategory, string> = {
  ROUTINE: 'Routine review',
  ELEVATED: 'Elevated review',
  HIGH: 'High review attention',
}

export const REVIEW_CATEGORY_EXPLANATION =
  'Review attention describes how closely Rivell suggests you check an entry before publication. It is not a legal-risk rating.'

export const REVIEW_CATEGORY_DESCRIPTIONS: Record<ReviewCategory, string> = {
  ROUTINE: 'No review concerns detected.',
  ELEVATED: 'Some values need a closer look.',
  HIGH: 'Conflicts, supersession, or a disputed reading need a decision.',
}

export const REVIEW_REASON_LABELS: Record<ReviewReason, string> = {
  DUPLICATE_DETECTED: 'Possible duplicate of published knowledge.',
  CONFLICT_DETECTED: 'May contradict published knowledge of the same topic and scope.',
  SUPERSESSION_PROPOSED:
    'Rivell suggests this entry may replace published knowledge.',
  ARTIFACT_TYPE_MISMATCH:
    'The source may not match the file type you chose.',
  VERIFICATION_DISPUTED:
    'A value Rivell read is not sufficiently supported by the source.',
  UNVERIFIED_GENERATIVE_FIELD:
    'A field Rivell read could not be independently verified.',
  LOW_CLASSIFICATION_CONFIDENCE:
    'Rivell could not confidently place this entry under a topic.',
  NEW_PROHIBITED_OR_ESCALATION_POSITION:
    'Proposes a prohibited or escalation position.',
  SCOPE_NARROWING_PROPOSED:
    'The source narrows the applicability scope beyond the manifest.',
  SEMANTIC_ANALYSIS_UNAVAILABLE:
    'Conflict analysis was unavailable. Other checks still ran.',
  SEMANTIC_COMPILATION_UNAVAILABLE:
    'Some checks could not run. Only basic reading was used for this entry.',
  UNMAPPED_AUTHORITY_WORDS:
    'The source states a walk-away or an approver Rivell could not map. Check the stances and approvals before you approve.',
}

export const ASSESSMENT_STATE_LABELS: Record<AssessmentState, string> = {
  MATCH: 'Matches declared type',
  POSSIBLE_MISMATCH: 'Possible mismatch',
  AMBIGUOUS: 'Ambiguous',
}

export function reviewReasonLabel(code: string): string {
  return REVIEW_REASON_LABELS[code as ReviewReason] ?? code
}

export function interpretationFor(
  candidate: Candidate,
  field: string,
): Interpretation | null {
  return candidate.interpretations.find((entry) => entry.field === field) ?? null
}

export function machineProposalValue(
  interpretation: Interpretation | null,
): string | null {
  if (!interpretation) return null
  const value = interpretation.proposedValue.trim()
  if (value.length === 0 || value === 'UNAVAILABLE') return null
  return value
}

export function proposedPositions(candidate: Candidate): PositionRole[] | null {
  const proposed = machineProposalValue(interpretationFor(candidate, 'positions'))
  if (proposed === null) return null
  return proposed
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry): entry is PositionRole =>
      entry.length > 0 && entry in POSITION_LABELS,
    )
}

function samePositionSet(a: PositionRole[], b: PositionRole[]): boolean {
  if (a.length !== b.length) return false
  const setB = new Set(b)
  return a.every((role) => setB.has(role))
}

/** A structured field a person corrected and saved. A stored proposal that was not applied is not one. */
export type CorrectedField =
  | 'positions'
  | 'variantRole'
  | 'escalationTrigger'
  | 'requiresApproval'
  | 'preferredText'
  | 'fallbackTexts'
  | 'prohibitedText'
  | 'authorityRules'

export function fieldCorrected(candidate: Candidate, field: CorrectedField): boolean {
  return candidate.correctedFields.includes(field)
}

export function positionsDiverge(candidate: Candidate): boolean {
  const proposed = proposedPositions(candidate)
  if (proposed === null || proposed.length === 0) return false
  return !samePositionSet(candidate.positions, proposed)
}

export function variantRoleDiverge(candidate: Candidate): boolean {
  const proposed = machineProposalValue(interpretationFor(candidate, 'variantRole'))
  if (proposed === null) return false
  return (candidate.variantRole ?? 'UNSPECIFIED') !== proposed
}

export function escalationDiverge(candidate: Candidate): boolean {
  const proposed = machineProposalValue(
    interpretationFor(candidate, 'escalationTrigger'),
  )
  if (proposed === null) return false
  return (candidate.escalationTrigger ?? '') !== proposed
}

export function approvalDiverge(candidate: Candidate): boolean {
  const proposed = machineProposalValue(
    interpretationFor(candidate, 'requiresApproval'),
  )
  if (proposed === null) return false
  return candidate.requiresApproval === null || String(candidate.requiresApproval) !== proposed
}

export function proposedApproval(candidate: Candidate): boolean | null {
  const proposed = machineProposalValue(
    interpretationFor(candidate, 'requiresApproval'),
  )
  if (proposed === null) return null
  if (proposed === 'true') return true
  if (proposed === 'false') return false
  return null
}

export function proposedVariantRole(candidate: Candidate): PositionRole | null {
  const proposed = machineProposalValue(interpretationFor(candidate, 'variantRole'))
  if (proposed === null || !(proposed in POSITION_LABELS)) return null
  return proposed as PositionRole
}

export function proposedEscalationTrigger(candidate: Candidate): string | null {
  return machineProposalValue(interpretationFor(candidate, 'escalationTrigger'))
}

/** The same approval rules, in the order the person saved them; conditions and stances compare by value. */
function sameAuthorityRules(
  left: readonly AuthorityRule[],
  right: readonly AuthorityRule[],
): boolean {
  if (left.length !== right.length) return false
  return left.every((rule, index) => {
    const other = right[index]
    return (
      rule.approver === other.approver &&
      rule.source === other.source &&
      (rule.condition ?? '') === (other.condition ?? '') &&
      [...rule.stances].sort().join(',') === [...other.stances].sort().join(',')
    )
  })
}

export interface ScopeDifference {
  label: string
  candidateValue: string | null
  manifestValue: string | null
}

/**
 * One equality form for jurisdiction names: lower case with non-alphanumerics removed,
 * plus the long-form aliases the product displays. Mirrors Jurisdiction.normalize in
 * the backend.
 */
export function normalizeJurisdiction(value: string | null): string | null {
  if (value === null) return null
  const normalized = value.toLowerCase().replace(/[^a-z0-9]/g, '')
  if (normalized === 'uk' || normalized === 'unitedkingdom') return 'uk'
  if (
    normalized === 'us' ||
    normalized === 'unitedstates' ||
    normalized === 'unitedstatesofamerica' ||
    normalized === 'usa'
  ) {
    return 'us'
  }
  return normalized
}

export function scopeDifferences(
  candidate: Candidate,
  artifact: ArtifactDetail,
): ScopeDifference[] {
  const differences: ScopeDifference[] = []
  const compare = (
    label: string,
    candidateValue: string | null,
    manifestValue: string | null,
    equal: (left: string | null, right: string | null) => boolean = (left, right) =>
      (left ?? '') === (right ?? ''),
  ) => {
    if (!equal(candidateValue, manifestValue)) {
      differences.push({ label, candidateValue, manifestValue })
    }
  }

  compare('Review pack', candidate.contractPackKey, artifact.contractPackKey)
  compare(
    'Declared contract types',
    candidate.declaredContractTypes.join(', ') || null,
    artifact.declaredContractTypes.join(', ') || null,
  )
  compare('Party perspective', candidate.partyPerspective, artifact.partyPerspective)
  compare('Whose template', candidate.paperSource, artifact.paperSource)
  compare(
    'Jurisdiction',
    candidate.jurisdiction,
    artifact.jurisdiction,
    (left, right) =>
      (normalizeJurisdiction(left) ?? '') === (normalizeJurisdiction(right) ?? ''),
  )
  compare('Effective from', candidate.effectiveFrom, artifact.effectiveFrom)
  compare('Effective to', candidate.effectiveTo, artifact.effectiveTo)

  return differences
}

export function isBulkEligible(candidate: Candidate): boolean {
  return candidate.reviewStatus === 'PENDING' && candidate.reviewCategory === 'ROUTINE'
}

export function hasSupersessionProposal(candidate: Candidate): boolean {
  return candidate.conflictSignals.some(
    (signal) => signal.conflictType === 'SUPERSEDES_EXISTING',
  )
}

export function formatBoolean(value: boolean | null): string {
  if (value === null) return 'Not specified'
  return value ? 'Required' : 'Not required'
}

export function formatPositions(positions: PositionRole[]): string {
  if (positions.length === 0) return 'None'
  return positions.map((role) => POSITION_LABELS[role]).join(', ')
}

export function sameStringMap(
  a: Record<string, string>,
  b: Record<string, string>,
): boolean {
  const keysA = Object.keys(a)
  const keysB = Object.keys(b)
  if (keysA.length !== keysB.length) return false
  return keysA.every((key) => a[key] === b[key])
}

export interface ScopeDraft {
  contractPackKey: string
  declaredContractTypes: string
  partyPerspective: string
  paperSource: string
  jurisdiction: string
  effectiveFrom: string
  effectiveTo: string
}

export function scopeDraftFrom(candidate: Candidate): ScopeDraft {
  return {
    contractPackKey: candidate.contractPackKey ?? '',
    declaredContractTypes: candidate.declaredContractTypes.join(', '),
    partyPerspective: candidate.partyPerspective ?? '',
    paperSource: candidate.paperSource ?? '',
    jurisdiction: candidate.jurisdiction ?? '',
    effectiveFrom: candidate.effectiveFrom ?? '',
    effectiveTo: candidate.effectiveTo ?? '',
  }
}

export function splitContractTypes(value: string): string[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
}

export function scopeInputFrom(draft: ScopeDraft): ScopeEditInput {
  const optional = (value: string) => {
    const trimmed = value.trim()
    return trimmed.length > 0 ? trimmed : undefined
  }
  return {
    contractPackKey: optional(draft.contractPackKey),
    declaredContractTypes: splitContractTypes(draft.declaredContractTypes),
    partyPerspective: optional(draft.partyPerspective),
    paperSource: optional(draft.paperSource),
    jurisdiction: optional(draft.jurisdiction),
    effectiveFrom: optional(draft.effectiveFrom),
    effectiveTo: optional(draft.effectiveTo),
  }
}

export function scopeDraftChanged(draft: ScopeDraft, candidate: Candidate): boolean {
  const current = scopeDraftFrom(candidate)
  return (Object.keys(current) as Array<keyof ScopeDraft>).some(
    (key) => normalizeDraftValue(draft[key]) !== normalizeDraftValue(current[key]),
  )
}

function normalizeDraftValue(value: string): string {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
    .join(',')
}

export interface AuthorityDraft {
  approver: string
  stances: PositionRole[]
  condition: string
  source: AuthorityLevel
  quote: string | null
}

export interface SemanticDraft {
  positions: PositionRole[]
  requiresApproval: 'unset' | 'true' | 'false'
  escalationTrigger: string
  variantRole: PositionRole
  preferredText: string
  /** One fallback per line, in source order. */
  fallbackTexts: string
  prohibitedText: string
  authorityRules: AuthorityDraft[]
}

export function semanticDraftFrom(candidate: Candidate): SemanticDraft {
  return {
    positions: [...candidate.positions],
    requiresApproval:
      candidate.requiresApproval === null
        ? 'unset'
        : candidate.requiresApproval
          ? 'true'
          : 'false',
    escalationTrigger: candidate.escalationTrigger ?? '',
    variantRole: candidate.variantRole ?? 'UNSPECIFIED',
    preferredText: candidate.preferredText ?? '',
    fallbackTexts: candidate.fallbackTexts.join('\n'),
    prohibitedText: candidate.prohibitedText ?? '',
    authorityRules: candidate.storedAuthorityRules.map((rule) => ({
      approver: rule.approver,
      stances: [...rule.stances],
      condition: rule.condition ?? '',
      source: rule.source,
      quote: rule.quote,
    })),
  }
}

/** One fallback per line; blank lines and outer spaces are not fallbacks. */
export function fallbackLines(value: string): string[] {
  return value
    .split('\n')
    .map((entry) => entry.trim())
    .filter((entry) => entry.length > 0)
}

function draftRules(rules: readonly AuthorityDraft[]): AuthorityRule[] {
  return rules
    .filter((rule) => rule.approver.trim().length > 0)
    .map((rule) => ({
      approver: rule.approver.trim(),
      stances: [...rule.stances],
      condition: rule.condition.trim().length > 0 ? rule.condition.trim() : null,
      source: rule.source,
      quote: rule.quote,
    }))
}

export function semanticInputFrom(
  draft: SemanticDraft,
  candidate: Candidate,
): SemanticFieldsEditInput | undefined {
  const isPlaybook = candidate.candidateType === 'PLAYBOOK_RULE'
  const isClause = candidate.candidateType === 'CLAUSE_VARIANT'

  const approvalDraft =
    draft.requiresApproval === 'unset' ? null : draft.requiresApproval === 'true'
  const escalationDraft = draft.escalationTrigger.trim()
  const effectiveVariantRole = candidate.variantRole ?? 'UNSPECIFIED'

  const positionsChanged =
    isPlaybook && !samePositionSet(draft.positions, candidate.positions)
  const approvalChanged = isPlaybook && approvalDraft !== candidate.requiresApproval
  const escalationChanged =
    isPlaybook && escalationDraft !== (candidate.escalationTrigger ?? '')
  const variantRoleChanged =
    isClause && draft.variantRole !== effectiveVariantRole
  const preferredTextChanged =
    isPlaybook && draft.preferredText.trim() !== (candidate.preferredText ?? '')
  const fallbackTextsChanged =
    isPlaybook &&
    fallbackLines(draft.fallbackTexts).join('\n') !== candidate.fallbackTexts.join('\n')
  const prohibitedTextChanged =
    isPlaybook && draft.prohibitedText.trim() !== (candidate.prohibitedText ?? '')
  const rulesChanged =
    isPlaybook && !sameAuthorityRules(draftRules(draft.authorityRules), candidate.storedAuthorityRules)

  if (
    !positionsChanged &&
    !approvalChanged &&
    !escalationChanged &&
    !variantRoleChanged &&
    !preferredTextChanged &&
    !fallbackTextsChanged &&
    !prohibitedTextChanged &&
    !rulesChanged
  ) {
    return undefined
  }

  // The backend replaces the whole human-corrected record, so send the complete
  // applicable set; the draft is seeded from effective values and therefore
  // preserves earlier corrections. An unset approval is sent as an explicit null so
  // "not specified" is a value, not an omitted field that keeps the old answer. An
  // empty position set or escalation trigger is a deliberate clear and is sent too.
  const input: SemanticFieldsEditInput = {}
  if (isPlaybook) {
    input.positions = draft.positions
    input.requiresApproval = approvalDraft
    input.escalationTrigger = escalationDraft
    input.preferredText = draft.preferredText.trim()
    input.fallbackTexts = fallbackLines(draft.fallbackTexts)
    input.prohibitedText = draft.prohibitedText.trim()
    input.authorityRules = draftRules(draft.authorityRules)
  }
  if (isClause) {
    input.variantRole = draft.variantRole
  }

  return Object.keys(input).length > 0 ? input : undefined
}
