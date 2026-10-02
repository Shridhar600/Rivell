import type { ArtifactSummary, ArtifactType, Publication } from '../api/knowledge'
import type { ReviewStatus } from '../api/reviews'
import { humanizeCode } from './domainLabels'

export type HomeGroupKey = 'needs_action' | 'running' | 'failed' | 'recently_completed'
export type HomeTone = 'active' | 'attention' | 'danger' | 'neutral' | 'complete'

export const HOME_GROUP_LABELS: Record<HomeGroupKey, string> = {
  needs_action: 'Needs action',
  running: 'Running',
  failed: 'Failed',
  recently_completed: 'Recently completed',
}

interface MappedState {
  group: HomeGroupKey
  label: string
  tone: HomeTone
}

const UNKNOWN_STATE: MappedState = { group: 'needs_action', label: 'Status unknown', tone: 'neutral' }

const INGEST_STATE: Record<string, MappedState> = {
  RECEIVED: { group: 'running', label: 'Queued for compilation', tone: 'active' },
  EXTRACTING: { group: 'running', label: 'Compiling', tone: 'active' },
  EXTRACTED: { group: 'needs_action', label: 'Ready for review', tone: 'attention' },
  FAILED: { group: 'failed', label: 'Ingestion failed', tone: 'danger' },
}

const REVIEW_STATE: Record<string, MappedState> = {
  ANALYSIS_QUEUED: { group: 'running', label: 'Analysis queued', tone: 'active' },
  ANALYZING: { group: 'running', label: 'Analyzing', tone: 'active' },
  COMPLETED: { group: 'needs_action', label: 'Ready for review', tone: 'attention' },
  FAILED: { group: 'failed', label: 'Analysis failed', tone: 'danger' },
}

export function mapIngestStatus(status: string | null | undefined): MappedState {
  return (status && INGEST_STATE[status]) || UNKNOWN_STATE
}

export function mapReviewStatus(status: string | null | undefined): MappedState {
  return (status && REVIEW_STATE[status]) || UNKNOWN_STATE
}

const ARTIFACT_CHIPS: Record<ArtifactType, string> = {
  PLAYBOOK: 'Playbook',
  CLAUSE_LIBRARY: 'Clause library',
  TEMPLATE: 'Template',
  EXECUTED_PRECEDENT: 'Precedent',
  NEGOTIATION_HISTORY: 'Negotiation history',
  EXTERNAL_REFERENCE: 'External reference',
}

export type HomeItemKind = 'review' | 'knowledge_artifact' | 'knowledge_publication'

export interface HomeWorkItem {
  id: string
  kind: HomeItemKind
  title: string
  meta: string | null
  chip: string | null
  stateLabel: string
  tone: HomeTone
  group: HomeGroupKey
  updatedAt: string
  /** Short, honest date for the WorkRow time column. Never a relative guess. */
  timeLabel: string
  /** Only set when the server actually supplied both counts. Never synthesised. */
  decisionProgress: { resolved: number; total: number } | null
  /** Real in-product route for the primary action. */
  to: string
  primaryActionLabel: string
  /** One truthful line of guidance, or null. */
  guidance: string | null
}

export function knowledgeItemView(artifact: ArtifactSummary): HomeWorkItem {
  const state = mapIngestStatus(artifact.ingestStatus)
  return {
    id: `artifact:${artifact.id}`,
    kind: 'knowledge_artifact',
    title: artifact.filename,
    meta: null,
    chip: ARTIFACT_CHIPS[artifact.artifactType] ?? 'Knowledge source',
    stateLabel: state.label,
    tone: state.tone,
    group: state.group,
    updatedAt: artifact.createdAt,
    timeLabel: shortDate(artifact.createdAt),
    decisionProgress: null,
    to: `/knowledge/artifacts/${artifact.id}`,
    primaryActionLabel: state.group === 'needs_action' ? 'Review candidates' : 'Open knowledge source',
    guidance: knowledgeGuidance(state),
  }
}

function knowledgeGuidance(state: MappedState): string | null {
  switch (state.label) {
    case 'Ready for review':
      return 'Extracted knowledge is not used in reviews until you publish it.'
    case 'Ingestion failed':
      return 'Nothing from this source was published.'
    case 'Compiling':
    case 'Queued for compilation':
      return 'You can leave this page. It moves to Needs action when compilation finishes.'
    default:
      return null
  }
}

export function publicationItemView(publication: Publication): HomeWorkItem {
  return {
    id: `publication:${publication.id}`,
    kind: 'knowledge_publication',
    title: `Knowledge publication ${publication.publicationNumber}`,
    meta: publication.note,
    chip: null,
    stateLabel: 'Published',
    tone: 'complete',
    group: 'recently_completed',
    updatedAt: publication.createdAt,
    timeLabel: shortDate(publication.createdAt),
    decisionProgress: null,
    to: `/knowledge/publications/${publication.id}`,
    primaryActionLabel: 'View publication',
    guidance: 'Published knowledge is an immutable versioned snapshot.',
  }
}

export interface HomeReviewSummary {
  id: string
  contractId: string
  contractTitle: string | null
  contractVersionNumber: number
  status: ReviewStatus | string
  createdAt: string
  completedAt: string | null
  decisionProgress?: { resolved: number; total: number } | null
}

export function reviewItemView(review: HomeReviewSummary): HomeWorkItem {
  const state = mapReviewStatus(review.status)
  const progress =
    review.decisionProgress && review.decisionProgress.total > 0
      ? review.decisionProgress
      : null
  return {
    id: `review:${review.id}`,
    kind: 'review',
    title: review.contractTitle?.trim() || 'Untitled contract',
    meta: progress ? `${progress.resolved} of ${progress.total} decided` : null,
    chip: `v${review.contractVersionNumber}`,
    stateLabel: state.label,
    tone: state.tone,
    group: state.group,
    updatedAt: review.completedAt ?? review.createdAt,
    timeLabel: shortDate(review.completedAt ?? review.createdAt),
    decisionProgress: progress,
    to: `/reviews/${review.contractId}/analysis/${review.id}`,
    primaryActionLabel: state.group === 'needs_action' ? 'Open review workspace' : 'Open review',
    guidance: reviewGuidance(state),
  }
}

function reviewGuidance(state: MappedState): string | null {
  if (state.label === 'Analysis failed') return 'The source document is unchanged.'
  if (state.label === 'Ready for review') return 'Findings await your decision. Rivell recommends; you decide.'
  if (state.group === 'running') return 'You can leave this page.'
  return null
}

export interface HomeGroups {
  needsAction: HomeWorkItem[]
  running: HomeWorkItem[]
  failed: HomeWorkItem[]
  recentlyCompleted: HomeWorkItem[]
}

export function buildHomeGroups(items: readonly HomeWorkItem[]): HomeGroups {
  const groups: HomeGroups = { needsAction: [], running: [], failed: [], recentlyCompleted: [] }
  for (const item of items) {
    switch (item.group) {
      case 'needs_action':
        groups.needsAction.push(item)
        break
      case 'running':
        groups.running.push(item)
        break
      case 'failed':
        groups.failed.push(item)
        break
      case 'recently_completed':
        groups.recentlyCompleted.push(item)
        break
      default:
        break
    }
  }
  return groups
}

export function unknownStateLabel(code: string): string {
  return humanizeCode(code)
}

/**
 * A real calendar date, not a relative guess. The prototype's "Today" / "9:12" / "2m" column
 * cannot be reproduced honestly from a timestamp without a timezone and a reference clock.
 */
function shortDate(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}