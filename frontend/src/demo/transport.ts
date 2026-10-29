/**
 * The demo transport: answers every API request from the captured states, moves the recorded
 * tour forward on the few writes the tour makes, and refuses everything else with a status the
 * product already handles.
 *
 * This module is reached only through `install.ts`, which the demo build resolves for the
 * `@demo-hooks` alias. The normal build resolves `off.ts` and never loads any of it.
 */
import type { DemoDecision, DemoStage } from './state'
import {
  DEMO_STAGES,
  advanceDemoStage,
  approveDemoCandidates,
  currentDemoDecision,
  getDemoState,
  recordDemoDecision,
  stageIndex,
} from './state'

export interface DemoManifestFile {
  filename: string
  contentType: string
  sizeBytes?: number
  sha256?: string
}

export interface DemoManifest {
  version: number
  states: string[]
  capturedAt: string
  ids?: {
    reviews?: Record<string, string>
    contracts?: Record<string, string>
    artifacts?: Record<string, string>
    publications?: Record<string, string>
    publicationNumbers?: Record<string, number>
    k2ArtifactId?: string
    k2PublicationId?: string
    c5ReviewId?: string
    c5ContractId?: string
    [key: string]: unknown
  }
  files?: Record<string, DemoManifestFile>
  preparedKnowledge?: { filename: string; path: string; size?: number; sizeBytes?: number; sha256?: string }
}

export interface DemoCaptureResponse {
  status: number
  body: unknown
}

export interface DemoMutation {
  method: string
  path: string
  request?: unknown
  status: number
  body: unknown
}

export interface DemoCapture {
  state: string
  capturedAt: string
  responses: Record<string, DemoCaptureResponse>
  mutations?: DemoMutation[]
}

export interface DemoIds {
  c5?: { reviewId: string; contractId: string }
  k2ArtifactId?: string
  orderForm?: { reviewId: string; contractId: string }
  msa?: { reviewId: string; contractId: string }
  vantage?: { reviewId: string; contractId: string }
}

export interface DemoRuntimeOptions {
  /** The original fetch, for the prepared file and the redline DOCX. */
  baseFetch?: typeof fetch
  /** Test seam. Milliseconds since the epoch. */
  now?: () => number
  /** Directory that serves the capture, with a trailing slash. */
  dataBase?: string
}

const DOCX_MEDIA_TYPE =
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
const PROBLEM_MEDIA_TYPE = 'application/problem+json'
const ISO_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/

function clone<T>(value: T): T {
  return value === undefined ? value : JSON.parse(JSON.stringify(value)) as T
}

function dataOf(body: unknown): unknown {
  if (body && typeof body === 'object' && 'data' in body) {
    return (body as { data: unknown }).data
  }
  return body
}

function listItems(body: unknown): Record<string, unknown>[] {
  const data = dataOf(body)
  if (Array.isArray(data)) return data as Record<string, unknown>[]
  if (data && typeof data === 'object') {
    const items = (data as { items?: unknown }).items
    if (Array.isArray(items)) return items as Record<string, unknown>[]
  }
  return []
}

function withItems(body: unknown, items: Record<string, unknown>[]): unknown {
  const copy = clone(body)
  const data = dataOf(copy)
  if (Array.isArray(data)) {
    if (copy && typeof copy === 'object' && 'data' in copy) {
      ;(copy as { data: unknown }).data = items
    } else {
      return items
    }
    return copy
  }
  if (data && typeof data === 'object') {
    ;(data as { items?: unknown }).items = items
    if ('truncated' in (data as Record<string, unknown>)) {
      ;(data as Record<string, unknown>).truncated = false
    }
  }
  return copy
}

function envelope(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body ?? {}), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function problem(status: number, detail?: string): Response {
  const body = detail ? { detail } : {}
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': PROBLEM_MEDIA_TYPE },
  })
}

export function shiftInstants(value: unknown, deltaMs: number): unknown {
  if (typeof value === 'string') {
    if (!ISO_INSTANT.test(value)) return value
    const parsed = Date.parse(value)
    if (Number.isNaN(parsed)) return value
    return new Date(parsed + deltaMs).toISOString()
  }
  if (Array.isArray(value)) return value.map((entry) => shiftInstants(entry, deltaMs))
  if (value && typeof value === 'object') {
    const copy: Record<string, unknown> = {}
    for (const [key, entry] of Object.entries(value)) copy[key] = shiftInstants(entry, deltaMs)
    return copy
  }
  return value
}

export function requestKey(method: string, pathname: string, search: URLSearchParams): string {
  const kept = [...search.entries()]
    .filter(([name]) => name !== 'limit' && name !== 'cursor')
    .sort(([leftName, leftValue], [rightName, rightValue]) =>
      leftName === rightName ? leftValue.localeCompare(rightValue) : leftName.localeCompare(rightName))
  const query = new URLSearchParams()
  for (const [name, value] of kept) query.append(name, value)
  const suffix = query.toString()
  return `${method.toUpperCase()} ${pathname}${suffix ? `?${suffix}` : ''}`
}

