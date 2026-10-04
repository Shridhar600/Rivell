import { describe, expect, it } from 'vitest'
import type { PositionComparison } from '../api/reviews'
import { factSchemaLabel } from './contractSemantics'
import { policyPositionView } from './positionComparison'

const facts = [
  { factSchema: 'liability-facts', schemaVersion: 1, values: { liabilityCapMultiple: '3', capBasis: 'PRECEDING_12_MONTH_FEES' } },
]
const contract = { evidenceItemId: 'c-1', authorityClass: 'CURRENT_CONTRACT', legalConcept: 'LIMITATION_OF_LIABILITY', facts }
const policy = (id: string, text = 'Cap at fees paid.') => ({
  evidenceItemId: id,
  authorityClass: 'POLICY',
  legalConcept: 'LIMITATION_OF_LIABILITY',
  publicationNumber: 2,
  text,
})
const escalation = (id: string, trigger = 'Uncapped liability') => ({
  evidenceItemId: id,
  authorityClass: 'POLICY',
  publicationNumber: 2,
  trigger,
})
const comparison = (overrides: Partial<PositionComparison> = {}): PositionComparison => ({
  contract: [],
  preferred: [],
  fallback: [],
  escalation: [],
  ...overrides,
})

describe('policyPositionView', () => {
  it('has no section without a comparison', () => {
    expect(policyPositionView(null)).toBeNull()
  })

  it('has no section when every slot is empty', () => {
    expect(policyPositionView(comparison())).toBeNull()
  })

  it('has no section for a contract slot alone: typed facts are not a policy position', () => {
    expect(policyPositionView(comparison({ contract: [contract] }))).toBeNull()
  })

  it('has no section when policy slots carry no wording and no trigger', () => {
    expect(policyPositionView(comparison({ preferred: [policy('p-1', '  ')], escalation: [escalation('p-1', ' ')] }))).toBeNull()
  })

  it('draws Contract, Preferred and Fallback in that order, from the structured slots', () => {
    const view = policyPositionView(
      comparison({ contract: [contract], preferred: [policy('p-1', 'Preferred wording.')], fallback: [policy('p-2', 'Fallback wording.')], escalation: [escalation('p-1')] }),
    )
    expect(view?.columns.map((column) => column.key)).toEqual(['contract', 'preferred', 'fallback'])
    expect(view?.columns[1].entries[0]).toMatchObject({ evidenceId: 'p-1', text: 'Preferred wording.', publication: 2 })
    expect(view?.columns[2].entries[0]).toMatchObject({ evidenceId: 'p-2', text: 'Fallback wording.' })
    expect(view?.escalations).toEqual([{ evidenceId: 'p-1', publication: 2, trigger: 'Uncapped liability' }])
  })

  it('shows the contract as labelled typed facts, not a sentence', () => {
    const view = policyPositionView(comparison({ contract: [contract], preferred: [policy('p-1')] }))
    const entry = view?.columns[0].entries[0]
    expect(entry?.text).toBeNull()
    expect(entry?.facts).toEqual([
      {
        title: 'Liability facts',
        rows: [
          { field: 'liabilityCapMultiple', label: 'Liability cap', value: '3×' },
          { field: 'capBasis', label: 'Cap basis', value: 'Preceding 12-month fees' },
        ],
      },
    ])
  })

  it('draws no column for a slot the server left empty, however the wording reads', () => {
    // The preferred wording mentions a fallback. Prose is never read for roles, so there is no Fallback.
    const view = policyPositionView(comparison({ preferred: [policy('p-1', 'Preferred: 12 months fees. Fallback: 24 months fees.')] }))
    expect(view?.columns.map((column) => column.key)).toEqual(['preferred'])
    expect(view?.escalations).toEqual([])
  })

  it('keeps every cited item in a slot, in the server’s order', () => {
    const view = policyPositionView(
      comparison({
        contract: [contract, { ...contract, evidenceItemId: 'c-2' }],
        preferred: [policy('p-1'), policy('p-2')],
        escalation: [escalation('p-1'), escalation('p-2', 'Consequential damages')],
      }),
    )
    expect(view?.columns.find((column) => column.key === 'contract')?.entries.map((entry) => entry.evidenceId)).toEqual(['c-1', 'c-2'])
    expect(view?.columns.find((column) => column.key === 'preferred')?.entries.map((entry) => entry.evidenceId)).toEqual(['p-1', 'p-2'])
    expect(view?.escalations.map((item) => item.trigger)).toEqual(['Uncapped liability', 'Consequential damages'])
  })

  it('shows an escalation condition on its own when it is the only position', () => {
    const view = policyPositionView(comparison({ escalation: [escalation('p-1')] }))
    expect(view?.columns).toEqual([])
    expect(view?.escalations).toHaveLength(1)
  })

  it('leaves out contract facts that have nothing to display', () => {
    const empty = { ...contract, facts: [{ factSchema: 'liability-facts', schemaVersion: 1, values: {} }] }
    const view = policyPositionView(comparison({ contract: [empty], preferred: [policy('p-1')] }))
    expect(view?.columns.map((column) => column.key)).toEqual(['preferred'])
  })

  it('labels a fact schema it does not know, from its own values', () => {
    const unknown = { ...contract, facts: [{ factSchema: 'privacy-facts', schemaVersion: 1, values: { dataTransfer: 'allowed' } }] }
    const view = policyPositionView(comparison({ contract: [unknown], preferred: [policy('p-1')] }))
    expect(view?.columns[0].entries[0].facts).toEqual([
      { title: factSchemaLabel('privacy-facts'), rows: [{ field: 'dataTransfer', label: 'Data transfer', value: 'allowed' }] },
    ])
  })
})
