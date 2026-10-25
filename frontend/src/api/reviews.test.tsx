import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import {
  createReview,
  fetchReviewPacks,
  parseFindingDetail,
  parseFindingList,
  parseReviewList,
  parseReviewProgress,
  parseReviewSummary,
  reviewErrorStatus,
  reviewKeys,
  reviewPollInterval,
  MAX_POLL_ATTEMPTS,
  REVIEW_POLL_INTERVAL_MS,
  useCreateReview,
  useFindingsQuery,
  useReviewProgressQuery,
  useReviewSummaryQuery,
} from './reviews'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-25T10:00:00Z' }
}

function stubFetch(handler: (request: Request) => Response) {
  const requests: Request[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(String(input), init)
    requests.push(request)
    return handler(request)
  })
  vi.stubGlobal('fetch', fetchMock)
  return requests
}

function createWrapper(mutationRetry = 0) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: mutationRetry },
    },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  return { queryClient, wrapper }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

const queuedSummary = {
  id: 'r-1',
  contractId: 'c-1',
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
  ],
  analysisWarnings: [],
  analysisWarningsTruncated: false,
}

const completedSummary = {
  ...queuedSummary,
  status: 'COMPLETED',
  startedAt: '2026-09-25T09:01:00Z',
  completedAt: '2026-09-25T09:02:00Z',
  activeRun: { id: 'run-1', runNumber: 1, status: 'COMPLETED' },
  latestRunStatus: 'COMPLETED',
  completedChecks: 2,
  degradedChecks: 1,
  signals: 5,
  findings: 2,
  analysisWarnings: [
    {
      checkKey: 'POLICY_COMPARISON:LIMITATION_OF_LIABILITY',
      status: 'DEGRADED',
      reasonCode: 'PROVIDER_UNAVAILABLE',
      message: 'An external analysis provider was unavailable for this check.',
    },
  ],
}

const contractEvidenceItem = {
  evidenceId: 'e-clause',
  authorityClass: 'CURRENT_CONTRACT',
  sourceType: 'CURRENT_CONTRACT_CLAUSE',
  sourceArtifactId: null,
  sourceCandidateId: null,
  sourceContractVersionId: 'cv-1',
  sourceClauseId: 'cl-6',
  legalConcept: 'LIMITATION_OF_LIABILITY',
  text: 'The total liability of Supplier shall not exceed three times the fees paid.',
  policyFacts: null,
  contractFacts: [],
  retrievalReason: 'STRUCTURED',
  laneInfo: { lexicalRank: null, vectorRank: null, fusedRank: null, fusedScore: null },
  semanticAssessment: null,
  policyCompatibility: null,
  provenanceNodeIds: ['node-6'],
  publicationId: null,
  publicationNumber: null,
  applicabilityScope: null,
}

const clauseFindingDetail = {
  id: 'f-1',
  reviewId: 'r-1',
  runId: 'run-1',
  subjectKind: 'CLAUSE',
  subjectHeadingNodeId: 'node-6',
  absenceConcept: null,
  concept: 'LIMITATION_OF_LIABILITY',
  secondaryConcept: null,
  concepts: ['LIMITATION_OF_LIABILITY'],
  issueFamily: 'POSITION_NEEDS_FALLBACK',
  observationKind: 'POSITION_BEYOND_FALLBACK',
  observationText: "The clause is worse for us than every fallback the position lists.",
  severity: 'MEDIUM',
  reviewClassification: 'REVIEW_REQUIRED',
  proposedAction: 'USE_APPROVED_FALLBACK',
  proposedLanguage: 'The total liability of Supplier shall not exceed two times the fees paid.',
  proposedLanguageSource: 'APPROVED_DRAFTING',
  approvedDraftingEvidenceId: 'e-drafting',
  quality: {
    sufficiency: 'SUFFICIENT',
    verificationOutcome: 'VERIFIED',
    degradationFlags: [],
    category: 'BEYOND_FALLBACK',
    flagProbability: 0.87,
    probabilities: { BEYOND_FALLBACK: 0.87, WALK_AWAY: 0.05 },
  },
  claims: [
    {
      ordinal: 0,
      claimKind: 'OBSERVATION',
      text: 'The clause is worse for us than every fallback the position lists.',
      absence: false,
      evidenceItemIds: ['e-clause', 'e-policy'],
    },
    {
      ordinal: 1,
      claimKind: 'POLICY_BASIS',
      text: 'Published policy prefers a cap of two times the fees paid.',
      absence: false,
      evidenceItemIds: ['e-policy'],
    },
    {
      ordinal: 2,
      claimKind: 'RECOMMENDATION',
      text: 'Use the approved fallback wording for this clause.',
      absence: false,
      evidenceItemIds: ['e-drafting'],
    },
  ],
  evidenceItems: [contractEvidenceItem],
  evidenceItemIds: ['e-clause', 'e-policy', 'e-drafting'],
  signalIds: ['s-1', 's-2', 's-3'],
  absenceBasis: null,
  createdAt: '2026-09-25T09:02:00Z',
}

