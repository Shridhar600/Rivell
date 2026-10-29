/**
 * The demo's browser state: which captured stage the visitor has reached, the K2 entries they
 * approved, their own C5 decisions, and whether the guide is collapsed.
 *
 * One versioned key. Every storage call is wrapped: a browser that refuses storage still gets a
 * working demo, it just forgets the visit when the tab closes.
 */

export const DEMO_STAGES = [
  'base',
  'tour-k2-compiled',
  'tour-k2-approved',
  'tour-k2-published',
  'tour-c5-decided',
  'tour-c5-finalized',
  'tour-c5-done',
] as const

export type DemoStage = (typeof DEMO_STAGES)[number]

export interface DemoDecision {
  id: string
  findingId: string
  revision: number
  action: string
  approvedAction: string | null
  approvedLanguage: string | null
  languageSource: string | null
  approvedDraftingEvidenceId: string | null
  comment: string | null
  rejectReason: string | null
  approvedBy: string | null
  actorId: string
  decidedAt: string
  clientRequestId: string | null
  supersedesDecisionId: string | null
}

export interface DemoState {
  version: 1
  stage: DemoStage
  k2Approved: string[]
  decisionHistory: Record<string, DemoDecision[]>
  guideHidden: boolean
}

const STORAGE_KEY = 'rivell-demo-v1'
const CHANGE_EVENT = 'rivell-demo-change'

function initial(): DemoState {
  return { version: 1, stage: 'base', k2Approved: [], decisionHistory: {}, guideHidden: false }
}

function isStage(value: unknown): value is DemoStage {
  return typeof value === 'string' && (DEMO_STAGES as readonly string[]).includes(value)
}

function read(): DemoState {
  try {
    const raw = globalThis.localStorage?.getItem(STORAGE_KEY)
    if (!raw) return initial()
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return initial()
    const candidate = parsed as Partial<DemoState>
    return {
      version: 1,
      stage: isStage(candidate.stage) ? candidate.stage : 'base',
      k2Approved: Array.isArray(candidate.k2Approved)
        ? candidate.k2Approved.filter((id): id is string => typeof id === 'string')
        : [],
      decisionHistory:
        candidate.decisionHistory && typeof candidate.decisionHistory === 'object'
          ? candidate.decisionHistory
          : {},
      guideHidden: candidate.guideHidden === true,
    }
  } catch {
    return initial()
  }
}

let current = read()
const listeners = new Set<() => void>()

function persist(): void {
  try {
    globalThis.localStorage?.setItem(STORAGE_KEY, JSON.stringify(current))
  } catch {
    // The in-memory copy is the demo state; storage is only a convenience.
  }
}

export function getDemoState(): DemoState {
  return current
}

export function subscribeDemo(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function commit(next: DemoState): void {
  current = next
  persist()
  for (const listener of listeners) listener()
  try {
    globalThis.dispatchEvent(new Event(CHANGE_EVENT))
  } catch {
    // A non-browser test host may not have an EventTarget; module listeners still ran.
  }
}

export function stageIndex(stage: DemoStage): number {
  return DEMO_STAGES.indexOf(stage)
}

/** Stages only move forward on the recorded tour path; reset is the only way back. */
export function advanceDemoStage(stage: DemoStage): boolean {
  if (stageIndex(stage) <= stageIndex(current.stage)) return false
  commit({ ...current, stage })
  return true
}

export function approveDemoCandidates(ids: readonly string[]): void {
  const merged = new Set(current.k2Approved)
  for (const id of ids) merged.add(id)
  commit({ ...current, k2Approved: [...merged] })
}

export function currentDemoDecision(findingId: string): DemoDecision | null {
  const history = current.decisionHistory[findingId]
  return history && history.length > 0 ? history[history.length - 1] : null
}

export function recordDemoDecision(decision: DemoDecision): void {
  const history = current.decisionHistory[decision.findingId] ?? []
  commit({
    ...current,
    decisionHistory: { ...current.decisionHistory, [decision.findingId]: [...history, decision] },
  })
}

export function setGuideHidden(hidden: boolean): void {
  if (current.guideHidden === hidden) return
  commit({ ...current, guideHidden: hidden })
}

/** Clears every choice this browser made. The transport and the guide read the fresh state. */
export function resetDemo(): void {
  current = initial()
  try {
    globalThis.localStorage?.removeItem(STORAGE_KEY)
  } catch {
    // Nothing was stored.
  }
  for (const listener of listeners) listener()
  try {
    globalThis.dispatchEvent(new Event(CHANGE_EVENT))
  } catch {
    // A non-browser test host may not have an EventTarget; module listeners still ran.
  }
}

export const DEMO_CHANGE_EVENT = CHANGE_EVENT