function regionOfFile(filename: string): 'c5' | 'orderForm' | 'msa' | 'vantage' | null {
  const name = filename.toLowerCase()
  if (name.includes('sablepoint')) return 'c5'
  if (name.includes('order form')) return 'orderForm'
  if (name.includes('master services agreement')) return 'msa'
  if (name.includes('vantage health')) return 'vantage'
  return null
}

export function buildDemoIds(manifest: DemoManifest): DemoIds {
  const reviews = manifest.ids?.reviews ?? {}
  const contracts = manifest.ids?.contracts ?? {}
  const ids: DemoIds = {}
  for (const [filename, reviewId] of Object.entries(reviews)) {
    const region = regionOfFile(filename)
    if (!region) continue
    const contractId = contracts[filename]
    if (!contractId) continue
    ids[region] = { reviewId, contractId }
  }
  const c5Review = manifest.ids?.c5ReviewId
  const c5Contract = manifest.ids?.c5ContractId
  if (c5Review && c5Contract) ids.c5 = { reviewId: c5Review, contractId: c5Contract }
  ids.k2ArtifactId = manifest.ids?.k2ArtifactId
    ?? Object.entries(manifest.ids?.artifacts ?? {})
      .find(([filename]) => filename.toLowerCase().includes('nda playbook'))?.[1]
  return ids
}

interface FoundResponse {
  response: DemoCaptureResponse
  search: URLSearchParams
}

interface ReviewDecisionState {
  actionableFindings: number
  resolvedFindings: number
  remainingFindings: number
  accepted: number
  edited: number
  rejected: number
  escalated: number
  informationalFindings: number
  informationalDecided: number
  readyToFinalize: boolean
  blockers: { code: string; message: string; findingIds: string[] }[]
  status: string
  analysisStatus: string
  changeSetId: string | null
  finalizedBy: string | null
  finalizedAt: string | null
}

export class DemoRuntime {
  readonly ids: DemoIds
  readonly preparedFilename: string
  private readonly manifest: DemoManifest
  private readonly captures: Record<string, DemoCapture>
  private readonly baseFetch: typeof fetch
  private readonly now: () => number
  private readonly dataBase: string
  private readonly files = new Map<string, ArrayBuffer>()

  constructor(manifest: DemoManifest, captures: Record<string, DemoCapture>,
              options: DemoRuntimeOptions = {}) {
    this.manifest = manifest
    this.captures = captures
    this.baseFetch = options.baseFetch ?? globalThis.fetch.bind(globalThis)
    this.now = options.now ?? Date.now
    this.dataBase = options.dataBase ?? '/demo-data/'
    this.ids = buildDemoIds(manifest)
    this.preparedFilename = manifest.preparedKnowledge?.filename ?? 'NDA Playbook.xlsx'
  }

  get stage(): DemoStage {
    return getDemoState().stage
  }

  private delta(): number {
    const captured = Date.parse(this.manifest.capturedAt)
    return Number.isNaN(captured) ? 0 : this.now() - captured
  }

  private captureOrder(name: string): number {
    return (DEMO_STAGES as readonly string[]).indexOf(name)
  }

  /** Merged responses from every captured state up to and including `stage`. */
  private mergedFor(stage: DemoStage): Record<string, DemoCaptureResponse> {
    const merged: Record<string, DemoCaptureResponse> = {}
    const reached = stageIndex(stage)
    for (const name of this.manifest.states) {
      const order = this.captureOrder(name)
      if (order >= 0 && order > reached) break
      const capture = this.captures[name]
      if (!capture) continue
      Object.assign(merged, capture.responses)
    }
    return merged
  }

  private lookup(pathname: string, search: URLSearchParams): FoundResponse | null {
    const merged = this.mergedFor(this.stage)
    const exact = merged[requestKey('GET', pathname, search)]
    if (exact) return { response: exact, search }
    const bare = merged[`GET ${pathname}`]
    if (bare) return { response: bare, search }
    return null
  }

  private rawLookup(stage: DemoStage, key: string): DemoCaptureResponse | null {
    return this.mergedFor(stage)[key] ?? null
  }

  private c5Findings(): Record<string, unknown>[] {
    if (!this.ids.c5) return []
    const body = this.rawLookup('tour-k2-published',
      `GET /api/reviews/${this.ids.c5.reviewId}/findings`)?.body
    return listItems(body)
  }

  private c5Finding(findingId: string): Record<string, unknown> | null {
    if (!this.ids.c5) return null
    const body = this.rawLookup('tour-k2-published',
      `GET /api/reviews/${this.ids.c5.reviewId}/findings/${findingId}`)?.body
    const data = dataOf(body)
    return data && typeof data === 'object' ? data as Record<string, unknown> : null
  }

