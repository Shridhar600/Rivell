import { describe, expect, it } from 'vitest'
import { conceptName, contractTypesLabel, positionRoles, positionRow, scopeLine, scopeRows } from './knowledgePosition'
import { humanizeCode } from './domainLabels'
import { candidate } from '../test/knowledgeFixtures'

const base = { id: 'c', artifactId: 'a' }

describe('position row layout', () => {
  it('shows a passage that is both preferred and fallback once, across both columns', () => {
    const row = positionRow(candidate({ ...base, positions: ['PREFERRED', 'FALLBACK'], contentText: 'One wording' }))
    expect(row).toMatchObject({ layout: 'both', text: 'One wording' })
  })

  it('puts the wording under the single role the compilation recorded', () => {
    expect(positionRow(candidate({ ...base, positions: ['PREFERRED'] })).layout).toBe('preferred')
    expect(positionRow(candidate({ ...base, positions: ['FALLBACK'] })).layout).toBe('fallback')
  })

  it('does not call a passage preferred or fallback when it holds neither role', () => {
    expect(positionRow(candidate({ ...base, positions: ['PROHIBITED', 'COMMENTARY'] })).layout).toBe('passage')
    expect(positionRow(candidate({ ...base, positions: [] })).layout).toBe('passage')
  })

  it('uses the authored escalation trigger and nothing derived from the wording', () => {
    expect(positionRow(candidate({ ...base, escalationTrigger: ' Above 2x ' })).escalation).toBe('Above 2x')
    expect(positionRow(candidate({ ...base, contentText: 'Escalate to legal if above 2x' })).escalation).toBeNull()
  })

  it('shows reviewer-edited wording when there is one', () => {
    expect(positionRow(candidate({ ...base, contentText: 'Original', editedContentText: 'Edited' })).text).toBe('Edited')
  })

  it('has no text for a blank passage', () => {
    expect(positionRow(candidate({ ...base, contentText: '   ' })).text).toBeNull()
  })

  it('titles the row with the title a reviewer sees, not the machine label', () => {
    const row = positionRow(candidate({ ...base, rawLabel: 'Limitation of Liability | one-sided', title: 'Limitation of Liability' }))
    expect(row.title).toBe('Limitation of Liability')
  })
})

describe('roles and scope', () => {
  it('lists recorded roles in reading order and drops the unspecified marker', () => {
    expect(positionRoles(candidate({ ...base, positions: ['COMMENTARY', 'UNSPECIFIED', 'FALLBACK', 'PREFERRED'] }))).toEqual([
      'PREFERRED',
      'FALLBACK',
      'COMMENTARY',
    ])
  })

  it('lists only the applicability that was declared', () => {
    expect(scopeRows(candidate(base))).toEqual([])
    const rows = scopeRows(
      candidate({ ...base, declaredContractTypes: ['NDA', 'MSA'], paperSource: 'OUR_PAPER', jurisdiction: 'UK', contractPackKey: 'COMMERCIAL_MSA' }),
    )
    expect(rows).toEqual([
      { label: 'Applies to', value: 'NDA, MSA, Commercial msa pack' },
      { label: 'Whose template', value: 'Our template' },
      { label: 'Jurisdiction', value: 'United Kingdom' },
    ])
  })

  it('states an empty contract-type scope as all contract types', () => {
    expect(contractTypesLabel([])).toBe('All contract types')
    expect(contractTypesLabel(['MSA', 'NDA'])).toBe('MSA, NDA')
  })

  it('does not let a pack hide that an empty contract-type scope is all types', () => {
    const rows = scopeRows(candidate({ ...base, contractPackKey: 'COMMERCIAL_MSA' }))
    expect(rows).toEqual([
      { label: 'Applies to', value: 'All contract types, Commercial msa pack' },
    ])
  })

  it('shows the contract types the record itself states ahead of the file manifest', () => {
    const rows = scopeRows(candidate({ ...base, declaredContractTypes: ['MSA'], sourceContractTypes: ['NDA'] }))
    expect(rows[0]).toEqual({ label: 'Applies to', value: 'NDA' })
  })

  it('formats an effective period without shifting the calendar day', () => {
    const rows = scopeRows(candidate({ ...base, effectiveFrom: '2027-01-01', effectiveTo: '2027-12-31' }))
    expect(rows[0]?.value).toMatch(/1 Jan 2027|Jan 1, 2027/)
    expect(rows[0]?.value).toMatch(/31 Dec 2027|Dec 31, 2027/)
    expect(scopeRows(candidate({ ...base, effectiveFrom: '2027-01-01' }))[0]?.value).toMatch(/^From /)
  })

  it('summarises declared contract types across passages once each', () => {
    const line = scopeLine([
      candidate({ ...base, id: '1', declaredContractTypes: ['MSA'] }),
      candidate({ ...base, id: '2', declaredContractTypes: ['MSA', 'NDA'] }),
    ])
    expect(line).toBe('MSA, NDA')
    expect(scopeLine([candidate(base)])).toBe('')
  })

  it('names the legal concept only when one is set', () => {
    expect(conceptName(candidate({ ...base, effectiveConceptKey: 'LIMITATION_OF_LIABILITY' }), humanizeCode)).toBe('Limitation of liability')
    expect(conceptName(candidate(base), humanizeCode)).toBeNull()
  })
})
