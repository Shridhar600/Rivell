import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import type { Candidate, Publication } from '../../api/knowledge'

function ok(body: unknown): Response {
  return new Response(
    JSON.stringify({ data: body, status: 200, timestamp: '2026-09-23T05:00:00Z' }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

function errorResponse(status: number, detail: string): Response {
  return new Response(JSON.stringify({ code: String(status), title: 'Error', detail }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const artifactDetail = {
  id: 'a1',
  artifactType: 'PLAYBOOK',
  filename: 'playbook.docx',
  contentType: 'application/octet-stream',
  sizeBytes: 2048,
  sha256: 'e'.repeat(64),
  storageKey: 'org/knowledge/a1.docx',
  declaredContractTypes: [],
  contractPackKey: null,
  partyPerspective: null,
  paperSource: null,
  jurisdiction: null,
  language: null,
  effectiveFrom: null,
  effectiveTo: null,
  manifestNote: null,
  ingestStatus: 'EXTRACTED',
  parserVersion: 'rivell-docx-parser-2',
  parseWarnings: [],
  artifactAssessment: null,
  createdAt: '2026-09-23T05:17:29.604991Z',
}

function candidate(partial: Partial<Candidate> & { id: string }): Candidate {
  return {
    artifactId: 'a1',
    extractionUnitId: 'u1',
    candidateType: 'PLAYBOOK_RULE',
    reviewStatus: 'PENDING',
    rawLabel: 'Rule',
    contentText: 'Rule content',
    payloadColumns: {},
    legalConceptKey: null,
    editedContentText: null,
    editedPayloadColumns: {},
    supersedingCandidateId: null,
    contractPackKey: null,
    declaredContractTypes: [],
    partyPerspective: null,
    paperSource: null,
    jurisdiction: null,
    effectiveFrom: null,
    effectiveTo: null,
    conflictSignals: [],
    classifiedConcepts: {},
    positions: [],
    variantRole: null,
    escalationTrigger: null,
    requiresApproval: null,
    interpretations: [],
    reviewCategory: null,
    reviewReasons: [],
    reviewNote: null,
    reviewedAt: null,
    createdAt: '2026-09-23T05:00:00Z',
    updatedAt: '2026-09-23T05:00:00Z',
    ...partial,
  }
}

const approvedOne = candidate({ id: 'c1', rawLabel: 'Termination', reviewStatus: 'APPROVED' })
const approvedTwo = candidate({
  id: 'c3',
  rawLabel: 'Governing law',
  reviewStatus: 'APPROVED',
})
const pendingOne = candidate({ id: 'c2', rawLabel: 'Liability cap' })

const publicationOne: Publication = {
  id: 'pub1',
  organizationId: 'o1',
  publicationNumber: 1,
  note: 'initial publication',
  items: [
    { id: 'i1', candidateId: 'c1', supersededCandidateId: null, action: 'ADD' },
    { id: 'i2', candidateId: 'c3', supersededCandidateId: null, action: 'ADD' },
  ],
  createdAt: '2026-09-23T06:00:00Z',
}

interface ApiState {
  candidates: Candidate[]
  publications: Publication[]
  publishError?: string
}

function stubKnowledgeApi(state: ApiState) {
  const requests: Request[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request
    requests.push(request)
    const url = new URL(request.url)
    const path = url.pathname

    if (path === '/api/knowledge/publications' && request.method === 'POST') {
      if (state.publishError) {
        return errorResponse(409, state.publishError)
      }
      const body = (await request.clone().json()) as {
        candidateIds: string[]
        supersededCandidates?: Record<string, string>
        note?: string
      }
      const publication: Publication = {
        id: 'pub9',
        organizationId: 'o1',
        publicationNumber: 9,
        note: body.note ?? null,
        items: body.candidateIds.map((candidateId, index) => ({
          id: `item-${index}`,
          candidateId,
          supersededCandidateId: body.supersededCandidates?.[candidateId] ?? null,
          action: body.supersededCandidates?.[candidateId]
            ? ('SUPERSEDE' as const)
            : ('ADD' as const),
        })),
        createdAt: '2026-09-23T07:00:00Z',
      }
      state.publications = [...state.publications, publication]
      return ok(publication)
    }
    if (path === '/api/knowledge/publications') {
      return ok(state.publications)
    }
    if (path.startsWith('/api/knowledge/publications/')) {
      const id = path.split('/').at(-1)!
      const found = state.publications.find((entry) => entry.id === id)
      return found ? ok(found) : errorResponse(404, 'Not found')
    }
    if (path.endsWith('/candidates')) return ok(state.candidates)
    if (path.endsWith('/structure') || path.endsWith('/units')) return ok([])
    if (path.includes('/api/knowledge/candidates/')) {
      const id = path.split('/').at(-1)!
      const found = state.candidates.find((entry) => entry.id === id)
      return found ? ok(found) : errorResponse(404, 'Not found')
    }
    if (path.includes('/api/knowledge/artifacts/')) return ok(artifactDetail)
    return errorResponse(404, 'Not found')
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, requests }
}

function renderRoute(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return {
    router,
    ...render(
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>,
    ),
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('knowledge publication', () => {
  it('publishes approved candidates through the confirmation dialog', async () => {
    const state: ApiState = {
      candidates: [approvedOne, approvedTwo, pendingOne],
      publications: [],
    }
    const { requests } = stubKnowledgeApi(state)

    renderRoute('/knowledge/artifacts/a1')

    const publishButton = await screen.findByRole('button', {
      name: /publish approved \(2\)/i,
    })
    await userEvent.click(publishButton)

    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByRole('heading', { name: /publish knowledge version/i }),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('Termination')).toBeInTheDocument()
    expect(within(dialog).getByText('Governing law')).toBeInTheDocument()
    expect(within(dialog).getByText(/become immutable/i)).toBeInTheDocument()

    await userEvent.type(
      within(dialog).getByLabelText(/version note/i),
      'Q3 playbook',
    )
    await userEvent.click(
      within(dialog).getByRole('button', { name: /publish version/i }),
    )

    await screen.findByRole('heading', {
      level: 1,
      name: /knowledge publication v9/i,
    })
    expect(screen.getByText(/Q3 playbook/)).toBeInTheDocument()
    expect(screen.getByText(/2 items · 2 added · 0 superseded/i)).toBeInTheDocument()

    const publishRequest = requests.find(
      (request) => request.method === 'POST' && request.url.endsWith('/publications'),
    )!
    const body = (await publishRequest.clone().json()) as {
      candidateIds: string[]
      note: string
    }
    expect(body.candidateIds).toEqual(['c1', 'c3'])
    expect(body.note).toBe('Q3 playbook')

    expect(
      await screen.findByRole('link', { name: 'Termination' }),
    ).toHaveAttribute('href', '/knowledge/artifacts/a1?candidate=c1')
    expect(screen.getAllByText('Add')).toHaveLength(2)
  })


  it('summarizes supersession and sends it only after an explicit choice', async () => {
    const superseding = candidate({
      id: 'c1',
      rawLabel: 'New liability rule',
      reviewStatus: 'APPROVED',
      reviewCategory: 'HIGH',
      legalConceptKey: 'LIMITATION_OF_LIABILITY',
      conflictSignals: [
        {
          conflictType: 'SUPERSEDES_EXISTING',
          message: 'Appears intended to replace a published position (p=0.93)',
          publishedCandidateId: 'old1',
        },
      ],
    })
    const old = candidate({
      id: 'old1',
      rawLabel: 'Old liability rule',
      reviewStatus: 'PUBLISHED',
      legalConceptKey: 'LIMITATION_OF_LIABILITY',
    })
    const state: ApiState = { candidates: [superseding, old], publications: [] }
    const { requests } = stubKnowledgeApi(state)

    renderRoute('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /New liability rule/i })
    await userEvent.click(
      within(panel).getByRole('checkbox', {
        name: /replace this published knowledge when publishing/i,
      }),
    )

    await userEvent.click(
      screen.getByRole('button', { name: /publish approved \(1\)/i }),
    )

    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByRole('heading', { name: /replacing published knowledge/i }),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('Old liability rule')).toBeInTheDocument()
    expect(
      within(dialog).getByText(/1 candidate with high review attention/i),
    ).toBeInTheDocument()

    await userEvent.click(
      within(dialog).getByRole('button', { name: /publish version/i }),
    )
    await screen.findByRole('heading', {
      level: 1,
      name: /knowledge publication v9/i,
    })

    const publishRequest = requests.find(
      (request) => request.method === 'POST' && request.url.endsWith('/publications'),
    )!
    const body = (await publishRequest.clone().json()) as {
      supersededCandidates?: Record<string, string>
    }
    expect(body.supersededCandidates).toEqual({ c1: 'old1' })
  })

  it('keeps publishing disabled until a candidate is approved', async () => {
    stubKnowledgeApi({ candidates: [pendingOne], publications: [] })

    renderRoute('/knowledge/artifacts/a1')

    const publishButton = await screen.findByRole('button', {
      name: /publish approved \(0\)/i,
    })
    expect(publishButton).toBeDisabled()
    expect(
      screen.getByText(/approve candidates to publish a new knowledge version/i),
    ).toBeInTheDocument()
  })

  it('surfaces publication failures inside the dialog', async () => {
    stubKnowledgeApi({
      candidates: [approvedOne],
      publications: [],
      publishError:
        'Candidate c1 cannot be published: only APPROVED candidates can be published',
    })

    renderRoute('/knowledge/artifacts/a1')

    await userEvent.click(
      await screen.findByRole('button', { name: /publish approved \(1\)/i }),
    )

    const dialog = await screen.findByRole('dialog')
    await userEvent.click(
      within(dialog).getByRole('button', { name: /publish version/i }),
    )

    expect(
      await within(dialog).findByText(/only APPROVED candidates can be published/i),
    ).toBeInTheDocument()
    expect(dialog).toHaveAttribute('open')
  })

  it('lists published versions and opens a version with item labels', async () => {
    stubKnowledgeApi({
      candidates: [approvedOne, approvedTwo],
      publications: [publicationOne],
    })

    renderRoute('/knowledge/publications')

    expect(
      await screen.findByRole('heading', { level: 1, name: 'Published versions' }),
    ).toBeInTheDocument()
    await userEvent.click(
      await screen.findByRole('link', { name: 'Publication v1' }),
    )

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Knowledge publication v1',
      }),
    ).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Termination' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Governing law' })).toBeInTheDocument()
    expect(screen.getByText(/initial publication/)).toBeInTheDocument()
  })

  it('shows the published version on an immutable candidate', async () => {
    const published = candidate({
      id: 'c4',
      rawLabel: 'Payment terms',
      reviewStatus: 'PUBLISHED',
    })
    stubKnowledgeApi({
      candidates: [published, pendingOne],
      publications: [
        {
          ...publicationOne,
          items: [
            { id: 'i1', candidateId: 'c4', supersededCandidateId: null, action: 'ADD' },
          ],
        },
      ],
    })

    renderRoute('/knowledge/artifacts/a1?candidate=c4')

    const panel = await screen.findByRole('article', { name: /Payment terms/i })
    expect(
      within(panel).getByText(/published knowledge is immutable/i),
    ).toBeInTheDocument()
    const link = await within(panel).findByRole('link', { name: /publication v1/i })
    expect(link).toHaveAttribute('href', '/knowledge/publications/pub1')
  })

  it('shows superseded knowledge in a publication with a SUPERSEDE item', async () => {
    const superseding = candidate({
      id: 'c5',
      rawLabel: 'New termination rule',
      reviewStatus: 'PUBLISHED',
    })
    const superseded = candidate({
      id: 'old1',
      rawLabel: 'Old termination rule',
      reviewStatus: 'PUBLISHED',
    })
    stubKnowledgeApi({
      candidates: [superseding, superseded],
      publications: [
        {
          id: 'pub2',
          organizationId: 'o1',
          publicationNumber: 2,
          note: null,
          items: [
            {
              id: 'i1',
              candidateId: 'c5',
              supersededCandidateId: 'old1',
              action: 'SUPERSEDE',
            },
          ],
          createdAt: '2026-09-23T08:00:00Z',
        },
      ],
    })

    renderRoute('/knowledge/publications/pub2')

    expect(
      await screen.findByRole('heading', {
        level: 1,
        name: 'Knowledge publication v2',
      }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('link', { name: 'New termination rule' }),
    ).toBeInTheDocument()
    expect(screen.getByText('Supersede')).toBeInTheDocument()
    expect(
      await screen.findByRole('link', { name: 'Old termination rule' }),
    ).toBeInTheDocument()
  })
})