  private recordedDecision(findingId: string): DemoDecision | null {
    if (!this.ids.c5) return null
    const body = this.rawLookup('tour-c5-decided',
      `GET /api/reviews/${this.ids.c5.reviewId}/decisions`)?.body
    const match = listItems(body).find((entry) => entry.findingId === findingId)
    return match ? match as unknown as DemoDecision : null
  }

  private recordedDecisionTemplate(findingId: string): DemoDecision | null {
    if (!this.ids.c5) return null
    const path = `/api/reviews/${this.ids.c5.reviewId}/findings/${findingId}/decision`
    for (const name of this.manifest.states) {
      for (const mutation of this.captures[name]?.mutations ?? []) {
        if (mutation.method.toUpperCase() !== 'PUT') continue
        if (mutation.path.split('?')[0] !== path) continue
        const body = dataOf(mutation.body)
        if (body && typeof body === 'object') return body as unknown as DemoDecision
      }
    }
    return this.recordedDecision(findingId)
  }

  private effectiveDecision(findingId: string): DemoDecision | null {
    const mine = currentDemoDecision(findingId)
    if (mine) return mine
    if (stageIndex(this.stage) >= stageIndex('tour-c5-decided')) {
      return this.capturedCurrentDecision(findingId)
    }
    return null
  }

  private capturedCurrentDecision(findingId: string): DemoDecision | null {
    if (!this.ids.c5) return null
    const body = this.rawLookup(this.stage,
      `GET /api/reviews/${this.ids.c5.reviewId}/decisions`)?.body
    const match = listItems(body).find((entry) => entry.findingId === findingId)
    return match ? match as unknown as DemoDecision : null
  }

  private reviewState(): ReviewDecisionState | null {
    if (!this.ids.c5) return null
    const c5 = this.ids.c5
    const capturedBody = this.rawLookup(this.stage,
      `GET /api/reviews/${c5.reviewId}/human-review`)?.body
    const captured = dataOf(capturedBody)
    if (!captured || typeof captured !== 'object') return null
    const base = captured as Record<string, unknown>
    const findings = this.c5Findings()
    const actionable = findings.filter(
      (finding) => finding.reviewClassification !== 'INFORMATIONAL')
    const informational = findings.length - actionable.length
    const decisions = new Map<string, DemoDecision>()
    for (const finding of findings) {
      const id = String(finding.id ?? '')
      const decision = id ? this.effectiveDecision(id) : null
      if (decision) decisions.set(id, decision)
    }
    let accepted = 0
    let edited = 0
    let rejected = 0
    let escalated = 0
    let resolved = 0
    let informationalDecided = 0
    const unresolvedBlocking: string[] = []
    const unresolvedReviewRequired: string[] = []
    const escalatedIds: string[] = []
    for (const finding of findings) {
      const id = String(finding.id ?? '')
      const decision = decisions.get(id)
      const isActionable = finding.reviewClassification !== 'INFORMATIONAL'
      if (!decision) {
        if (isActionable) {
          if (finding.reviewClassification === 'BLOCKING') unresolvedBlocking.push(id)
          else unresolvedReviewRequired.push(id)
        }
        continue
      }
      if (!isActionable) {
        informationalDecided += 1
        continue
      }
      switch (decision.action) {
        case 'ACCEPT': accepted += 1; resolved += 1; break
        case 'EDIT': edited += 1; resolved += 1; break
        case 'REJECT': rejected += 1; resolved += 1; break
        case 'ESCALATE': escalated += 1; escalatedIds.push(id); break
        case 'RESOLVE': resolved += 1; break
        default: break
      }
    }
    const blockers: ReviewDecisionState['blockers'] = []
    if (unresolvedBlocking.length > 0) {
      blockers.push({
        code: 'UNRESOLVED_BLOCKING_FINDINGS',
        message: unresolvedBlocking.length === 1
          ? '1 blocking finding still needs your decision.'
          : `${unresolvedBlocking.length} blocking findings still need your decision.`,
        findingIds: unresolvedBlocking,
      })
    }
    if (unresolvedReviewRequired.length > 0) {
      blockers.push({
        code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
        message: unresolvedReviewRequired.length === 1
          ? '1 finding that needs your decision is still open.'
          : `${unresolvedReviewRequired.length} findings that need your decision are still open.`,
        findingIds: unresolvedReviewRequired,
      })
    }
    if (escalatedIds.length > 0) {
      blockers.push({
        code: 'ACTIVE_ESCALATION',
        message: escalatedIds.length === 1
          ? '1 clause is waiting for approval.'
          : `${escalatedIds.length} clauses are waiting for approval.`,
        findingIds: escalatedIds,
      })
    }
    const finalized = base.status === 'COMPLETED'
    const remaining = actionable.length - resolved
    const ready = !finalized && blockers.length === 0 && remaining === 0
    const decisionsPresent = decisions.size
    const status = finalized
      ? 'COMPLETED'
      : decisionsPresent === 0 && actionable.length > 0
        ? 'NOT_STARTED'
        : ready
          ? 'READY_FOR_COMPLETION'
          : 'IN_PROGRESS'
    return {
      actionableFindings: actionable.length,
      resolvedFindings: resolved,
      remainingFindings: remaining,
      accepted,
      edited,
      rejected,
      escalated,
      informationalFindings: informational,
      informationalDecided,
      readyToFinalize: ready,
      blockers,
      status,
      analysisStatus: String(base.analysisStatus ?? 'COMPLETED'),
      changeSetId: (base.changeSetId as string | null) ?? null,
      finalizedBy: (base.finalizedBy as string | null) ?? null,
      finalizedAt: (base.finalizedAt as string | null) ?? null,
    }
  }

