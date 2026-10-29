import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  DemoRuntime,
  requestKey,
  shiftInstants,
  type DemoCapture,
  type DemoManifest,
} from './transport'
import { advanceDemoStage, getDemoState, resetDemo, setGuideHidden } from './state'

const CAPTURED = '2026-10-01T10:00:00Z'
const REVIEW = 'review-c5'
const CONTRACT = 'contract-c5'
const K2 = 'artifact-k2'
const OTHER_REVIEW = 'review-other'
const OTHER_CONTRACT = 'contract-other'

const findings = [
  {
    id: 'f1',
    reviewClassification: 'REVIEW_REQUIRED',
    concept: 'TERM',
    secondaryConcept: null,
    severity: 'HIGH',
    subjectKind: 'CLAUSE',
    proposedAction: 'CONSIDER_GENERATED_LANGUAGE',
    proposedLanguage: 'A new term.',
    proposedLanguageSource: 'GENERATIVE',
    approvedDraftingEvidenceId: null,
  },
  {
    id: 'f2',
    reviewClassification: 'BLOCKING',
    concept: 'FEES',
    secondaryConcept: 'PAYMENT',
    severity: 'MEDIUM',
    subjectKind: 'CLAUSE',
    proposedAction: 'USE_APPROVED_FALLBACK',
    proposedLanguage: 'The fallback.',
    proposedLanguageSource: 'APPROVED_DRAFTING',
    approvedDraftingEvidenceId: 'e1',
  },
  {
    id: 'f3',
    reviewClassification: 'INFORMATIONAL',
    concept: 'TERM',
    secondaryConcept: null,
    severity: 'LOW',
    subjectKind: 'ABSENCE',
    proposedAction: 'NO_ACTION',
    proposedLanguage: null,
    proposedLanguageSource: 'NONE',
    approvedDraftingEvidenceId: null,
  },
]

const recordedDecisions = [
  {
    id: 'recorded-f1',
    findingId: 'f1',
    revision: 1,
    action: 'REJECT',
    approvedAction: null,
    approvedLanguage: null,
    languageSource: null,
    approvedDraftingEvidenceId: null,
    comment: 'Keep it.',
    rejectReason: 'KEEP_AS_DRAFTED',
    approvedBy: null,
    actorId: 'actor',
    decidedAt: '2026-10-01T09:00:00Z',
    clientRequestId: null,
    supersedesDecisionId: null,
  },
  {
    id: 'recorded-f2',
    findingId: 'f2',
    revision: 1,
    action: 'ACCEPT',
    approvedAction: 'REPLACE',
    approvedLanguage: 'The fallback.',
    languageSource: 'APPROVED_DRAFTING',
    approvedDraftingEvidenceId: 'e1',
    comment: null,
    rejectReason: null,
    approvedBy: null,
    actorId: 'actor',
    decidedAt: '2026-10-01T09:00:00Z',
    clientRequestId: null,
    supersedesDecisionId: null,
  },
]

function response(body: unknown, status = 200) {
  return { status, body }
}

function envelope(data: unknown, status = 200) {
  return { data, status, timestamp: CAPTURED }
}

function humanReview(overrides: Record<string, unknown> = {}) {
  return response(envelope({
    reviewId: REVIEW,
    status: 'IN_PROGRESS',
    analysisStatus: 'COMPLETED',
    actionableFindings: 2,
    resolvedFindings: 0,
    remainingFindings: 2,
    accepted: 0,
    edited: 0,
    rejected: 0,
    escalated: 0,
    informationalFindings: 1,
    informationalDecided: 0,
    readyToFinalize: false,
    blockers: [
      {
        code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
        message: '1 finding that needs your decision is still open.',
        findingIds: ['f1'],
      },
      {
        code: 'UNRESOLVED_BLOCKING_FINDINGS',
        message: '1 blocking finding still needs your decision.',
        findingIds: ['f2'],
      },
    ],
    changeSetId: null,
    finalizedBy: null,
    finalizedAt: null,
    ...overrides,
  }))
}

