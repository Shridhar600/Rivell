import type {
  ArtifactDetail,
  AssessmentState,
  Candidate,
  Interpretation,
  PositionRole,
  ReviewCategory,
  ReviewReason,
  VerificationState,
} from '../api/knowledge'

export const POSITION_LABELS: Record<PositionRole, string> = {
  PREFERRED: 'Preferred',
  FALLBACK: 'Fallback',
  PROHIBITED: 'Prohibited',
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
  'Review attention describes how much human scrutiny Rivell suggests before publication. It is not a legal-risk rating.'

export const REVIEW_CATEGORY_DESCRIPTIONS: Record<ReviewCategory, string> = {
  ROUTINE: 'No review concerns detected.',
  ELEVATED: 'Some extracted values need a closer look.',
  HIGH: 'Conflicts, supersession, or disputed extraction need a decision.',
}

export const REVIEW_REASON_LABELS: Record<ReviewReason, string> = {
  DUPLICATE_DETECTED: 'Possible duplicate of published knowledge.',
  CONFLICT_DETECTED: 'May contradict published knowledge of the same concept and scope.',
  SUPERSESSION_PROPOSED:
    'Rivell suggests this candidate may replace published knowledge.',
  ARTIFACT_TYPE_MISMATCH:
    'The source may not match the declared artifact type.',
  VERIFICATION_DISPUTED:
    'An extracted value is not sufficiently supported by the source.',
  UNVERIFIED_GENERATIVE_FIELD:
    'An extracted field could not be independently verified.',
  LOW_CLASSIFICATION_CONFIDENCE:
    'Rivell could not classify this unit against a legal concept confidently.',
  NEW_PROHIBITED_OR_ESCALATION_POSITION:
    'Proposes a prohibited or escalation position.',
  SCOPE_NARROWING_PROPOSED:
    'The source narrows the applicability scope beyond the manifest.',
  SEMANTIC_ANALYSIS_UNAVAILABLE:
    'Semantic conflict analysis was unavailable; deterministic checks still ran.',
  SEMANTIC_COMPILATION_UNAVAILABLE:
    'Semantic compilation was unavailable; this candidate is deterministic extraction only.',
}

export const VERIFICATION_LABELS: Record<VerificationState, string> = {
  VERIFIED: 'Supported by source',
  UNCERTAIN: 'Verification uncertain',
  DISPUTED: 'Source support disputed',
  NOT_VERIFIED: 'Not independently verified',
}

export const ASSESSMENT_STATE_LABELS: Record<AssessmentState, string> = {
  MATCH: 'Matches declared type',
  POSSIBLE_MISMATCH: 'Possible mismatch',
  AMBIGUOUS: 'Ambiguous',
}

export const CONCEPT_LABELS: Record<string, string> = {
  CONFIDENTIALITY: 'Confidentiality',
  TERM_TERMINATION: 'Term and termination',
  LIMITATION_OF_LIABILITY: 'Limitation of liability',
  INDEMNIFICATION: 'Indemnification',
  GOVERNING_LAW: 'Governing law',
}

export function conceptLabel(key: string | null): string | null {
  if (key === null || key.trim().length === 0) return null
  return CONCEPT_LABELS[key] ?? humanizeCode(key)
}

export function humanizeCode(code: string): string {
  const lower = code
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replaceAll('_', ' ')
    .toLowerCase()
  return lower.charAt(0).toUpperCase() + lower.slice(1)
}

export function reviewReasonLabel(code: string): string {
  return REVIEW_REASON_LABELS[code as ReviewReason] ?? code
}

export function verificationLabel(state: VerificationState): string {
  return VERIFICATION_LABELS[state]
}

export function interpretationFor(
  candidate: Candidate,
  field: string,
): Interpretation | null {
  return candidate.interpretations.find((entry) => entry.field === field) ?? null
}

export function proposedPositions(candidate: Candidate): PositionRole[] | null {
  const interpretation = interpretationFor(candidate, 'positions')
  if (!interpretation) return null
  return interpretation.proposedValue
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

export function positionsDiverge(candidate: Candidate): boolean {
  const proposed = proposedPositions(candidate)
  if (proposed === null) return false
  return !samePositionSet(candidate.positions, proposed)
}

export function variantRoleDiverge(candidate: Candidate): boolean {
  const interpretation = interpretationFor(candidate, 'variantRole')
  if (!interpretation || candidate.variantRole === null) return false
  return candidate.variantRole !== interpretation.proposedValue
}

export function escalationDiverge(candidate: Candidate): boolean {
  const interpretation = interpretationFor(candidate, 'escalationTrigger')
  if (!interpretation || candidate.escalationTrigger === null) return false
  return candidate.escalationTrigger !== interpretation.proposedValue
}

export function approvalDiverge(candidate: Candidate): boolean {
  const interpretation = interpretationFor(candidate, 'requiresApproval')
  if (!interpretation || candidate.requiresApproval === null) return false
  return String(candidate.requiresApproval) !== interpretation.proposedValue
}

export interface ScopeDifference {
  label: string
  candidateValue: string | null
  manifestValue: string | null
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
  ) => {
    if ((candidateValue ?? '') !== (manifestValue ?? '')) {
      differences.push({ label, candidateValue, manifestValue })
    }
  }

  compare('Contract pack', candidate.contractPackKey, artifact.contractPackKey)
  compare(
    'Declared contract types',
    candidate.declaredContractTypes.join(', ') || null,
    artifact.declaredContractTypes.join(', ') || null,
  )
  compare('Party perspective', candidate.partyPerspective, artifact.partyPerspective)
  compare('Paper source', candidate.paperSource, artifact.paperSource)
  compare('Jurisdiction', candidate.jurisdiction, artifact.jurisdiction)
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

export function scopeInputFrom(draft: ScopeDraft): {
  contractPackKey?: string
  declaredContractTypes: string[]
  partyPerspective?: string
  paperSource?: string
  jurisdiction?: string
  effectiveFrom?: string
  effectiveTo?: string
} {
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
    .toLowerCase()
}

export interface SemanticDraft {
  positions: PositionRole[]
  requiresApproval: 'unset' | 'true' | 'false'
  escalationTrigger: string
  variantRole: PositionRole
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
  }
}

export interface SemanticFieldsInput {
  positions?: PositionRole[]
  requiresApproval?: boolean
  escalationTrigger?: string
  variantRole?: PositionRole
}

export function semanticInputFrom(
  draft: SemanticDraft,
  candidate: Candidate,
): SemanticFieldsInput | undefined {
  const input: SemanticFieldsInput = {}
  const isPlaybook = candidate.candidateType === 'PLAYBOOK_RULE'
  const isClause = candidate.candidateType === 'CLAUSE_VARIANT'

  if (isPlaybook && !samePositionSet(draft.positions, candidate.positions)) {
    if (draft.positions.length > 0) input.positions = draft.positions
  }
  if (isPlaybook && draft.requiresApproval !== 'unset') {
    const value = draft.requiresApproval === 'true'
    if (value !== candidate.requiresApproval) input.requiresApproval = value
  }
  if (isPlaybook) {
    const trimmed = draft.escalationTrigger.trim()
    if (trimmed.length > 0 && trimmed !== candidate.escalationTrigger) {
      input.escalationTrigger = trimmed
    }
  }
  if (isClause && draft.variantRole !== candidate.variantRole) {
    input.variantRole = draft.variantRole
  }

  return Object.keys(input).length > 0 ? input : undefined
}
