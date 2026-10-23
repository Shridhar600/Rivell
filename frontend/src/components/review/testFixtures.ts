export const fixturePackCatalog = {
  packs: [
    {
      key: 'MSA_PACK',
      displayName: 'MSA pack',
      summary: 'Liability, confidentiality and termination checks for master services agreements.',
      version: 'rivell-review-pack-1',
    },
    {
      key: 'NDA_PACK',
      displayName: 'NDA pack',
      summary: 'Confidentiality and term checks for non-disclosure agreements.',
      version: 'rivell-review-pack-1',
    },
  ],
}

export const fixtureQueuedReviewSummary = {
  id: 'r-1',
  contractId: 'c1',
  contractVersionNumber: 1,
  status: 'ANALYSIS_QUEUED',
  failure: null,
  createdAt: '2026-09-25T09:00:00Z',
  startedAt: null,
  completedAt: null,
  activeRun: null,
  latestRunStatus: null,
  plannedChecks: 3,
  completedChecks: 0,
  degradedChecks: 0,
  failedChecks: 0,
  signals: 0,
  findings: 0,
  proposedChanges: { replace: 0, insert: 0, delete: 0 },
  document: {
    contractFilename: 'msa.docx',
    uploadedAt: '2026-09-25T08:55:00Z',
    sizeBytes: 51200,
  },
  snapshot: {
    profile: {
      contractType: 'MSA',
      ourSideRole: 'CUSTOMER',
      paperSource: 'COUNTERPARTY_PAPER',
      jurisdiction: 'UK',
      contractPackKey: 'MSA_PACK',
    },
    packVersion: 'rivell-review-pack-1',
    knowledgePinNumber: 6,
    effectiveOn: '2026-09-25',
  },
  coverage: [
    { concept: 'LIMITATION_OF_LIABILITY', expectation: 'REQUIRED', coverage: 'PRESENT_PRIMARY' },
    { concept: 'CONFIDENTIALITY_OBLIGATIONS', expectation: 'EXPECTED', coverage: 'PRESENT_PRIMARY' },
  ],
  analysisWarnings: [],
  analysisWarningsTruncated: false,
}

export const fixtureAnalyzingReviewSummary = {
  ...fixtureQueuedReviewSummary,
  status: 'ANALYZING',
  startedAt: '2026-09-25T09:00:30Z',
  activeRun: { id: 'run-1', runNumber: 1, status: 'RUNNING' },
  latestRunStatus: 'RUNNING',
  completedChecks: 1,
}

export const fixtureCompletedReviewSummary = {
  ...fixtureQueuedReviewSummary,
  status: 'COMPLETED',
  startedAt: '2026-09-25T09:00:30Z',
  completedAt: '2026-09-25T09:01:10Z',
  activeRun: { id: 'run-1', runNumber: 1, status: 'COMPLETED' },
  latestRunStatus: 'COMPLETED',
  completedChecks: 2,
  degradedChecks: 1,
  signals: 5,
  findings: 2,
  proposedChanges: { replace: 1, insert: 1, delete: 0 },
  analysisWarnings: [
    {
      checkKey: 'POLICY_COMPARISON:LIMITATION_OF_LIABILITY',
      status: 'DEGRADED',
      reasonCode: 'PROVIDER_UNAVAILABLE',
      message: 'An external analysis provider was unavailable for this check.',
    },
  ],
}

export const fixtureFailedReviewSummary = {
  ...fixtureQueuedReviewSummary,
  status: 'FAILED',
  failure: {
    code: 'ANALYSIS_INTERNAL_ERROR',
    message: 'The automated review could not be completed.',
  },
  startedAt: '2026-09-25T09:00:30Z',
  completedAt: '2026-09-25T09:00:45Z',
  activeRun: null,
  latestRunStatus: 'FAILED',
  completedChecks: 2,
  failedChecks: 1,
}

export const fixtureCleanReviewSummary = {
  ...fixtureCompletedReviewSummary,
  signals: 0,
  findings: 0,
  degradedChecks: 0,
  analysisWarnings: [],
}