function reviewItem() {
  return {
    id: REVIEW,
    contractId: CONTRACT,
    contractVersionNumber: 1,
    contractFilename: 'Sablepoint Analytics - Mutual NDA.docx',
    status: 'COMPLETED',
    createdAt: '2026-10-01T08:00:00Z',
    humanReview: {
      status: 'IN_PROGRESS',
      actionableFindings: 2,
      resolvedFindings: 0,
      remainingFindings: 2,
      escalated: 0,
      readyToFinalize: false,
      blockers: [],
    },
  }
}

function otherReviewItem() {
  return {
    id: OTHER_REVIEW,
    contractId: OTHER_CONTRACT,
    contractVersionNumber: 1,
    contractFilename: 'Kestrel Assurance - Order Form KAG-2026-014.docx',
    status: 'COMPLETED',
    createdAt: '2026-09-30T08:00:00Z',
  }
}

function capture(state: string, responses: DemoCapture['responses'],
                 mutations: DemoCapture['mutations'] = []): DemoCapture {
  return { state, capturedAt: CAPTURED, responses, mutations }
}

function fixtures(): { manifest: DemoManifest; captures: Record<string, DemoCapture> } {
  const base = {
    'GET /api/reviews': response(envelope({
      items: [otherReviewItem()], nextCursor: null, truncated: false,
    })),
    'GET /api/reviews/packs': response(envelope({ packs: [] })),
    'GET /api/knowledge/artifacts': response(envelope([
      { id: K2, filename: 'NDA Playbook.xlsx', ingestStatus: 'EXTRACTED' },
    ])),
  }
  const compiled = {
    'GET /api/reviews': response(envelope({
      items: [otherReviewItem()], nextCursor: null, truncated: false,
    })),
    'GET /api/knowledge/artifacts': response(envelope([
      { id: K2, filename: 'NDA Playbook.xlsx', ingestStatus: 'EXTRACTED' },
    ])),
    [`GET /api/knowledge/artifacts/${K2}/candidates`]: response(envelope([
      { id: 'k1', reviewStatus: 'PENDING', reviewCategory: 'ELEVATED', title: 'One' },
      { id: 'k2', reviewStatus: 'PENDING', reviewCategory: 'ELEVATED', title: 'Two' },
    ])),
  }
  const approved = {
    ...compiled,
    [`GET /api/knowledge/artifacts/${K2}/candidates`]: response(envelope([
      { id: 'k1', reviewStatus: 'APPROVED', reviewCategory: 'ELEVATED', title: 'One' },
      { id: 'k2', reviewStatus: 'APPROVED', reviewCategory: 'ELEVATED', title: 'Two' },
    ])),
  }
  const published = {
    'GET /api/reviews': response(envelope({
      items: [otherReviewItem(), reviewItem()], nextCursor: null, truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/findings`]: response(envelope({
      items: findings, truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/findings/f1`]: response(envelope(findings[0])),
    [`GET /api/reviews/${REVIEW}/findings/f2`]: response(envelope(findings[1])),
    [`GET /api/reviews/${REVIEW}/findings/f3`]: response(envelope(findings[2])),
    [`GET /api/reviews/${REVIEW}/findings/f1/decisions`]: response(envelope({
      items: [], truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/findings/f2/decisions`]: response(envelope({
      items: [], truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/decisions`]: response(envelope({
      items: [], truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/human-review`]: humanReview(),
    [`GET /api/knowledge/artifacts/${K2}/candidates`]: response(envelope([
      { id: 'k1', reviewStatus: 'PUBLISHED', reviewCategory: 'ELEVATED', title: 'One' },
      { id: 'k2', reviewStatus: 'PUBLISHED', reviewCategory: 'ELEVATED', title: 'Two' },
    ])),
  }
  const decided = {
    ...published,
    'GET /api/reviews': response(envelope({
      items: [otherReviewItem(), {
        ...reviewItem(),
        humanReview: {
          status: 'READY_FOR_COMPLETION',
          actionableFindings: 2,
          resolvedFindings: 2,
          remainingFindings: 0,
          escalated: 0,
          readyToFinalize: true,
          blockers: [],
        },
      }], nextCursor: null, truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/decisions`]: response(envelope({
      items: recordedDecisions, truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/findings/f1/decisions`]: response(envelope({
      items: [recordedDecisions[0]], truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/findings/f2/decisions`]: response(envelope({
      items: [recordedDecisions[1]], truncated: false,
    })),
    [`GET /api/reviews/${REVIEW}/human-review`]: humanReview({
      status: 'READY_FOR_COMPLETION',
      resolvedFindings: 2,
      remainingFindings: 0,
      accepted: 1,
      rejected: 1,
      readyToFinalize: true,
      blockers: [],
    }),
  }
  const finalized = {
    ...decided,
    [`GET /api/reviews/${REVIEW}/human-review`]: humanReview({
      status: 'COMPLETED',
      resolvedFindings: 2,
      remainingFindings: 0,
      accepted: 1,
      rejected: 1,
      readyToFinalize: false,
      blockers: [],
      changeSetId: 'change-set-1',
      finalizedBy: 'actor',
      finalizedAt: '2026-10-01T09:30:00Z',
    }),
    [`GET /api/reviews/${REVIEW}/approved-change-set`]: response(envelope({
      id: 'change-set-1', reviewId: REVIEW, changes: [],
    })),
    [`GET /api/reviews/${REVIEW}/redline`]: response({}, 404),
  }
  const done = {
    ...finalized,
    [`GET /api/reviews/${REVIEW}/redline`]: response(envelope({
      id: 'redline-1',
      reviewId: REVIEW,
      status: 'COMPLETED',
      outcome: 'REDLINE_GENERATED',
      downloadAvailable: true,
      artifactFilename: 'nda-redline.docx',
      artifactSizeBytes: 4,
    })),
  }

  const manifest: DemoManifest = {
    version: 1,
    states: [
      'base', 'tour-k2-compiled', 'tour-k2-approved', 'tour-k2-published',
      'tour-c5-decided', 'tour-c5-finalized', 'tour-c5-done',
    ],
    capturedAt: CAPTURED,
    ids: {
      reviews: { 'Sablepoint Analytics - Mutual NDA.docx': REVIEW },
      contracts: { 'Sablepoint Analytics - Mutual NDA.docx': CONTRACT },
      artifacts: { 'NDA Playbook.xlsx': K2 },
      k2ArtifactId: K2,
    },
    files: {
      [REVIEW]: { filename: 'nda-redline.docx', contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document', sizeBytes: 4 },
    },
    preparedKnowledge: { filename: 'NDA Playbook.xlsx', path: 'files/NDA Playbook.xlsx' },
  }
  const captures: Record<string, DemoCapture> = {
    base: capture('base', base, [{
      method: 'POST',
      path: '/api/knowledge/artifacts?artifactType=PLAYBOOK',
      request: { filename: 'Commercial Contracts Playbook v4.2.docx' },
      status: 200,
      body: envelope({ id: 'artifact-k1', filename: 'Commercial Contracts Playbook v4.2.docx' }),
    }, {
      method: 'POST',
      path: '/api/knowledge/publications',
      request: { candidateIds: ['k1'] },
      status: 200,
      body: envelope({ id: 'publication-1', publicationNumber: 1, items: [] }),
    }]),
    'tour-k2-compiled': capture('tour-k2-compiled', compiled, [{
      method: 'POST',
      path: '/api/knowledge/artifacts?artifactType=PLAYBOOK&declaredContractTypes=MNDA',
      request: { filename: 'NDA Playbook.xlsx' },
      status: 200,
      body: envelope({ id: K2, filename: 'NDA Playbook.xlsx', ingestStatus: 'EXTRACTED' }),
    }]),
    'tour-k2-approved': capture('tour-k2-approved', approved, [{
      method: 'POST',
      path: '/api/knowledge/candidates/bulk-approve',
      request: { candidateIds: ['k1', 'k2'] },
      status: 200,
      body: envelope({ approvedIds: ['k1', 'k2'], skipped: {} }),
    }]),
    'tour-k2-published': capture('tour-k2-published', published, [{
      method: 'POST',
      path: '/api/knowledge/publications',
      request: { candidateIds: ['k1', 'k2'] },
      status: 200,
      body: envelope({ id: 'publication-1', publicationNumber: 4, items: [] }),
    }]),
    'tour-c5-decided': capture('tour-c5-decided', decided, [
      {
        method: 'PUT',
        path: `/api/reviews/${REVIEW}/findings/f1/decision`,
        request: { expectedVersion: 0, action: 'REJECT' },
        status: 200,
        body: envelope(recordedDecisions[0]),
      },
      {
        method: 'PUT',
        path: `/api/reviews/${REVIEW}/findings/f2/decision`,
        request: { expectedVersion: 0, action: 'ACCEPT' },
        status: 200,
        body: envelope(recordedDecisions[1]),
      },
    ]),
    'tour-c5-finalized': capture('tour-c5-finalized', finalized, [{
      method: 'POST',
      path: `/api/reviews/${REVIEW}/finalize`,
      status: 200,
      body: envelope({ id: 'change-set-1', reviewId: REVIEW, changes: [] }),
    }]),
    'tour-c5-done': capture('tour-c5-done', done, [{
      method: 'POST',
      path: `/api/reviews/${REVIEW}/redline`,
      status: 200,
      body: envelope({
        id: 'redline-1',
        reviewId: REVIEW,
        status: 'COMPLETED',
        outcome: 'REDLINE_GENERATED',
        downloadAvailable: true,
        artifactFilename: 'nda-redline.docx',
        artifactSizeBytes: 4,
      }),
    }]),
  }
  return { manifest, captures }
}

function runtime(options: { now?: () => number; baseFetch?: typeof fetch } = {}) {
  const { manifest, captures } = fixtures()
  return new DemoRuntime(manifest, captures, {
    now: options.now ?? (() => Date.parse(CAPTURED)),
    baseFetch: options.baseFetch,
    dataBase: 'http://localhost/demo-data/',
  })
}

async function json(fetchImpl: DemoRuntime, path: string, init?: RequestInit) {
  const result = await fetchImpl.fetch(new Request(`http://localhost${path}`, init))
  return { status: result.status, body: await result.json() }
}

function uploadRequest(filename: string): Request {
  const boundary = '----rivell-demo-test'
  const body = [
    `--${boundary}`,
    `Content-Disposition: form-data; name="file"; filename="${filename}"`,
    'Content-Type: application/octet-stream',
    '',
    'file-bytes',
    `--${boundary}--`,
    '',
  ].join('\r\n')
  return new Request('http://localhost/api/knowledge/artifacts?artifactType=PLAYBOOK', {
    method: 'POST',
    headers: { 'content-type': `multipart/form-data; boundary=${boundary}` },
    body,
  })
}

function decision(action: string, extra: Record<string, unknown> = {}) {
  return {
    method: 'PUT',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ expectedVersion: 0, action, ...extra }),
  }
}

beforeEach(() => {
  resetDemo()
  localStorage.clear()
})

describe('stage transitions', () => {
  it('moves through the recorded tour on the writes the tour makes', async () => {
    const demo = runtime()
    expect(getDemoState().stage).toBe('base')

    const upload = await demo.fetch(uploadRequest('NDA Playbook.xlsx'))
    expect(upload.status).toBe(200)
    expect((await upload.json() as { data: { id: string } }).data.id).toBe(K2)
    expect(getDemoState().stage).toBe('tour-k2-compiled')

    await json(demo, '/api/knowledge/candidates/k1/approve', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
    })
    await json(demo, '/api/knowledge/candidates/k2/approve', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
    })
    expect(getDemoState().stage).toBe('tour-k2-approved')

    const publish = await json(demo, '/api/knowledge/publications', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ candidateIds: ['k1', 'k2'] }),
    })
    expect((publish.body as { data: { publicationNumber: number } }).data.publicationNumber).toBe(4)
    expect(getDemoState().stage).toBe('tour-k2-published')

    await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, decision('ACCEPT'))
    expect(getDemoState().stage).toBe('tour-k2-published')
    await json(demo, `/api/reviews/${REVIEW}/findings/f2/decision`, decision('REJECT', { rejectReason: 'FINDING_WRONG' }))
    expect(getDemoState().stage).toBe('tour-c5-decided')

    await json(demo, `/api/reviews/${REVIEW}/finalize`, { method: 'POST' })
    expect(getDemoState().stage).toBe('tour-c5-finalized')
    await json(demo, `/api/reviews/${REVIEW}/redline`, { method: 'POST' })
    expect(getDemoState().stage).toBe('tour-c5-done')
  })

  it('refuses a write that is not on the tour', async () => {
    const demo = runtime()
    const heard = vi.fn()
    window.addEventListener('rivell-demo-blocked', heard)
    const result = await json(demo, '/api/contracts', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
    })
    window.removeEventListener('rivell-demo-blocked', heard)
    expect(result.status).toBe(403)
    expect(result.body).toEqual({})
    expect(heard).toHaveBeenCalledTimes(1)
  })

  it('refuses a multipart contract upload as a blocked demo write', async () => {
    const demo = runtime()
    const heard = vi.fn()
    window.addEventListener('rivell-demo-blocked', heard)
    const boundary = '----demo'
    const body = [
      `--${boundary}`,
      'Content-Disposition: form-data; name="file"; filename="contract.docx"',
      'Content-Type: application/octet-stream',
      '',
      'bytes',
      `--${boundary}--`,
      '',
    ].join('\r\n')
    const response = await demo.fetch(new Request('http://localhost/api/contracts', {
      method: 'POST',
      headers: { 'content-type': `multipart/form-data; boundary=${boundary}` },
      body,
    }))
    window.removeEventListener('rivell-demo-blocked', heard)
    expect(response.status).toBe(403)
    expect(heard).toHaveBeenCalledTimes(1)
  })

  it('refuses a file that is not the prepared playbook with the honest reason', async () => {
    const demo = runtime()
    const heard = vi.fn()
    window.addEventListener('rivell-demo-blocked', heard)
    const result = await demo.fetch(uploadRequest('some-other.docx'))
    window.removeEventListener('rivell-demo-blocked', heard)
    expect(result.status).toBe(403)
    expect(await result.json()).toEqual({
      detail: 'Only the prepared NDA playbook can be added in the demo.',
    })
    const event = heard.mock.calls[0][0] as CustomEvent<{ message?: string }>
    expect(event.detail.message).toBe(
      'Only the prepared NDA playbook can be added in the demo.')
    expect(getDemoState().stage).toBe('base')
  })

  it('does not claim a bulk approval the server would refuse for elevated entries', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-compiled')
    const result = await json(demo, '/api/knowledge/candidates/bulk-approve', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ candidateIds: ['k1', 'k2'] }),
    })
    expect(result.body.data).toEqual({
      approvedIds: [],
      skipped: { k1: 'not routine', k2: 'not routine' },
    })
    expect(getDemoState().stage).toBe('tour-k2-compiled')
  })

  it('keeps a partly approved playbook at the compiled stage', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-compiled')
    await json(demo, '/api/knowledge/candidates/k1/approve', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
    })
    expect(getDemoState().stage).toBe('tour-k2-compiled')
    const candidates = await json(demo, `/api/knowledge/artifacts/${K2}/candidates`)
    const items = (candidates.body as { data: { id: string; reviewStatus: string }[] }).data
    expect(items.find((entry) => entry.id === 'k1')?.reviewStatus).toBe('APPROVED')
    expect(items.find((entry) => entry.id === 'k2')?.reviewStatus).toBe('PENDING')
    await json(demo, '/api/knowledge/candidates/k2/approve', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: '{}',
    })
    expect(getDemoState().stage).toBe('tour-k2-approved')
  })
})

