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
 * The heading each node sits under, from that heading outwards, by document order and heading
 * level. A Word parse records heading levels and paragraph indexes but often no numbering label,
 * so this is the path a reader can follow. A title is the document's own name, not a section.
 */
export function headingPaths(nodes: KnowledgeNode[]): Map<string, string> {
  const ordered = [...nodes].sort((a, b) => a.bodyIndex - b.bodyIndex || a.ordinal - b.ordinal)
  const open: KnowledgeNode[] = []
  const paths = new Map<string, string>()
  for (const node of ordered) {
    if (node.nodeType === 'TITLE') continue
    if (node.nodeType === 'HEADING') {
      const level = node.headingLevel ?? 1
      while (open.length > 0 && (open[open.length - 1]?.headingLevel ?? 1) >= level) open.pop()
      open.push(node)
      const path = headingPathText(open)
      if (path) paths.set(node.id, path)
      continue
    }
    const path = headingPathText(open)
    if (path) paths.set(node.id, path)
  }
  return paths
}

function headingPathText(headings: KnowledgeNode[]): string {
  return headings
    .map((heading) => {
      const text = heading.text?.trim()
      if (!text) return ''
      return heading.numberingLabel ? `${heading.numberingLabel.trim()} ${text}` : text
    })
    .filter((entry) => entry.length > 0)
    .join(' > ')
}

/**
 * Where a position sits in its source, only as far as the extraction recorded it: the sheet name
 * for a workbook, the numbering label for a document, else its heading path and paragraph. Page
 * numbers are not extracted, so none is shown.
 */
export function sourceLocation(
  sourceNodes: KnowledgeNode[],
  paths?: ReadonlyMap<string, string>,
): string | null {
  const sheet = sourceNodes.find((node) => node.sheetName)?.sheetName
  if (sheet) return `Sheet "${sheet}"`
  const numbering = sourceNodes.find((node) => node.numberingLabel)?.numberingLabel
  if (numbering) return numbering
  const first = sourceNodes.find(
    (node) => paths?.get(node.id) !== undefined || node.paragraphIndex !== null,
  )
  if (first) {
    const located = passageLocation(first, new Map(), paths)
    if (located) return located
  }
  const heading = sourceNodes.find(
    (node) => node.nodeType === 'HEADING' && node.text?.trim(),
  )
  return heading?.text ? `Heading "${heading.text.trim()}"` : null
}

export interface SourcePassage {
  id: string
  kind: SourceBlockKind
  text: string
  /** The sheet and row, or the numbering label, only as far as the extraction recorded it. */
  location: string | null
}

function passageLocation(
  node: KnowledgeNode | undefined,
  byId: ReadonlyMap<string, KnowledgeNode>,
  paths?: ReadonlyMap<string, string>,
): string | null {
  if (!node) return null
  if (node.nodeType === 'TABLE_ROW' || node.nodeType === 'SPREADSHEET_ROW') {
    let sheet: string | null = node.sheetName
    for (let parent = node.parentId ? byId.get(node.parentId) : undefined; parent && !sheet; ) {
      sheet = parent.sheetName
      parent = parent.parentId ? byId.get(parent.parentId) : undefined
    }
    // Row indexes are zero-based in the extraction; a person counts rows from one. A Word table
    // row says so; a spreadsheet row stays the plain row the spreadsheet reader expects.
    const rowLabel = node.nodeType === 'TABLE_ROW' ? 'Table row' : 'row'
    const row = node.rowIndex !== null ? `${rowLabel} ${node.rowIndex + 1}` : null
    return [sheet ? `Sheet "${sheet}"` : null, row].filter(Boolean).join(', ') || null
  }
  if (node.numberingLabel) return node.numberingLabel
  const path = paths?.get(node.id)
  const paragraph = node.paragraphIndex !== null ? node.paragraphIndex + 1 : null
  if (path) {
    return [`Heading "${path}"`, paragraph !== null ? `paragraph ${paragraph}` : null]
      .filter(Boolean)
      .join(', ')
  }
  return paragraph !== null ? `Paragraph ${paragraph}` : null
}

export function indexNodes(nodes: KnowledgeNode[]): Map<string, KnowledgeNode> {
  return new Map(nodes.map((node) => [node.id, node]))
}

/** The passages of one candidate within already-built blocks, so a list does not rebuild the structure per row. */
export function passagesIn(
  blocks: SourceBlock[],
  byId: ReadonlyMap<string, KnowledgeNode>,
  candidateId: string,
  paths?: ReadonlyMap<string, string>,
): SourcePassage[] {
  return blocks
    .filter((block) => block.candidateIds.includes(candidateId))
    .map((block) => ({
      id: block.id,
      kind: block.kind,
      text: block.text,
      location: passageLocation(byId.get(block.id), byId, paths),
    }))
}

/**
 * The source passages one candidate was extracted from, in reading order: exactly the nodes its
 * extraction unit recorded. There is no page number and no sub-paragraph span, because the
 * extraction records neither.
 */
export function candidatePassages(candidate: Candidate, units: ExtractionUnit[], nodes: KnowledgeNode[]): SourcePassage[] {
  return passagesIn(
    sourceBlocks(nodes, highlightMap([candidate], units)),
    indexNodes(nodes),
    candidate.id,
    headingPaths(nodes),
  )
}

export interface ContextWindow {
  before: SourceBlock[]
  focus: SourceBlock[]
  after: SourceBlock[]
  /** More source exists above or below what is shown. */
  moreBefore: boolean
  moreAfter: boolean
}

/**
 * The candidate's passages with a few blocks of the original around them, so a reviewer can read
 * what comes before and after. It is a window onto the extracted original, not the whole document.
 */
export function contextWindow(blocks: SourceBlock[], candidateId: string, radius = 3): ContextWindow | null {
  const marked = blocks.flatMap((block, position) => (block.candidateIds.includes(candidateId) ? [position] : []))
  const first = marked[0]
  const last = marked[marked.length - 1]
  if (first === undefined || last === undefined) return null
  const start = Math.max(0, first - radius)
  const end = Math.min(blocks.length, last + 1 + radius)
  return {
    before: blocks.slice(start, first),
    focus: blocks.slice(first, last + 1),
    after: blocks.slice(last + 1, end),
    moreBefore: start > 0,
    moreAfter: end < blocks.length,
  }
}

/**
 * Where each candidate sits in its source, from the extraction unit's recorded nodes. Built once
 * for a whole list, so a long compilation does not search the structure per row.
 */
export function candidateLocations(candidates: Candidate[], units: ExtractionUnit[], nodes: KnowledgeNode[]): Map<string, string | null> {
  const unitById = new Map(units.map((unit) => [unit.id, unit]))
  const byId = new Map(nodes.map((node) => [node.id, node]))
  const paths = headingPaths(nodes)
  const found = new Map<string, string | null>()
  for (const candidate of candidates) {
    const unit = unitById.get(candidate.extractionUnitId)
    const sourceNodes = (unit?.sourceNodeIds ?? []).map((id) => byId.get(id)).filter((node): node is KnowledgeNode => node !== undefined)
    const first = sourceNodes[0]
    // A row keeps its sheet and row; anything else falls back to its heading path and paragraph.
    const label = passageLocation(first, byId, paths) ?? sourceLocation(sourceNodes, paths)
    found.set(candidate.id, label ?? null)
  }
  return found
}
