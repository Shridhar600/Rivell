import type { Candidate, ExtractionUnit, KnowledgeNode } from '../api/knowledge'

export type SourceBlockKind = 'title' | 'heading' | 'paragraph' | 'row'

export interface SourceBlock {
  id: string
  kind: SourceBlockKind
  text: string
  /** Candidates produced from exactly this node (or, for a row, one of its cells). */
  candidateIds: string[]
}

function childrenByParent(nodes: KnowledgeNode[]): Map<string | null, KnowledgeNode[]> {
  const map = new Map<string | null, KnowledgeNode[]>()
  for (const node of nodes) {
    const list = map.get(node.parentId)
    if (list) list.push(node)
    else map.set(node.parentId, [node])
  }
  for (const list of map.values()) {
    list.sort((a, b) => a.bodyIndex - b.bodyIndex || a.ordinal - b.ordinal)
  }
  return map
}

/**
 * Which candidates each source node produced, by the exact node ids the extraction unit recorded.
 * This is node granularity. It says which paragraph or row a position came from, not which words.
 */
export function highlightMap(candidates: Candidate[], units: ExtractionUnit[]): Map<string, string[]> {
  const unitById = new Map(units.map((unit) => [unit.id, unit]))
  const map = new Map<string, string[]>()
  for (const candidate of candidates) {
    const unit = unitById.get(candidate.extractionUnitId)
    if (!unit) continue
    for (const nodeId of unit.sourceNodeIds) {
      const list = map.get(nodeId)
      if (list) list.push(candidate.id)
      else map.set(nodeId, [candidate.id])
    }
  }
  return map
}

/** The extracted source in reading order, as readable blocks. Wording is the parser's, never reworded. */
export function sourceBlocks(nodes: KnowledgeNode[], highlights: ReadonlyMap<string, string[]>): SourceBlock[] {
  const children = childrenByParent(nodes)
  const blocks: SourceBlock[] = []

  const textOf = (node: KnowledgeNode): string => {
    if (node.text) return node.text
    return (children.get(node.id) ?? []).map(textOf).filter(Boolean).join(' ')
  }
  const idsUnder = (node: KnowledgeNode, into: Set<string>) => {
    for (const id of highlights.get(node.id) ?? []) into.add(id)
    for (const child of children.get(node.id) ?? []) idsUnder(child, into)
  }

  const visit = (node: KnowledgeNode) => {
    switch (node.nodeType) {
      case 'TITLE':
      case 'HEADING':
      case 'PARAGRAPH': {
        const text = node.text?.trim()
        if (text) {
          blocks.push({
            id: node.id,
            kind: node.nodeType === 'TITLE' ? 'title' : node.nodeType === 'HEADING' ? 'heading' : 'paragraph',
            text,
            candidateIds: highlights.get(node.id) ?? [],
          })
        }
        return
      }
      case 'SHEET': {
        if (node.sheetName) blocks.push({ id: node.id, kind: 'heading', text: node.sheetName, candidateIds: [] })
        break
      }
      case 'TABLE_ROW':
      case 'SPREADSHEET_ROW': {
        const cells = (children.get(node.id) ?? []).map(textOf).filter((cell) => cell.trim().length > 0)
        if (cells.length > 0) {
          const ids = new Set<string>()
          idsUnder(node, ids)
          blocks.push({ id: node.id, kind: 'row', text: cells.join(' | '), candidateIds: [...ids] })
        }
        return
      }
      default:
        break
    }
    for (const child of children.get(node.id) ?? []) visit(child)
  }

  for (const root of children.get(null) ?? []) visit(root)
  return blocks
}

/**
 * Where a position sits in its source, only as far as the extraction recorded it: the sheet name
 * for a workbook, the numbering label for a document. Page numbers are not extracted, so none is
 * shown.
 */
export function sourceLocation(sourceNodes: KnowledgeNode[]): string | null {
  const sheet = sourceNodes.find((node) => node.sheetName)?.sheetName
  if (sheet) return `Sheet "${sheet}"`
  const numbering = sourceNodes.find((node) => node.numberingLabel)?.numberingLabel
  return numbering ?? null
}
