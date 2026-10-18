import type {
  AnalysisWarning,
  ClauseMark,
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
  MISSING_REQUIRED_PROVISION: 'Missing required provision',
  EXPECTED_PROVISION_ABSENT: 'Expected provision absent',
  SURVIVAL_COVERAGE_GAP: 'Survival coverage gap',
  CARVE_OUT_INCONSISTENCY: 'Carve-out inconsistency',
  POLICY_UNAVAILABLE: 'Policy unavailable',
  POLICY_AMBIGUOUS: 'Policy ambiguous',
  POSITION_WALK_AWAY: 'Breaches walk-away',
  POSITION_NEEDS_FALLBACK: 'Beyond our fallbacks',
  POSITION_ACCEPTABLE_FALLBACK: 'Within an acceptable fallback',
  POSITION_AMBIGUOUS: 'Unclear against our position',
  POSITION_UNCERTAIN: 'Could not be assessed',
  OPEN_ENDED_POSITION: 'No approved position available',
  USER_ADDED: 'Added by a reviewer',
}

export function issueFamilyLabel(family: string): string {
  return ISSUE_FAMILY_LABELS[family] ?? humanizeCode(family)
}

const OBSERVATION_KIND_LABELS: Record<string, string> = {
  MISSING_REQUIRED_CONCEPT: 'Required provision missing',
  EXPECTED_CONCEPT_ABSENT: 'Expected provision absent',
  POLICY_UNAVAILABLE: 'Policy unavailable',
  NOT_COVERED_BY_PLAYBOOK: 'Unusual clause, not covered by your playbook',
  SPOTTED_IN_WHOLE_CONTRACT_READ: 'Picked by the full-contract read',
  MISSING_POSITION: 'Missing from the contract',
  AMBIGUOUS_POLICY: 'Policy ambiguous',
  SURVIVAL_COVERAGE_GAP: 'Survival coverage gap',
  CARVE_OUT_INCONSISTENCY: 'Carve-out inconsistency',
  OPEN_ENDED_ANALYSIS_RESULT: 'Open-ended analysis',
  POSITION_WALK_AWAY: 'Breaches walk-away',
  POSITION_BEYOND_FALLBACK: 'Beyond our fallbacks',
  POSITION_WITHIN_FALLBACK: 'Within an acceptable fallback',
  POSITION_UNCLEAR: 'Unclear against our position',
}

const UNCERTAINTY_REASON_LABELS: Record<string, string> = {
  bounded_decision_unavailable: 'Could not be assessed',
  second_opinion_disagrees: 'Needs your look: two reviewers disagree',
  second_opinion_unavailable: 'Needs your look: no second opinion',
}

export function observationKindLabel(kind: string): string {
  if (kind.startsWith('UNCERTAINTY:')) {
    const reason = kind.slice('UNCERTAINTY:'.length)
    return UNCERTAINTY_REASON_LABELS[reason] ?? 'Uncertain assessment'
  }
  return OBSERVATION_KIND_LABELS[kind] ?? humanizeCode(kind)
}

const COMPARISON_CATEGORY_LABELS: Record<string, string> = {
  MEETS_PREFERRED: 'Meets the preferred position',
  WITHIN_FALLBACK: 'Within an acceptable fallback',
  BEYOND_FALLBACK: 'Beyond every listed fallback',
  WALK_AWAY: 'Breaches the walk-away',
  UNCLEAR: 'Unclear against the position',
  NOT_ADDRESSED: 'Does not address the position',
}

export function comparisonCategoryLabel(category: string): string {
  return COMPARISON_CATEGORY_LABELS[category] ?? humanizeCode(category)
}

/**
 * The comparison's own assessment in one line: the code-owned category, then the model's flag
 * probability as a percentage. The uncovered-clause triage's probability stands in the same place
 * when no comparison category exists. The probability is the model's estimate, never a certainty,
 * and a finding whose quality lacks every part shows no line.
 */