const absenceFindingDetail = {
  ...clauseFindingDetail,
  id: 'f-2',
  subjectKind: 'ABSENCE',
  subjectHeadingNodeId: null,
  absenceConcept: 'LIMITATION_OF_LIABILITY',
  concept: 'LIMITATION_OF_LIABILITY',
  concepts: ['LIMITATION_OF_LIABILITY'],
  issueFamily: 'MISSING_REQUIRED_PROVISION',
  observationKind: 'MISSING_REQUIRED_CONCEPT',
  observationText: 'The required provision LIMITATION_OF_LIABILITY is missing from this contract.',
  severity: 'HIGH',
  reviewClassification: 'BLOCKING',
  proposedAction: 'ADD_REQUIRED_PROVISION',
  proposedLanguage: null,
  proposedLanguageSource: 'NONE',
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
  signalIds: ['s-4'],
  absenceBasis: { planCheckId: 'pc-1', checkKey: 'PRESENCE:LIMITATION_OF_LIABILITY' },
}

describe('review parsers', () => {
  it('parses a completed summary with pack, pin and warnings', () => {
    const summary = parseReviewSummary(completedSummary)
    expect(summary.status).toBe('COMPLETED')
    expect(summary.activeRun?.status).toBe('COMPLETED')
    expect(summary.snapshot?.profile?.contractPackKey).toBe('MSA_PACK')
    expect(summary.snapshot?.knowledgePinNumber).toBe(6)
    expect(summary.analysisWarnings[0].checkKey).toBe('POLICY_COMPARISON:LIMITATION_OF_LIABILITY')
    expect(summary.analysisWarnings[0].reasonCode).toBe('PROVIDER_UNAVAILABLE')
    expect(summary.coverage[0].coverage).toBe('PRESENT_PRIMARY')
  })

  it('parses the playbooks a review froze, and keeps a missing freeze unknown', () => {
    const withUsed = {
      ...completedSummary,
      snapshot: {
        ...completedSummary.snapshot,
        playbooksUsed: [
          {
            artifactId: 'pb-1',
            artifactType: 'PLAYBOOK',
            name: 'Commercial MSA Playbook',
            publicationNumber: 4,
          },
          {
            artifactId: 'cl-1',
            artifactType: 'CLAUSE_LIBRARY',
            name: 'Clause Library',
            publicationNumber: 2,
          },
        ],
      },
    }
    expect(parseReviewSummary(withUsed).snapshot?.playbooksUsed).toEqual([
      {
        artifactId: 'pb-1',
        artifactType: 'PLAYBOOK',
        name: 'Commercial MSA Playbook',
        publicationNumber: 4,
      },
      {
        artifactId: 'cl-1',
        artifactType: 'CLAUSE_LIBRARY',
        name: 'Clause Library',
        publicationNumber: 2,
      },
    ])
    // A review created before the freeze existed must not be recomputed at read time.
    expect(parseReviewSummary(completedSummary).snapshot?.playbooksUsed).toBeNull()
  })

  it('tolerates unknown enum-like values instead of throwing', () => {
    const summary = parseReviewSummary({
      ...completedSummary,
      status: 'REANALYZING',
      severity: 'EXTREME',
    })
    expect(summary.status).toBe('UNKNOWN')

    const detail = parseFindingDetail({ ...clauseFindingDetail, severity: 'EXTREME' })
    expect(detail.severity).toBe('UNKNOWN')
    expect(detail.issueFamily).toBe('POSITION_NEEDS_FALLBACK')
  })

  it('parses the typed approval rule the panel shows', () => {
    const detail = parseFindingDetail({
      ...clauseFindingDetail,
      quality: {
        ...clauseFindingDetail.quality,
        escalationApprover: 'Commercial Manager',
        escalationCondition: 'Uncapped liability or consequential damages',
        escalationSource: 'MATRIX',
        escalationStance: 'FALLBACK',
        escalationConflictApprover: 'General Counsel',
        escalationConflictSource: 'ENTRY',
      },
    })

    expect(detail.quality).toMatchObject({
      escalationApprover: 'Commercial Manager',
      escalationCondition: 'Uncapped liability or consequential damages',
      escalationSource: 'MATRIX',
      escalationStance: 'FALLBACK',
      escalationConflictApprover: 'General Counsel',
      escalationConflictSource: 'ENTRY',
    })
  })

  it('parses clause finding detail including untyped quality and claims', () => {
    const detail = parseFindingDetail(clauseFindingDetail)
    expect(detail.subjectKind).toBe('CLAUSE')
    expect(detail.proposedLanguageSource).toBe('APPROVED_DRAFTING')
    expect(detail.quality).toEqual({
      sufficiency: 'SUFFICIENT',
      verificationOutcome: 'VERIFIED',
      degradationFlags: [],
      category: 'BEYOND_FALLBACK',
      flagProbability: 0.87,
      probabilities: { BEYOND_FALLBACK: 0.87, WALK_AWAY: 0.05 },
      secondaryTopic: false,
      uncoveredProbability: null,
      route: null,
      jevCategory: null,
      secondOpinionOutcome: null,
      secondOpinionCategory: null,
      secondOpinionQuote: null,
      secondOpinionReason: null,
      uncertaintyReason: null,
      foundBy: null,
      spottedSeverity: null,
      spottedReason: null,
      wordingFitVerdict: null,
      wordingFitReason: null,
      fallbackConditionsOutcome: null,
      fallbackConditions: [],
      fitVerdict: null,
      fitReason: null,
      presenceProbability: null,
      escalationApprover: null,
      escalationCondition: null,
      escalationSource: null,
      escalationStance: null,
      escalationConflictApprover: null,
      escalationConflictSource: null,
    })
    expect(detail.claims.map((claim) => claim.claimKind)).toEqual([
      'OBSERVATION',
      'POLICY_BASIS',
      'RECOMMENDATION',
    ])
    expect(detail.absenceBasis).toBeNull()
    expect(detail.evidenceItems[0].evidenceId).toBe('e-clause')
    expect(detail.evidenceItems[0].authorityClass).toBe('CURRENT_CONTRACT')
  })

  it('reads who decided and both views of a "needs your look" item', () => {
    const detail = parseFindingDetail({
      ...clauseFindingDetail,
      quality: {
        ...clauseFindingDetail.quality,
        route: 'NEEDS_YOUR_LOOK',
        jevCategory: 'BEYOND_FALLBACK',
        secondOpinionOutcome: 'DISAGREED',
        secondOpinionCategory: 'MEETS_PREFERRED',
        secondOpinionQuote: 'shall not exceed the fees paid',
        secondOpinionReason: 'The cap matches the preferred basis.',
      },
    })
    expect(detail.quality?.route).toBe('NEEDS_YOUR_LOOK')
    expect(detail.quality?.jevCategory).toBe('BEYOND_FALLBACK')
    expect(detail.quality?.secondOpinionCategory).toBe('MEETS_PREFERRED')
    expect(detail.quality?.secondOpinionQuote).toBe('shall not exceed the fees paid')
  })

  it('reads what engine v2 P4 adds: search provenance, the read’s pick, an omission’s estimate and label', () => {
    const detail = parseFindingDetail({
      ...clauseFindingDetail,
      firstReviewCategory: 'WALK_AWAY',
      positionLabel: 'Cap Exceptions',
      quality: {
        ...clauseFindingDetail.quality,
        uncertaintyReason: 'playbook_silent',
        foundBy: 'SEARCH',
        spottedSeverity: 'HIGH',
        spottedReason: 'Only damages are available to us.',
        presenceProbability: 0.92,
      },
    })
    expect(detail.firstReviewCategory).toBe('WALK_AWAY')
    expect(detail.positionLabel).toBe('Cap Exceptions')
    expect(detail.quality?.uncertaintyReason).toBe('playbook_silent')
    expect(detail.quality?.foundBy).toBe('SEARCH')
    expect(detail.quality?.spottedSeverity).toBe('HIGH')
    expect(detail.quality?.spottedReason).toBe('Only damages are available to us.')
    expect(detail.quality?.presenceProbability).toBe(0.92)
    // an estimate off the 0–1 scale is not shown
    expect(
      parseFindingDetail({ ...clauseFindingDetail, quality: { ...clauseFindingDetail.quality, presenceProbability: 92 } })
        .quality?.presenceProbability,
    ).toBeNull()
  })

  it('drops a flag probability that is not on the comparison’s 0–1 scale', () => {
    const detail = parseFindingDetail({
      ...clauseFindingDetail,
      quality: { ...clauseFindingDetail.quality, flagProbability: 87 },
    })
    expect(detail.quality?.flagProbability).toBeNull()
  })

  it('reads the related-position flag and the uncovered probability, dropping one off the scale', () => {
    const detail = parseFindingDetail({
      ...clauseFindingDetail,
      quality: {
        ...clauseFindingDetail.quality,
        secondaryTopic: true,
        uncoveredProbability: 0.9,
      },
    })
    expect(detail.quality?.secondaryTopic).toBe(true)
    expect(detail.quality?.uncoveredProbability).toBe(0.9)

    const offScale = parseFindingDetail({
      ...clauseFindingDetail,
      quality: { ...clauseFindingDetail.quality, uncoveredProbability: 90 },
    })
    expect(offScale.quality?.uncoveredProbability).toBeNull()
  })

  it('parses absence finding detail with plan-check basis and no evidence', () => {
    const detail = parseFindingDetail(absenceFindingDetail)
    expect(detail.subjectKind).toBe('ABSENCE')
    expect(detail.subjectHeadingNodeId).toBeNull()
    expect(detail.absenceBasis?.checkKey).toBe('PRESENCE:LIMITATION_OF_LIABILITY')
    expect(detail.claims[0].absence).toBe(true)
    expect(detail.evidenceItemIds).toEqual([])
  })

  describe('position comparison', () => {
    const contractSlot = {
      evidenceItemId: 'e-contract',
      authorityClass: 'CURRENT_CONTRACT',
      legalConcept: 'LIMITATION_OF_LIABILITY',
      facts: [{ factSchema: 'liability-facts', schemaVersion: 1, values: { liabilityCapMultiple: '3' } }],
    }
    const policySlot = (id: string, publicationNumber: number | null = 2) => ({
      evidenceItemId: id,
      authorityClass: 'POLICY',
      legalConcept: 'LIMITATION_OF_LIABILITY',
      publicationNumber,
      text: 'Cap at fees paid.',
    })

    it('is null when the server sent no comparison', () => {
      expect(parseFindingDetail(absenceFindingDetail).positionComparison).toBeNull()
      expect(parseFindingDetail({ ...absenceFindingDetail, positionComparison: null }).positionComparison).toBeNull()
    })

    it('keeps every slot the server sent, in order', () => {
      const detail = parseFindingDetail({
        ...clauseFindingDetail,
        positionComparison: {
          contract: [contractSlot],
          preferred: [policySlot('e-1'), policySlot('e-2')],
          fallback: [policySlot('e-1')],
          walkAway: [policySlot('e-2')],
          escalation: [{ evidenceItemId: 'e-1', authorityClass: 'POLICY', publicationNumber: 2, trigger: 'Uncapped liability' }],
        },
      })
      const comparison = detail.positionComparison
      expect(comparison?.preferred.map((slot) => slot.evidenceItemId)).toEqual(['e-1', 'e-2'])
      expect(comparison?.fallback).toHaveLength(1)
      expect(comparison?.walkAway.map((slot) => slot.evidenceItemId)).toEqual(['e-2'])
      expect(comparison?.contract[0].facts[0]).toMatchObject({ factSchema: 'liability-facts', values: { liabilityCapMultiple: '3' } })
      expect(comparison?.escalation[0].trigger).toBe('Uncapped liability')
    })

    it('reads a slot list the server left out as empty, and never fills it in', () => {
      const detail = parseFindingDetail({ ...clauseFindingDetail, positionComparison: { preferred: [policySlot('e-1', null)] } })
      expect(detail.positionComparison).toEqual({
        contract: [],
        preferred: [expect.objectContaining({ evidenceItemId: 'e-1', publicationNumber: null })],
        fallback: [],
        walkAway: [],
        escalation: [],
      })
    })

    it('rejects a malformed comparison instead of showing part of it', () => {
      expect(() => parseFindingDetail({ ...clauseFindingDetail, positionComparison: { preferred: 'Cap at fees paid.' } })).toThrow()
      expect(() => parseFindingDetail({ ...clauseFindingDetail, positionComparison: { preferred: [{ text: 'no id' }] } })).toThrow()
    })
  })

  it('parses bounded lists with truncation', () => {
    const list = parseReviewList({
      items: [{ ...completedSummary, id: 'r-1' }],
      truncated: true,
    })
    expect(list.items).toHaveLength(1)
    expect(list.truncated).toBe(true)

    const findings = parseFindingList({
      items: [clauseFindingDetail, absenceFindingDetail],
      truncated: true,
    })
    expect(findings.items).toHaveLength(2)
    expect(findings.items[1].absenceConcept).toBe('LIMITATION_OF_LIABILITY')
  })

  it('parses the human-review, finalization, redline and context truth carried by a list row', () => {
    const list = parseReviewList({
      items: [
        {
          ...completedSummary,
          id: 'r-1',
          uploadedAt: '2026-10-02T07:30:00Z',
          context: { contractType: 'NDA', ourSideRole: null, paperSource: 'OUR_PAPER', jurisdiction: 'UNSPECIFIED' },
          humanReview: {
            status: 'IN_PROGRESS',
            actionableFindings: 8,
            resolvedFindings: 5,
            remainingFindings: 3,
            escalated: 1,
            readyToFinalize: false,
            blockers: ['ACTIVE_ESCALATION'],
          },
          finalization: { finalizedAt: '2026-10-02T10:00:00Z', approvedChanges: 7 },
          redline: { status: 'COMPLETED', outcome: 'REDLINE_GENERATED', verifiedChangeCount: 7, downloadAvailable: true, completedAt: '2026-10-02T10:05:00Z' },
        },
        { ...completedSummary, id: 'r-2', humanReview: { status: 'NOT_STARTED' } },
      ],
    })
    expect(list.items[0]).toMatchObject({
      uploadedAt: '2026-10-02T07:30:00Z',
      context: { contractType: 'NDA', ourSideRole: null, paperSource: 'OUR_PAPER', jurisdiction: 'UNSPECIFIED' },
      humanReview: { status: 'IN_PROGRESS', resolvedFindings: 5, escalated: 1, readyToFinalize: false, blockers: ['ACTIVE_ESCALATION'] },
      finalization: { approvedChanges: 7 },
      redline: { status: 'COMPLETED', verifiedChangeCount: 7, downloadAvailable: true },
    })
  })

  it('reads an absent list-row fact as absent, and never as ready', () => {
    const [bare, partial] = parseReviewList({
      items: [
        { ...completedSummary, id: 'r-1' },
        { ...completedSummary, id: 'r-2', humanReview: { status: 'NOT_STARTED' } },
      ],
    }).items
    expect(bare).toMatchObject({ uploadedAt: null, context: null, humanReview: null, finalization: null, redline: null })
    expect(partial.humanReview).toMatchObject({ readyToFinalize: false, blockers: [] })
  })

  it('rejects a list-row fact of the wrong shape', () => {
    expect(() =>
      parseReviewList({ items: [{ ...completedSummary, id: 'r-1', humanReview: { status: 'X', blockers: 'ACTIVE_ESCALATION' } }] }),
    ).toThrow()
  })

  it('parses progress counts and status', () => {
    const progress = parseReviewProgress({
      reviewStatus: 'ANALYZING',
      ...queuedSummary,
      pendingChecks: 2,
      runningChecks: 1,
      skippedChecks: 0,
    })
    expect(progress.reviewStatus).toBe('ANALYZING')
    expect(progress.pendingChecks).toBe(2)
    expect(progress.runningChecks).toBe(1)
  })

  it('polls only while a review is queued or analyzing', () => {
    expect(reviewPollInterval('ANALYSIS_QUEUED')).toBe(REVIEW_POLL_INTERVAL_MS)
    expect(reviewPollInterval('ANALYZING')).toBe(REVIEW_POLL_INTERVAL_MS)
    expect(reviewPollInterval('COMPLETED')).toBe(false)
    expect(reviewPollInterval('FAILED')).toBe(false)
    expect(reviewPollInterval('UNKNOWN')).toBe(false)
    expect(reviewPollInterval(undefined)).toBe(false)
  })

  it('keeps polling patience at least as long as the reference reproduction waits', () => {
    // scripts/demo-reproduction.sh waits 90 x 4s = 360s for the same run. A shorter budget let the
    // UI give up first, and the pending states have no refresh control, so the page then showed a
    // stale "analysing" view until a full reload.
    expect(MAX_POLL_ATTEMPTS * REVIEW_POLL_INTERVAL_MS).toBeGreaterThanOrEqual(360_000)
  })
})

