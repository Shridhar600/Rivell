import type {
  AnalysisWarning,
  FindingClaim,
  FindingDetail,
  FindingSummary,
  ReviewCoverage,
  ReviewFailure,
  ReviewSummary,
  FindingQuality,
} from '../api/reviews'
import { humanizeCode } from './domainLabels'
import type { ConceptLabel } from '../api/taxonomy'

const REVIEW_STATUS_LABELS: Record<string, string> = {
  ANALYSIS_QUEUED: 'Analysis queued',
  ANALYZING: 'Analyzing',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  UNKNOWN: 'Status unknown',
}

export function reviewStatusLabel(status: string): string {
  return REVIEW_STATUS_LABELS[status] ?? humanizeCode(status)
}

export function reviewStatusModifier(status: string): string {
  switch (status) {
    case 'COMPLETED':
      return 'badge--status-complete'
    case 'FAILED':
      return 'badge--status-failed'
    case 'ANALYZING':
    case 'ANALYSIS_QUEUED':
      return 'badge--status-active'
    default:
      return 'badge--muted'
  }
}

export function runStatusLabel(status: string): string {
  switch (status) {
    case 'RUNNING':
      return 'Run in progress'
    case 'COMPLETED':
      return 'Run completed'
    case 'FAILED':
      return 'Run failed'
    default:
      return 'Run status unknown'
  }
}

const SEVERITY_LABELS: Record<string, string> = {
  HIGH: 'High',
  MEDIUM: 'Medium',
  LOW: 'Low',
  UNKNOWN: 'Severity unknown',
}

export function severityLabel(severity: string): string {
  return SEVERITY_LABELS[severity] ?? humanizeCode(severity)
}

export function severityModifier(severity: string): string {
  switch (severity) {
    case 'HIGH':
      return 'badge--attention-high'
    case 'MEDIUM':
      return 'badge--attention-elevated'
    case 'LOW':
      return 'badge--attention-routine'
    default:
      return 'badge--muted'
  }
}

const CLASSIFICATION_LABELS: Record<string, string> = {
  BLOCKING: 'Blocking',
  REVIEW_REQUIRED: 'Review required',
  INFORMATIONAL: 'Informational',
  UNKNOWN: 'Classification unknown',
}

export function classificationLabel(classification: string): string {
  return CLASSIFICATION_LABELS[classification] ?? humanizeCode(classification)
}

const ISSUE_FAMILY_LABELS: Record<string, string> = {
  LIABILITY_CAP_DEVIATION: 'Liability cap deviation',
  LIABILITY_CAP_UNCERTAIN: 'Liability cap uncertainty',
  MISSING_REQUIRED_PROVISION: 'Missing required provision',
  EXPECTED_PROVISION_ABSENT: 'Expected provision absent',
  SURVIVAL_COVERAGE_GAP: 'Survival coverage gap',
  CARVE_OUT_INCONSISTENCY: 'Carve-out inconsistency',
  POLICY_UNAVAILABLE: 'Policy unavailable',
  POLICY_AMBIGUOUS: 'Policy ambiguous',
  OPEN_ENDED_LIABILITY: 'Open-ended liability',
}

export function issueFamilyLabel(family: string): string {
  return ISSUE_FAMILY_LABELS[family] ?? humanizeCode(family)
}

const OBSERVATION_KIND_LABELS: Record<string, string> = {
  CAP_ABOVE_PREFERRED: 'Liability cap above the preferred position',
  PROHIBITED_STRUCTURE: 'Structure the policy prohibits',
  MISSING_REQUIRED_CONCEPT: 'Required provision missing',
  EXPECTED_CONCEPT_ABSENT: 'Expected provision absent',
  POLICY_UNAVAILABLE: 'Policy unavailable',
  AMBIGUOUS_POLICY: 'Policy ambiguous',
  SURVIVAL_COVERAGE_GAP: 'Survival coverage gap',
  CARVE_OUT_INCONSISTENCY: 'Carve-out inconsistency',
  OPEN_ENDED_ANALYSIS_RESULT: 'Open-ended analysis',
}

export function observationKindLabel(kind: string): string {
  if (kind.startsWith('UNCERTAINTY:')) return 'Uncertain assessment'
  return OBSERVATION_KIND_LABELS[kind] ?? humanizeCode(kind)
}

