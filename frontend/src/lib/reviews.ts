import type {
  AnalysisWarning,
  ClauseMark,
  FindingClaim,
  FindingDetail,
  FindingSummary,
  PlaybookCoverageEntry,
  PlaybookUsed,
  ReviewCoverage,
  ReviewFailure,
  ReviewSummary,
  FindingQuality,
} from '../api/reviews'
import { humanizeCode } from './domainLabels'
import { CONTRACT_TYPE_LABELS } from './contractSemantics'
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

const FALLBACK_CONDITION_STATUS_LABELS: Record<string, string> = {
  MET: 'Stated',
  UNMET: 'Not met',
  NOT_STATED: 'Not stated',
  UNKNOWN: 'Could not check',
}

export function fallbackConditionStatusLabel(status: string): string {
  return FALLBACK_CONDITION_STATUS_LABELS[status] ?? humanizeCode(status)
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

const PLAYBOOK_COVERAGE_STATE_LABELS: Record<string, string> = {
  COMPARED: 'Compared',
  NOT_FOUND: 'Not found in this contract',
  NOT_COMPARED: 'Not compared',
  OUT_OF_SCOPE: 'Out of scope',
}

export function playbookCoverageStateLabel(state: string): string {
  return PLAYBOOK_COVERAGE_STATE_LABELS[state] ?? 'Not compared'
}

const PLAYBOOK_COVERAGE_VERDICT_LABELS: Record<string, string> = {
  MEETS_PREFERRED: 'Meets preferred',
  WITHIN_FALLBACK: 'Within fallback',
  PARTLY_WITHIN_FALLBACK: 'Partly within fallback',
  BEYOND_FALLBACK: 'Beyond fallback',
  WALK_AWAY: 'Walk-away',
  UNCLEAR: 'Unclear',
}

export function playbookCoverageVerdictLabel(verdict: string | null): string | null {
  if (!verdict) return null
  return PLAYBOOK_COVERAGE_VERDICT_LABELS[verdict] ?? null
}

const PLAYBOOK_COVERAGE_REASON_LABELS: Record<string, string> = {
  NOT_CHECKED: 'Not checked',
  FOUND_NOT_JUDGED: 'Found in a clause, not judged',
  CHECK_DID_NOT_COMPLETE: 'A check did not complete',
}

export function playbookCoverageReasonLabel(reason: string | null): string {
  if (!reason) return 'Not checked'
  return PLAYBOOK_COVERAGE_REASON_LABELS[reason] ?? 'Not checked'
}

/**
 * The one-line count of what the run did with its playbook positions. It names every state,
 * including zero, so a reader cannot read "0 not found" as "everything was checked".
 */
export function playbookCoverageSummary(
  entries: readonly PlaybookCoverageEntry[],
): string {
  const count = (state: PlaybookCoverageEntry['state']) =>
    entries.filter((entry) => entry.state === state).length
  const noun = entries.length === 1 ? 'position' : 'positions'
  return `${entries.length} ${noun}: ${count('COMPARED')} compared, ${count('NOT_FOUND')} not found, ${count('NOT_COMPARED')} not compared, ${count('OUT_OF_SCOPE')} out of scope.`
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
    'Depends on terms outside this contract; check them before deciding.',
  )
  text = text.replace(
    /The clause depends on something outside the contract, so Rivell cannot tell whether it meets your playbook\./gi,
    'Depends on terms outside this contract; check them before deciding.',
  )
  text = text.replace(
    /No playbook position addresses this clause, and it reads as unusual for this kind of contract(?: and against our side)?\.?/gi,
    'No playbook position covers this clause. It may be unusual for this kind of contract; check it.',
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
  /** Why a narrower position still governs this contract, or null when no reason needs saying. */
  appliesTo: string | null
}

/** Contract types a child agreement is made under. */
const CONTRACT_TYPE_PARENTS: Record<string, string[]> = {
  DPA: ['MSA'],
  SOW: ['MSA'],
  ORDER_FORM: ['MSA', 'SAAS'],
  AMENDMENT: ['MSA'],
}

/** Child types as a sentence names them, where the short label would read as a code. */
const CONTRACT_TYPE_SENTENCE_LABELS: Record<string, string> = {
  DPA: 'data processing agreement',
  SOW: 'statement of work',
  ORDER_FORM: 'order form',
  AMENDMENT: 'amendment',
}

const PARENT_PHRASES: Record<string, string> = {
  MSA: 'an MSA',
  SAAS: 'a SaaS',
}

/**
 * Why a position whose own "Applies to" is narrower than its playbook still governs this
 * contract: either the playbook declares more types than the position, or the contract type
 * reaches the position through the parent agreement it sits under.
 */
function appliesToLine(
  scope:
    | { declaredContractTypes: string[]; sourceContractTypes: string[] }
    | null
    | undefined,
  contractType: string | null,
): string | null {
  if (!scope || scope.sourceContractTypes.length === 0) return null
  const own = scope.sourceContractTypes
  const declared = scope.declaredContractTypes
  const narrower = declared.length === 0 || declared.some((type) => !own.includes(type))
  const parent = contractType
    ? (CONTRACT_TYPE_PARENTS[contractType] ?? []).find((type) => own.includes(type))
    : undefined
  const throughParent =
    parent !== undefined && contractType !== null && !own.includes(contractType)
  if (!narrower && !throughParent) return null
  const types = own.map((type) => CONTRACT_TYPE_LABELS[type] ?? humanizeCode(type)).join(', ')
  if (!throughParent) return `Applies to ${types}.`
  const parentPhrase =
    PARENT_PHRASES[parent] ?? `the ${CONTRACT_TYPE_LABELS[parent] ?? humanizeCode(parent)}`
  const contract =
    CONTRACT_TYPE_SENTENCE_LABELS[contractType] ??
    (CONTRACT_TYPE_LABELS[contractType] ?? humanizeCode(contractType)).toLowerCase()
  return `Applies to ${types}; used for this ${contract} because it sits under ${parentPhrase}.`
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
  contractType: string | null = null,
): FindingPlaybookRule | null {
  const concept = finding.secondaryConcept ?? finding.concept
  const labelText = finding.positionLabel ?? (concept ? label(concept) : '')
  const omission = Boolean(finding.positionLabel)
  if (!finding.issueFamily.startsWith('POSITION_') && !omission) return null
  const position = finding.positionComparison
  if (!position) {
    return omission && labelText
      ? { label: labelText, text: null, basedOn: null, escalation: null, appliesTo: null }
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
      ? { label: labelText, text: null, basedOn: null, escalation: null, appliesTo: null }
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
  const scope = finding.evidenceItems.find(
    (item) => item.evidenceId === chosen[0].evidenceItemId,
  )?.applicabilityScope
  return {
    label: labelText,
    text: texts.length > 0 ? texts.join('\n\n') : null,
    basedOn: playbook && version !== null ? `${playbook.name}, version ${version}` : null,
    escalation,
    appliesTo: appliesToLine(scope, contractType),
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
 * read's advisory is the same gap said in different words, so it uses the same line.
 * A partly-met fallback is not simply within it, so it says so wherever the line appears.
 */
export function findingIssueLabel(
  finding: Pick<FindingSummary, 'issueFamily' | 'observationKind'> & {
    partlyWithinFallback?: boolean
    quality?: FindingQuality | null
  },
): string {
  if (finding.partlyWithinFallback || finding.quality?.fallbackConditionsOutcome === 'PARTIAL') {
    return 'Partly within your fallback'
  }
  if (finding.observationKind === 'NOT_COVERED_BY_PLAYBOOK') {
    return observationKindLabel(finding.observationKind)
  }
  if (finding.observationKind === 'SPOTTED_IN_WHOLE_CONTRACT_READ') {
    // The whole-contract read picks the same gap a clause mark calls "Not covered by your
    // playbook"; the header says the same, never the older "Policy unavailable".
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
 * The typed approval rule behind a finding, as the panel shows it. An approval
 * exists only when a rule names an approver, so this is null when no rule applies, whatever the
 * finding's trigger text says.
 */
export type ApprovalSummary = {
  approver: string
  /**
   * The rule's own condition: an escalation trigger for a walk-away, an acceptance condition
   * for a fallback.
   */
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
    // the panel names the approver in the approval line ("Approval required: General counsel")
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

/**
 * What the clause departs from, so the approval line names the departure and not the rule's
 * stance: a fallback rule also covers a clause beyond the fallback.
 */
export function approvalDeparture(
  issueFamily: string,
  summary: ApprovalSummary | null,
): string | null {
  if (!summary) return null
  switch (issueFamily) {
    case 'POSITION_WALK_AWAY':
      return 'for this walk-away'
    case 'POSITION_NEEDS_FALLBACK':
      return 'for a clause beyond the fallback'
    case 'POSITION_ACCEPTABLE_FALLBACK':
      return 'for this fallback'
    default:
      return summary.stance === 'PROHIBITED' ? 'for this walk-away' : 'for this clause'
  }
}

/** The one line that shows a lower-level rule naming another approver. */
export function approvalConflictLine(summary: ApprovalSummary | null): string | null {
  if (!summary?.conflict) return null
  const { approver, source } = summary.conflict
  if (source === 'MATRIX') return `The approval matrix names ${approver} for this.`
  if (source === 'TABLE') return `The approval table names ${approver} for this.`
  return `The entry names ${approver} for this.`
}

/**
 * The rule's condition. A walk-away rule's condition is an escalation trigger; a fallback
 * rule's condition is the acceptance condition the source puts on taking the fallback, so it
 * is never labelled "Escalate if".
 */
export function escalateIfLine(summary: ApprovalSummary | null): string | null {
  if (!summary?.condition) return null
  return summary.stance === 'FALLBACK'
    ? `Approval applies if: ${summary.condition}.`
    : `Escalate if: ${summary.condition}.`
}

/**
 * A source condition the playbook states without naming who approves it. The panel shows it,
 * followed by "No approver is named.", so it never reads as a broken approval.
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

/** The sentence used wherever a review's frozen knowledge holds no playbook. */
export const NO_PLAYBOOK_NOTICE =
  'No playbook applied. General scan only; review the full contract.'

/** A clause library or template offers wording; only a playbook is policy the review is judged against. */
export function withoutPlaybook(used: readonly PlaybookUsed[] | null | undefined): boolean {
  return used != null && !used.some((item) => item.artifactType === 'PLAYBOOK')
}

const KNOWLEDGE_KIND_LABELS: Record<string, string> = {
  PLAYBOOK: 'Playbook',
  CLAUSE_LIBRARY: 'Clause library',
  TEMPLATE: 'Template',
  EXECUTED_PRECEDENT: 'Signed precedent',
  NEGOTIATION_HISTORY: 'Negotiation history',
  EXTERNAL_REFERENCE: 'External reference',
}

/**
 * The knowledge a review actually froze, each with the kind of source it is. Null means the
 * review predates the freeze, so there is nothing honest to show; an empty list is a real answer.
 */
export function knowledgeUsedText(
  used: readonly PlaybookUsed[] | null,
): string | null {
  if (used === null) return null
  if (used.length === 0) return NO_PLAYBOOK_NOTICE
  const names = used
    .map((item) => {
      const kind = KNOWLEDGE_KIND_LABELS[item.artifactType]
      return kind ? `${item.name} (${kind})` : item.name
    })
    .join(', ')
  return withoutPlaybook(used) ? `${names}. ${NO_PLAYBOOK_NOTICE}` : names
}
