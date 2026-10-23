import { describe, expect, it } from 'vitest'
import { hasHumanCorrection, interpretationRows as labeledRows } from './compilationFields'
import { humanizeCode } from './semantics'
import { artifact, candidate } from '../test/knowledgeFixtures'
import type { ArtifactDetail, Candidate } from '../api/knowledge'

const make = (over: Partial<Candidate> = {}) => candidate({ id: 'c', artifactId: 'a', reviewStatus: 'PENDING', ...over })
/** The fallback resolver, so these tests read the same words a user sees for a key the API cannot label. */
const interpretationRows = (entry: Candidate, file?: ArtifactDetail) => labeledRows(entry, humanizeCode, file)
const file = (declaredContractTypes: string[]): ArtifactDetail => ({
  ...artifact({ id: 'a' }),
  storageKey: 'org/knowledge/a.docx',
  declaredContractTypes,
  contractPackKey: null,
  partyPerspective: null,
  paperSource: null,
  jurisdiction: null,
  language: null,
  effectiveFrom: null,
  effectiveTo: null,
  manifestNote: null,
  parserVersion: null,
  parseWarnings: [],
  artifactAssessment: null,
})
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
    expect(rows.map((entry) => entry.label)).toEqual(['Topic', 'Covers', 'Escalate', 'Counsel approval', 'Wording', 'Applies to'])
    expect(row(rows, 'positions')?.label).toBe('Covers')
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
    expect(row(rows, 'scope')?.value).toBe('All contract types')
  })

  it('shows a clause with its role and approved wording', () => {
    const rows = interpretationRows(make({ candidateType: 'CLAUSE_VARIANT', variantRole: 'FALLBACK', positions: [], contentText: 'Liability is capped.' }))
    expect(rows.map((entry) => entry.label)).toEqual(['Topic', 'Role', 'Approved wording', 'Applies to'])
    expect(row(rows, 'variantRole')?.value).toBe('Fallback')
  })

  it('keeps the machine wording beside a correction', () => {
    const rows = interpretationRows(make({ contentText: 'Net 90', editedContentText: 'Net 45' }))
    expect(row(rows, 'wording')).toMatchObject({ value: 'Net 45', machine: 'Rivell read: Net 90', corrected: true })
  })

  it('shows spreadsheet columns, hiding the wording column that repeats the shown wording', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        contentText: 'Mutual | Capped at 12 months',
        payloadColumns: { Variant: 'Mutual', Wording: 'Capped at 12 months' },
        editedPayloadColumns: { Variant: 'Mutual', Wording: 'Capped at 6 months' },
      }),
    )
    expect(row(rows, 'column:Variant')).toMatchObject({ value: 'Mutual', machine: null, corrected: false })
    // The payload wording column would repeat the approved wording, so it is not a second row.
    expect(row(rows, 'column:Wording')).toBeUndefined()
    // A clause variant's wording is its wording column, so changing another column does not
    // change it; the machine wording stays beside the corrected one.
    expect(row(rows, 'wording')).toMatchObject({
      value: 'Capped at 6 months',
      machine: 'Rivell read: Mutual | Capped at 12 months',
      corrected: true,
    })
  })

  it('keeps the machine value of a changed column that is not the wording', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        contentText: 'Capped at 12 months',
        payloadColumns: { Variant: 'Mutual', 'Clause text': 'Capped at 12 months' },
        editedPayloadColumns: { Variant: 'Mutual, standard', 'Clause text': 'Capped at 12 months' },
      }),
    )
    expect(row(rows, 'column:Variant')).toMatchObject({
      value: 'Mutual, standard',
      machine: 'Rivell read: Mutual',
      corrected: true,
    })
    expect(row(rows, 'column:Clause text')).toBeUndefined()
  })

  it('keeps a wording column whose value is not the shown wording', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        contentText: 'Capped at 12 months',
        editedContentText: 'A different agreed wording.',
        payloadColumns: { Variant: 'Mutual', 'Clause text': 'Capped at 12 months' },
      }),
    )
    expect(row(rows, 'wording')?.value).toBe('A different agreed wording.')
    expect(row(rows, 'column:Clause text')).toMatchObject({ value: 'Capped at 12 months' })
  })

  it('does not hide another column whose value repeats the shown wording', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        contentText: 'G-GOV-01',
        payloadColumns: { 'Clause ID': 'G-GOV-01' },
      }),
    )
    expect(row(rows, 'column:Clause ID')).toMatchObject({ value: 'G-GOV-01' })
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

  it('never shows a placeholder column name to a reviewer', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        payloadColumns: { 'Column 1': 'Mutual', 'Field 2': 'Capped at 12 months' },
      }),
    )
    expect(row(rows, 'column:Column 1')).toMatchObject({ label: 'Value', value: 'Mutual' })
    expect(row(rows, 'column:Field 2')).toMatchObject({ label: 'Value', value: 'Capped at 12 months' })
    expect(rows.map((entry) => entry.label)).not.toContain('Column 1')
    expect(rows.map((entry) => entry.label)).not.toContain('Field 2')
  })

  it('puts the payload topic beside the classified topic without repeating its label', () => {
    const rows = interpretationRows(
      make({
        candidateType: 'CLAUSE_VARIANT',
        effectiveConceptKey: 'LIMITATION_OF_LIABILITY',
        legalConceptKey: 'LIMITATION_OF_LIABILITY',
        payloadColumns: { Topic: 'IP indemnity' },
      }),
    )
    expect(rows.filter((entry) => entry.label === 'Topic')).toHaveLength(1)
    expect(row(rows, 'column:Topic')).toMatchObject({ label: 'Topic in the file', value: 'IP indemnity' })
  })

  it('lists only the scope that was declared', () => {
    const rows = interpretationRows(make({ declaredContractTypes: ['MSA'], jurisdiction: 'ENGLAND_WALES' }))
    expect(rows.filter((entry) => entry.key.startsWith('scope')).map((entry) => entry.label)).toEqual(['Applies to', 'Jurisdiction'])
  })

  it('calls the file manifest scope the file scope, and keeps a record scope row its own label', () => {
    const fromFile = interpretationRows(make({ declaredContractTypes: ['MSA'] }), file(['MSA']))
    expect(row(fromFile, 'scope:Applies to')).toMatchObject({ label: 'File scope', value: 'MSA' })

    const own = interpretationRows(
      make({ declaredContractTypes: ['MSA'], sourceContractTypes: ['NDA'] }),
      file(['MSA']),
    )
    expect(row(own, 'scope:Applies to')).toMatchObject({ label: 'Applies to', value: 'NDA' })
    expect(row(own, 'scope:File scope')).toMatchObject({ label: 'File scope', value: 'MSA' })
  })

  it('says a file with no declared types applies to all contract types', () => {
    const rows = interpretationRows(make(), file([]))
    expect(row(rows, 'scope:File scope')).toMatchObject({
      label: 'File scope',
      value: 'All contract types',
    })
  })

  it('shows the file scope when an all-types file is narrowed by the entry', () => {
    const narrowed = interpretationRows(make({ sourceContractTypes: ['NDA'] }), file([]))
    expect(row(narrowed, 'scope:Applies to')).toMatchObject({ label: 'Applies to', value: 'NDA' })
    expect(row(narrowed, 'scope:File scope')).toMatchObject({
      label: 'File scope',
      value: 'All contract types',
    })
  })

  it('shows the file scope when only another dimension narrows the entry', () => {
    const rows = interpretationRows(make({ jurisdiction: 'UK' }), file([]))
    expect(row(rows, 'scope:File scope')).toMatchObject({
      label: 'File scope',
      value: 'All contract types',
    })
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
    expect(hasHumanCorrection(make({ editedTitle: 'A shorter title' }))).toBe(true)
  })
})