const CLAIM_KIND_LABELS: Record<string, string> = {
  OBSERVATION: 'Observation',
  POLICY_BASIS: 'Policy basis',
  EVIDENCE_BASIS: 'Evidence basis',
  ABSENCE_BASIS: 'Absence basis',
  RECOMMENDATION: 'Recommended action',
  GENERATIVE_EXPLANATION: 'AI-proposed explanation',
}

export function claimKindLabel(kind: string): string {
  return CLAIM_KIND_LABELS[kind] ?? humanizeCode(kind)
}

const PROPOSED_ACTION_LABELS: Record<string, string> = {
  USE_APPROVED_FALLBACK: 'Use the approved fallback wording',
  ADD_REQUIRED_PROVISION: 'Add the required provision',
  REVIEW_AMBIGUITY: 'Policy ambiguity needs human review',
  ESCALATE_TO_HUMAN: 'Human review needed',
  CONSIDER_GENERATED_LANGUAGE: 'Consider the AI-proposed wording',
  NO_ACTION: 'No action proposed',
  UNKNOWN: 'Recommended action',
}

export function proposedActionLabel(action: string): string {
  return PROPOSED_ACTION_LABELS[action] ?? humanizeCode(action)
}

export function checkKeyLabel(checkKey: string, label: ConceptLabel): string {
  const [mechanism, concept] = checkKey.split(':')
  if (!concept) return humanizeCode(checkKey)
  return `${humanizeCode(mechanism)} · ${label(concept)}`
}

const SUFFICIENCY_LABELS: Record<string, string> = {
  SUFFICIENT: 'Evidence complete',
  PARTIAL: 'Evidence partially complete',
  PRECEDENT_WEAK: 'Weak precedent evidence',
  DRAFTING_MISSING: 'No approved drafting available',
  POLICY_MISSING: 'Policy evidence missing',
  AMBIGUOUS_POLICY: 'Policy evidence ambiguous',
  SOURCE_CONFLICT: 'Conflicting sources',
  CONTRACT_CONTEXT_INCOMPLETE: 'Contract context incomplete',
}

const VERIFICATION_OUTCOME_LABELS: Record<string, string> = {
  VERIFIED: 'Independently verified',
  UNCERTAIN: 'Verification uncertain',
  DISPUTED: 'Verification disputed',
}

export function qualityFactorRows(quality: FindingQuality | null): string[] {
  if (!quality) return []
  const rows: string[] = []
  if (quality.sufficiency) {
    rows.push(
      SUFFICIENCY_LABELS[quality.sufficiency] ?? humanizeCode(quality.sufficiency),
    )
  }
  if (quality.verificationOutcome) {
    rows.push(
      VERIFICATION_OUTCOME_LABELS[quality.verificationOutcome] ??
        humanizeCode(quality.verificationOutcome),
    )
  }
  for (const flag of quality.degradationFlags) {
    rows.push(degradationFlagLabel(flag))
  }
  return rows
}

function degradationFlagLabel(flag: string): string {
  if (flag.includes('provider_unavailable') || flag.includes('unavailable')) {
    return 'A supporting semantic decision was unavailable'
  }
  if (flag.includes('not_comparable')) return 'The assessment was not comparable'
  if (flag.includes('uncertain')) return 'An uncertain classification is recorded'
  if (flag.includes('too_large')) return 'The bounded context was exceeded'
  if (flag.includes('missing')) return 'Some evidence was unavailable'
  return 'The check ran in a degraded mode'
}

// `AnalysisWarning.message` and `ReviewFailure.message` are the only server strings still rendered
// verbatim, and they are exempt from the rule the rest of the API layer follows on purpose: the
// backend builds both from a closed set of fixed sentences (it maps the raw execution detail to a
// warning reason and replaces anything unrecognised with a generic sentence), so they carry no
// identifiers or internals and the specific wording is more useful to a reviewer than a flag would
// be. This exemption depends on that backend sanitization. If a raw message ever appears here, the
// fix belongs in the same place the other fields were fixed: at the parser boundary.
export function analysisWarningMessage(warning: AnalysisWarning): string {
  const message = warning.message.trim()
  const fallback =
    warning.status === 'FAILED'
      ? 'This check did not complete; its result is missing.'
      : 'This check ran in a degraded mode; its result may be incomplete.'
  const base = message || fallback
  if (warning.reasonCode === 'SURVIVAL_COVERAGE_PARTIAL') {
    return `${base} Human review is required before treating the provision as covered or uncovered.`
  }
  return base
}

export function reviewFailureMessage(failure: ReviewFailure | null): string {
  if (failure?.message.trim()) return failure.message.trim()
  return 'The automated review could not be completed. The contract and any earlier reviews are unaffected.'
}

