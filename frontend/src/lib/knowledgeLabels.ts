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
  TEMPLATE: 'Template / house paper',
  EXECUTED_PRECEDENT: 'Executed precedent',
  NEGOTIATION_HISTORY: 'Negotiation history',
  EXTERNAL_REFERENCE: 'External reference',
}

export const ARTIFACT_TYPE_DESCRIPTIONS: Record<ArtifactType, string> = {
  PLAYBOOK:
    'Negotiation policy: preferred, fallback, and escalation positions for a contract family.',
  CLAUSE_LIBRARY: 'Approved reusable clause wording, preserved exactly as written.',
  TEMPLATE: 'Standard organizational drafting and document structure.',
  EXECUTED_PRECEDENT:
    'Historically accepted language. Evidence for future negotiations, not policy.',
  NEGOTIATION_HISTORY: 'How language evolved through a negotiation.',
  EXTERNAL_REFERENCE:
    'Informational material with lower authority than internal policy.',
}

export const INGEST_STATUS_LABELS: Record<IngestStatus, string> = {
  RECEIVED: 'Received',
  EXTRACTING: 'Extracting',
  EXTRACTED: 'Extracted',
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
  HEADING_SECTION: 'Heading section',
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
