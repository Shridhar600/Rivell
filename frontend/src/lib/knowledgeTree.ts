import type { KnowledgeNode } from '../api/knowledge'
import type { StructureNode } from './documentTree'

export function toStructureNodes(nodes: KnowledgeNode[]): StructureNode[] {
  return nodes.map((node) => ({
    id: node.id,
    parentId: node.parentId,
    nodeType: node.nodeType,
    ordinal: node.ordinal,
    text: node.text,
    headingLevel: node.headingLevel,
    numberingLabel: node.numberingLabel,
    rawStyleId: node.rawStyleId,
    sourceSpan: {
      partPath: node.partPath,
      bodyIndex: node.bodyIndex,
      paragraphIndex: node.paragraphIndex,
      tableIndex: node.tableIndex,
      rowIndex: node.rowIndex,
      cellIndex: node.columnIndex,
      runStart: node.runStart,
      runEnd: node.runEnd,
    },
  }))
}

export function isSpreadsheetStructure(nodes: KnowledgeNode[]): boolean {
  return nodes.some(
    (node) => node.nodeType === 'WORKBOOK' || node.nodeType === 'SHEET',
  )
}

export interface SheetRowView {
  row: KnowledgeNode
  cells: KnowledgeNode[]
}

export interface SheetView {
  sheet: KnowledgeNode
  rows: SheetRowView[]
}

function comparePosition(a: KnowledgeNode, b: KnowledgeNode): number {
  const rowDiff = (a.rowIndex ?? 0) - (b.rowIndex ?? 0)
  if (rowDiff !== 0) return rowDiff
  const columnDiff = (a.columnIndex ?? 0) - (b.columnIndex ?? 0)
  if (columnDiff !== 0) return columnDiff
  return a.ordinal - b.ordinal
}

export function buildSheetViews(nodes: KnowledgeNode[]): SheetView[] {
  const childrenOf = new Map<string, KnowledgeNode[]>()
  for (const node of nodes) {
    if (node.parentId === null) continue
    const children = childrenOf.get(node.parentId)
    if (children) {
      children.push(node)
    } else {
      childrenOf.set(node.parentId, [node])
    }
  }

  return nodes
    .filter((node) => node.nodeType === 'SHEET')
    .sort((a, b) => a.ordinal - b.ordinal)
    .map((sheet) => ({
      sheet,
      rows: [...(childrenOf.get(sheet.id) ?? [])]
        .filter((node) => node.nodeType === 'SPREADSHEET_ROW')
        .sort(comparePosition)
        .map((row) => ({
          row,
          cells: [...(childrenOf.get(row.id) ?? [])]
            .filter((node) => node.nodeType === 'SPREADSHEET_CELL')
            .sort(comparePosition),
        })),
    }))
}

export function mergedRegionLabel(region: {
  firstRow: number
  lastRow: number
  firstColumn: number
  lastColumn: number
}): string {
  const rowPart =
    region.firstRow === region.lastRow
      ? `row ${region.firstRow}`
      : `rows ${region.firstRow}–${region.lastRow}`
  const columnPart =
    region.firstColumn === region.lastColumn
      ? `column ${region.firstColumn}`
      : `columns ${region.firstColumn}–${region.lastColumn}`
  return `${rowPart}, ${columnPart}`
}