  private applyReviewOverlay(body: unknown): unknown {
    if (!this.ids.c5) return body
    const review = this.reviewState()
    if (!review) return body
    const copy = clone(body)
    const data = dataOf(copy)
    if (!data || typeof data !== 'object') return copy
    const record = data as Record<string, unknown>
    if (Array.isArray(record.items)) {
      for (const item of record.items as Record<string, unknown>[]) {
        if (item.id !== this.ids.c5.reviewId) continue
        item.humanReview = {
          status: review.status,
          actionableFindings: review.actionableFindings,
          resolvedFindings: review.resolvedFindings,
          remainingFindings: review.remainingFindings,
          escalated: review.escalated,
          readyToFinalize: review.readyToFinalize,
          blockers: review.blockers.map((blocker) => blocker.code),
        }
      }
    }
    return copy
  }

  private applyHumanReviewOverlay(body: unknown): unknown {
    const review = this.reviewState()
    if (!review) return body
    const copy = clone(body)
    const data = dataOf(copy)
    if (!data || typeof data !== 'object') return copy
    Object.assign(data as Record<string, unknown>, review)
    return copy
  }

  private overlayCurrentDecisions(captured: Record<string, unknown>[]): Record<string, unknown>[] {
    const ordered = new Map<string, Record<string, unknown>>()
    for (const entry of captured) {
      const id = String(entry.findingId ?? '')
      if (id) ordered.set(id, entry)
    }
    const state = getDemoState()
    for (const [findingId, history] of Object.entries(state.decisionHistory)) {
      if (history.length === 0) continue
      ordered.set(findingId, history[history.length - 1] as unknown as Record<string, unknown>)
    }
    return [...ordered.values()]
  }

  private overlayDecisionHistory(findingId: string, captured: Record<string, unknown>[]): Record<string, unknown>[] {
    const history = getDemoState().decisionHistory[findingId] ?? []
    if (history.length === 0) return captured
    const superseded = new Set(
      history.map((entry) => entry.supersedesDecisionId).filter((id): id is string => !!id))
    const byId = new Map<string, Record<string, unknown>>()
    for (const entry of captured) {
      const id = String(entry.id ?? '')
      if (superseded.has(id)) continue
      byId.set(id, entry)
    }
    for (const entry of history) {
      byId.set(entry.id, entry as unknown as Record<string, unknown>)
    }
    return [...byId.values()].sort((left, right) =>
      Number(right.revision ?? 0) - Number(left.revision ?? 0))
  }

  private k2Candidates(): Record<string, unknown>[] {
    if (!this.ids.k2ArtifactId) return []
    const body = this.rawLookup(this.stage,
      `GET /api/knowledge/artifacts/${this.ids.k2ArtifactId}/candidates`)?.body
    return listItems(body)
  }

  private isK2Approved(candidateId: string): boolean {
    return getDemoState().k2Approved.includes(candidateId)
  }

  private applyCandidateOverlay(pathname: string, body: unknown): unknown {
    if (!this.ids.k2ArtifactId || this.stage !== 'tour-k2-compiled') return body
    if (pathname === `/api/knowledge/artifacts/${this.ids.k2ArtifactId}/candidates`) {
      const items = listItems(body).map((candidate) => {
        const id = String(candidate.id ?? '')
        if (!id || !this.isK2Approved(id) || candidate.reviewStatus !== 'PENDING') return candidate
        return { ...candidate, reviewStatus: 'APPROVED' }
      })
      return withItems(body, items)
    }
    const detail = /^\/api\/knowledge\/candidates\/([^/]+)$/.exec(pathname)
    if (detail && this.isK2Approved(detail[1])) {
      const copy = clone(body)
      const data = dataOf(copy)
      if (data && typeof data === 'object' && (data as Record<string, unknown>).reviewStatus === 'PENDING') {
        ;(data as Record<string, unknown>).reviewStatus = 'APPROVED'
      }
      return copy
    }
    return body
  }