export const fixtureReviewList = {
  items: [
    {
      id: 'r-2',
      contractId: 'c1',
      contractVersionNumber: 1,
      status: 'ANALYZING',
      createdAt: '2026-09-25T10:00:00Z',
      completedAt: null,
    },
    {
      id: 'r-1',
      contractId: 'c1',
      contractVersionNumber: 1,
      status: 'COMPLETED',
      createdAt: '2026-09-25T09:00:00Z',
      completedAt: '2026-09-25T09:01:10Z',
    },
  ],
  truncated: false,
}

import {
  fixtureCompatiblePrecedent,
  fixtureContractItem,
  fixtureDraftingItem,
  fixturePolicyItem,
  fixtureRelatedContractItem,
} from '../evidence/testFixtures'

const contractPositionSlot = {
  evidenceItemId: 'e-contract',
  authorityClass: 'CURRENT_CONTRACT',
  legalConcept: 'LIMITATION_OF_LIABILITY',
  facts: fixtureContractItem.contractFacts,
}

function policyPositionSlot(evidenceItemId: string) {
  return {
    evidenceItemId,
    authorityClass: 'POLICY',
    legalConcept: 'LIMITATION_OF_LIABILITY',
    publicationNumber: 2,
    text: fixturePolicyItem.text,
  }
}

function escalationSlot(evidenceItemId: string) {
  return {
    evidenceItemId,
    authorityClass: 'POLICY',
    publicationNumber: 2,
    trigger: 'Uncapped liability or consequential damages',
  }
}

/** What the server groups from a finding whose one policy item carries every role and an escalation. */
export const fixtureFullPositionComparison = {
  contract: [contractPositionSlot],
  preferred: [policyPositionSlot('e-policy')],
  fallback: [policyPositionSlot('e-policy')],
  walkAway: [policyPositionSlot('e-policy')],
  escalation: [escalationSlot('e-policy')],
}

export const fixtureClauseFindingSummary = {
  id: 'f-1',
  subjectKind: 'CLAUSE',
  subjectHeadingNodeId: 'node-6',
  absenceConcept: null,
  concept: 'LIMITATION_OF_LIABILITY',
  secondaryConcept: null,
  issueFamily: 'POSITION_NEEDS_FALLBACK',
  observationKind: 'POSITION_BEYOND_FALLBACK',
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'USE_APPROVED_FALLBACK',
  proposedLanguageSource: 'APPROVED_DRAFTING',
  relatedPosition: false,
  createdAt: '2026-09-25T09:01:10Z',
}

export const fixtureAbsenceFindingSummary = {
  id: 'f-2',
  subjectKind: 'ABSENCE',
  subjectHeadingNodeId: null,
  absenceConcept: 'LIMITATION_OF_LIABILITY',
  concept: 'LIMITATION_OF_LIABILITY',
  secondaryConcept: null,
  issueFamily: 'MISSING_REQUIRED_PROVISION',
  observationKind: 'MISSING_REQUIRED_CONCEPT',
  severity: 'HIGH',
  reviewClassification: 'BLOCKING',
  proposedAction: 'ADD_REQUIRED_PROVISION',
  proposedLanguageSource: 'NONE',
  relatedPosition: false,
  createdAt: '2026-09-25T09:01:10Z',
}

/** A finding a reviewer added on a clause Rivell passed (P2b): no proposal, decided like any other. */
export const fixtureUserFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-user',
  subjectHeadingNodeId: 'node-1',
  concept: 'DEFINITIONS',
  issueFamily: 'USER_ADDED',
  observationKind: 'USER_ADDED',
  severity: 'HIGH',
  proposedAction: 'NO_ACTION',
  proposedLanguageSource: 'NONE',
  addedBy: 'u-1',
}

