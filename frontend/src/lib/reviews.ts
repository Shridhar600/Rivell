import type {
  AnalysisWarning,
  FindingDetail,
  FindingSummary,
  ReviewSummary,
  FindingQuality,
} from '../api/reviews'
import { humanizeCode } from './domainLabels'
import { conceptLabel } from './contractSemantics'

export const REVIEW_PACK_OPTIONS = [
  { key: 'MSA_PACK', label: 'MSA pack' },
  { key: 'NDA_PACK', label: 'NDA pack' },
] as const

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
      return 'badge--approved'
    case 'FAILED':
      return 'badge--rejected'
    case 'ANALYZING':
    case 'ANALYSIS_QUEUED':
      return 'badge--pending'
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
  REVIEW_AMBIGUITY: 'Resolve the policy ambiguity',
  ESCALATE_TO_HUMAN: 'Human review needed',
  CONSIDER_GENERATED_LANGUAGE: 'Consider the AI-proposed wording',
  NO_ACTION: 'No action proposed',
  UNKNOWN: 'Recommended action',
}

export function proposedActionLabel(action: string): string {
  return PROPOSED_ACTION_LABELS[action] ?? humanizeCode(action)
}

export function checkKeyLabel(checkKey: string): string {
  const [mechanism, concept] = checkKey.split(':')
  if (!concept) return humanizeCode(checkKey)
  return `${humanizeCode(mechanism)} · ${conceptLabel(concept)}`
}

export function proposedLanguageSourceLabel(source: string): string {
  switch (source) {
    case 'APPROVED_DRAFTING':
      return 'Approved language'
    case 'GENERATIVE':
      return 'AI-proposed language'
    case 'NONE':
      return 'No proposed language'
    default:
      return humanizeCode(source)
  }
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

export function analysisWarningMessage(warning: AnalysisWarning): string {
  const reason = warning.reason ?? ''
  if (reason.includes('provider_unavailable') || reason.includes('bounded_decision_unavailable')) {
    return 'A bounded semantic decision was unavailable; the clause was not classified.'
  }
  if (reason.includes('open_ended_generation_unavailable')) {
    return 'The machine-proposed wording could not be produced; human review is required.'
  }
  if (reason.includes('cross_clause_decision_unavailable')) {
    return 'The cross-clause assessment was unavailable.'
  }
  if (reason.includes('carve_out_context_too_large')) {
    return 'The cross-clause context exceeded the bounded analysis size.'
  }
  if (reason.includes('carve_out_not_comparable')) {
    return 'The cross-clause classifier could not compare the provisions.'
  }
  if (reason.includes('classification_uncertain')) {
    return 'The classifier did not reach a clear classification for this clause.'
  }
  return warning.status === 'FAILED'
    ? 'This check did not complete; its result is missing.'
    : 'This check ran in a degraded mode; its result may be incomplete.'
}

export function reviewFailureMessage(_reason: string | null): string {
  return 'The automated review could not be completed. The contract and any earlier reviews are unaffected.'
}

export function findingSubjectLabel(
  finding: Pick<
    FindingSummary,
    'subjectKind' | 'subjectHeadingNodeId' | 'absenceConcept' | 'concept'
  >,
  regionLabels: Map<string, string>,
): string {
  if (finding.subjectKind === 'ABSENCE' && finding.absenceConcept) {
    return `Missing: ${conceptLabel(finding.absenceConcept)}`
  }
  if (finding.subjectKind === 'CLAUSE' && finding.subjectHeadingNodeId) {
    const label = regionLabels.get(finding.subjectHeadingNodeId)
    if (label) return label
    return finding.concept
      ? `Clause · ${conceptLabel(finding.concept)}`
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
  if (!summary.activeRun || !summary.latestRunStatus) return false
  return summary.activeRun.status !== summary.latestRunStatus
}

export function findingConcepts(finding: FindingDetail): string[] {
  if (finding.concepts.length > 0) return finding.concepts
  const concepts = [finding.concept, finding.secondaryConcept].filter(
    (concept): concept is string => Boolean(concept),
  )
  return concepts
}