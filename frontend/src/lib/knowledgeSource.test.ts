import { describe, expect, it } from 'vitest'
import { highlightMap, sourceBlocks, sourceLocation } from './knowledgeSource'
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