export const fixtureUserFindingDetail = {
  ...fixtureUserFindingSummary,
  reviewId: 'r-1',
  runId: 'run-1',
  concepts: ['DEFINITIONS'],
  observationText: 'The definition of Services has no carve-out for third-party software.',
  proposedLanguage: null,
  approvedDraftingEvidenceId: null,
  quality: {},
  claims: [],
  evidenceItems: [],
  evidenceItemIds: [],
  positionComparison: null,
  signalIds: [],
  absenceBasis: null,
}

/** Every mark once on the fixture contract; "Northwind Customer Group LLC" (node-10) is unplanned. */
export const fixtureClauseMarks = {
  runId: 'run-1',
  clauses: [
    { headingNodeId: 'node-1', mark: 'CHECKED' },
    { headingNodeId: 'node-5', mark: 'NOT_COVERED' },
    { headingNodeId: 'node-6', mark: 'FLAGGED' },
    { headingNodeId: 'node-9', mark: 'NOT_ANALYSED' },
  ],
}

export const fixtureFindingList = {
  items: [fixtureClauseFindingSummary, fixtureAbsenceFindingSummary],
  truncated: false,
}

const clauseClaims = [
  {
    ordinal: 0,
    claimKind: 'OBSERVATION',
    text: 'The clause is worse for us than every fallback the position lists.',
    absence: false,
    evidenceItemIds: ['e-contract', 'e-policy'],
  },
  {
    ordinal: 1,
    claimKind: 'POLICY_BASIS',
    text: 'Published policy lists a fallback at two times the fees paid over a twelve-month period.',
    absence: false,
    evidenceItemIds: ['e-policy'],
  },
  {
    ordinal: 2,
    claimKind: 'RECOMMENDATION',
    text: 'Replace the clause with the approved fallback wording for the liability cap.',
    absence: false,
    evidenceItemIds: ['e-drafting'],
  },
]

export const fixtureClauseFindingDetail = {
  ...fixtureClauseFindingSummary,
  reviewId: 'r-1',
  runId: 'run-1',
  concepts: ['LIMITATION_OF_LIABILITY'],
  observationText: 'The clause is worse for us than every fallback the position lists.',
  proposedLanguage:
    'The total liability of Supplier shall not exceed two times the fees paid in the twelve months preceding the claim.',
  approvedDraftingEvidenceId: 'e-drafting',
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: 'VERIFIED',
    degradationFlags: [],
    category: 'BEYOND_FALLBACK',
    flagProbability: 0.87,
    probabilities: { BEYOND_FALLBACK: 0.87, WALK_AWAY: 0.05 },
  },
  claims: clauseClaims,
  evidenceItems: [
    fixturePolicyItem,
    fixtureDraftingItem,
    { ...fixtureContractItem, sourceClauseId: 'cl6' },
    fixtureCompatiblePrecedent,
  ],
  evidenceItemIds: ['e-contract', 'e-policy', 'e-drafting', 'e-precedent-1'],
  positionComparison: fixtureFullPositionComparison,
  signalIds: ['s-1', 's-2', 's-3'],
  absenceBasis: null,
}

const withinFallbackComparison = {
  contract: [contractPositionSlot],
  preferred: [policyPositionSlot('e-policy')],
  fallback: [policyPositionSlot('e-policy')],
  walkAway: [],
  escalation: [],
}

export const fixtureWithinFallbackFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-7',
  issueFamily: 'POSITION_ACCEPTABLE_FALLBACK',
  observationKind: 'POSITION_WITHIN_FALLBACK',
  severity: 'LOW',
  reviewClassification: 'INFORMATIONAL',
  proposedAction: 'NO_ACTION',
  proposedLanguageSource: 'NONE',
}

