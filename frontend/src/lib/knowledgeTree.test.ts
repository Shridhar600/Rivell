import { describe, expect, it } from 'vitest'
import type { KnowledgeNode } from '../api/knowledge'
import {
  buildSheetViews,
  isSpreadsheetStructure,
  mergedRegionLabel,
  toStructureNodes,
} from './knowledgeTree'

function node(
  partial: Partial<KnowledgeNode> & { id: string; nodeType: string },
): KnowledgeNode {
  return {
    artifactId: 'a1',
    parentId: null,
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
    ...partial,
  }
}

describe('toStructureNodes', () => {
  it('maps knowledge nodes onto the structural renderer model', () => {
    const [mapped] = toStructureNodes([
      node({
        id: 'p1',
        nodeType: 'PARAGRAPH',
        text: 'The parties agree.',
        bodyIndex: 2,
        paragraphIndex: 3,
      }),
    ])

    expect(mapped.nodeType).toBe('PARAGRAPH')
    expect(mapped.sourceSpan.bodyIndex).toBe(2)
    expect(mapped.sourceSpan.paragraphIndex).toBe(3)
  })

  it('maps spreadsheet column index onto the cell index', () => {
    const [mapped] = toStructureNodes([
      node({
        id: 'c1',
        nodeType: 'SPREADSHEET_CELL',
        rowIndex: 4,
        columnIndex: 2,
        sheetName: 'Positions',
      }),
    ])

    expect(mapped.sourceSpan.cellIndex).toBe(2)
    expect(mapped.sourceSpan.rowIndex).toBe(4)
  })
})

describe('spreadsheet structure', () => {
  const nodes: KnowledgeNode[] = [
    node({ id: 'wb', nodeType: 'WORKBOOK' }),
    node({ id: 's2', nodeType: 'SHEET', parentId: 'wb', ordinal: 1, sheetName: 'Notes' }),
    node({ id: 's1', nodeType: 'SHEET', parentId: 'wb', ordinal: 0, sheetName: 'Positions' }),
    node({ id: 'r1', nodeType: 'SPREADSHEET_ROW', parentId: 's1', rowIndex: 1 }),
    node({ id: 'r0', nodeType: 'SPREADSHEET_ROW', parentId: 's1', rowIndex: 0 }),
    node({
      id: 'r0c1',
      nodeType: 'SPREADSHEET_CELL',
      parentId: 'r0',
      rowIndex: 0,
      columnIndex: 1,
      text: 'Preferred',
      sheetName: 'Positions',
    }),
    node({
      id: 'r0c0',
      nodeType: 'SPREADSHEET_CELL',
      parentId: 'r0',
      rowIndex: 0,
      columnIndex: 0,
      text: 'Clause',
      sheetName: 'Positions',
    }),
    node({
      id: 'r1c0',
      nodeType: 'SPREADSHEET_CELL',
      parentId: 'r1',
      rowIndex: 1,
      columnIndex: 0,
      text: 'Liability cap',
      sheetName: 'Positions',
      mergedRegion: { firstRow: 1, lastRow: 1, firstColumn: 0, lastColumn: 2 },
    }),
  ]

  it('detects workbook structure', () => {
    expect(isSpreadsheetStructure(nodes)).toBe(true)
    expect(
      isSpreadsheetStructure([node({ id: 'p1', nodeType: 'PARAGRAPH' })]),
    ).toBe(false)
  })

  it('groups sheets in workbook order with position-sorted rows and cells', () => {
    const sheets = buildSheetViews(nodes)

    expect(sheets.map((view) => view.sheet.sheetName)).toEqual([
      'Positions',
      'Notes',
    ])
    expect(sheets[0].rows.map((row) => row.row.rowIndex)).toEqual([0, 1])
    expect(sheets[0].rows[0].cells.map((cell) => cell.text)).toEqual([
      'Clause',
      'Preferred',
    ])
    expect(sheets[0].rows[1].cells[0].mergedRegion).not.toBeNull()
    expect(sheets[1].rows).toEqual([])
  })

  it('labels merged regions in source terms', () => {
    expect(
      mergedRegionLabel({ firstRow: 0, lastRow: 0, firstColumn: 0, lastColumn: 2 }),
    ).toBe('row 0, columns 0–2')
    expect(
      mergedRegionLabel({ firstRow: 1, lastRow: 3, firstColumn: 2, lastColumn: 2 }),
    ).toBe('rows 1–3, column 2')
  })
})
