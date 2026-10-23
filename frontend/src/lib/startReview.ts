import type { ReviewProfileInput } from '../api/semantics'
import { CONTRACT_TYPE_LABELS, JURISDICTION_LABELS, PAPER_SOURCE_LABELS, roleLabel } from './contractSemantics'

/**
 * The reviewer's declared context for a new review, as the Start Review form holds it. Nothing is
 * defaulted on their behalf except "jurisdiction not specified", which is the real constant for
 * an undeclared jurisdiction. An empty string means "not declared".
 */
export interface StartContext {
  contractType: string
  ourSideRole: string
  paperSource: string
  jurisdiction: string
  /** The explicit contract pack, or empty for a pack-less review. */
  packKey: string
}

export const EMPTY_CONTEXT: StartContext = {
  contractType: '',
  ourSideRole: '',
  paperSource: '',
  jurisdiction: 'UNSPECIFIED',
  packKey: '',
}

/** The domain needs a contract type before it can interpret the document. Everything else is optional. */
export function canStart(file: File | null, context: StartContext): boolean {
  return file !== null && context.contractType !== ''
}

/** The body of the enrichment request: the confirmed context, plus the pack when one was chosen. */
export function profileFrom(context: StartContext): ReviewProfileInput {
  const profile: ReviewProfileInput = {
    contractType: context.contractType as ReviewProfileInput['contractType'],
  }
  if (context.ourSideRole) profile.ourSideRole = context.ourSideRole as ReviewProfileInput['ourSideRole']
  if (context.paperSource) profile.paperSource = context.paperSource as ReviewProfileInput['paperSource']
  if (context.jurisdiction) profile.jurisdiction = context.jurisdiction as ReviewProfileInput['jurisdiction']
  if (context.packKey) profile.contractPackKey = context.packKey
  return profile
}

/**
 * The canonical advisory: the filename names a different contract type than the one declared.
 * Rivell still reviews it as declared; this only lets the reviewer catch a slip.
 */
export function filenameSuggestsOtherType(fileName: string, contractType: string): boolean {
  if (!contractType) return false
  if (/nda/i.test(fileName) && contractType !== 'NDA') return true
  return /(msa|services)/i.test(fileName) && contractType === 'NDA'
}

/** "NDA, Customer side, counterparty paper, United Kingdom": what the reviewer has declared so far. */
export function contextSummary(context: StartContext): string {
  const parts: string[] = []
  if (context.contractType) parts.push(CONTRACT_TYPE_LABELS[context.contractType] ?? context.contractType)
  if (context.ourSideRole) parts.push(`${roleLabel(context.ourSideRole)} side`)
  if (context.paperSource) {
    parts.push((PAPER_SOURCE_LABELS[context.paperSource] ?? context.paperSource).toLowerCase())
  }
  if (context.jurisdiction && context.jurisdiction !== 'UNSPECIFIED') {
    parts.push(JURISDICTION_LABELS[context.jurisdiction] ?? context.jurisdiction)
  }
  return parts.length > 0 ? parts.join(', ') : 'Nothing declared yet'
}

export function fileSizeLabel(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function isDocx(file: File): boolean {
  return file.name.toLowerCase().endsWith('.docx')
}

/** The three requests, in the only order that is valid: store, confirm context, then start analysis. */
export type StartStage = 'upload' | 'confirm' | 'create'

export const STAGE_LABELS: Record<StartStage, string> = {
  upload: 'Uploading and reading the contract…',
  confirm: 'Saving the review details…',
  create: 'Starting analysis…',
}