  private applyFilters(pathname: string, search: URLSearchParams, body: unknown): unknown {
    if (pathname === '/api/reviews') {
      const contractId = search.get('contractId')
      if (!contractId) return body
      const items = listItems(body).filter((review) => review.contractId === contractId)
      return withItems(body, items)
    }
    const findingList = /^\/api\/reviews\/([^/]+)\/findings$/.exec(pathname)
    if (!findingList) return body
    const concept = search.get('concept')
    const classification = search.get('classification')
    const severity = search.get('severity')
    if (!concept && !classification && !severity) return body
    const items = listItems(body).filter((finding) => {
      if (concept
        && finding.concept !== concept
        && finding.secondaryConcept !== concept) return false
      if (classification && finding.reviewClassification !== classification) return false
      if (severity && finding.severity !== severity) return false
      return true
    })
    return withItems(body, items)
  }

  private applyOverlays(pathname: string, body: unknown): unknown {
    if (!this.ids.c5) return this.applyCandidateOverlay(pathname, body)
    const c5 = this.ids.c5
    if (pathname === '/api/reviews') return this.applyReviewOverlay(body)
    if (pathname === `/api/reviews/${c5.reviewId}/human-review`) {
      return this.applyHumanReviewOverlay(body)
    }
    if (pathname === `/api/reviews/${c5.reviewId}/decisions`) {
      const copy = clone(body)
      const data = dataOf(copy)
      if (data && typeof data === 'object' && Array.isArray((data as { items?: unknown }).items)) {
        ;(data as Record<string, unknown>).items = this.overlayCurrentDecisions(
          (data as { items: Record<string, unknown>[] }).items)
        ;(data as Record<string, unknown>).truncated = false
      }
      return copy
    }
    const history = new RegExp(
      `^/api/reviews/${c5.reviewId}/findings/([^/]+)/decisions$`).exec(pathname)
    if (history) {
      const copy = clone(body)
      const data = dataOf(copy)
      if (data && typeof data === 'object' && Array.isArray((data as { items?: unknown }).items)) {
        ;(data as Record<string, unknown>).items = this.overlayDecisionHistory(
          history[1], (data as { items: Record<string, unknown>[] }).items)
        ;(data as Record<string, unknown>).truncated = false
      }
      return copy
    }
    return this.applyCandidateOverlay(pathname, body)
  }

  private async serveDownload(pathname: string): Promise<Response | null> {
    const match = /^\/api\/reviews\/([^/]+)\/redline\/download$/.exec(pathname)
    if (!match) return null
    const reviewId = match[1]
    const redline = this.rawLookup(this.stage, `GET /api/reviews/${reviewId}/redline`)?.body
    const execution = dataOf(redline)
    if (!execution || typeof execution !== 'object'
      || (execution as Record<string, unknown>).status !== 'COMPLETED'
      || (execution as Record<string, unknown>).downloadAvailable !== true) {
      return problem(404)
    }
    const file = this.manifest.files?.[reviewId]
    if (!file) return problem(404)
    let bytes = this.files.get(reviewId)
    if (!bytes) {
      try {
        const response = await this.baseFetch(`${this.dataBase}files/${reviewId}.docx`)
        if (!response.ok) return problem(404)
        bytes = await response.arrayBuffer()
      } catch {
        return problem(404)
      }
      this.files.set(reviewId, bytes)
    }
    try {
      globalThis.dispatchEvent(new CustomEvent('rivell-demo-download', {
        detail: { reviewId, filename: file.filename },
      }))
    } catch {
      // The notice is a convenience; the download still works.
    }
    return new Response(bytes, {
      status: 200,
      headers: {
        'Content-Type': file.contentType || DOCX_MEDIA_TYPE,
        'Content-Length': String(bytes.byteLength),
        'Content-Disposition': `attachment; filename="${file.filename.replaceAll('"', '')}"`,
      },
    })
  }

  private async serveGet(pathname: string, search: URLSearchParams): Promise<Response> {
    const download = await this.serveDownload(pathname)
    if (download) return download
    const found = this.lookup(pathname, search)
    if (!found) return problem(404)
    let body = shiftInstants(clone(found.response.body), this.delta())
    if (found.response.status === 200) {
      body = this.applyFilters(pathname, found.search, body)
      body = this.applyOverlays(pathname, body)
    }
    return envelope(found.response.status, body)
  }

  private blocked(pathname: string, method: string, message?: string): Response {
    try {
      globalThis.dispatchEvent(new CustomEvent('rivell-demo-blocked', {
        detail: { method, path: pathname, ...(message ? { message } : {}) },
      }))
    } catch {
      // The toast is a convenience.
    }
    return problem(403, message)
  }

  private findMutation(method: string, pathname: string): DemoMutation | null {
    // Later states first: the base capture contains the earlier knowledge uploads and
    // publications, and the tour's write is the most recent occurrence of its path.
    for (const name of [...this.manifest.states].reverse()) {
      const mutations = this.captures[name]?.mutations ?? []
      for (let index = mutations.length - 1; index >= 0; index -= 1) {
        const mutation = mutations[index]
        if (mutation.method.toUpperCase() !== method.toUpperCase()) continue
        if (mutation.path.split('?')[0] !== pathname) continue
        return mutation
      }
    }
    return null
  }