describe('decision overlay', () => {
  it('recomputes human-review counts and the review list from the visitor\'s decisions', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')

    const before = await json(demo, `/api/reviews/${REVIEW}/human-review`)
    expect(before.body.data).toMatchObject({
      resolvedFindings: 0,
      remainingFindings: 2,
      readyToFinalize: false,
    })

    await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, decision('ACCEPT'))
    const one = await json(demo, `/api/reviews/${REVIEW}/human-review`)
    expect(one.body.data).toMatchObject({
      resolvedFindings: 1,
      remainingFindings: 1,
      accepted: 1,
      readyToFinalize: false,
    })
    const partialList = await json(demo, '/api/reviews')
    const partial = (partialList.body.data.items as { id: string; humanReview: { blockers: string[] } }[])
      .find((item) => item.id === REVIEW)
    expect(partial?.humanReview.blockers).toEqual(['UNRESOLVED_BLOCKING_FINDINGS'])

    await json(demo, `/api/reviews/${REVIEW}/findings/f2/decision`,
      decision('REJECT', { rejectReason: 'KEEP_AS_DRAFTED' }))
    const two = await json(demo, `/api/reviews/${REVIEW}/human-review`)
    expect(two.body.data).toMatchObject({
      resolvedFindings: 2,
      remainingFindings: 0,
      accepted: 1,
      rejected: 1,
      readyToFinalize: true,
      status: 'READY_FOR_COMPLETION',
    })
    const list = await json(demo, '/api/reviews')
    const c5 = (list.body.data.items as { id: string; humanReview: unknown }[])
      .find((item) => item.id === REVIEW)
    expect(c5?.humanReview).toMatchObject({
      resolvedFindings: 2,
      remainingFindings: 0,
      readyToFinalize: true,
    })
    expect((c5?.humanReview as { blockers?: unknown[] } | undefined)?.blockers).toEqual([])

    const current = await json(demo, `/api/reviews/${REVIEW}/decisions`)
    const items = current.body.data.items as { findingId: string; action: string }[]
    expect(items).toHaveLength(2)
    expect(items.find((item) => item.findingId === 'f1')?.action).toBe('ACCEPT')
    expect(items.find((item) => item.findingId === 'f2')?.action).toBe('REJECT')

    const history = await json(demo, `/api/reviews/${REVIEW}/findings/f1/decisions`)
    const historyItems = history.body.data.items as { action: string; supersedesDecisionId: string | null }[]
    expect(historyItems.at(-1)?.action).toBe('ACCEPT')
    expect(historyItems.at(-1)?.supersedesDecisionId).toBe('recorded-f1')
  })

  it('updates the counts when the visitor changes a decision', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, decision('ACCEPT'))
    await json(demo, `/api/reviews/${REVIEW}/findings/f2/decision`, decision('ACCEPT'))
    expect(getDemoState().stage).toBe('tour-c5-decided')
    const second = await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ expectedVersion: 1, action: 'REJECT', rejectReason: 'FINDING_WRONG' }),
    })
    expect(second.status).toBe(200)
    const review = await json(demo, `/api/reviews/${REVIEW}/human-review`)
    expect(review.body.data).toMatchObject({
      accepted: 1,
      rejected: 1,
      resolvedFindings: 2,
      remainingFindings: 0,
    })
  })

  it('keeps the revision in step when the visitor repeats the recorded action', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    const first = await json(demo, `/api/reviews/${REVIEW}/findings/f2/decision`,
      decision('ACCEPT'))
    expect(first.status).toBe(200)
    expect((first.body.data as { revision: number }).revision).toBe(1)
    const second = await json(demo, `/api/reviews/${REVIEW}/findings/f2/decision`, {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ expectedVersion: 1, action: 'REJECT', rejectReason: 'KEEP_AS_DRAFTED' }),
    })
    expect(second.status).toBe(200)
    const history = await json(demo, `/api/reviews/${REVIEW}/findings/f2/decisions`)
    const revisions = (history.body.data.items as { revision: number }[])
      .map((entry) => entry.revision)
    expect(revisions).toEqual([2, 1])
  })

  it('rejects a decision written against a stale revision', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, decision('ACCEPT'))
    const stale = await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, decision('REJECT'))
    expect(stale.status).toBe(409)
  })

  it('keeps an escalation unresolved and blocking', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`,
      decision('ESCALATE', { comment: 'Ask counsel.' }))
    await json(demo, `/api/reviews/${REVIEW}/findings/f2/decision`, decision('ACCEPT'))
    const review = await json(demo, `/api/reviews/${REVIEW}/human-review`)
    expect(review.body.data).toMatchObject({
      resolvedFindings: 1,
      remainingFindings: 1,
      escalated: 1,
      readyToFinalize: false,
    })
    expect(getDemoState().stage).toBe('tour-k2-published')
    const blockers = review.body.data.blockers as { code: string }[]
    expect(blockers.map((entry) => entry.code)).toContain('ACTIVE_ESCALATION')
  })
})

describe('reads', () => {
  it('shifts ISO instants by the time between capture and now', async () => {
    const now = Date.parse('2026-10-10T10:00:00Z')
    const demo = runtime({ now: () => now })
    advanceDemoStage('tour-k2-published')
    const result = await json(demo, '/api/reviews')
    const item = (result.body as { data: { items: { id: string; createdAt: string }[] } })
      .data.items.find((entry) => entry.id === REVIEW)
    expect(item?.createdAt).toBe('2026-10-10T08:00:00.000Z')
  })

  it('leaves non-instant strings alone when shifting', () => {
    expect(shiftInstants('2026-10-01', 1000)).toBe('2026-10-01')
    expect(shiftInstants('not a date', 1000)).toBe('not a date')
    expect(shiftInstants({ at: '2026-10-01T10:00:00Z' }, 3_600_000))
      .toEqual({ at: '2026-10-01T11:00:00.000Z' })
  })

  it('filters the reviews list by contractId', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    const result = await json(demo, `/api/reviews?contractId=${CONTRACT}&limit=20`)
    const items = (result.body as { data: { items: { id: string }[] } }).data.items
    expect(items.map((item) => item.id)).toEqual([REVIEW])
  })

  it('filters findings by concept, classification and severity', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    const byConcept = await json(demo, `/api/reviews/${REVIEW}/findings?concept=TERM&limit=100`)
    expect((byConcept.body.data.items as { id: string }[]).map((item) => item.id))
      .toEqual(['f1', 'f3'])
    const byClassification = await json(
      demo, `/api/reviews/${REVIEW}/findings?classification=INFORMATIONAL`)
    expect((byClassification.body.data.items as { id: string }[]).map((item) => item.id))
      .toEqual(['f3'])
    const bySeverity = await json(demo, `/api/reviews/${REVIEW}/findings?severity=BLOCKING`)
    expect((bySeverity.body.data.items as { id: string }[]).map((item) => item.id)).toEqual([])
  })

  it('answers an unknown GET with 404 and an empty problem body', async () => {
    const demo = runtime()
    const result = await json(demo, '/api/not-captured')
    expect(result.status).toBe(404)
    expect(result.body).toEqual({})
  })

  it('serves the captured DOCX with its recorded filename and length', async () => {
    const bytes = new Uint8Array([0x50, 0x4b, 0x03, 0x04])
    const baseFetch = vi.fn(async () => new Response(bytes,
      { headers: { 'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' } })) as unknown as typeof fetch
    const demo = runtime({ baseFetch })
    advanceDemoStage('tour-c5-done')
    const response = await demo.fetch(
      new Request(`http://localhost/api/reviews/${REVIEW}/redline/download`))
    expect(response.status).toBe(200)
    expect(response.headers.get('Content-Length')).toBe('4')
    expect(response.headers.get('Content-Disposition')).toContain('nda-redline.docx')
    expect((await response.arrayBuffer()).byteLength).toBe(4)
  })

  it('does not serve a redline before it exists', async () => {
    const demo = runtime()
    const response = await demo.fetch(
      new Request(`http://localhost/api/reviews/${REVIEW}/redline/download`))
    expect(response.status).toBe(404)
  })
})

describe('state', () => {
  it('keeps query normalisation independent of limit and cursor', () => {
    expect(requestKey('GET', '/api/reviews', new URLSearchParams('limit=20'))).toBe(
      'GET /api/reviews')
    expect(requestKey('GET', '/api/reviews',
      new URLSearchParams('limit=20&contractId=c&cursor=x'))).toBe(
      'GET /api/reviews?contractId=c')
  })

  it('reset clears the stage, decisions and the collapsed guide', async () => {
    const demo = runtime()
    advanceDemoStage('tour-k2-published')
    await json(demo, `/api/reviews/${REVIEW}/findings/f1/decision`, decision('ACCEPT'))
    setGuideHidden(true)
    expect(getDemoState().stage).toBe('tour-k2-published')
    resetDemo()
    expect(getDemoState()).toMatchObject({
      stage: 'base',
      decisionHistory: {},
      k2Approved: [],
      guideHidden: false,
    })
    expect(localStorage.getItem('rivell-demo-v1')).toBeNull()
  })
})
