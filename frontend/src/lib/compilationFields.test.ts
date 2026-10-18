import { describe, expect, it } from 'vitest'
import { hasHumanCorrection, interpretationRows as labeledRows } from './compilationFields'
import { humanizeCode } from './semantics'
import { candidate } from '../test/knowledgeFixtures'
import type { Candidate } from '../api/knowledge'

const make = (over: Partial<Candidate> = {}) => candidate({ id: 'c', artifactId: 'a', reviewStatus: 'PENDING', ...over })
/** The fallback resolver, so these tests read the same words a user sees for a key the API cannot label. */
const interpretationRows = (entry: Candidate) => labeledRows(entry, humanizeCode)
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
    expect(row(rows, 'wording')?.value).toBe('No wording was read')
    expect(row(rows, 'scope')?.value).toBe('Not declared')
  })

  it('shows a clause with its role and approved wording', () => {
    const rows = interpretationRows(make({ candidateType: 'CLAUSE_VARIANT', variantRole: 'FALLBACK', positions: [], contentText: 'Liability is capped.' }))
    expect(rows.map((entry) => entry.label)).toEqual(['Legal concept', 'Role', 'Approved wording', 'Applies to'])
    expect(row(rows, 'variantRole')?.value).toBe('Fallback')
  })

  it('keeps the machine wording beside a correction', () => {
    const rows = interpretationRows(make({ contentText: 'Net 90', editedContentText: 'Net 45' }))
    expect(row(rows, 'wording')).toMatchObject({ value: 'Net 45', machine: 'Rivell read: Net 90', corrected: true })
  })

  it('shows spreadsheet columns, with the machine value only where a column changed', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        contentText: 'Mutual | Capped at 12 months',
        payloadColumns: { Variant: 'Mutual', Wording: 'Capped at 12 months' },
        editedPayloadColumns: { Variant: 'Mutual', Wording: 'Capped at 6 months' },
      }),
    )
    expect(row(rows, 'column:Variant')).toMatchObject({ value: 'Mutual', machine: null, corrected: false })
    expect(row(rows, 'column:Wording')).toMatchObject({ value: 'Capped at 6 months', machine: 'Rivell read: Capped at 12 months', corrected: true })
    // The text Rivell uses is one value resolved from wording then corrected columns, so a
    // column-only correction changes it: the corrected wording is shown with the machine row.
    expect(row(rows, 'wording')).toMatchObject({
      value: 'Mutual | Capped at 6 months',
      machine: 'Rivell read: Mutual | Capped at 12 months',
      corrected: true,
    })
  })

  it('shows the reviewer concept correction and what Rivell classified', () => {
    const rows = interpretationRows(make({ legalConceptKey: 'CONFIDENTIALITY_OBLIGATIONS', conceptCorrection: 'INDEMNIFICATION', effectiveConceptKey: 'INDEMNIFICATION' }))
    expect(row(rows, 'concept')).toMatchObject({ value: 'Indemnification', machine: 'Rivell classified: Confidentiality obligations', corrected: true })
  })

  it('shows a cleared concept as not classified, with the machine concept kept', () => {
    const rows = interpretationRows(make({ legalConceptKey: 'CONFIDENTIALITY_OBLIGATIONS', conceptCorrection: '', effectiveConceptKey: null }))
    expect(row(rows, 'concept')).toMatchObject({ value: 'Not classified', machine: 'Rivell classified: Confidentiality obligations', corrected: true })
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
