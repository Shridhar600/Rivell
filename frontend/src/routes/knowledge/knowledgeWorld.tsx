import { vi } from 'vitest'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../../app/router'
import type { ArtifactSummary, Candidate, ExtractionUnit, KnowledgeNode, Publication } from '../../api/knowledge'
import { artifact, candidate, node, publication, unit } from '../../test/knowledgeFixtures'

export interface World {
  artifacts: ArtifactSummary[]
  candidates: Record<string, Candidate[]>
  publications: Publication[]
  units: Record<string, ExtractionUnit[]>
  structure: Record<string, KnowledgeNode[]>
}

/**
 * A small organization's Knowledge, as the real API would return it:
 * - `pb` is a playbook published in publication 1; publication 2 supersedes one of its positions.
 * - `cl` is a clause library published in publication 1, with two variants on one legal concept.
 * - `draft` is a playbook still in review. `broken` failed extraction.
 */
export function buildWorld(): World {
  const pb = artifact({ id: 'pb', filename: 'Commercial MSA Playbook.docx', createdAt: '2026-10-01T09:00:00Z' })
  const cl = artifact({ id: 'cl', artifactType: 'CLAUSE_LIBRARY', filename: 'Clause Library.xlsx', createdAt: '2026-10-02T09:00:00Z' })
  const draft = artifact({ id: 'draft', filename: 'Draft Playbook.docx', createdAt: '2026-10-03T09:00:00Z' })
  const broken = artifact({ id: 'broken', filename: 'Broken.docx', ingestStatus: 'FAILED', createdAt: '2026-10-03T10:00:00Z' })
  return {
    artifacts: [broken, draft, cl, pb],
    candidates: {
      pb: [
        candidate({
          id: 'p1',
          artifactId: 'pb',
          extractionUnitId: 'u1',
          rawLabel: 'Limitation of Liability',
          contentText: 'A cap at twelve months of fees is preferred.',
          positions: ['PREFERRED', 'FALLBACK'],
          effectiveConceptKey: 'LIMITATION_OF_LIABILITY',
          legalConceptKey: 'LIMITATION_OF_LIABILITY',
          escalationTrigger: 'Any cap above two times annual fees',
          declaredContractTypes: ['MSA'],
        }),
        candidate({ id: 'p2', artifactId: 'pb', extractionUnitId: 'u2', rawLabel: 'Payment Terms', contentText: 'Net 45.', positions: ['PREFERRED'] }),
        candidate({ id: 'p3', artifactId: 'pb', extractionUnitId: 'u3', rawLabel: 'Termination', contentText: '30 days notice.', positions: ['FALLBACK'] }),
        candidate({ id: 'p4', artifactId: 'pb', extractionUnitId: 'u4', rawLabel: 'Termination for Convenience', contentText: '60 days notice.', positions: ['PREFERRED'], supersedingCandidateId: 'p3' }),
      ],
      cl: [
        candidate({ id: 'c1', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Mutual, standard', contentText: 'Each party liability is capped at twelve months of fees.', effectiveConceptKey: 'LIMITATION_OF_LIABILITY', positions: [], variantRole: 'PREFERRED' }),
        candidate({ id: 'c2', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Supplier-favorable', contentText: 'Supplier liability is capped at six months of fees.', effectiveConceptKey: 'LIMITATION_OF_LIABILITY', positions: [], variantRole: 'FALLBACK' }),
        candidate({ id: 'c3', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Audit Rights', contentText: 'Customer may audit once a year.', effectiveConceptKey: null, positions: [] }),
      ],
      draft: [
        candidate({ id: 'd1', artifactId: 'draft', reviewStatus: 'PENDING', rawLabel: 'Draft one' }),
        candidate({ id: 'd2', artifactId: 'draft', reviewStatus: 'APPROVED', rawLabel: 'Draft two' }),
      ],
    },
    publications: [
      publication(1, [{ candidateId: 'p1' }, { candidateId: 'p2' }, { candidateId: 'p3' }, { candidateId: 'c1' }, { candidateId: 'c2' }, { candidateId: 'c3' }], { note: 'Initial publication' }),
      publication(2, [{ candidateId: 'p4', supersedes: 'p3' }], { note: 'Termination update' }),
    ],
    units: {
      pb: [unit({ id: 'u1', artifactId: 'pb', sourceNodeIds: ['n-h', 'n-p'] }), unit({ id: 'u2', artifactId: 'pb', sourceNodeIds: ['n-pay'] })],
      cl: [
        unit({ id: 'unit-c1', artifactId: 'cl', sourceNodeIds: ['r1'] }),
        unit({ id: 'unit-c2', artifactId: 'cl', sourceNodeIds: ['r2'] }),
        unit({ id: 'unit-c3', artifactId: 'cl', sourceNodeIds: ['r3'] }),
      ],
    },
    structure: {
      pb: [
        node({ id: 'body', nodeType: 'BODY', artifactId: 'pb' }),
        node({ id: 'n-h', nodeType: 'HEADING', parentId: 'body', ordinal: 0, bodyIndex: 0, text: 'Limitation of Liability', numberingLabel: '4.2', artifactId: 'pb' }),
        node({ id: 'n-p', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 1, bodyIndex: 1, text: 'A cap at twelve months of fees is preferred.', artifactId: 'pb' }),
        node({ id: 'n-pay', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 2, bodyIndex: 2, text: 'Net 45.', artifactId: 'pb' }),
        node({ id: 'n-other', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 3, bodyIndex: 3, text: 'Unrelated source text.', artifactId: 'pb' }),
      ],
      cl: [
        node({ id: 'wb', nodeType: 'WORKBOOK', artifactId: 'cl' }),
        node({ id: 'sh', nodeType: 'SHEET', parentId: 'wb', sheetName: 'Clauses', artifactId: 'cl' }),
        ...[
          ['r1', 0, 'Mutual, standard', 'Each party liability is capped at twelve months of fees.'],
          ['r2', 1, 'Supplier-favorable', 'Supplier liability is capped at six months of fees.'],
          ['r3', 2, 'Audit Rights', 'Customer may audit once a year.'],
        ].flatMap(([id, row, label, text]) => [
          node({ id: String(id), nodeType: 'SPREADSHEET_ROW', parentId: 'sh', ordinal: Number(row), rowIndex: Number(row), artifactId: 'cl' }),
          node({ id: `${id}-a`, nodeType: 'SPREADSHEET_CELL', parentId: String(id), ordinal: 0, text: String(label), artifactId: 'cl' }),
          node({ id: `${id}-b`, nodeType: 'SPREADSHEET_CELL', parentId: String(id), ordinal: 1, text: String(text), artifactId: 'cl' }),
        ]),
      ],
    },
  }
}

export interface StubOptions {
  /** Status for the source list. Anything other than 200 fails the whole read. */
  listStatus?: number
  /** Sources whose candidates cannot be read. */
  failCandidatesFor?: string[]
  /** Delay the source list until released. */
  hold?: boolean
}

export interface Call {
  method: string
  path: string
}

function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data, status, timestamp: '2026-10-04T09:00:00Z' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function detail(source: ArtifactSummary) {
  return {
    ...source,
    storageKey: 'k',
    declaredContractTypes: [],
    contractPackKey: null,
    partyPerspective: null,
    paperSource: null,
    jurisdiction: null,
    language: null,
    effectiveFrom: null,
    effectiveTo: null,
    manifestNote: null,
    parserVersion: 'parser-1',
    parseWarnings: [],
    artifactAssessment: null,
  }
}

export function stubKnowledgeApi(world: World, options: StubOptions = {}) {
  const calls: Call[] = []
  let release: () => void = () => undefined
  const gate = options.hold ? new Promise<void>((resolve) => (release = resolve)) : null
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init)
      const url = new URL(request.url)
      calls.push({ method: request.method, path: url.pathname })
      const path = url.pathname
      if (path === '/api/knowledge/artifacts') {
        if (gate) await gate
        if ((options.listStatus ?? 200) !== 200) {
          return new Response(JSON.stringify({ title: 'x' }), { status: options.listStatus, headers: { 'Content-Type': 'application/json' } })
        }
        return envelope(world.artifacts)
      }
      if (path === '/api/knowledge/publications') return envelope(world.publications)
      const match = /^\/api\/knowledge\/artifacts\/([^/]+)(?:\/(candidates|units|structure|original))?$/.exec(path)
      if (match) {
        const id = match[1] ?? ''
        const source = world.artifacts.find((entry) => entry.id === id)
        if (!source) return new Response('{}', { status: 404 })
        if (match[2] === 'candidates') {
          if (options.failCandidatesFor?.includes(id)) return new Response('{}', { status: 500 })
          return envelope(world.candidates[id] ?? [])
        }
        if (match[2] === 'units') return envelope(world.units[id] ?? [])
        if (match[2] === 'structure') return envelope(world.structure[id] ?? [])
        return envelope(detail(source))
      }
      return new Response('{}', { status: 404 })
    }),
  )
  return { calls, release: () => release() }
}

export function renderKnowledge(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, router, client }
}
