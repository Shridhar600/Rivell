import { describe, expect, it } from 'vitest'
import { candidateLocations, candidatePassages, contextWindow, highlightMap, sourceBlocks, sourceLocation } from './knowledgeSource'
import { candidate, node, unit } from '../test/knowledgeFixtures'

const docNodes = [
  node({ id: 'body', nodeType: 'BODY' }),
  node({ id: 'title', nodeType: 'TITLE', parentId: 'body', ordinal: 0, bodyIndex: 0, text: 'Playbook' }),
  node({ id: 'h1', nodeType: 'HEADING', parentId: 'body', ordinal: 1, bodyIndex: 1, text: 'Liability', numberingLabel: '4.2' }),
  node({ id: 'p1', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 2, bodyIndex: 2, text: 'A cap at 12 months.' }),
  node({ id: 'p2', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 3, bodyIndex: 3, text: 'Not highlighted.' }),
  node({ id: 'empty', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 4, bodyIndex: 4, text: '  ' }),
]

describe('source lineage', () => {
  it('maps source nodes to the candidates whose unit recorded them', () => {
    const map = highlightMap(
      [candidate({ id: 'c1', artifactId: 'a', extractionUnitId: 'u1' }), candidate({ id: 'c2', artifactId: 'a', extractionUnitId: 'u2' })],
      [unit({ id: 'u1', artifactId: 'a', sourceNodeIds: ['h1', 'p1'] }), unit({ id: 'u2', artifactId: 'a', sourceNodeIds: ['p1'] })],
    )
    expect(map.get('h1')).toEqual(['c1'])
    expect(map.get('p1')).toEqual(['c1', 'c2'])
    expect(map.has('p2')).toBe(false)
  })

  it('ignores a candidate whose unit is unknown rather than guessing a location', () => {
    expect(highlightMap([candidate({ id: 'c1', artifactId: 'a', extractionUnitId: 'missing' })], []).size).toBe(0)
  })

  it('renders document nodes in order, marks only recorded nodes and skips blank ones', () => {
    const blocks = sourceBlocks(docNodes, new Map([['p1', ['c1']]]))
    expect(blocks.map((b) => [b.kind, b.text, b.candidateIds])).toEqual([
      ['title', 'Playbook', []],
      ['heading', 'Liability', []],
      ['paragraph', 'A cap at 12 months.', ['c1']],
      ['paragraph', 'Not highlighted.', []],
    ])
  })

  it('renders a workbook as sheet headings and one block per row, with cell text in order', () => {
    const nodes = [
      node({ id: 'wb', nodeType: 'WORKBOOK' }),
      node({ id: 's', nodeType: 'SHEET', parentId: 'wb', sheetName: 'Clauses' }),
      node({ id: 'r1', nodeType: 'SPREADSHEET_ROW', parentId: 's', ordinal: 0, rowIndex: 0 }),
      node({ id: 'c1', nodeType: 'SPREADSHEET_CELL', parentId: 'r1', ordinal: 0, text: 'Liability cap' }),
      node({ id: 'c2', nodeType: 'SPREADSHEET_CELL', parentId: 'r1', ordinal: 1, text: '1x fees' }),
    ]
    const blocks = sourceBlocks(nodes, new Map([['c2', ['cand']]]))
    expect(blocks).toEqual([
      { id: 's', kind: 'heading', text: 'Clauses', candidateIds: [] },
      { id: 'r1', kind: 'row', text: 'Liability cap | 1x fees', candidateIds: ['cand'] },
    ])
  })

  it('reports a location only as far as the extraction recorded it', () => {
    expect(sourceLocation([node({ id: 'x', nodeType: 'SHEET', sheetName: 'Positions' })])).toBe('Sheet "Positions"')
    expect(sourceLocation([node({ id: 'x', nodeType: 'HEADING', numberingLabel: '4.2' })])).toBe('4.2')
    expect(sourceLocation([node({ id: 'x', nodeType: 'PARAGRAPH' })])).toBeNull()
    expect(sourceLocation([])).toBeNull()
  })
})

