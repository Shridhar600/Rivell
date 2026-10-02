import type { ArtifactSummary, ArtifactType, Publication } from '../api/knowledge'
import type { ReviewStatus } from '../api/reviews'
import { shortDate } from './dates'

export type HomeGroupKey = 'needs_action' | 'running' | 'failed' | 'extracted' | 'completed'
export type HomeTone = 'active' | 'attention' | 'danger' | 'neutral' | 'complete'

export const HOME_GROUP_LABELS: Record<HomeGroupKey, string> = {
  needs_action: 'Needs action',
  running: 'Running',
  failed: 'Failed',
  extracted: 'Extracted',
  // Deliberately not "Recently": /api/knowledge/publications is unbounded and carries no recency
  // field, so the old label was a time claim the data cannot support.
  completed: 'Completed',
}

interface MappedState {
  group: HomeGroupKey
  label: string
  tone: HomeTone
}

const UNKNOWN_STATE: MappedState = { group: 'needs_action', label: 'Status unknown', tone: 'neutral' }

/**
 * `ingestStatus` never changes after extraction, so it cannot prove that a source still awaits
 * human review: once every candidate is published, the summary still reads EXTRACTED. The label
 * therefore states only what the field proves. Whether outstanding review work exists is a
 * backend gap, recorded in DEFERRED_WORK.md.
 */
const INGEST_STATE: Record<string, MappedState> = {
  RECEIVED: { group: 'running', label: 'Queued for compilation', tone: 'active' },
  EXTRACTING: { group: 'running', label: 'Compiling', tone: 'active' },
  EXTRACTED: { group: 'extracted', label: 'Extracted', tone: 'neutral' },
  FAILED: { group: 'failed', label: 'Ingestion failed', tone: 'danger' },
}

/**
 * COMPLETED means analysis finished. It says nothing about whether decisions remain: a finalized
 * review reports COMPLETED too. Outstanding human work lives on HumanReviewView
 * (actionableFindings / remainingFindings), one read per review, so this must not claim it.
 */
const REVIEW_STATE: Record<string, MappedState> = {
  ANALYSIS_QUEUED: { group: 'running', label: 'Analysis queued', tone: 'active' },
  ANALYZING: { group: 'running', label: 'Analyzing', tone: 'active' },
  COMPLETED: { group: 'completed', label: 'Analysis complete', tone: 'complete' },
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
  /** Short, honest date for the WorkRow time column. Never a relative guess. */
  timeLabel: string
  /** The raw API timestamp behind `timeLabel`, for a full date in the inspector. */
  timeValue: string
  /** How to label that timestamp truthfully: `createdAt` is an "Added" time, not an "Updated" one. */
  timeLabelKind: 'added' | 'updated'
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
    timeLabel: shortDate(artifact.createdAt),
    timeValue: artifact.createdAt,
    timeLabelKind: 'added',
    to: `/knowledge/artifacts/${artifact.id}`,
    primaryActionLabel: 'Open knowledge source',
    guidance: knowledgeGuidance(state),
  }
}

function knowledgeGuidance(state: MappedState): string | null {
  switch (state.label) {
    case 'Extracted':
      // ingestStatus cannot say whether review work is outstanding, so it says only what happened.
      return 'Compilation finished. Nothing from this source is used until it is published.'
    case 'Ingestion failed':
      return 'Nothing from this source was published.'
    case 'Compiling':
    case 'Queued for compilation':
      return 'You can leave this page. This source keeps moving until compilation finishes, or it stops in Failed.'
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
    group: 'completed',
    timeLabel: shortDate(publication.createdAt),
    timeValue: publication.createdAt,
    timeLabelKind: 'added',
    to: `/knowledge/publications/${publication.id}`,
    primaryActionLabel: 'View publication',
    guidance: 'Published knowledge is an immutable versioned snapshot.',
  }
}

/**
 * The subset of `ReviewListItemView` Home needs. `contractTitle` and decision counts stay absent:
 * the read model carries a filename and a findings total, but nothing that says how much of that
 * work still awaits a human. Outstanding decisions live on `HumanReviewView`, one read per review.
 */
export interface HomeReviewSummary {
  id: string
  contractId: string
  filename: string | null
  contractVersionNumber: number | null
  status: ReviewStatus | string
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  findings: number
}

export function reviewItemView(review: HomeReviewSummary): HomeWorkItem {
  const state = mapReviewStatus(review.status)
  const time = review.completedAt ?? review.startedAt ?? review.createdAt
  const timeKind = review.completedAt ? 'updated' : review.startedAt ? 'updated' : 'added'
  return {
    id: `review:${review.id}`,
    kind: 'review',
    title: review.filename?.trim() || 'Untitled contract',
    meta: review.findings > 0 ? `${review.findings} ${review.findings === 1 ? 'finding' : 'findings'}` : null,
    chip: review.contractVersionNumber === null ? null : `v${review.contractVersionNumber}`,
    stateLabel: state.label,
    tone: state.tone,
    group: state.group,
    timeLabel: shortDate(time),
    timeValue: time,
    timeLabelKind: timeKind,
    to: `/reviews/${review.contractId}/analysis/${review.id}`,
    primaryActionLabel: 'Open review',
    guidance: reviewGuidance(state),
  }
}

function reviewGuidance(state: MappedState): string | null {
  if (state.label === 'Analysis failed') return 'The source document is unchanged.'
  if (state.group === 'running') return 'You can leave this page.'
  return null
}

export interface HomeGroups {
  needsAction: HomeWorkItem[]
  running: HomeWorkItem[]
  failed: HomeWorkItem[]
  extracted: HomeWorkItem[]
  completed: HomeWorkItem[]
}

export function buildHomeGroups(items: readonly HomeWorkItem[]): HomeGroups {
  const groups: HomeGroups = { needsAction: [], running: [], failed: [], extracted: [], completed: [] }
  for (const item of items) {
    if (item.group === 'needs_action') groups.needsAction.push(item)
    else if (item.group === 'running') groups.running.push(item)
    else if (item.group === 'failed') groups.failed.push(item)
    else if (item.group === 'extracted') groups.extracted.push(item)
    else groups.completed.push(item)
  }
  return groups
}