const COVERAGE_LABELS: Record<string, string> = {
  PRESENT_PRIMARY: 'Reviewed as a primary clause',
  PRESENT_SECONDARY_ONLY: 'Detected as a secondary mention only',
  ABSENT: 'Not found in the contract',
  UNKNOWN: 'Coverage not established',
}

export function coverageLabel(state: string): string {
  return COVERAGE_LABELS[state] ?? 'Coverage not established'
}

export function coverageExpectationLabel(expectation: string): string {
  switch (expectation) {
    case 'REQUIRED':
      return 'Required'
    case 'EXPECTED':
      return 'Expected'
    case 'OPTIONAL':
      return 'Optional'
    default:
      return humanizeCode(expectation)
  }
}

export function coverageModifier(state: string): string {
  return state === 'PRESENT_PRIMARY' ? 'badge--muted' : 'badge--status-active'
}

export function coverageLimitations(coverage: ReviewCoverage[]): ReviewCoverage[] {
  return coverage.filter((entry) => entry.coverage !== 'PRESENT_PRIMARY')
}

export function coverageLimitationSummary(
  coverage: ReviewCoverage[],
  label: ConceptLabel,
): string | null {
  const limited = coverageLimitations(coverage)
  if (limited.length === 0) return null
  const parts = limited.map(
    (entry) => `${label(entry.concept)} (${coverageLabel(entry.coverage).toLowerCase()})`,
  )
  return `Review coverage is limited for: ${parts.join('; ')}.`
}

export function findingSubjectLabel(
  finding: Pick<
    FindingSummary,
    'subjectKind' | 'subjectHeadingNodeId' | 'absenceConcept' | 'concept'
  >,
  regionLabels: Map<string, string>,
  label: ConceptLabel,
): string {
  if (finding.subjectKind === 'ABSENCE' && finding.absenceConcept) {
    return `Missing: ${label(finding.absenceConcept)}`
  }
  if (finding.subjectKind === 'CLAUSE' && finding.subjectHeadingNodeId) {
    const region = regionLabels.get(finding.subjectHeadingNodeId)
    if (region) return region
    return finding.concept
      ? `Clause · ${label(finding.concept)}`
      : 'Clause source unavailable'
  }
  return 'Subject unavailable'
}

export function progressPercent(counts: {
  plannedChecks: number
  completedChecks: number
}): number | null {
  if (counts.plannedChecks <= 0) return null
  return Math.min(100, Math.round((counts.completedChecks / counts.plannedChecks) * 100))
}

export function hasNewerRun(summary: ReviewSummary): boolean {
  return summary.activeRun !== null && summary.latestRunStatus !== null
}

const OBSERVATION_CLAIM_KINDS = new Set<string>(['OBSERVATION'])
const RECOMMENDATION_CLAIM_KINDS = new Set<string>(['RECOMMENDATION'])
const ABSENCE_CLAIM_KINDS = new Set<string>(['ABSENCE_BASIS'])

export function whyClaims(claims: FindingClaim[]): FindingClaim[] {
  return claims
    .filter(
      (claim) =>
        !OBSERVATION_CLAIM_KINDS.has(claim.claimKind) &&
        !RECOMMENDATION_CLAIM_KINDS.has(claim.claimKind) &&
        !ABSENCE_CLAIM_KINDS.has(claim.claimKind) &&
        !claim.absence,
    )
    .sort((left, right) => left.ordinal - right.ordinal)
}

export function recommendationClaims(claims: FindingClaim[]): FindingClaim[] {
  return claims
    .filter((claim) => RECOMMENDATION_CLAIM_KINDS.has(claim.claimKind) && !claim.absence)
    .sort((left, right) => left.ordinal - right.ordinal)
}

export function absenceClaims(claims: FindingClaim[]): FindingClaim[] {
  return claims
    .filter((claim) => ABSENCE_CLAIM_KINDS.has(claim.claimKind) || claim.absence)
    .sort((left, right) => left.ordinal - right.ordinal)
}

export function policyNoteText(
  finding: Pick<FindingDetail, 'issueFamily'>,
): string | null {
  if (finding.issueFamily === 'POLICY_UNAVAILABLE') {
    return 'No organizational policy covers this clause. Historical precedent is evidence only and does not establish policy.'
  }
  if (finding.issueFamily === 'POLICY_AMBIGUOUS') {
    return 'More than one published policy applies. Rivell did not choose between them; the ambiguity is preserved for human review.'
  }
  return null
}
