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
    { concept: 'CONFIDENTIALITY', expectation: 'EXPECTED', coverage: 'PRESENT_PRIMARY' },
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
      checkKey: 'LIABILITY_CAP:LIMITATION_OF_LIABILITY',
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

/** What the server groups from a finding whose one policy item carries both roles and an escalation. */
export const fixtureFullPositionComparison = {
  contract: [contractPositionSlot],
  preferred: [policyPositionSlot('e-policy')],
  fallback: [policyPositionSlot('e-policy')],
  escalation: [escalationSlot('e-policy')],
}

export const fixtureClauseFindingSummary = {
  id: 'f-1',
  subjectKind: 'CLAUSE',
  subjectHeadingNodeId: 'node-6',
  absenceConcept: null,
  concept: 'LIMITATION_OF_LIABILITY',
  secondaryConcept: null,
  issueFamily: 'LIABILITY_CAP_DEVIATION',
  observationKind: 'CAP_ABOVE_PREFERRED',
  severity: 'HIGH',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'USE_APPROVED_FALLBACK',
  proposedLanguageSource: 'APPROVED_DRAFTING',
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
  createdAt: '2026-09-25T09:01:10Z',
}

export const fixtureFindingList = {
  items: [fixtureClauseFindingSummary, fixtureAbsenceFindingSummary],
  truncated: false,
}

const clauseClaims = [
  {
    ordinal: 0,
    claimKind: 'OBSERVATION',
    text:
      'The liability cap of three times the fees paid is above the organization’s preferred position.',
    absence: false,
    evidenceItemIds: ['e-contract', 'e-policy'],
  },
  {
    ordinal: 1,
    claimKind: 'POLICY_BASIS',
    text: 'Published policy prefers a cap of two times the fees paid over a twelve-month period.',
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
  observationText:
    'The bounded classification found the liability cap above the organization’s preferred position (cap multiple 3x).',
  proposedLanguage:
    'The total liability of Supplier shall not exceed two times the fees paid in the twelve months preceding the claim.',
  approvedDraftingEvidenceId: 'e-drafting',
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: 'VERIFIED',
    degradationFlags: [],
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
      text: 'Historical precedent is evidence only and does not establish organizational policy.',
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
  positionComparison: { contract: [contractPositionSlot], preferred: [], fallback: [], escalation: [] },
}

export const fixtureAmbiguousPolicyFindingDetail = {
  ...fixturePolicyMissingFindingDetail,
  id: 'f-4',
  issueFamily: 'POLICY_AMBIGUOUS',
  observationKind: 'AMBIGUOUS_POLICY',
  observationText:
    'Multiple conflicting published policies apply to LIMITATION_OF_LIABILITY; no rule was chosen and the ambiguity is preserved.',
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
      text: 'The applicable policies disagree; no rule was chosen.',
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
  concept: 'CONFIDENTIALITY',
  concepts: ['CONFIDENTIALITY'],
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
  positionComparison: { contract: [contractPositionSlot], preferred: [], fallback: [], escalation: [] },
}
