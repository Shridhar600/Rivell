import type { ApprovedChangeSet, HumanDecision, HumanReview } from '../../api/workflow'
import type { FindingDetail, FindingSummary } from '../../api/reviews'

export const fixtureAcceptableFinding = {
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
  createdAt: '2026-09-26T09:01:10Z',
} satisfies FindingSummary

export const fixtureAcceptableFindingDetail = {
  ...fixtureAcceptableFinding,
  reviewId: 'r-1',
  runId: 'run-1',
  concepts: ['LIMITATION_OF_LIABILITY'],
  observationText: 'The liability cap is above the preferred position.',
  proposedLanguage: 'Liability is capped at two times the fees paid.',
  approvedDraftingEvidenceId: 'e-drafting',
  quality: { sufficiency: 'SUFFICIENT', verificationOutcome: 'VERIFIED', degradationFlags: [] },
  claims: [],
  evidenceItems: [],
  evidenceItemIds: [],
  signalIds: [],
  absenceBasis: null,
} satisfies FindingDetail

export const fixtureGeneratedFindingDetail = {
  ...fixtureAcceptableFindingDetail,
  id: 'f-5',
  proposedAction: 'CONSIDER_GENERATED_LANGUAGE',
  proposedLanguageSource: 'GENERATIVE',
  proposedLanguage: 'Neither party shall exceed the fees paid in the preceding twelve months.',
  approvedDraftingEvidenceId: null,
} satisfies FindingDetail

export const fixtureAbsenceFindingForDecision = {
  ...fixtureAcceptableFindingDetail,
  id: 'f-2',
  subjectKind: 'ABSENCE',
  subjectHeadingNodeId: null,
  absenceConcept: 'LIMITATION_OF_LIABILITY',
  reviewClassification: 'BLOCKING',
  proposedAction: 'ADD_REQUIRED_PROVISION',
  proposedLanguage: null,
  proposedLanguageSource: 'NONE',
  approvedDraftingEvidenceId: null,
  absenceBasis: { planCheckId: 'pc-1', checkKey: 'PRESENCE:LIMITATION_OF_LIABILITY' },
} satisfies FindingDetail

export const fixtureInformationalFindingDetail = {
  ...fixtureAcceptableFindingDetail,
  id: 'f-7',
  reviewClassification: 'INFORMATIONAL',
  proposedAction: 'NO_ACTION',
  proposedLanguage: null,
  proposedLanguageSource: 'NONE',
  approvedDraftingEvidenceId: null,
} satisfies FindingDetail

export function fixtureDecision(overrides: Partial<HumanDecision> = {}): HumanDecision {
  return {
    id: 'd-1',
    findingId: 'f-1',
    revision: 1,
    action: 'ACCEPT',
    approvedAction: 'REPLACE',
    approvedLanguage: 'Liability is capped at two times the fees paid.',
    languageSource: 'APPROVED_DRAFTING',
    approvedDraftingEvidenceId: 'e-drafting',
    comment: null,
    actorId: 'u-1',
    decidedAt: '2026-09-26T09:10:00Z',
    clientRequestId: 'cr-1',
    supersedesDecisionId: null,
    ...overrides,
  }
}

export function fixtureHumanReview(overrides: Partial<HumanReview> = {}): HumanReview {
  return {
    reviewId: 'r-1',
    status: 'IN_PROGRESS',
    analysisStatus: 'COMPLETED',
    actionableFindings: 1,
    resolvedFindings: 0,
    remainingFindings: 1,
    accepted: 0,
    edited: 0,
    rejected: 0,
    escalated: 0,
    informationalFindings: 0,
    informationalDecided: 0,
    readyToFinalize: false,
    blockers: [
      {
        code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
        message: '1 finding that requires review still needs a decision.',
        findingIds: ['f-1'],
      },
    ],
    changeSetId: null,
    finalizedBy: null,
    finalizedAt: null,
    ...overrides,
  }
}

export function fixtureChangeSet(
  overrides: Partial<ApprovedChangeSet> = {},
): ApprovedChangeSet {
  return {
    id: 'cs-1',
    reviewId: 'r-1',
    contractId: 'c1',
    contractVersionNumber: 1,
    runId: 'run-1',
    changeSetVersion: 1,
    finalizedBy: 'u-1',
    finalizedAt: '2026-09-26T11:00:00Z',
    changes: [
      {
        id: 'ch-1',
        findingId: 'f-1',
        decisionId: 'd-2',
        ordinal: 1,
        actionType: 'REPLACE',
        targetKind: 'CLAUSE',
        targetHeadingNodeId: 'node-6',
        absenceConcept: null,
        approvedLanguage: 'Liability is capped at two times the fees paid.',
        languageSource: 'HUMAN_EDIT',
        approvedDraftingEvidenceId: null,
      },
    ],
    ...overrides,
  }
}
