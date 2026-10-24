import { vi } from 'vitest'
import { render } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../../app/router'
import type { ArtifactSummary, Candidate, ExtractionUnit, KnowledgeNode, Publication } from '../../api/knowledge'
import { artifact, candidate, node, unit } from '../../test/knowledgeFixtures'
import { taxonomyJsonResponse } from '../../test/taxonomyFixtures'

/**
 * A server for the compilation tests that behaves the way the real one does, for the rules the UI
 * depends on. It speaks the wire format, keeps state between requests, and refuses what the real
 * service refuses, so a test shows what the screen does with the server's answers and never with
 * a state the screen invented.
 *
 * Rules taken from the backend: approve is for pending or corrected candidates, edit moves any
 * unpublished candidate to corrected, a concept correction demotes an approved candidate to
 * corrected, bulk approval takes only pending routine candidates and reports the rest as skipped,
 * and a publication takes only approved candidates.
 */

export interface Fake {
  artifacts: ArtifactSummary[]
  /** Candidates by source id. */
  candidates: Record<string, Candidate[]>
  units: Record<string, ExtractionUnit[]>
  structure: Record<string, KnowledgeNode[]>
  publications: Publication[]
  /** Anything a test wants the server to refuse: a request key such as 'POST approve a1' to a status. */
  fail: Record<string, number>
  /** Changes the server makes by itself the next time a source's candidates are read. */
  beforeRead: Record<string, () => void>
  calls: { method: string; path: string; body: unknown }[]
  uploads: { name: string; artifactType: string | null; query: Record<string, string> }[]
  /** An upload waits for this before it answers. */
  holdUpload?: Promise<void>
  /** A source's candidate read waits for this before it answers. */
  holdCandidates?: Promise<void>
  /** What a successful upload returns. */
  uploadResult: { id: string; status: ArtifactSummary['ingestStatus'] } | { status: number }
}

export const SRC = 'src1'

