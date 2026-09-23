import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import type { Candidate } from '../../api/knowledge'

function ok(body: unknown): Response {
  return new Response(
    JSON.stringify({ data: body, status: 200, timestamp: '2026-09-23T05:00:00Z' }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

function errorResponse(code: string, status: number, detail: string): Response {
  return new Response(JSON.stringify({ code, title: code, detail }), {
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
  sha256: 'd'.repeat(64),
  storageKey: 'org/knowledge/a1.docx',
  declaredContractTypes: [],
  contractPackKey: 'MSA',
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

function knowledgeNode(
  id: string,
  nodeType: string,
  parentId: string | null,
  extra: Record<string, unknown> = {},
) {
  return {
    id,
    artifactId: 'a1',
    parentId,
    nodeType,
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
    ...extra,
  }
}

const structureNodes = [
  knowledgeNode('body', 'BODY', null),
  knowledgeNode('h1', 'HEADING', 'body', {
    text: 'Limitation of Liability',
    headingLevel: 1,
    bodyIndex: 1,
    paragraphIndex: 1,
  }),
  knowledgeNode('tbl', 'TABLE', 'body', { bodyIndex: 2, tableIndex: 0 }),
  knowledgeNode('row0', 'TABLE_ROW', 'tbl', { rowIndex: 0, tableIndex: 0 }),
  knowledgeNode('row1', 'TABLE_ROW', 'tbl', { rowIndex: 1, tableIndex: 0 }),
  knowledgeNode('cell00', 'TABLE_CELL', 'row0', {
    text: 'Clause',
    rowIndex: 0,
    columnIndex: 0,
  }),
  knowledgeNode('cell01', 'TABLE_CELL', 'row0', {
    text: 'Preferred',
    rowIndex: 0,
    columnIndex: 1,
  }),
  knowledgeNode('cell10', 'TABLE_CELL', 'row1', {
    text: 'Liability cap',
    rowIndex: 1,
    columnIndex: 0,
  }),
  knowledgeNode('cell11', 'TABLE_CELL', 'row1', {
    text: '1x annual fees',
    rowIndex: 1,
    columnIndex: 1,
  }),
]

const units = [
  {
    id: 'u1',
    artifactId: 'a1',
    ordinal: 0,
    kind: 'HEADING_SECTION',
    title: 'Limitation of Liability',
    contentText: null,
    sourceNodeIds: ['h1'],
  },
  {
    id: 'u2',
    artifactId: 'a1',
    ordinal: 1,
    kind: 'TABLE',
    title: null,
    contentText: null,
    sourceNodeIds: ['tbl'],
  },
]

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
    contractPackKey: 'MSA',
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

const publishedCandidate = candidate({
  id: 'p1',
  reviewStatus: 'PUBLISHED',
  rawLabel: 'Liability cap',
  payloadColumns: {
    Clause: 'Liability cap',
    Preferred: '1x annual fees',
    Fallback: '2x annual fees',
  },
})

function baseCandidates(): Candidate[] {
  return [
    candidate({ id: 'c1', rawLabel: 'Termination' }),
    candidate({
      id: 'c2',
      rawLabel: 'Liability cap',
      extractionUnitId: 'u2',
      contentText: 'Liability cap | 1x annual fees',
      payloadColumns: {
        Clause: 'Liability cap',
        Preferred: '1x annual fees',
        Fallback: '2x annual fees',
      },
      conflictSignals: [
        {
          conflictType: 'DUPLICATE',
          message: 'Matches published candidate with the same label and content',
          publishedCandidateId: 'p1',
        },
      ],
    }),
    candidate({ id: 'c3', rawLabel: 'Governing law', reviewStatus: 'APPROVED' }),
    candidate({ id: 'c4', rawLabel: 'Payment terms', reviewStatus: 'PUBLISHED' }),
    candidate({
      id: 'c5',
      rawLabel: 'Unlimited liability',
      reviewStatus: 'REJECTED',
      reviewNote: 'Not organizational policy',
    }),
  ]
}

interface ApiState {
  candidates: Candidate[]
  structureError?: boolean
  bulkSkipped?: Record<string, string>
}

function stubKnowledgeApi(state: ApiState) {
  const requests: Request[] = []
  const machineById = new Map(
    state.candidates.map((entry) => [
      entry.id,
      {
        positions: entry.positions,
        requiresApproval: entry.requiresApproval,
        escalationTrigger: entry.escalationTrigger,
        variantRole: entry.variantRole,
      },
    ]),
  )
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const request = input as Request
    requests.push(request)
    const url = new URL(request.url)
    const path = url.pathname

    if (path.endsWith('/candidates') && request.method === 'GET') {
      return ok(state.candidates)
    }
    if (path.endsWith('/structure')) {
      return state.structureError
        ? errorResponse('500', 500, 'Structure unavailable')
        : ok(structureNodes)
    }
    if (path.endsWith('/units')) return ok(units)
    if (path.endsWith('/bulk-approve')) {
      const body = (await request.clone().json()) as { candidateIds?: string[] }
      const requested = body.candidateIds ?? []
      const approvedIds = requested.filter(
        (candidateId) => !state.bulkSkipped?.[candidateId],
      )
      state.candidates = state.candidates.map((entry) =>
        approvedIds.includes(entry.id)
          ? { ...entry, reviewStatus: 'APPROVED', updatedAt: '2026-09-23T06:00:00Z' }
          : entry,
      )
      return ok({ approvedIds, skipped: state.bulkSkipped ?? {} })
    }
    if (path.endsWith('/approve')) {
      const id = path.split('/').at(-2)!
      const body = await request.clone().json().catch(() => ({}))
      void body
      state.candidates = state.candidates.map((entry) =>
        entry.id === id
          ? { ...entry, reviewStatus: 'APPROVED', updatedAt: '2026-09-23T06:00:00Z' }
          : entry,
      )
      return ok(state.candidates.find((entry) => entry.id === id))
    }
    if (path.endsWith('/edit')) {
      const id = path.split('/').at(-2)!
      const body = (await request.clone().json()) as {
        contentText?: string
        payloadColumns?: Record<string, string>
        scope?: {
          contractPackKey?: string
          declaredContractTypes?: string[]
          partyPerspective?: string
          paperSource?: string
          jurisdiction?: string
          effectiveFrom?: string
          effectiveTo?: string
        }
        semanticFields?: {
          positions?: Candidate['positions']
          requiresApproval?: boolean
          escalationTrigger?: string
          variantRole?: Candidate['variantRole']
        }
        note?: string
      }
      state.candidates = state.candidates.map((entry) => {
        if (entry.id !== id) return entry
        const machine = machineById.get(id)!
        const edited =
          body.semanticFields && Object.keys(body.semanticFields).length > 0
            ? body.semanticFields
            : null
        return {
              ...entry,
              reviewStatus: 'EDITED',
              editedContentText: body.contentText ?? entry.editedContentText,
              editedPayloadColumns:
                body.payloadColumns ?? entry.editedPayloadColumns,
              contractPackKey: body.scope
                ? (body.scope.contractPackKey ?? null)
                : entry.contractPackKey,
              declaredContractTypes: body.scope
                ? (body.scope.declaredContractTypes ?? [])
                : entry.declaredContractTypes,
              partyPerspective: body.scope
                ? (body.scope.partyPerspective ?? null)
                : entry.partyPerspective,
              paperSource: body.scope
                ? (body.scope.paperSource ?? null)
                : entry.paperSource,
              jurisdiction: body.scope
                ? (body.scope.jurisdiction ?? null)
                : entry.jurisdiction,
              effectiveFrom: body.scope
                ? (body.scope.effectiveFrom ?? null)
                : entry.effectiveFrom,
              effectiveTo: body.scope
                ? (body.scope.effectiveTo ?? null)
                : entry.effectiveTo,
              positions:
                edited && edited.positions && edited.positions.length > 0
                  ? edited.positions
                  : edited
                    ? machine.positions
                    : entry.positions,
              requiresApproval:
                edited && edited.requiresApproval !== undefined
                  ? edited.requiresApproval
                  : edited
                    ? machine.requiresApproval
                    : entry.requiresApproval,
              escalationTrigger:
                edited && edited.escalationTrigger !== undefined
                  ? edited.escalationTrigger
                  : edited
                    ? machine.escalationTrigger
                    : entry.escalationTrigger,
              variantRole:
                edited && edited.variantRole !== undefined
                  ? edited.variantRole
                  : edited
                    ? machine.variantRole
                    : entry.variantRole,
              reviewNote: body.note ?? null,
              updatedAt: '2026-09-23T06:00:00Z',
            }
      })
      return ok(state.candidates.find((entry) => entry.id === id))
    }
    if (path.endsWith('/reject')) {
      const id = path.split('/').at(-2)!
      const body = (await request.clone().json()) as { note?: string }
      state.candidates = state.candidates.map((entry) =>
        entry.id === id
          ? {
              ...entry,
              reviewStatus: 'REJECTED',
              reviewNote: body.note ?? null,
              updatedAt: '2026-09-23T06:00:00Z',
            }
          : entry,
      )
      return ok(state.candidates.find((entry) => entry.id === id))
    }
    if (path.includes('/api/knowledge/candidates/')) {
      const id = path.split('/').at(-1)!
      const found =
        state.candidates.find((entry) => entry.id === id) ?? publishedCandidate
      return found.id === id ? ok(found) : errorResponse('404', 404, 'Not found')
    }
    if (path.includes('/api/knowledge/artifacts/')) {
      const artifactId = path.split('/')[4]
      return ok({ ...artifactDetail, id: artifactId })
    }
    return errorResponse('404', 404, 'Not found')
  })
  vi.stubGlobal('fetch', fetchMock)
  return { fetchMock, requests }
}

function renderArtifact(path = '/knowledge/artifacts/a1') {
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

function queue() {
  return screen.getByRole('region', { name: 'Candidates' })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('candidate review workspace', () => {
  it('reviews a table candidate from the queue through approval', async () => {
    const state: ApiState = { candidates: baseCandidates() }
    stubKnowledgeApi(state)

    renderArtifact()

    expect(
      await screen.findByText(/5 candidates · 1 approved · 0 edited · 2 pending/),
    ).toBeInTheDocument()
    expect(within(queue()).getByText('Conflict')).toBeInTheDocument()

    await userEvent.click(
      within(queue()).getByRole('button', { name: /Liability cap/ }),
    )

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(within(panel).getByText(/Playbook rule/)).toBeInTheDocument()
    expect(within(panel).getAllByText('Clause').length).toBeGreaterThan(0)
    expect(within(panel).getAllByText('1x annual fees').length).toBeGreaterThan(0)
    expect(within(panel).getByText(/Table · unit 2/)).toBeInTheDocument()
    expect(
      within(panel).getByText(/possible duplicate/i),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText(/extraction unit 2 \(table\)/i),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText(/source nodes: word\/document\.xml/i),
    ).toBeInTheDocument()
    expect(within(panel).getByText(/playbook\.docx · sha256/)).toBeInTheDocument()

    await userEvent.click(within(panel).getByRole('button', { name: 'Approve' }))

    await waitFor(() =>
      expect(screen.getByText(/2 approved/)).toBeInTheDocument(),
    )
    expect(screen.getAllByText('Approved').length).toBeGreaterThan(0)
  })


  it('shows review attention and reasons without implying legal risk', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          reviewCategory: 'HIGH',
          reviewReasons: ['VERIFICATION_DISPUTED', 'CONFLICT_DETECTED'],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    await screen.findByRole('region', { name: 'Candidates' })
    expect(
      within(queue()).getByText(/high review attention/i),
    ).toBeInTheDocument()

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(within(panel).getByText(/high review attention/i)).toBeInTheDocument()
    expect(
      within(panel).getByText(/not sufficiently supported by the source/i),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText(/may contradict published knowledge/i),
    ).toBeInTheDocument()
    expect(within(panel).getByText(/not a legal-risk rating/i)).toBeInTheDocument()
  })


  it('shows semantic interpretation with machine proposal and verification', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          legalConceptKey: 'LIMITATION_OF_LIABILITY',
          positions: ['PREFERRED'],
          escalationTrigger: 'Anything above 2x requires GC approval.',
          interpretations: [
            {
              field: 'positions',
              proposedValue: 'FALLBACK,PREFERRED',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'playbook-position',
              specVersion: 1,
              supportingSourceNodes: 2,
              verification: 'NOT_VERIFIED',
            },
            {
              field: 'escalationTrigger',
              proposedValue: 'Anything above 2x requires GC approval.',
              kind: 'GENERATIVE_EXTRACTION',
              specId: 'escalation-trigger-extraction',
              specVersion: 1,
              supportingSourceNodes: 3,
              verification: 'DISPUTED',
            },
          ],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(
      within(panel).getByText('Limitation of liability'),
    ).toBeInTheDocument()
    expect(within(panel).getByText('Preferred')).toBeInTheDocument()
    expect(
      within(panel).getByText(/Rivell proposed: Fallback, Preferred/),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText('Anything above 2x requires GC approval.'),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText('Source support disputed'),
    ).toBeInTheDocument()
    expect(within(panel).getByText(/supported by 2 source regions/i)).toBeInTheDocument()
    expect(
      within(panel).getByText(/positions: classified from the source text/i),
    ).toBeInTheDocument()
    expect(panel.textContent).not.toMatch(/0\.9|spec v|specVersion/i)
  })

  it('shows an unclassified concept honestly', async () => {
    stubKnowledgeApi({
      candidates: [candidate({ id: 'c1', rawLabel: 'Misc note', legalConceptKey: null })],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Misc note/i })
    expect(
      within(panel).getByText(/not confidently classified/i),
    ).toBeInTheDocument()
  })

  it('edits a candidate column value and preserves the machine proposal', async () => {
    const state: ApiState = { candidates: baseCandidates() }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c2')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    await userEvent.click(within(panel).getByRole('button', { name: 'Edit' }))

    const preferred = screen.getByLabelText('Preferred', { selector: 'textarea' })
    expect(preferred).toHaveValue('1x annual fees')
    await userEvent.clear(preferred)
    await userEvent.type(preferred, '1.5x annual fees')
    await userEvent.type(screen.getByLabelText(/review note/i), 'negotiated')

    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))

    await waitFor(() => {
      const editRequest = requests.find((request) => request.url.endsWith('/edit'))
      expect(editRequest).toBeDefined()
    })
    const editRequest = requests.find((request) => request.url.endsWith('/edit'))!
    const body = (await editRequest.clone().json()) as {
      payloadColumns?: Record<string, string>
      note?: string
    }
    expect(body.payloadColumns?.Preferred).toBe('1.5x annual fees')
    expect(body.payloadColumns?.Clause).toBe('Liability cap')
    expect(body.note).toBe('negotiated')

    const editedPanel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(within(editedPanel).getByText('1.5x annual fees')).toBeInTheDocument()
    expect(
      within(editedPanel).getByRole('heading', { name: /machine proposal/i }),
    ).toBeInTheDocument()
    expect(
      within(editedPanel).getByText(/must be approved before they can be published/i),
    ).toBeInTheDocument()
  })


  it('edits applicability scope and shows the manifest comparison', async () => {
    const state: ApiState = {
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          jurisdiction: 'US',
          interpretations: [
            {
              field: 'scope',
              proposedValue: 'UK',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'scope-narrowing',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
      ],
    }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(
      within(panel).getByText(/Rivell suggests narrowing jurisdiction to UK/i),
    ).toBeInTheDocument()

    await userEvent.click(within(panel).getByRole('button', { name: 'Edit' }))
    const jurisdiction = screen.getByLabelText('Jurisdiction')
    await userEvent.clear(jurisdiction)
    await userEvent.type(jurisdiction, 'UK')
    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))

    await waitFor(() => {
      expect(state.candidates[0].jurisdiction).toBe('UK')
    })
    const editRequest = requests.find((request) => request.url.endsWith('/edit'))!
    const body = (await editRequest.clone().json()) as {
      scope?: { jurisdiction?: string; declaredContractTypes?: string[] }
    }
    expect(body.scope?.jurisdiction).toBe('UK')
    expect(body.scope?.declaredContractTypes).toEqual([])

    const editedPanel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(within(editedPanel).getByText('UK')).toBeInTheDocument()
    expect(
      within(editedPanel).getByText(/adjusted from the artifact manifest/i),
    ).toBeInTheDocument()
  })

  it('edits a negotiation position and keeps the machine proposal visible', async () => {
    const state: ApiState = {
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          positions: ['PREFERRED'],
          interpretations: [
            {
              field: 'positions',
              proposedValue: 'FALLBACK,PREFERRED',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'playbook-position',
              specVersion: 1,
              supportingSourceNodes: 2,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
      ],
    }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    await userEvent.click(within(panel).getByRole('button', { name: 'Edit' }))

    await userEvent.click(screen.getByRole('checkbox', { name: 'Preferred' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Fallback' }))
    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))

    await waitFor(() => {
      expect(state.candidates[0].positions).toEqual(['FALLBACK'])
    })
    const editRequest = requests.find((request) => request.url.endsWith('/edit'))!
    const body = (await editRequest.clone().json()) as {
      semanticFields?: { positions?: string[] }
    }
    expect(body.semanticFields?.positions).toEqual(['FALLBACK'])

    const editedPanel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(within(editedPanel).getByText('Fallback')).toBeInTheDocument()
    expect(
      within(editedPanel).getByText(/Rivell proposed: Fallback, Preferred/),
    ).toBeInTheDocument()
  })


  it('preserves earlier human semantic corrections across sequential edits', async () => {
    const state: ApiState = {
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          positions: ['PREFERRED'],
          requiresApproval: false,
          interpretations: [
            {
              field: 'positions',
              proposedValue: 'PREFERRED',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'playbook-position',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
            {
              field: 'requiresApproval',
              proposedValue: 'false',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'playbook-position',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
      ],
    }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    // Edit 1: set approval to Required.
    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    await userEvent.click(within(panel).getByRole('button', { name: 'Edit' }))
    await userEvent.selectOptions(screen.getByLabelText(/general counsel approval/i), 'true')
    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))
    await waitFor(() => expect(state.candidates[0].requiresApproval).toBe(true))

    // Edit 2: change positions only; approval correction must survive.
    const panelAfterFirst = await screen.findByRole('article', {
      name: /Liability cap/i,
    })
    await userEvent.click(within(panelAfterFirst).getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Preferred' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Fallback' }))
    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))

    await waitFor(() => expect(state.candidates[0].positions).toEqual(['FALLBACK']))
    expect(state.candidates[0].requiresApproval).toBe(true)

    const editRequests = requests.filter((request) => request.url.endsWith('/edit'))
    expect(editRequests).toHaveLength(2)
    const secondBody = (await editRequests[1].clone().json()) as {
      semanticFields?: { positions?: string[]; requiresApproval?: boolean }
    }
    expect(secondBody.semanticFields?.positions).toEqual(['FALLBACK'])
    expect(secondBody.semanticFields?.requiresApproval).toBe(true)
  })

  it('sends only changed content and payload on a semantic-only edit', async () => {
    const state: ApiState = {
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          positions: ['PREFERRED'],
          interpretations: [
            {
              field: 'positions',
              proposedValue: 'PREFERRED',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'playbook-position',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
      ],
    }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    await userEvent.click(within(panel).getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Preferred' }))
    await userEvent.click(screen.getByRole('checkbox', { name: 'Fallback' }))
    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))

    await waitFor(() => expect(state.candidates[0].positions).toEqual(['FALLBACK']))
    const editRequest = requests.find((request) => request.url.endsWith('/edit'))!
    const body = (await editRequest.clone().json()) as Record<string, unknown>
    expect(body.semanticFields).toBeDefined()
    expect(body.contentText).toBeUndefined()
    expect(body.payloadColumns).toBeUndefined()
    expect(body.scope).toBeUndefined()
  })

  it('renders human-corrected content as effective and keeps the machine text', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Termination',
          contentText: 'MACHINE TEXT',
          editedContentText: 'HUMAN CORRECTED TEXT',
          reviewStatus: 'EDITED',
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Termination/i })
    expect(within(panel).getByText('HUMAN CORRECTED TEXT')).toBeInTheDocument()
    expect(
      within(panel).getByRole('heading', { name: /machine proposal/i }),
    ).toBeInTheDocument()
    expect(within(panel).getByText('MACHINE TEXT')).toBeInTheDocument()
  })


  it('renders edited text for payload-row candidates', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          contentText: 'Liability cap | 1x annual fees',
          payloadColumns: { Clause: 'Liability cap', Preferred: '1x annual fees' },
          editedContentText: 'Liability cap | 1x annual fees | reviewed',
          editedPayloadColumns: { Clause: 'Liability cap', Preferred: '1x annual fees' },
          reviewStatus: 'EDITED',
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(
      within(panel).getByText('Liability cap | 1x annual fees | reviewed'),
    ).toBeInTheDocument()
    expect(
      within(panel).getByRole('heading', { name: /machine proposal/i }),
    ).toBeInTheDocument()
    expect(
      within(panel).getByText('Liability cap | 1x annual fees'),
    ).toBeInTheDocument()
  })

  it('shows a disputed generative proposal instead of hiding it', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Escalation',
          escalationTrigger: null,
          interpretations: [
            {
              field: 'escalationTrigger',
              proposedValue: 'Anything above 2x requires GC approval.',
              kind: 'GENERATIVE_EXTRACTION',
              specId: 'escalation-trigger-extraction',
              specVersion: 1,
              supportingSourceNodes: 3,
              verification: 'DISPUTED',
            },
          ],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Escalation/i })
    expect(within(panel).getByText('None extracted')).toBeInTheDocument()
    expect(
      within(panel).getByText(/Rivell proposed: Anything above 2x requires GC approval/),
    ).toBeInTheDocument()
    expect(within(panel).getByText('Source support disputed')).toBeInTheDocument()
  })

  it('renders clause variant semantics only for clause variants and formats proposals', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Confidentiality',
          candidateType: 'CLAUSE_VARIANT',
          variantRole: 'PREFERRED',
          interpretations: [
            {
              field: 'variantRole',
              proposedValue: 'FALLBACK',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'clause-variant-role',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
        candidate({
          id: 'c2',
          rawLabel: 'Scope of application',
          variantRole: 'UNSPECIFIED',
          positions: [],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const clausePanel = await screen.findByRole('article', { name: /Confidentiality/i })
    expect(within(clausePanel).getByText('Clause variant role')).toBeInTheDocument()
    expect(within(clausePanel).getByText(/Rivell proposed: Fallback/)).toBeInTheDocument()

    await userEvent.click(
      within(queue()).getByRole('button', { name: /Scope of application/ }),
    )
    const playbookPanel = await screen.findByRole('article', {
      name: /Scope of application/i,
    })
    expect(
      within(playbookPanel).queryByText('Clause variant role'),
    ).not.toBeInTheDocument()
  })

  it('suppresses the narrowing suggestion once the human applied it', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          jurisdiction: 'UK',
          interpretations: [
            {
              field: 'scope',
              proposedValue: 'UK',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'scope-narrowing',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(
      within(panel).queryByText(/Rivell suggests narrowing jurisdiction/i),
    ).not.toBeInTheDocument()
    expect(
      within(panel).getByText(/adjusted from the artifact manifest/i),
    ).toBeInTheDocument()
  })

  it('formats machine proposals in product language', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'Liability cap',
          requiresApproval: true,
          interpretations: [
            {
              field: 'requiresApproval',
              proposedValue: 'false',
              kind: 'BOUNDED_CLASSIFICATION',
              specId: 'playbook-position',
              specVersion: 1,
              supportingSourceNodes: 1,
              verification: 'NOT_VERIFIED',
            },
          ],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(
      within(panel).getByText(/Rivell proposed: Not required/),
    ).toBeInTheDocument()
  })

  it('rejects a save with no changes and sends no request', async () => {
    const state: ApiState = {
      candidates: [candidate({ id: 'c1', rawLabel: 'Liability cap' })],
    }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    await userEvent.click(within(panel).getByRole('button', { name: 'Edit' }))
    await userEvent.click(screen.getByRole('button', { name: /save edit/i }))

    expect(
      await screen.findByText(/an edit must change content/i),
    ).toBeInTheDocument()
    expect(requests.some((request) => request.url.endsWith('/edit'))).toBe(false)
  })

  it('requires a reason before rejecting and records it', async () => {
    const state: ApiState = { candidates: baseCandidates() }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Termination/i })
    await userEvent.click(within(panel).getByRole('button', { name: 'Reject' }))
    await userEvent.click(
      within(panel).getByRole('button', { name: /reject candidate/i }),
    )

    expect(
      await within(panel).findByText(/add a short reason/i),
    ).toBeInTheDocument()

    await userEvent.type(
      within(panel).getByLabelText(/reason for rejection/i),
      'Not policy',
    )
    await userEvent.click(
      within(panel).getByRole('button', { name: /reject candidate/i }),
    )

    await waitFor(() => {
      expect(state.candidates.find((entry) => entry.id === 'c1')?.reviewStatus).toBe(
        'REJECTED',
      )
    })
    const rejectRequest = requests.find((request) =>
      request.url.endsWith('/reject'),
    )!
    const body = (await rejectRequest.clone().json()) as { note?: string }
    expect(body.note).toBe('Not policy')

    const rejected = await screen.findByRole('article', { name: /Termination/i })
    expect(
      within(rejected).getByText(/rejected: not policy/i),
    ).toBeInTheDocument()
  })

  it('treats published and rejected candidates as final', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    renderArtifact('/knowledge/artifacts/a1?candidate=c4')

    const published = await screen.findByRole('article', { name: /Payment terms/i })
    expect(
      within(published).getByText(/published knowledge is immutable/i),
    ).toBeInTheDocument()
    expect(within(published).queryByRole('button', { name: 'Approve' })).toBeNull()
    expect(within(published).queryByRole('button', { name: 'Edit' })).toBeNull()
    expect(within(published).queryByRole('button', { name: 'Reject' })).toBeNull()
  })

  it('shows advisory conflicts and compares with the published candidate', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    renderArtifact('/knowledge/artifacts/a1?candidate=c2')

    const panel = await screen.findByRole('article', { name: /Liability cap/i })
    expect(
      within(panel).getByText(/signals are advisory/i),
    ).toBeInTheDocument()
    expect(panel.textContent).not.toMatch(/p=0\./)

    await userEvent.click(
      within(panel).getByRole('button', { name: /compare with published knowledge/i }),
    )

    expect(
      await within(panel).findByText(/published knowledge: Liability cap/i),
    ).toBeInTheDocument()
  })


  it('requires an explicit human choice to replace published knowledge', async () => {
    stubKnowledgeApi({
      candidates: [
        candidate({
          id: 'c1',
          rawLabel: 'New liability rule',
          legalConceptKey: 'LIMITATION_OF_LIABILITY',
          positions: ['FALLBACK'],
          conflictSignals: [
            {
              conflictType: 'SUPERSEDES_EXISTING',
              message: 'Appears intended to replace a published position (p=0.93)',
              publishedCandidateId: 'p1',
            },
          ],
        }),
      ],
    })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /New liability rule/i })
    expect(
      within(panel).getByText(/possible supersession/i),
    ).toBeInTheDocument()

    const replacement = within(panel).getByRole('checkbox', {
      name: /replace this published knowledge when publishing/i,
    })
    expect(replacement).not.toBeChecked()

    await userEvent.click(replacement)
    expect(replacement).toBeChecked()
  })

  it('keeps review actions available when the source context fails', async () => {
    stubKnowledgeApi({ candidates: baseCandidates(), structureError: true })

    renderArtifact('/knowledge/artifacts/a1?candidate=c1')

    const panel = await screen.findByRole('article', { name: /Termination/i })
    expect(
      await within(panel).findByText(/source region is not available/i),
    ).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Approve' })).toBeInTheDocument()
  })

  it('reports a stale deep-linked candidate', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    renderArtifact('/knowledge/artifacts/a1?candidate=missing')

    expect(
      await screen.findByText(/selected candidate is not part of this knowledge source/i),
    ).toBeInTheDocument()
  })


  it('bulk approves only eligible candidates and announces server outcomes', async () => {
    const state: ApiState = {
      candidates: [
        candidate({ id: 'c1', rawLabel: 'Termination', reviewCategory: 'ROUTINE' }),
        candidate({ id: 'c2', rawLabel: 'Liability cap', reviewCategory: 'HIGH' }),
        candidate({
          id: 'c3',
          rawLabel: 'Governing law',
          reviewCategory: 'ROUTINE',
          reviewStatus: 'APPROVED',
        }),
      ],
    }
    const { requests } = stubKnowledgeApi(state)

    renderArtifact()

    expect(
      await screen.findByText(/1 of 3 candidates are eligible for bulk approval/i),
    ).toBeInTheDocument()

    expect(
      screen.getByRole('checkbox', { name: /select Termination for bulk approval/i }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('checkbox', { name: /select Liability cap for bulk approval/i }),
    ).toBeNull()

    await userEvent.click(
      screen.getByRole('checkbox', { name: /select Termination for bulk approval/i }),
    )
    await userEvent.click(
      screen.getByRole('button', { name: /approve selected \(1\)/i }),
    )

    await waitFor(() => {
      expect(state.candidates[0].reviewStatus).toBe('APPROVED')
    })
    const bulkRequest = requests.find((request) =>
      request.url.endsWith('/bulk-approve'),
    )!
    const body = (await bulkRequest.clone().json()) as { candidateIds: string[] }
    expect(body.candidateIds).toEqual(['c1'])
    expect(await screen.findByText(/approved 1 candidate/i)).toBeInTheDocument()
  })

  it('surfaces server-skipped bulk candidates with reasons', async () => {
    const state: ApiState = {
      candidates: [
        candidate({ id: 'c1', rawLabel: 'Termination', reviewCategory: 'ROUTINE' }),
      ],
      bulkSkipped: { c1: 'not ROUTINE after re-evaluation' },
    }
    stubKnowledgeApi(state)

    renderArtifact()

    await userEvent.click(
      await screen.findByRole('checkbox', {
        name: /select Termination for bulk approval/i,
      }),
    )
    await userEvent.click(
      screen.getByRole('button', { name: /approve selected \(1\)/i }),
    )

    expect(
      await screen.findByText(/1 skipped by the server/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/Termination: not ROUTINE after re-evaluation/i),
    ).toBeInTheDocument()
  })


  it('resets workspace filters when navigating to another artifact', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    const { router } = renderArtifact('/knowledge/artifacts/a1')

    await screen.findByRole('region', { name: 'Candidates' })
    await act(async () => {
      await router.navigate('/knowledge/artifacts/a2')
    })
    await screen.findByRole('region', { name: 'Candidates' })
    await act(async () => {
      await router.navigate('/knowledge/artifacts/a1')
    })
    await screen.findByRole('region', { name: 'Candidates' })

    await userEvent.selectOptions(within(queue()).getByLabelText('Status'), 'APPROVED')
    expect(within(queue()).getByLabelText('Status')).toHaveValue('APPROVED')

    await act(async () => {
      await router.navigate('/knowledge/artifacts/a2')
    })
    await screen.findByRole('region', { name: 'Candidates' })
    expect(within(queue()).getByLabelText('Status')).toHaveValue('ALL')
  })

  it('moves focus to the review panel when a candidate is selected', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    renderArtifact()

    await screen.findByRole('region', { name: 'Candidates' })
    const item = await within(queue()).findByRole('button', { name: /Termination/ })
    await userEvent.click(item)

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 3, name: /Termination/ }),
      ).toHaveFocus()
    })
  })

  it('does not stack history entries when the same candidate is selected again', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    const { router } = renderArtifact()

    await screen.findByRole('region', { name: 'Candidates' })
    const item = await within(queue()).findByRole('button', { name: /Termination/ })
    await userEvent.click(item)
    await waitFor(() => expect(item).toHaveAttribute('aria-current', 'true'))

    await userEvent.click(item)
    await router.navigate(-1)

    await waitFor(() => expect(item).not.toHaveAttribute('aria-current'))
  })

  it('filters the queue by status and conflicts', async () => {
    stubKnowledgeApi({ candidates: baseCandidates() })

    renderArtifact()

    await screen.findByRole('region', { name: 'Candidates' })
    await within(queue()).findByRole('button', { name: /Termination/ })

    await userEvent.selectOptions(
      within(queue()).getByLabelText('Status'),
      'APPROVED',
    )
    expect(within(queue()).getByRole('button', { name: /Governing law/ })).toBeInTheDocument()
    expect(within(queue()).queryByRole('button', { name: /Termination/ })).toBeNull()

    await userEvent.selectOptions(within(queue()).getByLabelText('Status'), 'ALL')
    await userEvent.click(within(queue()).getByLabelText(/conflicts only/i))
    expect(within(queue()).getByRole('button', { name: /Liability cap/ })).toBeInTheDocument()
    expect(within(queue()).queryByRole('button', { name: /Termination/ })).toBeNull()
  })
})