describe('review requests', () => {
  it('parses the authoritative pack catalog', async () => {
    const requests = stubFetch(() =>
      jsonResponse(
        envelope({
          packs: [
            {
              key: 'MSA_PACK',
              displayName: 'MSA pack',
              summary: 'MSA checks',
              version: 'rivell-review-pack-1',
            },
          ],
        }),
      ),
    )
    const packs = await fetchReviewPacks()
    expect(packs).toEqual([
      { key: 'MSA_PACK', displayName: 'MSA pack', summary: 'MSA checks', version: 'rivell-review-pack-1' },
    ])
    expect(requests[0].url).toContain('/api/reviews/packs')
  })

  it('sends exactly one create POST with the explicit pack', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    const summary = await createReview('c-1', 2, 'MSA_PACK')
    expect(summary.id).toBe('r-1')
    expect(requests).toHaveLength(1)
    expect(requests[0].method).toBe('POST')
    expect(requests[0].url).toContain('/api/contracts/c-1/versions/2/reviews')
    expect(await requests[0].json()).toEqual({ contractPackKey: 'MSA_PACK' })
  })

  it('omits the pack key for a pack-less review', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    await createReview('c-1', 1)
    expect(await requests[0].json()).toEqual({})
  })

  it('sends a supplied client request id and omits it when absent', async () => {
    const withId = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    await createReview('c-1', 1, 'MSA_PACK', '11111111-1111-4111-8111-111111111111')
    expect(await withId[0].json()).toEqual({
      contractPackKey: 'MSA_PACK',
      clientRequestId: '11111111-1111-4111-8111-111111111111',
    })

    const withoutId = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    await createReview('c-1', 1, null, null)
    expect(await withoutId[0].json()).toEqual({})
  })

  it('exposes the failing status and classifies a payload ceiling failure', async () => {
    stubFetch(() => jsonResponse({ code: '413' }, 413))
    const error = await createReview('c-1', 1).catch((thrown: unknown) => thrown)

    expect(reviewErrorStatus(error)).toBe(413)
    expect(reviewErrorStatus(new Error('boom'))).toBeNull()
  })

  it('classifies a typed conflict without retrying the request', async () => {
    const requests = stubFetch(() =>
      jsonResponse({ code: 'ENRICHMENT_NOT_READY', detail: 'Semantic analysis not ready' }, 409),
    )
    const error = await createReview('c-1', 1).catch((thrown: unknown) => thrown)

    expect(reviewErrorStatus(error)).toBe(409)
    expect(requests).toHaveLength(1)
  })

  it('never retries the create mutation and seeds the summary cache', async () => {
    const requests = stubFetch(() => jsonResponse(envelope(queuedSummary)))
    const { queryClient, wrapper } = createWrapper()
    const create = renderHook(() => useCreateReview('c-1', 1), { wrapper })

    await act(async () => {
      await create.result.current.mutateAsync({ contractPackKey: 'MSA_PACK' })
    })

    expect(requests).toHaveLength(1)
    expect(queryClient.getQueryData(reviewKeys.summary('r-1'))).toMatchObject({ id: 'r-1' })
  })

  it('overrides an app-level mutation retry so a failed create never repeats', async () => {
    const requests = stubFetch(() => jsonResponse({ code: 'SERVER_ERROR' }, 500))
    const { wrapper } = createWrapper(2)
    const create = renderHook(() => useCreateReview('c-1', 1), { wrapper })

    await act(async () => {
      await expect(create.result.current.mutateAsync({})).rejects.toThrow()
    })

    expect(requests).toHaveLength(1)
  })

  it('rejects malformed payloads with a malformed-response error', async () => {
    stubFetch(() => jsonResponse(envelope({ id: 'r-1' })))
    await expect(createReview('c-1', 1)).rejects.toThrow(/could not read this response/)
  })
})

