import type { PositionComparison } from '../api/reviews'
import { authorityLabel } from './evidence'
import { describeFact, factSchemaLabel, type FactRow } from './contractSemantics'
import type { AuthorityClass } from '../api/retrieval'

/** One cited item inside a slot. `evidenceId` opens the frozen source behind it. */
export interface PositionEntry {
  evidenceId: string
  /** Where the item comes from, as the server labelled it. */
  authority: string
  /** The publication a playbook item was frozen from. Null for the contract. */
  publication: number | null
  /** Authored policy wording, verbatim. Null for the contract slot. */
  text: string | null
  /** Typed contract facts, grouped by fact schema. Empty for policy slots. */
  facts: { title: string; rows: FactRow[] }[]
}

export interface PositionColumn {
  key: 'contract' | 'preferred' | 'fallback'
  label: string
  entries: PositionEntry[]
}

export interface PolicyPositionView {
  columns: PositionColumn[]
  /** Escalation conditions as authored, each with its source. */
  escalations: { evidenceId: string; publication: number | null; trigger: string }[]
}

function policyEntries(slots: PositionComparison['preferred']): PositionEntry[] {
  return slots
    .filter((slot) => slot.text.trim() !== '')
    .map((slot) => ({
      evidenceId: slot.evidenceItemId,
      authority: authorityLabel(slot.authorityClass as AuthorityClass),
      publication: slot.publicationNumber,
      text: slot.text,
      facts: [],
    }))
}

/**
 * The "Policy position" section, read from the structured slots the server grouped. A slot with
 * nothing in it gets no column, and a comparison holding no policy position at all gives no section:
 * there is no empty Preferred or Fallback box, and no role is guessed from prose. The contract
 * column only ever travels with a policy position, since it is the thing being compared to.
 */
export function policyPositionView(comparison: PositionComparison | null): PolicyPositionView | null {
  if (!comparison) return null
  const preferred = policyEntries(comparison.preferred)
  const fallback = policyEntries(comparison.fallback)
  const escalations = comparison.escalation
    .filter((slot) => slot.trigger.trim() !== '')
    .map((slot) => ({ evidenceId: slot.evidenceItemId, publication: slot.publicationNumber, trigger: slot.trigger }))
  if (preferred.length === 0 && fallback.length === 0 && escalations.length === 0) return null

  const contract: PositionEntry[] = comparison.contract.flatMap((slot) => {
    const facts = slot.facts
      .map((fact) => ({ title: factSchemaLabel(fact.factSchema), rows: describeFact({ ...fact, sourceNodeIds: [] }) }))
      .filter((fact) => fact.rows.length > 0)
    return facts.length === 0
      ? []
      : [
          {
            evidenceId: slot.evidenceItemId,
            authority: authorityLabel(slot.authorityClass as AuthorityClass),
            publication: null,
            text: null,
            facts,
          },
        ]
  })

  const columns: PositionColumn[] = [
    { key: 'contract', label: 'Contract', entries: contract },
    { key: 'preferred', label: 'Preferred', entries: preferred },
    { key: 'fallback', label: 'Fallback', entries: fallback },
  ]
  return { columns: columns.filter((column) => column.entries.length > 0), escalations }
}