export function assessmentSummary(quality: FindingQuality | null): string | null {
  const parts: string[] = []
  if (quality?.category) parts.push(comparisonCategoryLabel(quality.category))
  const probability = quality?.flagProbability ?? quality?.uncoveredProbability ?? null
  if (probability !== null) {
    parts.push(`Rivell's estimate: ${Math.round(probability * 100)}% (not a certainty)`)
  } else if (quality?.presenceProbability != null) {
    // an omission: how likely the playbook position requires the provision at all
    parts.push(
      `Your playbook requires this (estimate ${Math.round(quality.presenceProbability * 100)}%).`,
    )
  }
  return parts.length > 0 ? parts.join(' · ') : null
}

const CLAIM_KIND_LABELS: Record<string, string> = {
  OBSERVATION: 'Observation',
  POLICY_BASIS: 'Policy basis',
  EVIDENCE_BASIS: 'Evidence basis',
  ABSENCE_BASIS: 'Absence basis',
  RECOMMENDATION: 'Recommended action',
  GENERATIVE_EXPLANATION: "Rivell's explanation",
}

export function claimKindLabel(kind: string): string {
  return CLAIM_KIND_LABELS[kind] ?? humanizeCode(kind)
}

const PROPOSED_ACTION_LABELS: Record<string, string> = {
  USE_APPROVED_FALLBACK: 'Use the approved fallback wording',
  ADD_REQUIRED_PROVISION: 'Add the required provision',
  REVIEW_AMBIGUITY: 'Policy ambiguity needs your review',
  ESCALATE_TO_HUMAN: 'Your review needed',
  CONSIDER_GENERATED_LANGUAGE: "Consider Rivell's proposed wording",
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
  PRECEDENT_WEAK: 'No comparable past agreement found',
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
    // why Rivell left an item to the user is said by the item itself; it is not a degraded check
    if (LOOK_REASONS.has(flag)) continue
    rows.push(degradationFlagLabel(flag))
  }
  return rows
}

const LOOK_REASONS = new Set([
  'second_opinion_disagrees',
  'second_opinion_unavailable',
  'playbook_silent',
  'omission_unsure',
])