describe('review polling integration', () => {
  it('polls a queued review and stops once it completes', async () => {
    vi.useFakeTimers()
    let call = 0
    const requests = stubFetch(() => {
      const body = call === 0 ? queuedSummary : completedSummary
      call += 1
      return jsonResponse(envelope(body))
    })
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useReviewSummaryQuery('r-1'), { wrapper })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(result.current.data?.status).toBe('ANALYSIS_QUEUED')

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600)
    })
    expect(result.current.data?.status).toBe('COMPLETED')
    expect(requests).toHaveLength(2)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000)
    })
    expect(requests).toHaveLength(2)
    vi.useRealTimers()
  })

  it('refreshes disclosed progress while analysis is pending', async () => {
    vi.useFakeTimers()
    const requests = stubFetch(() =>
      jsonResponse(envelope({ ...queuedSummary, reviewStatus: 'ANALYZING', pendingChecks: 2 })),
    )
    const { wrapper } = createWrapper()
    renderHook(() => useReviewProgressQuery('r-1', true, true), { wrapper })

    await act(async () => {
      await vi.advanceTimersByTimeAsync(10)
    })
    expect(requests).toHaveLength(1)

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2600)
    })
    expect(requests).toHaveLength(2)
    vi.useRealTimers()
  })
})

describe('query key isolation', () => {
  it('keeps summaries separate per review id', async () => {
    const requests = stubFetch((request) => {
      const id = request.url.split('/api/reviews/')[1]?.split('?')[0] ?? 'unknown'
      return jsonResponse(envelope({ ...queuedSummary, id }))
    })
    const { wrapper } = createWrapper()
    const first = renderHook(() => useReviewSummaryQuery('r-1'), { wrapper })
    const second = renderHook(() => useReviewSummaryQuery('r-2'), { wrapper })

    await waitFor(() => expect(first.result.current.data?.id).toBe('r-1'))
    await waitFor(() => expect(second.result.current.data?.id).toBe('r-2'))
    expect(requests).toHaveLength(2)
  })

  it('refetches findings when a filter changes', async () => {
    const requests = stubFetch(() => jsonResponse(envelope({ items: [], truncated: false })))
    const { wrapper } = createWrapper()
    const view = renderHook(
      (props: { severity: 'HIGH' | 'LOW' }) =>
        useFindingsQuery('r-1', { severity: props.severity }, true),
      { wrapper, initialProps: { severity: 'HIGH' } as { severity: 'HIGH' | 'LOW' } },
    )

    await waitFor(() => expect(view.result.current.data).toBeDefined())
    view.rerender({ severity: 'LOW' })

    await waitFor(() => expect(requests).toHaveLength(2))
    expect(new URL(requests[1].url).searchParams.get('severity')).toBe('LOW')
  })
})

describe('findings query gating', () => {
  it('does not request findings while disabled and includes filters when enabled', async () => {
    const requests = stubFetch(() => jsonResponse(envelope({ items: [], truncated: false })))
    const { wrapper } = createWrapper()

    const disabled = renderHook(() => useFindingsQuery('r-1', {}, false), { wrapper })
    await act(async () => {
      await Promise.resolve()
    })
    expect(disabled.result.current.fetchStatus).toBe('idle')
    expect(requests).toHaveLength(0)

    const enabled = renderHook(
      () => useFindingsQuery('r-1', { severity: 'HIGH', classification: 'BLOCKING' }, true),
      { wrapper },
    )
    await waitFor(() => expect(enabled.result.current.data?.items).toEqual([]))
    expect(requests).toHaveLength(1)
    expect(requests[0].url).toContain('severity=HIGH')
    expect(requests[0].url).toContain('classification=BLOCKING')
    expect(requests[0].url).toContain('limit=100')
  })
})