/** A LOW informational finding: the clause stays within a fallback, so it reads as a note. */
export const fixtureWithinFallbackFindingDetail = {
  ...fixtureClauseFindingDetail,
  ...fixtureWithinFallbackFindingSummary,
  observationText:
    'The clause departs from the preferred position but stays within a listed fallback; accept and note.',
  proposedLanguage: null,
  approvedDraftingEvidenceId: null,
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: null,
    degradationFlags: [],
    category: 'WITHIN_FALLBACK',
    flagProbability: 0.74,
    probabilities: { WITHIN_FALLBACK: 0.74 },
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: 'The clause departs from the preferred position but stays within a listed fallback.',
      absence: false,
      evidenceItemIds: ['e-contract', 'e-policy'],
    },
  ],
  positionComparison: withinFallbackComparison,
}

/**
 * A LOW informational note whose position governs a topic the clause only touches. It must read as
 * a related position, never as a breach of the clause itself.
 */
export const fixtureRelatedPositionFindingSummary = {
  ...fixtureWithinFallbackFindingSummary,
  id: 'f-9',
  secondaryConcept: 'GOVERNING_LAW',
  issueFamily: 'POSITION_WALK_AWAY',
  observationKind: 'POSITION_WALK_AWAY',
  relatedPosition: true,
}

export const fixtureRelatedPositionFindingDetail = {
  ...fixtureWithinFallbackFindingDetail,
  ...fixtureRelatedPositionFindingSummary,
  observationText:
    'The clause contains or permits what the position’s walk-away says we must never accept.',
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: null,
    degradationFlags: [],
    category: 'WALK_AWAY',
    flagProbability: 0.9,
    probabilities: { WALK_AWAY: 0.9 },
    secondaryTopic: true,
  },
  positionComparison: {
    contract: [contractPositionSlot],
    preferred: [policyPositionSlot('e-policy')],
    fallback: [],
    walkAway: [policyPositionSlot('e-policy')],
    escalation: [],
  },
}

/** The same note, but the position names an approver: approval is needed and the trigger is shown. */
export const fixtureEscalatedFallbackFindingDetail = {
  ...fixtureWithinFallbackFindingDetail,
  id: 'f-8',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'ESCALATE_TO_HUMAN',
  positionComparison: { ...withinFallbackComparison, escalation: [escalationSlot('e-policy')] },
}

export const fixtureAbsenceFindingDetail = {
  ...fixtureAbsenceFindingSummary,
  reviewId: 'r-1',
  runId: 'run-1',
  concepts: ['LIMITATION_OF_LIABILITY'],
  observationText: 'The required provision LIMITATION_OF_LIABILITY is missing from this contract.',
  proposedLanguage: null,
  approvedDraftingEvidenceId: null,
  quality: { sufficiency: null, verificationOutcome: null, degradationFlags: [] },
  claims: [
    {
      ordinal: 0,
      claimKind: 'ABSENCE_BASIS',
      text: 'No limitation of liability provision was found in this contract.',
      absence: true,
      evidenceItemIds: [],
    },
  ],
  evidenceItems: [],
  evidenceItemIds: [],
  positionComparison: null,
  signalIds: ['s-4'],
  absenceBasis: { planCheckId: 'pc-1', checkKey: 'PRESENCE:LIMITATION_OF_LIABILITY' },
}

export const fixturePolicyMissingFindingDetail = {
  ...fixtureClauseFindingDetail,
  id: 'f-3',
  issueFamily: 'POLICY_UNAVAILABLE',
  observationKind: 'POLICY_UNAVAILABLE',
  observationText:
    'No applicable published policy exists for LIMITATION_OF_LIABILITY; no deviation judgment was made and precedent does not substitute for policy.',
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'ESCALATE_TO_HUMAN',
  proposedLanguage: null,
  proposedLanguageSource: 'NONE',
  approvedDraftingEvidenceId: null,
  quality: {
    sufficiency: 'POLICY_MISSING',
    verificationOutcome: null,
    degradationFlags: [],
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: 'No applicable published policy exists for this clause.',
      absence: false,
      evidenceItemIds: ['e-contract'],
    },
    {
      ordinal: 1,
      claimKind: 'EVIDENCE_BASIS',
      text: 'Your playbook has no position on this topic. Past contracts are shown as examples only, not as your position.',
      absence: false,
      evidenceItemIds: ['e-precedent-1'],
    },
    {
      ordinal: 2,
      claimKind: 'RECOMMENDATION',
      text: 'Human review is required because no organizational policy covers this clause.',
      absence: false,
      evidenceItemIds: ['e-contract'],
    },
  ],
  evidenceItems: [
    { ...fixtureContractItem, sourceClauseId: 'cl6' },
    fixtureCompatiblePrecedent,
  ],
  // The contract has typed facts, but no policy position exists to compare them with.
  positionComparison: { contract: [contractPositionSlot], preferred: [], fallback: [], walkAway: [], escalation: [] },
}