function degradationFlagLabel(flag: string): string {
  if (flag.includes('provider_unavailable') || flag.includes('unavailable')) {
    return 'A supporting check was unavailable'
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
    return `${base} Your review is required before treating the provision as covered or uncovered.`
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
  > & { positionLabel?: string | null },
  regionLabels: Map<string, string>,
  label: ConceptLabel,
): string {
  // an omission names the playbook position it misses: its concept may be one the contract covers
  if (finding.subjectKind === 'ABSENCE' && finding.positionLabel) return `Missing: ${finding.positionLabel}`
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
  finding: Pick<FindingDetail, 'issueFamily' | 'observationKind'>,
): string | null {
  if (finding.issueFamily === 'POLICY_UNAVAILABLE') {
    return finding.observationKind === 'NOT_COVERED_BY_PLAYBOOK'
      ? 'This is a gap in the playbook, not a breach of one. Precedent is evidence only and does not establish a position.'
      : 'No organizational policy covers this clause. Historical precedent is evidence only and does not establish policy.'
  }
  if (finding.issueFamily === 'POLICY_AMBIGUOUS') {
    return 'More than one published policy applies. Rivell did not choose between them; the ambiguity is preserved for your review.'
  }
  return null
}

/**
 * Whether a position finding is only related: the position governs a topic the clause touches, not
 * the clause's own subject, so the backend records it LOW and INFORMATIONAL.
 */
export function isRelatedPosition(
  finding: Pick<FindingDetail, 'issueFamily' | 'quality'>,
): boolean {
  return finding.issueFamily.startsWith('POSITION_') && finding.quality?.secondaryTopic === true
}

/**
 * The issue line a finding is listed under. A clause no playbook position addresses is its own
 * kind: the family alone would read as the older "policy unavailable" case.
 */
export function findingIssueLabel(
  finding: Pick<FindingSummary, 'issueFamily' | 'observationKind'>,
): string {
  if (finding.observationKind === 'NOT_COVERED_BY_PLAYBOOK') return observationKindLabel(finding.observationKind)
  if (finding.observationKind.startsWith('UNCERTAINTY:second_opinion_')) {
    return observationKindLabel(finding.observationKind)
  }
  return issueFamilyLabel(finding.issueFamily)
}

/**
 * Who decided a finding (engine v2 spec §9): Rivell's first review alone, with its estimate; the
 * first review confirmed by a second opinion; or nobody, because the two disagreed or the second
 * opinion was not available, so the user decides. The UI never names a model provider. Null when
 * the finding carries no route (older reviews, other checks).
 */
export function decidedByLabel(quality: FindingQuality | null): string | null {
  if (!quality) return null
  if (quality.route === 'JEV') {
    const probability = quality.flagProbability ?? quality.uncoveredProbability ?? quality.presenceProbability
    return probability === null
      ? 'Decided by Rivell’s first review'
      : `Decided by Rivell’s first review · ${Math.round(probability * 100)}%`
  }
  if (quality.route === 'SECOND_OPINION') return 'Decided with a second opinion'
  if (quality.route === 'NEEDS_YOUR_LOOK') return 'Needs your look'
  return null
}

/**
 * A mark as shown: the server's mark for a planned clause, or NOT_PLANNED for a heading the review
 * plan left out (a title, the signature block). Both mean Rivell did not judge the clause, for
 * different reasons, so the user sees which.
 */
export type ShownClauseMark = ClauseMark | 'NOT_PLANNED'

const CLAUSE_MARK_LABELS: Record<ShownClauseMark, string> = {
  FLAGGED: 'Flagged',
  CHECKED: 'Checked, no issue found',
  NOT_COVERED: 'Not covered by your playbook',
  NOT_ANALYSED: 'Not analysed: the check did not finish',
  NOT_PLANNED: 'Not reviewed: outside the review plan',
}

const CLAUSE_MARK_COUNTS: Record<ShownClauseMark, string> = {
  FLAGGED: 'flagged',
  CHECKED: 'checked',
  NOT_COVERED: 'not covered',
  NOT_ANALYSED: 'not analysed',
  NOT_PLANNED: 'not reviewed',
}

/** What Rivell did with one clause (spec §9): the whole contract is marked, not only the flags. */
export function clauseMarkLabel(mark: ShownClauseMark): string {
  return CLAUSE_MARK_LABELS[mark]
}

/** The legend over the contract: how many clauses carry each mark, in a fixed order. */
export function clauseMarksLegend(marks: readonly ShownClauseMark[]): string {
  return (Object.keys(CLAUSE_MARK_COUNTS) as ShownClauseMark[])
    .map((mark) => [mark, marks.filter((each) => each === mark).length] as const)
    .filter(([, count]) => count > 0)
    .map(([mark, count]) => `${count} ${CLAUSE_MARK_COUNTS[mark]}`)
    .join(' · ')
}

export function engineLabel(engine: string | null): string {
  if (engine === 'V2') return 'A second opinion when the first check is unsure'
  if (engine === 'V1') return 'One check only'
  return 'Not recorded'
}

/** How many findings a second opinion settled and how many wait for the user; null when none. */
export function routeCountsLabel(findings: readonly Pick<FindingSummary, 'decidedBy'>[]): string | null {
  const second = findings.filter((finding) => finding.decidedBy === 'SECOND_OPINION').length
  const look = findings.filter((finding) => finding.decidedBy === 'NEEDS_YOUR_LOOK').length
  if (second === 0 && look === 0) return null
  return `${second} decided with a second opinion · ${look} need${look === 1 ? 's' : ''} your look`
}
