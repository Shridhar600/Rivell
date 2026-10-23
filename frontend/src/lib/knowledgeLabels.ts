import type {
  ArtifactType,
  CandidateType,
  IngestStatus,
  RecordKind,
  ReviewStatus,
  UnitKind,
} from '../api/knowledge'

export const ARTIFACT_TYPE_LABELS: Record<ArtifactType, string> = {
  PLAYBOOK: 'Contract playbook',
  CLAUSE_LIBRARY: 'Clause library',
  TEMPLATE: 'Standard template',
  EXECUTED_PRECEDENT: 'Signed precedent',
  NEGOTIATION_HISTORY: 'Negotiation history',
  EXTERNAL_REFERENCE: 'External reference',
}

export const ARTIFACT_TYPE_DESCRIPTIONS: Record<ArtifactType, string> = {
  PLAYBOOK: 'Your approved positions for a type of contract, including preferred and fallback wording.',
  CLAUSE_LIBRARY: 'Approved clause wording your team can reuse in a contract.',
  TEMPLATE: 'A standard document your organization starts from.',
  EXECUTED_PRECEDENT: 'A signed agreement kept as an example of wording accepted before.',
  NEGOTIATION_HISTORY: 'A record of how wording changed during a negotiation.',
  EXTERNAL_REFERENCE: 'Background material that is not approved policy.',
}

export const INGEST_STATUS_LABELS: Record<IngestStatus, string> = {
  RECEIVED: 'Received',
  EXTRACTING: 'Reading',
  EXTRACTED: 'Read',
  FAILED: 'Failed',
}

export const CANDIDATE_TYPE_LABELS: Record<CandidateType, string> = {
  PLAYBOOK_RULE: 'Playbook rule',
  CLAUSE_VARIANT: 'Clause variant',
  TEMPLATE_CLAUSE: 'Template clause',
  PRECEDENT: 'Precedent',
  REFERENCE: 'Reference',
}

export const REVIEW_STATUS_LABELS: Record<ReviewStatus, string> = {
  PENDING: 'Pending review',
  APPROVED: 'Approved',
  EDITED: 'Edited',
  REJECTED: 'Rejected',
  PUBLISHED: 'Published',
}

export const UNIT_KIND_LABELS: Record<UnitKind, string> = {
  HEADING_SECTION: 'Section',
  TABLE: 'Table',
  SPREADSHEET_ROW: 'Spreadsheet row',
}

export const RECORD_KIND_LABELS: Record<RecordKind, string> = {
  POSITION: 'Position',
  WORDING: 'Wording',
  ESCALATION: 'Escalation row',
  GUIDANCE: 'Guidance',
  DOCUMENT_CONTROL: 'Document control',
  OTHER: 'Other',
}

export const PUBLICATION_ACTION_LABELS = {
  ADD: 'Add',
  SUPERSEDE: 'Supersede',
} as const

export function formatBytes(sizeBytes: number): string {
  if (sizeBytes < 1024) return `${sizeBytes} B`
  const kb = sizeBytes / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  return `${(kb / 1024).toFixed(1)} MB`
}

export function formatTimestamp(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  })
}

export function shortSha(sha256: string): string {
  return sha256.length > 12 ? `${sha256.slice(0, 12)}…` : sha256
}