  private async handleUpload(request: Request, pathname: string): Promise<Response> {
    if (this.stage !== 'base') {
      return this.blocked(pathname, request.method, 'The prepared playbook has already been added.')
    }
    let filename = ''
    const copy = request.clone()
    try {
      const form = await request.formData()
      const file = form.get('file')
      filename = file && typeof file === 'object' && 'name' in file
        ? String((file as File).name)
        : ''
    } catch {
      // Some non-browser test hosts cannot construct a native File from a multipart body.
      // The only thing the demo needs from it is the name.
      try {
        const text = await copy.text()
        filename = /filename="([^"]+)"/.exec(text)?.[1] ?? ''
      } catch {
        return problem(400)
      }
    }
    if (!filename) return problem(400)
    const prepared = this.manifest.preparedKnowledge?.filename ?? 'NDA Playbook.xlsx'
    if (filename !== prepared) {
      return this.blocked(pathname, request.method,
        'Only the prepared NDA playbook can be added in the demo.')
    }
    const mutation = this.findMutation('POST', pathname)
    if (!mutation) return this.blocked(pathname, request.method)
    advanceDemoStage('tour-k2-compiled')
    return envelope(mutation.status, shiftInstants(clone(mutation.body), this.delta()))
  }

  private async handleBulkApprove(request: Request, pathname: string,
                                  body: Record<string, unknown>): Promise<Response> {
    if (this.stage !== 'tour-k2-compiled') return this.blocked(pathname, request.method)
    const requested = Array.isArray(body.candidateIds)
      ? body.candidateIds.filter((id): id is string => typeof id === 'string')
      : []
    if (requested.length === 0) return problem(400)
    const candidates = this.k2Candidates()
    const eligible = requested.filter((id) => {
      const candidate = candidates.find((entry) => entry.id === id)
      return candidate?.reviewCategory === 'ROUTINE'
    })
    const skipped = Object.fromEntries(
      requested.filter((id) => !eligible.includes(id)).map((id) => [id, 'not routine']))
    approveDemoCandidates(eligible)
    const mutation = this.findMutation('POST', pathname)
    let responseBody: unknown
    if (mutation) {
      const recorded = clone(mutation.body)
      const data = dataOf(recorded)
      const record = data && typeof data === 'object'
        ? data as Record<string, unknown>
        : null
      const recordedIds = Array.isArray(record?.approvedIds) ? record.approvedIds : []
      if (recordedIds.length === eligible.length
        && eligible.every((id) => recordedIds.includes(id))) {
        responseBody = recorded
      } else {
        responseBody = { data: { approvedIds: eligible, skipped }, status: 200 }
      }
    } else {
      responseBody = { data: { approvedIds: eligible, skipped }, status: 200 }
    }
    this.maybeFinishK2()
    return envelope(mutation?.status ?? 200, shiftInstants(responseBody, this.delta()))
  }

  private maybeFinishK2(): void {
    const candidates = this.k2Candidates()
    if (candidates.length === 0) return
    const done = candidates.every((candidate) => {
      const id = String(candidate.id ?? '')
      const status = candidate.reviewStatus
      return status === 'APPROVED' || status === 'EDITED' || status === 'PUBLISHED'
        || (id !== '' && this.isK2Approved(id))
    })
    if (done) advanceDemoStage('tour-k2-approved')
  }

  private handleApprove(request: Request, pathname: string, candidateId: string): Response {
    if (this.stage !== 'tour-k2-compiled') return this.blocked(pathname, request.method)
    approveDemoCandidates([candidateId])
    const mutation = this.findMutation('POST', pathname)
    let responseBody: unknown
    if (mutation) {
      responseBody = clone(mutation.body)
    } else {
      const candidate = this.k2Candidates().find((entry) => entry.id === candidateId)
      if (!candidate) return problem(404)
      responseBody = { data: { ...candidate, reviewStatus: 'APPROVED' }, status: 200 }
    }
    this.maybeFinishK2()
    return envelope(mutation?.status ?? 200, shiftInstants(responseBody, this.delta()))
  }

  private handlePublish(request: Request, pathname: string): Response {
    if (this.stage !== 'tour-k2-approved') return this.blocked(pathname, request.method)
    const mutation = this.findMutation('POST', pathname)
    if (!mutation) return this.blocked(pathname, request.method)
    advanceDemoStage('tour-k2-published')
    return envelope(mutation.status, shiftInstants(clone(mutation.body), this.delta()))
  }

  private approvedActionFor(finding: Record<string, unknown>): string | null {
    switch (finding.proposedAction) {
      case 'USE_APPROVED_FALLBACK': return 'REPLACE'
      case 'ADD_REQUIRED_PROVISION': return 'INSERT'
      case 'CONSIDER_GENERATED_LANGUAGE':
        return finding.subjectKind === 'ABSENCE' ? 'INSERT' : 'REPLACE'
      default: return null
    }
  }

  private handleDecision(request: Request, pathname: string, reviewId: string,
                         findingId: string, body: Record<string, unknown>): Response {
    if (!this.ids.c5 || reviewId !== this.ids.c5.reviewId) {
      return this.blocked(pathname, request.method)
    }
    const stage = this.stage
    if (stage === 'tour-c5-finalized' || stage === 'tour-c5-done') {
      return this.blocked(pathname, request.method)
    }
    if (stageIndex(stage) < stageIndex('tour-k2-published')) {
      return this.blocked(pathname, request.method)
    }
    const action = String(body.action ?? '')
    if (!['ACCEPT', 'EDIT', 'REJECT', 'ESCALATE', 'RESOLVE'].includes(action)) {
      return problem(400)
    }
    const previous = this.effectiveDecision(findingId)
    const expectedVersion = typeof body.expectedVersion === 'number' ? body.expectedVersion : 0
    if ((previous?.revision ?? 0) !== expectedVersion) {
      return problem(409)
    }
    const finding = this.c5Finding(findingId)
    if (!finding) return problem(404)
    const template = this.recordedDecisionTemplate(findingId)
    const revision = (previous?.revision ?? 0) + 1
    const approvedAction = typeof body.approvedAction === 'string'
      ? body.approvedAction
      : action === 'ACCEPT'
        ? this.approvedActionFor(finding)
        : null
    const approvedLanguage = typeof body.approvedLanguage === 'string'
      ? body.approvedLanguage
      : action === 'ACCEPT' && typeof finding.proposedLanguage === 'string'
        ? finding.proposedLanguage
        : null
    const languageSource = action === 'EDIT'
      ? (approvedLanguage ? 'HUMAN_EDIT' : null)
      : action === 'ACCEPT'
        ? (typeof finding.proposedLanguageSource === 'string'
          && finding.proposedLanguageSource !== 'NONE'
          ? finding.proposedLanguageSource
          : template?.languageSource ?? null)
        : null
    const decision: DemoDecision = {
      id: `demo-decision-${findingId}-${revision}`,
      findingId,
      revision,
      action,
      approvedAction,
      approvedLanguage,
      languageSource,
      approvedDraftingEvidenceId: action === 'ACCEPT'
        ? (finding.approvedDraftingEvidenceId as string | null) ?? null
        : null,
      comment: typeof body.comment === 'string' ? body.comment : null,
      rejectReason: typeof body.rejectReason === 'string' ? body.rejectReason : null,
      approvedBy: typeof body.approvedBy === 'string' ? body.approvedBy : null,
      actorId: template?.actorId ?? '00000000-0000-0000-0000-000000000001',
      decidedAt: new Date(this.now()).toISOString(),
      clientRequestId: typeof body.clientRequestId === 'string' ? body.clientRequestId : null,
      supersedesDecisionId: previous?.id ?? this.recordedDecision(findingId)?.id ?? null,
    }
    recordDemoDecision(decision)
    this.maybeFinishC5()
    // Always answer with the decision this browser stored. The recorded template supplies the
    // shape and ids where useful, but returning its revision would disagree with the overlay the
    // next request reads and would make the following expectedVersion look stale.
    return envelope(200, {
      data: decision,
      status: 200,
      timestamp: new Date(this.now()).toISOString(),
    })
  }

  private maybeFinishC5(): void {
    if (!this.ids.c5) return
    const actionable = this.c5Findings().filter(
      (finding) => finding.reviewClassification !== 'INFORMATIONAL')
    if (actionable.length === 0) return
    const all = actionable.every((finding) => {
      const id = String(finding.id ?? '')
      const decision = id !== '' ? this.effectiveDecision(id) : null
      // An escalation is recorded but not resolved, and finalization stays blocked. The tour
      // only moves to the decided view once every finding has a resolved decision.
      return decision !== null && decision.action !== 'ESCALATE'
    })
    if (all) advanceDemoStage('tour-c5-decided')
  }

  private handleFinalize(request: Request, pathname: string, reviewId: string): Response {
    if (!this.ids.c5 || reviewId !== this.ids.c5.reviewId) {
      return this.blocked(pathname, request.method)
    }
    if (this.stage !== 'tour-c5-decided') return this.blocked(pathname, request.method)
    const mutation = this.findMutation('POST', pathname)
    if (!mutation) return this.blocked(pathname, request.method)
    advanceDemoStage('tour-c5-finalized')
    try {
      globalThis.dispatchEvent(new CustomEvent('rivell-demo-finalized', {
        detail: { reviewId },
      }))
    } catch {
      // The notice is a convenience; the finalize still succeeded.
    }
    return envelope(mutation.status, shiftInstants(clone(mutation.body), this.delta()))
  }

  private handleRedline(request: Request, pathname: string, reviewId: string): Response {
    if (!this.ids.c5 || reviewId !== this.ids.c5.reviewId) {
      return this.blocked(pathname, request.method)
    }
    if (this.stage !== 'tour-c5-finalized') return this.blocked(pathname, request.method)
    const mutation = this.findMutation('POST', pathname)
    if (!mutation) return this.blocked(pathname, request.method)
    advanceDemoStage('tour-c5-done')
    return envelope(mutation.status, shiftInstants(clone(mutation.body), this.delta()))
  }

  private async handleWrite(request: Request, pathname: string): Promise<Response> {
    if (pathname === '/api/knowledge/artifacts') return this.handleUpload(request, pathname)
    const contentType = (request.headers.get('content-type') ?? '').toLowerCase()
    if (contentType.includes('multipart/form-data')) {
      // Start review's contract upload is multipart. It is not on the tour; refuse it as a
      // blocked demo write rather than trying to parse it as JSON.
      return this.blocked(pathname, request.method)
    }
    let raw = ''
    try {
      raw = await request.text()
    } catch {
      return problem(400)
    }
    let body: Record<string, unknown> = {}
    if (raw.trim()) {
      try {
        const parsed: unknown = JSON.parse(raw)
        if (parsed && typeof parsed === 'object') body = parsed as Record<string, unknown>
      } catch {
        return problem(400)
      }
    }
    if (request.method === 'POST' && pathname === '/api/knowledge/candidates/bulk-approve') {
      return this.handleBulkApprove(request, pathname, body)
    }
    const approve = /^\/api\/knowledge\/candidates\/([^/]+)\/approve$/.exec(pathname)
    if (request.method === 'POST' && approve) {
      return this.handleApprove(request, pathname, approve[1])
    }
    if (request.method === 'POST' && pathname === '/api/knowledge/publications') {
      return this.handlePublish(request, pathname)
    }
    const decision = /^\/api\/reviews\/([^/]+)\/findings\/([^/]+)\/decision$/.exec(pathname)
    if (request.method === 'PUT' && decision) {
      return this.handleDecision(request, pathname, decision[1], decision[2], body)
    }
    const finalize = /^\/api\/reviews\/([^/]+)\/finalize$/.exec(pathname)
    if (request.method === 'POST' && finalize) {
      return this.handleFinalize(request, pathname, finalize[1])
    }
    const redline = /^\/api\/reviews\/([^/]+)\/redline$/.exec(pathname)
    if (request.method === 'POST' && redline) {
      return this.handleRedline(request, pathname, redline[1])
    }
    return this.blocked(pathname, request.method)
  }

  async fetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
    const request = input instanceof Request ? input : new Request(input, init)
    const url = new URL(request.url, globalThis.location?.origin ?? 'http://localhost')
    if (!url.pathname.startsWith('/api/')) return this.baseFetch(input, init)
    const method = request.method.toUpperCase()
    if (method === 'GET' || method === 'HEAD') {
      return this.serveGet(url.pathname, url.searchParams)
    }
    return this.handleWrite(request, url.pathname)
  }
}

