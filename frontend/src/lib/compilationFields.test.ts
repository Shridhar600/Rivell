import { describe, expect, it } from 'vitest'
import { hasHumanCorrection, interpretationRows } from './compilationFields'
import { candidate } from '../test/knowledgeFixtures'
import type { Candidate } from '../api/knowledge'

const make = (over: Partial<Candidate> = {}) => candidate({ id: 'c', artifactId: 'a', reviewStatus: 'PENDING', ...over })
const row = (rows: ReturnType<typeof interpretationRows>, key: string) => rows.find((entry) => entry.key === key)

describe('structured interpretation', () => {
  it('shows only what the backend holds for a playbook position', () => {
    const rows = interpretationRows(
      make({
        positions: ['PREFERRED', 'FALLBACK'],
        escalationTrigger: 'Below six months',
        requiresApproval: true,
        effectiveConceptKey: 'LIMITATION_OF_LIABILITY',
        legalConceptKey: 'LIMITATION_OF_LIABILITY',
        declaredContractTypes: ['MSA'],
      }),
    )
    expect(rows.map((entry) => entry.label)).toEqual(['Legal concept', 'Position', 'Escalate', 'Counsel approval', 'Wording', 'Applies to'])
    expect(row(rows, 'positions')?.value).toBe('Preferred, Fallback')
    expect(row(rows, 'escalation')?.value).toBe('Below six months')
    expect(row(rows, 'approval')?.value).toBe('Required')
    expect(row(rows, 'concept')?.value).toBe('Limitation of liability')
  })

  it('never invents a value that was not extracted', () => {
    const rows = interpretationRows(make({ positions: [], escalationTrigger: null, requiresApproval: null, effectiveConceptKey: null, legalConceptKey: null, contentText: null }))
    expect(row(rows, 'positions')?.value).toBe('Not specified')
    expect(row(rows, 'escalation')?.value).toBe('Not stated')
    expect(row(rows, 'approval')).toBeUndefined()
    expect(row(rows, 'concept')?.value).toBe('Not classified')
    expect(row(rows, 'wording')?.value).toBe('No wording was extracted')
    expect(row(rows, 'scope')?.value).toBe('Not declared')
  })

  it('shows a clause with its role and approved wording', () => {
    const rows = interpretationRows(make({ candidateType: 'CLAUSE_VARIANT', variantRole: 'FALLBACK', positions: [], contentText: 'Liability is capped.' }))
    expect(rows.map((entry) => entry.label)).toEqual(['Legal concept', 'Role', 'Approved wording', 'Applies to'])
    expect(row(rows, 'variantRole')?.value).toBe('Fallback')
  })

  it('keeps the machine wording beside a correction', () => {
    const rows = interpretationRows(make({ contentText: 'Net 90', editedContentText: 'Net 45' }))
    expect(row(rows, 'wording')).toMatchObject({ value: 'Net 45', machine: 'Rivell extracted: Net 90', corrected: true })
  })

  it('shows spreadsheet columns, with the machine value only where a column changed', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        payloadColumns: { Variant: 'Mutual', Wording: 'Capped at 12 months' },
        editedPayloadColumns: { Variant: 'Mutual', Wording: 'Capped at 6 months' },
      }),
    )
    expect(row(rows, 'column:Variant')).toMatchObject({ value: 'Mutual', machine: null, corrected: false })
    expect(row(rows, 'column:Wording')).toMatchObject({ value: 'Capped at 6 months', machine: 'Rivell extracted: Capped at 12 months', corrected: true })
    // The text Rivell uses is its own row. Editing a column does not change it.
    expect(row(rows, 'wording')).toMatchObject({ corrected: false })
  })

  it('shows the reviewer concept correction and what Rivell classified', () => {
    const rows = interpretationRows(make({ legalConceptKey: 'CONFIDENTIALITY', conceptCorrection: 'INDEMNIFICATION', effectiveConceptKey: 'INDEMNIFICATION' }))
    expect(row(rows, 'concept')).toMatchObject({ value: 'Indemnification', machine: 'Rivell classified: Confidentiality', corrected: true })
  })

  it('shows a cleared concept as not classified, with the machine concept kept', () => {
    const rows = interpretationRows(make({ legalConceptKey: 'CONFIDENTIALITY', conceptCorrection: '', effectiveConceptKey: null }))
    expect(row(rows, 'concept')).toMatchObject({ value: 'Not classified', machine: 'Rivell classified: Confidentiality', corrected: true })
  })

  it('flags a field the source supports only weakly', () => {
    const rows = interpretationRows(
      make({
        interpretations: [
          { field: 'escalationTrigger', proposedValue: 'x', kind: 'GENERATIVE_EXTRACTION', specId: 's', specVersion: 1, supportingSourceNodes: 1, verification: 'UNCERTAIN' },
        ],
        escalationTrigger: 'x',
      }),
    )
    expect(row(rows, 'escalation')?.support).toBe('Uncertain, check the source')
  })

  it('lists only the scope that was declared', () => {
    const rows = interpretationRows(make({ declaredContractTypes: ['MSA'], jurisdiction: 'ENGLAND_WALES' }))
    expect(rows.filter((entry) => entry.key.startsWith('scope')).map((entry) => entry.label)).toEqual(['Applies to', 'Jurisdiction'])
  })
})

describe('human correction', () => {
  it('is false for an untouched machine candidate', () => {
    expect(hasHumanCorrection(make())).toBe(false)
  })

  it('is true when any part of the interpretation was corrected', () => {
    expect(hasHumanCorrection(make({ editedContentText: 'changed' }))).toBe(true)
    expect(hasHumanCorrection(make({ conceptCorrection: 'GOVERNING_LAW' }))).toBe(true)
    expect(hasHumanCorrection(make({ editedPayloadColumns: { A: 'b' } }))).toBe(true)
  })
})
