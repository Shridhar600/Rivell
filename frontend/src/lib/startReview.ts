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
const NDA_TYPES = new Set(['NDA', 'MNDA', 'ONDA'])

/**
 * The contract type a file name points to when it differs from the chosen one ("an NDA", "an MSA"),
 * or null. Every NDA kind counts as an NDA, so a mutual or one-way NDA never warns.
 */
export function filenameSuggestedType(fileName: string, contractType: string): string | null {
  if (!contractType) return null
  if (/nda/i.test(fileName)) return NDA_TYPES.has(contractType) ? null : 'an NDA'
  if (/(msa|services)/i.test(fileName) && NDA_TYPES.has(contractType)) return 'an MSA'
  return null
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
  return parts.join(', ')
}

/** What is still missing before Start analysis can be pressed, or null when nothing is. */
export function startNeeds(file: File | null, context: StartContext): string | null {
  if (!file && !context.contractType) return 'Choose a Word file and a contract type to start'
  if (!file) return 'Choose a Word file to start'
  if (!context.contractType) return 'Choose a contract type to start'
  return null
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
