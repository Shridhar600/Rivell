import type {
  AnalysisWarning,
  ClauseMark,
  FindingClaim,
  FindingDetail,
  FindingSummary,
  PlaybookUsed,
  ReviewCoverage,
  ReviewFailure,
  ReviewSummary,
  FindingQuality,
} from '../api/reviews'
import { humanizeCode } from './domainLabels'
import type { ConceptLabel } from '../api/taxonomy'

const REVIEW_STATUS_LABELS: Record<string, string> = {
  ANALYSIS_QUEUED: 'Waiting to start',
  ANALYZING: 'Analyzing',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  UNKNOWN: 'Status unknown',
}

export function reviewStatusLabel(status: string): string {
  return REVIEW_STATUS_LABELS[status] ?? humanizeCode(status)
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
  bounded_decision_unavailable: 'Could not be checked',
  cross_clause_decision_unavailable: 'Could not be checked',
  classification_uncertain: 'Could not be checked',
  cross_clause_classification_uncertain: 'Could not be checked',
  carve_out_context_too_large: 'Too much text to compare',
  carve_out_not_comparable: 'Could not be compared',
  survival_coverage_partial: 'Survival not fully checked',
  second_opinion_disagrees: 'Rivell is unsure',
  second_opinion_unavailable: 'Not double-checked',
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
 * The comparison's own assessment in one line: the code-owned category, then how sure Rivell is,
 * in words. A percentage read as false precision to lawyers, so the model's flag probability (or
 * the uncovered-clause triage's) is shown as high, medium or low. A finding whose quality lacks
 * every part shows no line.
 */
export function confidenceWord(probability: number): 'high' | 'medium' | 'low' {
  if (probability >= 0.8) return 'high'
  if (probability >= 0.6) return 'medium'
  return 'low'
}

export function assessmentSummary(quality: FindingQuality | null): string | null {
  const parts: string[] = []
  if (quality?.category) parts.push(comparisonCategoryLabel(quality.category))
  const probability = quality?.flagProbability ?? quality?.uncoveredProbability ?? null
  if (probability !== null) {
    parts.push(`Confidence: ${confidenceWord(probability)}`)
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
  POLICY_BASIS: 'Playbook',
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

export function proposedActionLabel(
  action: string,
  wording?: Pick<FindingDetail, 'proposedLanguageSource' | 'quality'> | null,
): string {
  if (
    action === 'USE_APPROVED_FALLBACK' &&
    wording?.proposedLanguageSource === 'APPROVED_DRAFTING' &&
    wording.quality?.wordingFitVerdict !== 'FITS'
  ) {
    return 'Use the library wording (not checked against this position)'
  }
  return PROPOSED_ACTION_LABELS[action] ?? humanizeCode(action)
}

export function checkKeyLabel(checkKey: string, label: ConceptLabel): string {
  const [mechanism, concept] = checkKey.split(':')
  if (!concept) return humanizeCode(checkKey)
  if (mechanism === 'CROSS') {
    if (concept === 'CARVE_OUT_CONSISTENCY') return 'Liability cap and its exceptions'
    if (concept === 'CONFIDENTIALITY_SURVIVAL') return 'Confidentiality after termination'
    return `Cross-check · ${humanizeCode(concept)}`
  }
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
  if (flag.includes('uncertain')) return 'Rivell could not check part of this clause'
  if (flag.includes('too_large')) return 'There was too much text for this check'
  if (flag.includes('missing')) return 'Some evidence was unavailable'
  return 'The check ran in a degraded mode'
}

/**
 * The check label before a warning's own sentence. The carve-out check's sentence already names
 * what it covers, so it gets no second label and reads as one plain sentence.
 */
export function analysisWarningCheckLabel(
  warning: AnalysisWarning,
  label: ConceptLabel,
): string | null {
  if (
    warning.reasonCode === 'CLASSIFICATION_UNCERTAIN' &&
    warning.checkKey.startsWith('CROSS:CARVE_OUT_CONSISTENCY')
  ) {
    return null
  }
  return checkKeyLabel(warning.checkKey, label)
}

// Most `AnalysisWarning.message` and `ReviewFailure.message` strings are rendered verbatim, and
// they are exempt from the rule the rest of the API layer follows on purpose: the backend builds
// both from a closed set of fixed sentences (it maps the raw execution detail to a warning reason
// and replaces anything unrecognised with a generic sentence), so they carry no identifiers or
// internals and the specific wording is more useful to a reviewer than a flag would be. This
// exemption depends on that backend sanitization. Classification uncertainty is the exception: its
// backend sentence is technical, so this layer replaces it with the plain sentence below. If a raw
// message ever appears here, the fix belongs in the same place the other fields were fixed: at the
// parser boundary.
export function analysisWarningMessage(warning: AnalysisWarning): string {
  const message = warning.message.trim()
  if (warning.reasonCode === 'CLASSIFICATION_UNCERTAIN') {
    return warning.checkKey.startsWith('CROSS:CARVE_OUT_CONSISTENCY')
      ? 'Rivell could not check how the liability cap and its exceptions fit together. Please check both clauses yourself.'
      : 'Rivell could not check this clause against your playbook. Please check it yourself.'
  }
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
  // An absent topic was checked and not found, so it is not a topic Rivell failed to check.
  return coverage.filter(
    (entry) => entry.coverage === 'PRESENT_SECONDARY_ONLY' || entry.coverage === 'UNKNOWN',
  )
}

export function coverageLimitationSummary(
  coverage: ReviewCoverage[],
  label: ConceptLabel,
): string | null {
  const limited = coverageLimitations(coverage)
  if (limited.length === 0) return null
  const topics = limited.map((entry) => label(entry.concept))
  return `Rivell did not check: ${topics.join(', ')}`
}

/**
 * The one sentence a finding stands on. Stored findings can predate the current templates, so the
 * old disagreement and compliance sentences are read into the words Rivell uses now, and the old
 * "a lawyer should review it" ending is dropped: the whole product is a review tool.
 */
export function findingGapText(
  finding: Pick<
    FindingDetail,
    'observationText' | 'concepts' | 'concept' | 'secondaryConcept' | 'absenceConcept' | 'addedBy'
  >,
  label: ConceptLabel,
): string {
  let text = finding.observationText.trim()
  if (!text) return ''
  // A reviewer's own note is theirs: the boilerplate pass is for machine-written findings only.
  if (finding.addedBy) return text
  text = text.replace(
    /Rivell['’]s two reviewers disagree[\s\S]*?please take a look\./i,
    'Rivell is unsure about this clause. Here is the clause and the closest rule.',
  )
  text = text.replace(
    /Rivell is unsure whether this clause meets your position: one check finds an issue and the other does not\. Compare the clause with the position below\./gi,
    'Rivell is unsure about this clause. Here is the clause and the closest rule.',
  )
  text = text.replace(
    /Rivell is unsure whether this clause meets your position, and could not get a second check\. Compare the clause with the position below\./gi,
    'Rivell is unsure about this clause, and could not get a second check. Here is the clause and the closest rule.',
  )
  text = text.replace(
    /Rivell was not confident enough about this clause to judge it against the position\. Please take a look\./gi,
    'Rivell could not check this clause against your playbook. Please check it yourself.',
  )
  text = text.replace(
    /Rivell could not check this clause because the analysis service was unavailable\. Please take a look\./gi,
    'Rivell could not check this clause because the analysis service was unavailable. Please check it yourself.',
  )
  text = text.replace(
    /The clauses were too long for Rivell to compare, so it did not check how they interact\. Please take a look\./gi,
    'There was too much text for Rivell to compare these clauses. Please check how the liability cap and its exceptions fit together.',
  )
  text = text.replace(
    /Rivell could not tell how the liability carve-out and the indemnification clause interact, so it reports no conflict either way\. Please take a look\./gi,
    'Rivell could not check how the liability cap and its exceptions fit together. Please check both clauses yourself.',
  )
  text = text.replace(
    /Rivell could not reach a clear view of how these clauses interact\. Please take a look\./gi,
    'Rivell could not check how these clauses fit together. Please check them yourself.',
  )
  text = text.replace(
    /Rivell could not check whether every confidentiality obligation survives termination\. Please take a look\./gi,
    'Rivell could not check whether every confidentiality obligation survives termination. Please check the termination clause yourself.',
  )
  text = text.replace(
    /The clause deals with this subject, but its wording is ambiguous or depends on something outside the contract, so compliance cannot be decided\./gi,
    'The clause depends on something outside the contract, so Rivell cannot tell whether it meets your playbook.',
  )
  text = text.replace(
    /Multiple conflicting published policies apply to ([A-Z_]+); no rule was chosen and the ambiguity is preserved\./g,
    'More than one rule in your playbook applies to $1, and they conflict. Rivell did not choose between them.',
  )
  text = text.replace(/\s*Recommend human legal review\.?/gi, '')
  text = text.replace(/\s*;?\s*a lawyer should review it\.?/gi, '')
  text = text.replace(/\s+/g, ' ').trim()
  if (text && !/[.!?]$/.test(text)) text += '.'
  const codes = [
    ...new Set(
      [
        finding.concept,
        finding.secondaryConcept,
        finding.absenceConcept,
        ...finding.concepts,
      ].filter((code): code is string => Boolean(code)),
    ),
  ].sort((left, right) => right.length - left.length)
  for (const code of codes) text = text.split(code).join(label(code))
  return text
}

export type FindingQuote = {
  text: string
  source: 'second-opinion' | 'contract'
}

/**
 * The exact words the finding turns on. On a "needs your look" item the second opinion's quote is
 * the explanation the reader asked for; otherwise the pinned contract words are used, never policy
 * or precedent prose.
 */
export function findingQuote(
  finding: Pick<
    FindingDetail,
    'subjectKind' | 'decidedBy' | 'quality' | 'evidenceItems' | 'evidenceItemIds'
  >,
  currentText: string | null,
): FindingQuote | null {
  if (finding.subjectKind !== 'CLAUSE') return null
  const secondOpinion = finding.quality?.secondOpinionQuote?.trim()
  if (finding.decidedBy === 'NEEDS_YOUR_LOOK' && secondOpinion) {
    return { text: secondOpinion, source: 'second-opinion' }
  }
  if (currentText?.trim()) return { text: currentText.trim(), source: 'contract' }
  const cited = new Set(finding.evidenceItemIds)
  const item = finding.evidenceItems.find(
    (evidence) =>
      evidence.authorityClass === 'CURRENT_CONTRACT' &&
      (cited.size === 0 || cited.has(evidence.evidenceId)),
  )
  return item?.text.trim() ? { text: item.text.trim(), source: 'contract' } : null
}

export type FindingPlaybookRule = {
  label: string
  text: string | null
  basedOn: string | null
  escalation: string | null
}

function firstNonEmpty<T>(...lists: readonly (readonly T[])[]): readonly T[] {
  return lists.find((list) => list.length > 0) ?? []
}

/**
 * The closest playbook rules behind a finding: the walk-away for a walk-away, the fallbacks for a
 * beyond-fallback or within-fallback note, the preferred rule otherwise. Every rule the evidence
 * supports is shown rather than choosing one the API cannot identify as the match. The playbook
 * name is used only when the rules' own evidence names one artifact the review actually froze.
 */
export function findingPlaybookRule(
  finding: Pick<
    FindingDetail,
    | 'issueFamily'
    | 'concept'
    | 'secondaryConcept'
    | 'positionLabel'
    | 'positionComparison'
    | 'evidenceItems'
  >,
  label: ConceptLabel,
  playbooks: readonly PlaybookUsed[] | null,
): FindingPlaybookRule | null {
  const concept = finding.secondaryConcept ?? finding.concept
  const labelText = finding.positionLabel ?? (concept ? label(concept) : '')
  const omission = Boolean(finding.positionLabel)
  if (!finding.issueFamily.startsWith('POSITION_') && !omission) return null
  const position = finding.positionComparison
  if (!position) {
    return omission && labelText
      ? { label: labelText, text: null, basedOn: null, escalation: null }
      : null
  }
  const family = finding.issueFamily
  const chosen = family.startsWith('POSITION_')
    ? family === 'POSITION_WALK_AWAY'
      ? firstNonEmpty(position.walkAway, position.preferred)
      : family === 'POSITION_ACCEPTABLE_FALLBACK' || family === 'POSITION_NEEDS_FALLBACK'
        ? firstNonEmpty(position.fallback, position.preferred)
        : firstNonEmpty(position.preferred, position.fallback)
    : firstNonEmpty(position.preferred, position.fallback, position.walkAway)
  if (chosen.length === 0) {
    return omission && labelText
      ? { label: labelText, text: null, basedOn: null, escalation: null }
      : null
  }
  const texts = chosen.map((entry) => entry.text.trim()).filter(Boolean)
  const artifacts = new Set(
    chosen
      .map(
        (entry) =>
          finding.evidenceItems.find((item) => item.evidenceId === entry.evidenceItemId)
            ?.sourceArtifactId,
      )
      .filter((artifactId): artifactId is string => Boolean(artifactId)),
  )
  const artifact = artifacts.size === 1 ? [...artifacts][0] : null
  const playbook = artifact
    ? (playbooks?.find((used) => used.artifactId === artifact) ?? null)
    : null
  const version =
    playbook?.publicationNumber ?? (chosen.length === 1 ? chosen[0].publicationNumber : null)
  const escalation = position.escalation[0]?.trigger.trim() || null
  return {
    label: labelText,
    text: texts.length > 0 ? texts.join('\n\n') : null,
    basedOn: playbook && version !== null ? `${playbook.name}, version ${version}` : null,
    escalation,
  }
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
      ? 'This is a gap in the playbook, not a breach of one. Past contracts are examples only, not your position.'
      : 'Your playbook has no position on this clause. Past contracts are examples only, not your position.'
  }
  if (finding.issueFamily === 'POLICY_AMBIGUOUS') {
    return 'More than one rule in your playbook applies. Rivell did not choose between them.'
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
 * kind: the family alone would read as the older "policy unavailable" case. The whole-contract
 * read's advisory is the same gap said in different words, so it uses the same line (DW-070).
 */
export function findingIssueLabel(
  finding: Pick<FindingSummary, 'issueFamily' | 'observationKind'>,
): string {
  if (finding.observationKind === 'NOT_COVERED_BY_PLAYBOOK') {
    return observationKindLabel(finding.observationKind)
  }
  if (finding.observationKind === 'SPOTTED_IN_WHOLE_CONTRACT_READ') {
    // The whole-contract read picks the same gap a clause mark calls "Not covered by your
    // playbook"; the header says the same, never the older "Policy unavailable" (DW-070).
    return 'Not covered by your playbook'
  }
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
 * The typed approval rule behind a finding, as the panel shows it. Council §A.3: an approval
 * exists only when a rule names an approver, so this is null when no rule applies, whatever the
 * finding's trigger text says.
 */
export type ApprovalSummary = {
  approver: string
  /** The rule's own condition, shown after "Escalate if". */
  condition: string | null
  source: string | null
  stance: 'FALLBACK' | 'PROHIBITED' | null
  /** A lower-level rule naming a different approver for the same stance. */
  conflict: { approver: string; source: string | null } | null
}

export function approvalSummary(quality: FindingQuality | null): ApprovalSummary | null {
  const approver = quality?.escalationApprover?.trim()
  if (!quality || !approver) return null
  const stance =
    quality.escalationStance === 'PROHIBITED' || quality.escalationStance === 'FALLBACK'
      ? quality.escalationStance
      : null
  return {
    // the panel opens a sentence with the approver ("General counsel approves this fallback")
    approver: approver.charAt(0).toUpperCase() + approver.slice(1),
    condition: quality.escalationCondition?.trim() || null,
    source: quality.escalationSource?.trim() || null,
    stance,
    conflict: quality.escalationConflictApprover
      ? {
          approver: quality.escalationConflictApprover.trim(),
          source: quality.escalationConflictSource?.trim() || null,
        }
      : null,
  }
}

/** The one line that shows a lower-level rule naming another approver (council §A.2). */
export function approvalConflictLine(summary: ApprovalSummary | null): string | null {
  if (!summary?.conflict) return null
  const { approver, source } = summary.conflict
  if (source === 'MATRIX') return `The approval matrix names ${approver} for this.`
  if (source === 'TABLE') return `The approval table names ${approver} for this.`
  return `The entry names ${approver} for this.`
}

/** The rule's condition, in the council's own "Escalate if" form. */
export function escalateIfLine(summary: ApprovalSummary | null): string | null {
  return summary?.condition ? `Escalate if: ${summary.condition}.` : null
}

/**
 * A source condition the playbook states without naming who approves it. The panel shows it,
 * followed by "No approver is named.", so it never reads as a broken approval (council §E).
 * An "Escalate to <role>" route is not a condition: only the part after a boundary word is,
 * and a route without one yields nothing.
 */
export function escalateIfText(trigger: string | null | undefined): string | null {
  const text = trigger?.trim()
  if (!text) return null
  const routed = /^escalate\s+to\b/i.test(text)
  const withoutVerb = text
    .replace(/^escalate\b[\s.,;:]*/i, '')
    .replace(/[.;,]+$/, '')
    .trim()
  if (!routed) {
    return withoutVerb.length > 0 ? withoutVerb : null
  }
  const boundary = withoutVerb.match(/\b(before|if|when|where|unless|on|for|after|once)\b/i)
  if (!boundary || boundary.index === undefined) return null
  const condition = withoutVerb
    .slice(boundary.index + boundary[0].length)
    .replace(/^[\s,;:.]+/, '')
    .replace(/[.;,]+$/, '')
    .trim()
  return condition.length > 0 ? condition : null
}

/** Where the approval rule was stated, in words a reader can check. */
export function approvalSourceLabel(source: string | null): string | null {
  if (source === 'ENTRY') return 'stated on this entry'
  if (source === 'MATRIX') return 'from the approval matrix'
  if (source === 'TABLE') return 'from the approval table'
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

/** The sentence used wherever a review's frozen playbook set is empty. */
export const NO_PLAYBOOK_COVERS =
  'No playbook covers this contract type, so this is not a policy review. Rivell flagged only clauses that read as unusual. Read the whole contract yourself.'

/**
 * The names of the playbooks a review actually froze. Null means the review predates the freeze,
 * so there is nothing honest to show; an empty list is a real answer.
 */
export function playbooksUsedText(
  used: readonly Pick<PlaybookUsed, 'name'>[] | null,
): string | null {
  if (used === null) return null
  return used.length === 0 ? NO_PLAYBOOK_COVERS : used.map((playbook) => playbook.name).join(', ')
}