/** A playbook in review: a conflict, an uncertain reading, a duplicate, a supersession, and three routine candidates. */
export function reviewWorld(): Fake {
  const flagged = (id: string, over: Partial<Candidate>) =>
    candidate({
      id,
      artifactId: SRC,
      reviewStatus: 'PENDING',
      extractionUnitId: `u-${id}`,
      reviewCategory: 'ELEVATED',
      ...over,
    })
  const routine = (id: string, label: string, text: string) =>
    candidate({
      id,
      artifactId: SRC,
      reviewStatus: 'PENDING',
      extractionUnitId: `u-${id}`,
      reviewCategory: 'ROUTINE',
      rawLabel: label,
      contentText: text,
      positions: ['PREFERRED'],
      effectiveConceptKey: 'GOVERNING_LAW',
      legalConceptKey: 'GOVERNING_LAW',
    })
  const peer = candidate({
    id: 'peer1',
    artifactId: 'pub',
    rawLabel: 'Published Liability Cap | MSA | 12 months',
    title: 'Published Liability Cap',
    contentText: 'A published cap at twelve months.',
    positions: ['PREFERRED'],
    effectiveConceptKey: 'LIMITATION_OF_LIABILITY',
    declaredContractTypes: ['MSA'],
  })
  const cands: Candidate[] = [
    flagged('conflict', {
      rawLabel: 'Assignment',
      contentText: 'Assignment is allowed with consent.',
      reviewCategory: 'HIGH',
      reviewReasons: ['CONFLICT_DETECTED'],
      conflictSignals: [{ conflictType: 'CONTRADICTS_EXISTING', message: 'Contradicts a published position.', publishedCandidateId: 'peer1' }],
      effectiveConceptKey: 'LIMITATION_OF_LIABILITY',
      legalConceptKey: 'LIMITATION_OF_LIABILITY',
    }),
    flagged('uncertain', {
      rawLabel: 'Indemnification',
      contentText: 'Mutual and capped.',
      reviewReasons: ['UNVERIFIED_GENERATIVE_FIELD'],
      escalationTrigger: 'Any uncapped indemnity',
      interpretations: [
        { field: 'escalationTrigger', proposedValue: 'Any uncapped indemnity', kind: 'GENERATIVE_EXTRACTION', specId: 's', specVersion: 1, supportingSourceNodes: 1, verification: 'UNCERTAIN' },
      ],
    }),
    flagged('duplicate', {
      rawLabel: 'Subcontracting',
      contentText: 'Permitted with notice.',
      reviewReasons: ['DUPLICATE_DETECTED'],
      conflictSignals: [{ conflictType: 'DUPLICATE', message: 'Same content.', publishedCandidateId: 'peer1' }],
    }),
    flagged('supersede', {
      rawLabel: 'Confidentiality Term',
      contentText: 'Three years after termination.',
      reviewCategory: 'HIGH',
      reviewReasons: ['SUPERSESSION_PROPOSED'],
      conflictSignals: [{ conflictType: 'SUPERSEDES_EXISTING', message: 'Replaces a published position.', publishedCandidateId: 'peer1' }],
    }),
    routine('r1', 'Notices', 'In writing, to the order form address.'),
    routine('r2', 'Counterparts', 'Electronic signature is allowed.'),
    routine('r3', 'Survival', 'Payment and liability survive.'),
  ]
  const nodes: KnowledgeNode[] = [node({ id: 'body', nodeType: 'BODY', artifactId: SRC })]
  const units: ExtractionUnit[] = []
  cands.forEach((entry, position) => {
    const heading = `h-${entry.id}`
    const para = `p-${entry.id}`
    nodes.push(
      node({ id: heading, nodeType: 'HEADING', parentId: 'body', ordinal: position * 2, bodyIndex: position * 2, text: entry.rawLabel, numberingLabel: `${position + 1}.1`, artifactId: SRC }),
      node({ id: para, nodeType: 'PARAGRAPH', parentId: 'body', ordinal: position * 2 + 1, bodyIndex: position * 2 + 1, text: entry.contentText ?? '', artifactId: SRC }),
    )
    units.push(unit({ id: `u-${entry.id}`, artifactId: SRC, ordinal: position, sourceNodeIds: [heading, para], recordKind: 'POSITION' }))
  })
  // A record the kind gate kept out of the candidate list: it stays visible as a source unit.
  units.push(
    unit({
      id: 'u-guidance',
      artifactId: SRC,
      ordinal: cands.length,
      kind: 'HEADING_SECTION',
      title: 'How to use this playbook',
      contentText: 'Drafting notes for the negotiation team.',
      recordKind: 'GUIDANCE',
      sourceNodeIds: [],
    }),
  )
  return {
    artifacts: [artifact({ id: SRC, filename: 'Commercial MSA Playbook v4.docx', createdAt: '2026-10-04T09:00:00Z' })],
    candidates: { [SRC]: cands, pub: [peer] },
    units: { [SRC]: units },
    structure: { [SRC]: nodes },
    publications: [
      {
        id: 'pub-1',
        organizationId: 'org-1',
        publicationNumber: 1,
        note: null,
        items: [{ id: 'i1', candidateId: 'peer1', supersededCandidateId: null, action: 'ADD' }],
        createdAt: '2026-10-01T10:00:00Z',
      },
    ],
    fail: {},
    beforeRead: {},
    calls: [],
    uploads: [],
    uploadResult: { id: SRC, status: 'EXTRACTED' },
  }
}

/** A world that is already published and reviewed: only the published record remains. */
export function publishedWorld(): Fake {
  const world = reviewWorld()
  const cands = world.candidates[SRC] ?? []
  world.candidates[SRC] = cands.map((entry, position) =>
    position === 0 ? { ...entry, reviewStatus: 'REJECTED' as const } : { ...entry, reviewStatus: 'PUBLISHED' as const, reviewCategory: 'ROUTINE' as const, reviewReasons: [], conflictSignals: [] },
  )
  world.publications.push({
    id: 'pub-2',
    organizationId: 'org-1',
    publicationNumber: 2,
    note: null,
    items: cands.slice(1).map((entry) => ({ id: `i-${entry.id}`, candidateId: entry.id, supersededCandidateId: null, action: 'ADD' as const })),
    createdAt: '2026-10-03T10:00:00Z',
  })
  return world
}

