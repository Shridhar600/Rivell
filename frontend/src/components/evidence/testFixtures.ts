import type {
  ApplicabilityScope,
  EvidenceBundle,
  EvidenceItem,
  RetrievalTrace,
} from '../../api/retrieval'

export function fixtureApplicabilityScope(
  overrides: Partial<ApplicabilityScope> = {},
): ApplicabilityScope {
  return {
    contractPackKey: null,
    declaredContractTypes: [],
    partyPerspective: null,
    paperSource: null,
    jurisdiction: null,
    effectiveFrom: null,
    effectiveTo: null,
    ...overrides,
  }
}

function item(partial: Partial<EvidenceItem> & { evidenceId: string }): EvidenceItem {
  return {
    authorityClass: 'POLICY',
    applicabilityScope: null,
    sourceType: 'PUBLISHED_PLAYBOOK_RULE',
    sourceArtifactId: null,
    sourceCandidateId: null,
    sourceContractVersionId: null,
    sourceClauseId: null,
    legalConcept: 'LIMITATION_OF_LIABILITY',
    text: 'text',
    policyFacts: null,
    contractFacts: [],
    retrievalReason: 'STRUCTURED',
    laneInfo: { lexicalRank: null, vectorRank: null, fusedRank: null, fusedScore: null },
    semanticAssessment: null,
    policyCompatibility: null,
    provenanceNodeIds: [],
    publicationId: null,
    publicationNumber: null,
    ...partial,
  }
}

export const fixturePolicyItem = item({
  evidenceId: 'e-policy',
  authorityClass: 'POLICY',
  sourceType: 'PUBLISHED_PLAYBOOK_RULE',
  sourceArtifactId: 'a-playbook',
  sourceCandidateId: 'k-rule',
  applicabilityScope: fixtureApplicabilityScope({
    contractPackKey: 'MSA_PACK',
    declaredContractTypes: ['MSA'],
    partyPerspective: 'CUSTOMER',
    paperSource: 'COUNTERPARTY_PAPER',
    jurisdiction: 'UK',
    effectiveFrom: '2026-01-01',
  }),
  text: 'Liability must be capped at fees paid in the preceding 12 months.',
  policyFacts: {
    positions: ['FALLBACK', 'PREFERRED'],
    requiresApproval: true,
    escalationTrigger: 'Uncapped liability or consequential damages',
    authorityRules: [
      {
        approver: 'Commercial Manager',
        stances: ['FALLBACK'],
        condition: null,
        source: 'MATRIX',
        quote: null,
      },
    ],
    authorityConflicts: [],
  },
  policyCompatibility: 'NOT_ASSESSED',
  publicationId: 'pub-2',
  publicationNumber: 2,
})

export const fixtureDraftingItem = item({
  evidenceId: 'e-drafting',
  authorityClass: 'APPROVED_DRAFTING',
  sourceType: 'PUBLISHED_CLAUSE_VARIANT',
  sourceArtifactId: 'a-clauses',
  sourceCandidateId: 'k-variant',
  applicabilityScope: fixtureApplicabilityScope({ contractPackKey: 'MSA_PACK' }),
  text: 'The aggregate liability of each party shall not exceed the fees paid in the twelve months preceding the claim.',
  policyCompatibility: 'NOT_ASSESSED',
  publicationId: 'pub-2',
  publicationNumber: 2,
})

export const fixtureContractItem = item({
  evidenceId: 'e-contract',
  authorityClass: 'CURRENT_CONTRACT',
  sourceType: 'CURRENT_CONTRACT_CLAUSE',
  sourceContractVersionId: 'v-1',
  sourceClauseId: 'cl-6',
  text: `6. Limitation of Liability. ${'Subject to the provisions of this Agreement, '.repeat(8)}the aggregate liability shall not exceed the fees paid.`,
  contractFacts: [
    {
      factSchema: 'liability-facts',
      schemaVersion: 1,
      values: { liabilityCapMultiple: '3', capBasis: 'PRECEDING_12_MONTH_FEES' },
    },
  ],
  retrievalReason: 'CANONICAL_STRUCTURE',
  provenanceNodeIds: ['node-6', 'node-7'],
})

export const fixtureRelatedContractItem = item({
  evidenceId: 'e-related',
  authorityClass: 'CURRENT_CONTRACT',
  sourceType: 'CURRENT_CONTRACT_CLAUSE',
  sourceContractVersionId: 'v-1',
  sourceClauseId: 'cl-3',
  text: '3. Fees. Fees are invoiced monthly and payable within 30 days.',
  retrievalReason: 'SEMANTIC_RELATIONSHIP:DEPENDS_ON',
  provenanceNodeIds: ['node-3'],
})

export const fixtureDefinedTermItem = item({
  evidenceId: 'e-term',
  authorityClass: 'CURRENT_CONTRACT',
  sourceType: 'CURRENT_CONTRACT_DEFINED_TERM',
  sourceContractVersionId: 'v-1',
  legalConcept: null,
  text: 'Services means the managed cloud services described in Schedule A.',
  retrievalReason: 'DEFINED_TERM',
  provenanceNodeIds: ['node-1'],
})