/** A clause no playbook position addresses, judged unusual and against our side. */
export const fixtureNotCoveredFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-10',
  issueFamily: 'POLICY_UNAVAILABLE',
  observationKind: 'NOT_COVERED_BY_PLAYBOOK',
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'ESCALATE_TO_HUMAN',
  proposedLanguageSource: 'NONE',
}

export const fixtureNotCoveredFindingDetail = {
  ...fixturePolicyMissingFindingDetail,
  ...fixtureNotCoveredFindingSummary,
  observationText:
    'No playbook position addresses this clause, and it reads as unusual for this kind of contract and against our side.',
  quality: {
    sufficiency: 'POLICY_MISSING',
    verificationOutcome: null,
    degradationFlags: [],
    uncoveredProbability: 0.9,
  },
}

export const fixtureNeedsYourLookFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-11',
  issueFamily: 'POSITION_UNCERTAIN',
  observationKind: 'UNCERTAINTY:second_opinion_disagrees',
  severity: 'LOW',
  proposedAction: 'ESCALATE_TO_HUMAN',
  proposedLanguageSource: 'NONE',
  decidedBy: 'NEEDS_YOUR_LOOK',
}

export const fixtureNeedsYourLookFindingDetail = {
  ...fixtureClauseFindingDetail,
  ...fixtureNeedsYourLookFindingSummary,
  observationText:
    'Rivell is unsure about this clause. Here is the clause and the closest rule.',
  proposedLanguage: null,
  approvedDraftingEvidenceId: null,
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: null,
    degradationFlags: [],
    route: 'NEEDS_YOUR_LOOK',
    jevCategory: 'BEYOND_FALLBACK',
    flagProbability: 0.68,
    probabilities: { BEYOND_FALLBACK: 0.62, MEETS_PREFERRED: 0.3 },
    secondOpinionOutcome: 'DISAGREED',
    secondOpinionCategory: 'MEETS_PREFERRED',
    secondOpinionQuote: 'liability shall not exceed the fees paid',
    secondOpinionReason: 'The cap matches the preferred basis in the position.',
  },
}

export const fixtureSecondOpinionFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-12',
  decidedBy: 'SECOND_OPINION',
}

export const fixtureSecondOpinionFindingDetail = {
  ...fixtureClauseFindingDetail,
  ...fixtureSecondOpinionFindingSummary,
  quality: {
    ...fixtureClauseFindingDetail.quality,
    route: 'SECOND_OPINION',
    secondOpinionOutcome: 'AGREED',
    secondOpinionCategory: 'BEYOND_FALLBACK',
  },
}

/** A "needs your look" item whose first review called a walk-away breach (engine v2, P4 D9). */
export const fixtureWalkAwayLookFindingSummary = {
  ...fixtureNeedsYourLookFindingSummary,
  id: 'f-13',
  firstReviewCategory: 'WALK_AWAY',
}

export const fixtureWalkAwayLookFindingDetail = {
  ...fixtureNeedsYourLookFindingDetail,
  ...fixtureWalkAwayLookFindingSummary,
  quality: { ...fixtureNeedsYourLookFindingDetail.quality, jevCategory: 'WALK_AWAY' },
}

