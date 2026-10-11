import type { Candidate, PositionRole } from '../api/knowledge'
import type { ConceptLabel } from '../api/taxonomy'
import { longDate } from './dates'
import {
  CONTRACT_TYPE_LABELS,
  JURISDICTION_LABELS,
  PAPER_SOURCE_LABELS,
  humanizeCode,
  roleLabel,
} from './contractSemantics'
import { positionText } from './knowledgeCatalog'

/** Roles in the order a reviewer reads them. A candidate can hold several at once. */
const ROLE_ORDER: PositionRole[] = ['PREFERRED', 'FALLBACK', 'PROHIBITED', 'ESCALATION', 'COMMENTARY', 'UNSPECIFIED']

export const POSITION_ROLE_LABELS: Record<PositionRole, string> = {
  PREFERRED: 'Preferred',
  FALLBACK: 'Fallback',
  PROHIBITED: 'Prohibited',
  ESCALATION: 'Escalation',
  COMMENTARY: 'Commentary',
  UNSPECIFIED: 'Unspecified',
}

/** The roles the compilation recorded for a passage, in reading order. Never inferred from its wording. */
export function positionRoles(candidate: Candidate): PositionRole[] {
  const held = new Set(candidate.positions)
  return ROLE_ORDER.filter((role) => held.has(role) && role !== 'UNSPECIFIED')
}

export type CellLayout = 'both' | 'preferred' | 'fallback' | 'passage'

export interface PositionRow {
  /** The heading the source itself gives this passage. */
  title: string
  layout: CellLayout
  text: string | null
  escalation: string | null
}

/**
 * How a published passage fills the Preferred / Fallback / Escalate columns.
 *
 * A playbook passage is one authored text with flags for the roles it plays. It is not three
 * separate values. When it holds both Preferred and Fallback it is shown once across both
 * columns, rather than repeated and read as two positions. The escalation trigger is its own
 * authored field and is the only value that has a column of its own.
 */
export function positionRow(candidate: Candidate): PositionRow {
  const roles = new Set(candidate.positions)
  const preferred = roles.has('PREFERRED')
  const fallback = roles.has('FALLBACK')
  const layout: CellLayout = preferred && fallback ? 'both' : preferred ? 'preferred' : fallback ? 'fallback' : 'passage'
  return {
    title: candidate.rawLabel,
    layout,
    text: positionText(candidate),
    escalation: candidate.escalationTrigger?.trim() || null,
  }
}

export interface ScopeRow {
  label: string
  value: string
}

/** A calendar date has no time zone. Parsed as local so it never shifts a day. */
function dateOnly(value: string): string {
  return longDate(/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00` : value)
}

function labelled(map: Record<string, string>, value: string): string {
  return map[value] ?? humanizeCode(value)
}

/**
 * The applicability the source declared for this passage. Only what was actually declared is
 * listed: an unset field is absent, never "Any".
 */
export function scopeRows(candidate: Candidate): ScopeRow[] {
  const rows: ScopeRow[] = []
  const types = candidate.declaredContractTypes.map((type) => labelled(CONTRACT_TYPE_LABELS, type))
  if (candidate.contractPackKey) types.push(`${humanizeCode(candidate.contractPackKey)} pack`)
  if (types.length > 0) rows.push({ label: 'Applies to', value: types.join(', ') })
  if (candidate.paperSource) rows.push({ label: 'Paper', value: labelled(PAPER_SOURCE_LABELS, candidate.paperSource) })
  if (candidate.partyPerspective) rows.push({ label: 'Perspective', value: roleLabel(candidate.partyPerspective) })
  if (candidate.jurisdiction) rows.push({ label: 'Jurisdiction', value: labelled(JURISDICTION_LABELS, candidate.jurisdiction) })
  const from = candidate.effectiveFrom ? dateOnly(candidate.effectiveFrom) : ''
  const to = candidate.effectiveTo ? dateOnly(candidate.effectiveTo) : ''
  if (from && to) rows.push({ label: 'Effective', value: `${from} to ${to}` })
  else if (from) rows.push({ label: 'Effective', value: `From ${from}` })
  else if (to) rows.push({ label: 'Effective', value: `Until ${to}` })
  return rows
}

export function conceptName(candidate: Candidate, label: ConceptLabel): string | null {
  return candidate.effectiveConceptKey ? label(candidate.effectiveConceptKey) : null
}

/** The contract types a set of passages was declared for, e.g. "NDA, MSA". Empty when none was declared. */
export function scopeLine(candidates: Candidate[]): string {
  const types = new Set<string>()
  for (const candidate of candidates) {
    for (const type of candidate.declaredContractTypes) types.add(labelled(CONTRACT_TYPE_LABELS, type))
  }
  return [...types].join(', ')
}