let installed = false
let runtime: DemoRuntime | null = null
let demoIds: DemoIds = {}

export function getDemoIds(): DemoIds {
  return demoIds
}

export function getDemoPreparedFilename(): string {
  return runtime?.preparedFilename ?? 'NDA Playbook.xlsx'
}

async function loadCapture(baseFetch: typeof fetch) {
  const origin = globalThis.location?.origin ?? 'http://localhost'
  const root = new URL(`${import.meta.env.BASE_URL}demo-data/`, origin)
  const manifestResponse = await baseFetch(new URL('manifest.json', root))
  if (!manifestResponse.ok) throw new Error('manifest unavailable')
  const manifest = await manifestResponse.json() as DemoManifest
  const states = Array.isArray(manifest.states) ? manifest.states : []
  const entries = await Promise.all(states.map(async (state) => {
    const response = await baseFetch(new URL(`${encodeURIComponent(state)}.json`, root))
    if (!response.ok) throw new Error(`state ${state} unavailable`)
    return [state, await response.json() as DemoCapture] as const
  }))
  return { manifest, captures: Object.fromEntries(entries) }
}

export async function installDemoTransport(): Promise<void> {
  if (installed) return
  installed = true
  const baseFetch: typeof fetch = globalThis.fetch.bind(globalThis)
  try {
    const { manifest, captures } = await loadCapture(baseFetch)
    const origin = globalThis.location?.origin ?? 'http://localhost'
    runtime = new DemoRuntime(manifest, captures, {
      baseFetch,
      dataBase: new URL(`${import.meta.env.BASE_URL}demo-data/`, origin).toString(),
    })
    demoIds = runtime.ids
    globalThis.fetch = ((input: RequestInfo | URL, init?: RequestInit) =>
      runtime!.fetch(input, init)) as typeof fetch
  } catch {
    // A broken or absent capture must not let the page call a real backend by accident.
    globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init)
      const url = new URL(request.url, globalThis.location?.origin ?? 'http://localhost')
      if (url.pathname.startsWith('/api/')) return problem(503)
      return baseFetch(input, init)
    }) as typeof fetch
  }
}