/** The whole-contract read picked a clause the playbook says nothing about (engine v2, P4 D3). */
export const fixturePlaybookSilentFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-14',
  issueFamily: 'POLICY_UNAVAILABLE',
  observationKind: 'SPOTTED_IN_WHOLE_CONTRACT_READ',
  severity: 'LOW',
  proposedAction: 'ESCALATE_TO_HUMAN',
  proposedLanguageSource: 'NONE',
  decidedBy: 'NEEDS_YOUR_LOOK',
}

export const fixturePlaybookSilentFindingDetail = {
  ...fixtureClauseFindingDetail,
  ...fixturePlaybookSilentFindingSummary,
  observationText:
    'A whole-contract read named this clause as one our side must act on, but no playbook position check flagged it.',
  proposedLanguage: null,
  approvedDraftingEvidenceId: null,
  positionComparison: null,
  quality: {
    sufficiency: 'POLICY_MISSING',
    verificationOutcome: null,
    degradationFlags: ['playbook_silent'],
    route: 'NEEDS_YOUR_LOOK',
    uncertaintyReason: 'playbook_silent',
    uncoveredProbability: 0.12,
    spottedSeverity: 'HIGH',
    spottedReason: 'Only damages are available to us for a breach.',
  },
}

/** A playbook position no clause addresses, which the playbook requires (engine v2 omissions lane). */
export const fixtureOmissionFindingSummary = {
  ...fixtureAbsenceFindingSummary,
  id: 'f-15',
  observationKind: 'MISSING_POSITION',
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  positionLabel: 'Cap Exceptions',
  decidedBy: 'JEV',
}

export const fixtureOmissionFindingDetail = {
  ...fixtureAbsenceFindingDetail,
  ...fixtureOmissionFindingSummary,
  observationText: 'Missing: Cap Exceptions. Searched 5 clauses; none addresses it.',
  quality: {
    sufficiency: null,
    verificationOutcome: null,
    degradationFlags: [],
    route: 'JEV',
    searchedClauses: 5,
    comparedClauses: 2,
    presenceProbability: 0.92,
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'ABSENCE_BASIS',
      text: 'No clause in this contract addresses this playbook position; the absence is the observation.',
      absence: true,
      evidenceItemIds: [],
    },
  ],
  absenceBasis: { planCheckId: 'pc-9', checkKey: 'OMISSION' },
}

/** The same kind of omission, where Rivell is unsure the playbook requires the provision. */
export const fixtureOmissionUnsureFindingSummary = {
  ...fixtureOmissionFindingSummary,
  id: 'f-16',
  positionLabel: 'Data protection',
  severity: 'LOW',
  decidedBy: 'NEEDS_YOUR_LOOK',
}

export const fixtureOmissionUnsureFindingDetail = {
  ...fixtureOmissionFindingDetail,
  ...fixtureOmissionUnsureFindingSummary,
  observationText: 'Missing: Data protection. Searched 5 clauses; none addresses it.',
  quality: {
    ...fixtureOmissionFindingDetail.quality,
    degradationFlags: ['omission_unsure'],
    route: 'NEEDS_YOUR_LOOK',
    uncertaintyReason: 'omission_unsure',
    presenceProbability: 0.62,
  },
}

/** A clause no topic paired with a position, compared with a position a playbook search found. */
export const fixtureSearchFoundFindingSummary = {
  ...fixtureClauseFindingSummary,
  id: 'f-17',
  decidedBy: 'JEV',
}

export const fixtureSearchFoundFindingDetail = {
  ...fixtureClauseFindingDetail,
  ...fixtureSearchFoundFindingSummary,
  quality: { ...fixtureClauseFindingDetail.quality, route: 'JEV', foundBy: 'SEARCH' },
}