function envelope(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data, status, timestamp: '2026-10-04T09:00:00Z' }), { status, headers: { 'Content-Type': 'application/json' } })
}
const refuse = (status: number) => new Response(JSON.stringify({ title: 'refused' }), { status, headers: { 'Content-Type': 'application/json' } })

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
    parseWarnings: source.ingestStatus === 'FAILED' ? ['PARSE_FAILED'] : [],
    artifactAssessment: null,
  }
}

function findCandidate(fake: Fake, id: string): Candidate | undefined {
  for (const list of Object.values(fake.candidates)) {
    const found = list.find((entry) => entry.id === id)
    if (found) return found
  }
  return undefined
}

function update(fake: Fake, id: string, change: (entry: Candidate) => Candidate): Candidate | undefined {
  for (const [source, list] of Object.entries(fake.candidates)) {
    const at = list.findIndex((entry) => entry.id === id)
    const current = list[at]
    if (current) {
      const next = change(current)
      fake.candidates[source] = list.map((entry, position) => (position === at ? next : entry))
      return next
    }
  }
  return undefined
}

export function startFakeServer(fake: Fake) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init)
      const url = new URL(request.url)
      const path = url.pathname
      const method = request.method
      let body: unknown = null
      let uploadName = ''
      if (method === 'POST') {
        const type = request.headers.get('content-type') ?? ''
        if (type.includes('multipart/form-data')) {
          // The multipart body is read as text: the filename is all a test needs from it.
          const raw = await request.text().catch(() => '')
          uploadName = /filename="([^"]+)"/.exec(raw)?.[1] ?? ''
        } else {
          const text = await request.text()
          body = text ? JSON.parse(text) : {}
        }
      }
      fake.calls.push({ method, path, body })
      const key = (verb: string, id: string) => `${verb} ${id}`

      if (method === 'GET' && path === '/api/taxonomy/concepts') {
        return taxonomyJsonResponse()
      }

      if (method === 'POST' && path === '/api/knowledge/artifacts') {
        if (fake.holdUpload) await fake.holdUpload
        fake.uploads.push({
          name: uploadName,
          artifactType: url.searchParams.get('artifactType'),
          query: Object.fromEntries(url.searchParams.entries()),
        })
        if ('status' in fake.uploadResult && typeof fake.uploadResult.status === 'number' && !('id' in fake.uploadResult)) return refuse(fake.uploadResult.status)
        const result = fake.uploadResult as { id: string; status: ArtifactSummary['ingestStatus'] }
        const created = artifact({ id: result.id, ingestStatus: result.status, filename: uploadName || 'new.docx' })
        fake.artifacts = [created, ...fake.artifacts.filter((entry) => entry.id !== created.id)]
        return envelope(detail(created))
      }
      if (path === '/api/knowledge/artifacts') return envelope(fake.artifacts)
      if (path === '/api/knowledge/publications' && method === 'GET') return envelope(fake.publications)
      if (path === '/api/knowledge/publications' && method === 'POST') {
        if (fake.fail['POST publish']) return refuse(fake.fail['POST publish'])
        const request = body as { candidateIds: string[]; supersededCandidates?: Record<string, string>; note?: string }
        for (const id of request.candidateIds) {
          if (findCandidate(fake, id)?.reviewStatus !== 'APPROVED') return refuse(409)
        }
        const number = Math.max(0, ...fake.publications.map((entry) => entry.publicationNumber)) + 1
        const items: Publication['items'] = []
        for (const id of request.candidateIds) {
          items.push({ id: `item-${number}-${id}`, candidateId: id, supersededCandidateId: null, action: 'ADD' })
          const old = request.supersededCandidates?.[id]
          if (old) items.push({ id: `sup-${number}-${id}`, candidateId: id, supersededCandidateId: old, action: 'SUPERSEDE' })
          update(fake, id, (entry) => ({ ...entry, reviewStatus: 'PUBLISHED' }))
        }
        const created: Publication = { id: `pub-${number}`, organizationId: 'org-1', publicationNumber: number, note: request.note ?? null, items, createdAt: '2026-10-04T12:00:00Z' }
        fake.publications = [created, ...fake.publications]
        return envelope(created)
      }
      if (method === 'POST' && path === '/api/knowledge/candidates/bulk-approve') {
        if (fake.fail['POST bulk']) return refuse(fake.fail['POST bulk'])
        const ids = (body as { candidateIds: string[] }).candidateIds
        const approvedIds: string[] = []
        const skipped: Record<string, string> = {}
        for (const id of ids) {
          const entry = findCandidate(fake, id)
          if (!entry) skipped[id] = 'not found in this organization'
          else if (entry.reviewStatus !== 'PENDING') skipped[id] = 'not pending'
          else if (entry.reviewCategory !== 'ROUTINE') skipped[id] = `review-risk category ${entry.reviewCategory ?? 'UNSET'}`
          else {
            update(fake, id, (current) => ({ ...current, reviewStatus: 'APPROVED' }))
            approvedIds.push(id)
          }
        }
        return envelope({ approvedIds, skipped })
      }
      const position = /^\/api\/knowledge\/artifacts\/([^/]+)\/units\/([^/]+)\/position$/.exec(path)
      if (method === 'POST' && position) {
        if (fake.fail['POST position']) return refuse(fake.fail['POST position'])
        const sourceId = position[1] ?? ''
        const unitId = position[2] ?? ''
        const part = (fake.units[sourceId] ?? []).find((entry) => entry.id === unitId)
        if (!part) return refuse(404)
        const list = fake.candidates[sourceId] ?? []
        if (part.recordKindOverride || list.some((entry) => entry.extractionUnitId === unitId)) return refuse(409)
        const made = candidate({
          id: `c-${unitId}`,
          artifactId: sourceId,
          extractionUnitId: unitId,
          reviewStatus: 'PENDING',
          reviewCategory: 'ROUTINE',
          rawLabel: part.title ?? 'Part',
          contentText: part.contentText,
        })
        fake.candidates[sourceId] = [...list, made]
        fake.units[sourceId] = (fake.units[sourceId] ?? []).map((entry) =>
          entry.id === unitId
            ? { ...entry, recordKindOverride: 'POSITION', recordKindOverriddenAt: '2026-10-12T10:00:00Z' }
            : entry,
        )
        return envelope([made], 201)
      }
      const decision = /^\/api\/knowledge\/candidates\/([^/]+)\/(approve|reject|edit|concept)$/.exec(path)
      if (method === 'POST' && decision) {
        const id = decision[1] ?? ''
        const verb = decision[2] ?? ''
        const failure = fake.fail[key(`POST ${verb}`, id)] ?? fake.fail[`POST ${verb}`]
        if (failure) return refuse(failure)
        const current = findCandidate(fake, id)
        if (!current) return refuse(404)
        if (current.reviewStatus === 'PUBLISHED') return refuse(409)
        const input = body as {
          note?: string
          title?: string
          contentText?: string
          payloadColumns?: Record<string, string>
          scope?: Record<string, unknown>
          semanticFields?: { positions?: Candidate['positions']; escalationTrigger?: string; requiresApproval?: boolean | null; variantRole?: Candidate['variantRole'] }
          primary?: string
        }
        if (verb === 'approve') {
          if (current.reviewStatus !== 'PENDING' && current.reviewStatus !== 'EDITED') return refuse(409)
          return envelope(update(fake, id, (entry) => ({ ...entry, reviewStatus: 'APPROVED' })))
        }
        if (verb === 'reject') return envelope(update(fake, id, (entry) => ({ ...entry, reviewStatus: 'REJECTED' })))
        if (verb === 'edit') {
          const approvalSent =
            input.semanticFields !== undefined &&
            Object.prototype.hasOwnProperty.call(input.semanticFields, 'requiresApproval')
          return envelope(
            update(fake, id, (entry) => {
              const editedTitle = input.title === undefined ? entry.editedTitle : input.title.trim()
              return {
                ...entry,
                reviewStatus: 'EDITED',
                editedTitle: editedTitle ? editedTitle : null,
                title: editedTitle ? editedTitle : entry.rawLabel,
                editedContentText: input.contentText ?? entry.editedContentText,
                editedPayloadColumns: input.payloadColumns ?? entry.editedPayloadColumns,
                positions: input.semanticFields?.positions ?? entry.positions,
                escalationTrigger: input.semanticFields?.escalationTrigger ?? entry.escalationTrigger,
                requiresApproval: approvalSent ? input.semanticFields?.requiresApproval ?? null : entry.requiresApproval,
                variantRole: input.semanticFields?.variantRole ?? entry.variantRole,
                jurisdiction: typeof input.scope?.jurisdiction === 'string' ? input.scope.jurisdiction : entry.jurisdiction,
                declaredContractTypes: Array.isArray(input.scope?.declaredContractTypes) ? (input.scope.declaredContractTypes as string[]) : entry.declaredContractTypes,
                reviewNote: input.note ?? entry.reviewNote,
              }
            }),
          )
        }
        if (current.reviewStatus === 'REJECTED') return refuse(409)
        const primary = (input.primary ?? '').trim()
        // A concept correction re-runs the conflict comparison on the effective concept and sets the category again.
        return envelope(
          update(fake, id, (entry) => ({
            ...entry,
            reviewStatus: entry.reviewStatus === 'APPROVED' ? 'EDITED' : entry.reviewStatus,
            conceptCorrection: primary,
            effectiveConceptKey: primary === '' ? null : primary,
            conflictSignals: [],
            reviewReasons: [],
            reviewCategory: 'ROUTINE',
          })),
        )
      }
      const read = /^\/api\/knowledge\/artifacts\/([^/]+)(?:\/(candidates|units|structure))?$/.exec(path)
      if (method === 'GET' && read) {
        const id = read[1] ?? ''
        const source = fake.artifacts.find((entry) => entry.id === id)
        if (!source) return refuse(404)
        if (read[2] === 'candidates') {
          if (fake.holdCandidates) await fake.holdCandidates
          if (fake.fail[`GET candidates ${id}`]) return refuse(fake.fail[`GET candidates ${id}`] as number)
          fake.beforeRead[id]?.()
          return envelope(fake.candidates[id] ?? [])
        }
        if (read[2] === 'units') return envelope(fake.units[id] ?? [])
        if (read[2] === 'structure') return envelope(fake.structure[id] ?? [])
        if (fake.fail[`GET artifact ${id}`]) return refuse(fake.fail[`GET artifact ${id}`] as number)
        return envelope(detail(source))
      }
      const single = /^\/api\/knowledge\/candidates\/([^/]+)$/.exec(path)
      if (method === 'GET' && single) {
        const found = findCandidate(fake, single[1] ?? '')
        return found ? envelope(found) : refuse(404)
      }
      return refuse(404)
    }),
  )
}

export function renderApp(path: string, state?: unknown) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(appRoutes, { initialEntries: [state ? { pathname: path.split('?')[0] ?? path, search: path.includes('?') ? `?${path.split('?')[1]}` : '', state } : path] })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, router, client }
}

export const requests = (fake: Fake, method: string, pattern: RegExp) => fake.calls.filter((call) => call.method === method && pattern.test(call.path))