export const fixtureCompatiblePrecedent = item({
  evidenceId: 'e-precedent-1',
  authorityClass: 'PRECEDENT',
  sourceType: 'PUBLISHED_PRECEDENT',
  sourceArtifactId: 'a-precedent-1',
  sourceCandidateId: 'k-precedent-1',
  applicabilityScope: fixtureApplicabilityScope({
    declaredContractTypes: ['MSA'],
    partyPerspective: 'CUSTOMER',
    paperSource: 'THIRD_PARTY',
    jurisdiction: 'US',
  }),
  text: `The aggregate liability of either party under this Agreement is limited to ${'the fees paid during the preceding twelve months '.repeat(3)}and excludes consequential damages.`,
  retrievalReason: 'FUSION',
  laneInfo: { lexicalRank: 2, vectorRank: 1, fusedRank: 1, fusedScore: 0.0328 },
  semanticAssessment: {
    judgments: { sameLegalIssue: true, sameClauseFunction: true, comparableContext: false },
    decisionSpecId: 'm6-precedent-relevance-1',
    decisionSpecVersion: 1,
  },
  policyCompatibility: 'COMPATIBLE',
  publicationId: 'pub-2',
  publicationNumber: 2,
})

export const fixtureIncompatiblePrecedent = item({
  evidenceId: 'e-precedent-2',
  authorityClass: 'PRECEDENT',
  sourceType: 'PUBLISHED_PRECEDENT',
  sourceArtifactId: 'a-precedent-2',
  sourceCandidateId: 'k-precedent-2',
  text: 'Liability under this Agreement is unlimited.',
  retrievalReason: 'LEXICAL',
  laneInfo: { lexicalRank: 1, vectorRank: null, fusedRank: 2, fusedScore: 0.0164 },
  policyCompatibility: 'INCOMPATIBLE',
  publicationId: 'pub-2',
  publicationNumber: 2,
})

export function fixtureBundle(overrides: Partial<EvidenceBundle> = {}): EvidenceBundle {
  return {
    bundleId: 'b-1',
    request: {
      purpose: 'CLAUSE_REVIEW',
      legalConcept: 'LIMITATION_OF_LIABILITY',
      contractId: 'c-1',
      versionNumber: 1,
      clauseId: 'cl-6',
      effectiveOn: '2026-09-24',
      knowledgePinPublicationNumber: null,
    },
    items: [
      fixturePolicyItem,
      fixtureDraftingItem,
      fixtureContractItem,
      fixtureRelatedContractItem,
      fixtureDefinedTermItem,
      fixtureCompatiblePrecedent,
      fixtureIncompatiblePrecedent,
    ],
    sufficiency: 'SUFFICIENT',
    traceId: 't-1',
    degradationWarnings: [],
    ...overrides,
  }
}

export const fixtureClauseHeadings = new Map<string, string>([
  ['cl-6', 'node-6'],
  ['cl-3', 'node-3'],
])

export const fixtureRegionLabels = new Map<string, string>([
  ['node-1', '1. Definitions'],
  ['node-3', '3. Fees'],
  ['node-6', '6. Limitation of Liability'],
  ['node-7', '6.2 Cap'],
])

export const fixtureTrace: RetrievalTrace = {
  id: 't-1',
  purpose: 'CLAUSE_REVIEW',
  request: {
    purpose: 'CLAUSE_REVIEW',
    legalConcept: 'LIMITATION_OF_LIABILITY',
    effectiveOn: '2026-09-24',
    knowledgePin: null,
    resolvedAsOfPublication: 2,
    reviewProfile: {
      contractType: 'MSA',
      ourSideRole: 'CUSTOMER',
      paperSource: 'COUNTERPARTY_PAPER',
      jurisdiction: 'UK',
    },
    clauseText: '6. Limitation of Liability…',
  },
  stages: {
    policy: {
      stage: 'policy',
      candidateCount: 1,
      candidateIds: ['k-rule'],
      candidateRanks: [],
      executionIds: [],
    },
    lexical: {
      stage: 'lexical',
      candidateCount: 2,
      candidateIds: ['k-precedent-2', 'k-precedent-1'],
      candidateRanks: [1, 2],
      executionIds: [],
    },
    fusion: {
      stage: 'fusion',
      candidateCount: 2,
      candidateIds: ['k-precedent-1', 'k-precedent-2'],
      candidateRanks: [1, 2],
      executionIds: [],
    },
    rerank: {
      stage: 'rerank',
      candidateCount: 1,
      candidateIds: ['k-precedent-1'],
      candidateRanks: [],
      executionIds: ['exec-1'],
    },
  },
  selectedEvidenceIds: ['e-policy', 'e-contract', 'e-precedent-1'],
  rejectedEvidence: [
    { candidateId: 'k-precedent-3', stage: 'rerank', reason: 'different legal issue' },
  ],
  degradation: ['INDEX_INCOMPLETE'],
  stageTimings: { pinResolution: 4, structured: 12, precedent: 240, currentContract: 8, totalMs: 300 },
  modelVersions: {
    'spec:m6-precedent-relevance-1': 'spec:m6-precedent-relevance-1',
    embeddings: 'openai-compatible:embeddinggemma:768',
  },
  knowledgePinPublicationNumber: null,
}