export const fixtureAmbiguousPolicyFindingDetail = {
  ...fixturePolicyMissingFindingDetail,
  id: 'f-4',
  issueFamily: 'POLICY_AMBIGUOUS',
  observationKind: 'AMBIGUOUS_POLICY',
  observationText:
    'More than one rule in your playbook applies to LIMITATION_OF_LIABILITY, and they conflict. Rivell did not choose between them.',
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'REVIEW_AMBIGUITY',
  quality: {
    sufficiency: 'AMBIGUOUS_POLICY',
    verificationOutcome: null,
    degradationFlags: [],
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: 'More than one published policy applies to this clause.',
      absence: false,
      evidenceItemIds: ['e-policy', 'e-policy-2'],
    },
    {
      ordinal: 1,
      claimKind: 'POLICY_BASIS',
      text: 'More than one playbook position applies to this topic and they conflict. Rivell did not choose between them.',
      absence: false,
      evidenceItemIds: ['e-policy', 'e-policy-2'],
    },
  ],
  evidenceItems: [fixturePolicyItem, { ...fixturePolicyItem, evidenceId: 'e-policy-2' }],
  // Two cited items hold each role. The server keeps both, in evidence order.
  positionComparison: {
    contract: [],
    preferred: [policyPositionSlot('e-policy'), policyPositionSlot('e-policy-2')],
    fallback: [policyPositionSlot('e-policy'), policyPositionSlot('e-policy-2')],
    walkAway: [],
    escalation: [escalationSlot('e-policy'), escalationSlot('e-policy-2')],
  },
}

export const fixtureGenerativeFindingDetail = {
  ...fixtureClauseFindingDetail,
  id: 'f-5',
  observationKind: 'OPEN_ENDED_ANALYSIS_RESULT',
  observationText:
    'The clause cannot be compared with policy and no approved drafting exists; an independently verified machine candidate is available for human review.',
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'CONSIDER_GENERATED_LANGUAGE',
  proposedLanguage: 'Neither party’s aggregate liability shall exceed the fees paid in the preceding twelve months.',
  proposedLanguageSource: 'GENERATIVE',
  approvedDraftingEvidenceId: null,
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: 'VERIFIED',
    degradationFlags: [],
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: 'The clause cannot be compared with the published policy position.',
      absence: false,
      evidenceItemIds: ['e-contract', 'e-policy'],
    },
    {
      ordinal: 1,
      claimKind: 'GENERATIVE_EXPLANATION',
      text: 'The proposed wording introduces a mutual cap tied to fees paid in the preceding twelve months.',
      absence: false,
      evidenceItemIds: ['e-contract', 'e-policy'],
    },
    {
      ordinal: 2,
      claimKind: 'RECOMMENDATION',
      text: 'Consider the AI-proposed wording; it is machine-proposed and not approved policy.',
      absence: false,
      evidenceItemIds: ['e-contract'],
    },
  ],
  evidenceItems: [
    { ...fixtureContractItem, sourceClauseId: 'cl6' },
    fixturePolicyItem,
  ],
}

export const fixtureCrossClauseFindingDetail = {
  ...fixtureClauseFindingDetail,
  id: 'f-6',
  concept: 'CONFIDENTIALITY_OBLIGATIONS',
  concepts: ['CONFIDENTIALITY_OBLIGATIONS'],
  issueFamily: 'SURVIVAL_COVERAGE_GAP',
  observationKind: 'SURVIVAL_COVERAGE_GAP',
  observationText: 'Confidentiality obligations are not covered by a survival provision.',
  severity: 'HIGH',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'NO_ACTION',
  proposedLanguage: null,
  proposedLanguageSource: 'NONE',
  approvedDraftingEvidenceId: null,
  quality: { sufficiency: 'SUFFICIENT', verificationOutcome: null, degradationFlags: [] },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: 'No survival provision covers the confidentiality obligations.',
      absence: false,
      evidenceItemIds: ['e-contract', 'e-related'],
    },
  ],
  evidenceItems: [
    { ...fixtureContractItem, sourceClauseId: 'cl6' },
    { ...fixtureRelatedContractItem, sourceClauseId: 'cl5', provenanceNodeIds: ['node-63'] },
  ],
  evidenceItemIds: ['e-contract', 'e-related'],
  positionComparison: { contract: [contractPositionSlot], preferred: [], fallback: [], walkAway: [], escalation: [] },
}