describe('candidate passages and context', () => {
  const nodes = [
    node({ id: 'body', nodeType: 'BODY' }),
    node({ id: 'h1', nodeType: 'HEADING', parentId: 'body', ordinal: 0, bodyIndex: 0, text: 'Liability', numberingLabel: '4.2' }),
    node({ id: 'p1', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 1, bodyIndex: 1, text: 'Cap at twelve months.' }),
    node({ id: 'p2', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 2, bodyIndex: 2, text: 'Payment in 45 days.' }),
    node({ id: 'p3', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 3, bodyIndex: 3, text: 'Notices in writing.' }),
    node({ id: 'p4', nodeType: 'PARAGRAPH', parentId: 'body', ordinal: 4, bodyIndex: 4, text: 'Entire agreement.' }),
  ]
  const units = [unit({ id: 'u', artifactId: 'a1', sourceNodeIds: ['h1', 'p1'] })]
  const target = candidate({ id: 'c', artifactId: 'a1', extractionUnitId: 'u' })

  it('returns exactly the nodes the extraction unit recorded, with their numbering label', () => {
    const passages = candidatePassages(target, units, nodes)
    expect(passages.map((entry) => [entry.id, entry.text, entry.location])).toEqual([
      ['h1', 'Liability', '4.2'],
      ['p1', 'Cap at twelve months.', null],
    ])
  })

  it('returns no passage for a candidate whose unit is missing, instead of guessing one', () => {
    expect(candidatePassages(candidate({ id: 'c', artifactId: 'a1', extractionUnitId: 'gone' }), units, nodes)).toEqual([])
  })

  it('numbers spreadsheet rows from one, because the extraction counts from zero', () => {
    const sheet = [
      node({ id: 'wb', nodeType: 'WORKBOOK' }),
      node({ id: 'sh', nodeType: 'SHEET', parentId: 'wb', sheetName: 'Clauses' }),
      node({ id: 'row', nodeType: 'SPREADSHEET_ROW', parentId: 'sh', rowIndex: 13 }),
      node({ id: 'cell', nodeType: 'SPREADSHEET_CELL', parentId: 'row', text: 'Mutual' }),
    ]
    const found = candidatePassages(candidate({ id: 'c', artifactId: 'a1', extractionUnitId: 'u' }), [unit({ id: 'u', artifactId: 'a1', sourceNodeIds: ['row'] })], sheet)
    expect(found[0]?.location).toBe('Sheet "Clauses", row 14')
  })

  it('names a Word table row as a table row and leaves a spreadsheet row as a row', () => {
    const table = [
      node({ id: 'body', nodeType: 'BODY' }),
      node({ id: 'table', nodeType: 'TABLE', parentId: 'body' }),
      node({ id: 'trow', nodeType: 'TABLE_ROW', parentId: 'table', rowIndex: 0 }),
      node({ id: 'tcell', nodeType: 'TABLE_CELL', parentId: 'trow', text: 'Cap at 12 months' }),
    ]
    const word = candidatePassages(candidate({ id: 'c', artifactId: 'a1', extractionUnitId: 'u' }), [unit({ id: 'u', artifactId: 'a1', sourceNodeIds: ['trow'] })], table)
    expect(word[0]?.location).toBe('Table row 1')

    const sheet = [
      node({ id: 'wb', nodeType: 'WORKBOOK' }),
      node({ id: 'sh', nodeType: 'SHEET', parentId: 'wb', sheetName: 'Clauses' }),
      node({ id: 'row', nodeType: 'SPREADSHEET_ROW', parentId: 'sh', rowIndex: 13 }),
      node({ id: 'cell', nodeType: 'SPREADSHEET_CELL', parentId: 'row', text: 'Mutual' }),
    ]
    const excel = candidatePassages(candidate({ id: 'c', artifactId: 'a1', extractionUnitId: 'u' }), [unit({ id: 'u', artifactId: 'a1', sourceNodeIds: ['row'] })], sheet)
    expect(excel[0]?.location).toBe('Sheet "Clauses", row 14')
  })

  it('locates a Word table row by its table row in a list', () => {
    const nodes = [
      node({ id: 'body', nodeType: 'BODY' }),
      node({ id: 'table', nodeType: 'TABLE', parentId: 'body' }),
      node({ id: 'trow', nodeType: 'TABLE_ROW', parentId: 'table', rowIndex: 2 }),
      node({ id: 'tcell', nodeType: 'TABLE_CELL', parentId: 'trow', text: 'Cap at 12 months' }),
    ]
    const found = candidateLocations(
      [candidate({ id: 'c', artifactId: 'a1', extractionUnitId: 'u' })],
      [unit({ id: 'u', artifactId: 'a1', sourceNodeIds: ['trow'] })],
      nodes,
    )
    expect(found.get('c')).toBe('Table row 3')
  })

  it('windows the original around the passage and says when more exists', () => {
    const blocks = sourceBlocks(nodes, highlightMap([target], units))
    const around = contextWindow(blocks, 'c', 1)
    expect(around?.focus.map((block) => block.id)).toEqual(['h1', 'p1'])
    expect(around?.before).toEqual([])
    expect(around?.after.map((block) => block.id)).toEqual(['p2'])
    expect(around?.moreBefore).toBe(false)
    expect(around?.moreAfter).toBe(true)
  })

  it('has no window for a candidate with no marked passage', () => {
    expect(contextWindow(sourceBlocks(nodes, new Map()), 'c')).toBeNull()
  })
})
