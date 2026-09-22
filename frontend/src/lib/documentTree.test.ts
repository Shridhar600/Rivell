import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import {
  buildDocumentTree,
  buildOutlineItems,
  flattenDocumentNodes,
  type StructureNode,
} from './documentTree'

type NodeView = components['schemas']['NodeView']

function node(
  partial: Partial<NodeView> & { id: string },
): StructureNode {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    rawStyleId: null,
    sourceSpan: {
      partPath: 'word/document.xml',
      bodyIndex: 0,
      paragraphIndex: null,
      tableIndex: null,
      rowIndex: null,
      cellIndex: null,
      runStart: null,
      runEnd: null,
    },
    ...partial,
  }
}

const mixedDocument: StructureNode[] = [
  node({ id: 'body', nodeType: 'BODY', sourceSpan: { partPath: 'word/document.xml', bodyIndex: 0, paragraphIndex: null, tableIndex: null, rowIndex: null, cellIndex: null, runStart: null, runEnd: null } }),
  node({ id: 'p0', parentId: 'body', ordinal: 0, text: 'intro', sourceSpan: { partPath: 'word/document.xml', bodyIndex: 1, paragraphIndex: 0, tableIndex: null, rowIndex: null, cellIndex: null, runStart: null, runEnd: null } }),
  node({ id: 'p1', parentId: 'body', ordinal: 1, text: 'outro', sourceSpan: { partPath: 'word/document.xml', bodyIndex: 3, paragraphIndex: 1, tableIndex: null, rowIndex: null, cellIndex: null, runStart: null, runEnd: null } }),
  node({ id: 'tbl', parentId: 'body', nodeType: 'TABLE', ordinal: 0, sourceSpan: { partPath: 'word/document.xml', bodyIndex: 2, paragraphIndex: null, tableIndex: 0, rowIndex: null, cellIndex: null, runStart: null, runEnd: null } }),
  node({ id: 'row0', parentId: 'tbl', nodeType: 'TABLE_ROW', ordinal: 0, sourceSpan: { partPath: 'word/document.xml', bodyIndex: 2, paragraphIndex: null, tableIndex: 0, rowIndex: 0, cellIndex: null, runStart: null, runEnd: null } }),
  node({ id: 'row1', parentId: 'tbl', nodeType: 'TABLE_ROW', ordinal: 1, sourceSpan: { partPath: 'word/document.xml', bodyIndex: 2, paragraphIndex: null, tableIndex: 0, rowIndex: 1, cellIndex: null, runStart: null, runEnd: null } }),
  node({ id: 'cell1', parentId: 'row0', nodeType: 'TABLE_CELL', ordinal: 1, text: 'Duration', sourceSpan: { partPath: 'word/document.xml', bodyIndex: 2, paragraphIndex: null, tableIndex: 0, rowIndex: 0, cellIndex: 1, runStart: null, runEnd: null } }),
  node({ id: 'cell0', parentId: 'row0', nodeType: 'TABLE_CELL', ordinal: 0, text: 'Term', sourceSpan: { partPath: 'word/document.xml', bodyIndex: 2, paragraphIndex: null, tableIndex: 0, rowIndex: 0, cellIndex: 0, runStart: null, runEnd: null } }),
]

describe('buildDocumentTree', () => {
  it('nests children under parents', () => {
    const tree = buildDocumentTree(mixedDocument)

    expect(tree.map((n) => n.id)).toEqual(['body'])
    const body = tree[0]
    expect(body.children.map((n) => n.id)).toEqual(['p0', 'tbl', 'p1'])
    const table = body.children[1]
    expect(table.children.map((n) => n.id)).toEqual(['row0', 'row1'])
    expect(table.children[0].children.map((n) => n.id)).toEqual(['cell0', 'cell1'])
  })

  it('orders siblings by true source position, not per-type ordinal', () => {
    const tree = buildDocumentTree(mixedDocument)

    expect(tree[0].children.map((n) => n.id)).toEqual(['p0', 'tbl', 'p1'])
  })

  it('keeps input order for siblings sharing a source position', () => {
    const shared = [
      node({ id: 'body', nodeType: 'BODY' }),
      node({ id: 'first', parentId: 'body', ordinal: 0, text: 'one' }),
      node({ id: 'second', parentId: 'body', ordinal: 1, text: 'two' }),
    ]

    const tree = buildDocumentTree(shared)

    expect(tree[0].children.map((n) => n.id)).toEqual(['first', 'second'])
  })

  it('treats nodes with unknown parents as roots', () => {
    const orphan = node({ id: 'orphan', parentId: 'missing', text: 'stray' })

    const tree = buildDocumentTree([orphan])

    expect(tree.map((n) => n.id)).toEqual(['orphan'])
  })

  it('returns empty array for empty input', () => {
    expect(buildDocumentTree([])).toEqual([])
  })
})

describe('flattenDocumentNodes', () => {
  it('returns nodes in document order, parents before children', () => {
    const flat = flattenDocumentNodes(mixedDocument)

    expect(flat.map((n) => n.id)).toEqual([
      'body',
      'p0',
      'tbl',
      'row0',
      'cell0',
      'cell1',
      'row1',
      'p1',
    ])
  })
})

describe('buildOutlineItems', () => {
  const outlineDocument = [
    node({ id: 'body', nodeType: 'BODY' }),
    node({ id: 'title', parentId: 'body', nodeType: 'TITLE', text: 'Agreement' }),
    node({
      id: 'h1',
      parentId: 'body',
      nodeType: 'HEADING',
      headingLevel: 1,
      text: 'Definitions',
    }),
    node({
      id: 'h2',
      parentId: 'body',
      nodeType: 'HEADING',
      headingLevel: 2,
      text: 'Terms',
    }),
    node({ id: 'p', parentId: 'body', text: 'body text' }),
    node({
      id: 'h1b',
      parentId: 'body',
      nodeType: 'HEADING',
      headingLevel: 1,
      text: 'Payment',
    }),
  ]

  it('nests outline entries by heading level', () => {
    const outline = buildOutlineItems(outlineDocument)

    expect(outline).toHaveLength(1)
    expect(outline[0].label).toBe('Agreement')
    expect(outline[0].children.map((item) => item.label)).toEqual([
      'Definitions',
      'Payment',
    ])
    expect(outline[0].children[0].children.map((item) => item.label)).toEqual([
      'Terms',
    ])
  })

  it('uses the document title as the outline root', () => {
    const outline = buildOutlineItems(outlineDocument)

    expect(outline[0].level).toBe(0)
    expect(outline[0].children[0].level).toBe(1)
    expect(outline[0].children[0].children[0].level).toBe(2)
  })

  it('treats headings without a level as level 1', () => {
    const outline = buildOutlineItems([
      node({ id: 'h', nodeType: 'HEADING', headingLevel: null, text: 'Intro' }),
    ])

    expect(outline).toEqual([
      {
        id: 'h',
        label: 'Intro',
        level: 1,
        children: [],
      },
    ])
  })

  it('falls back to an untitled label when heading text is empty', () => {
    const outline = buildOutlineItems([
      node({ id: 'h', nodeType: 'HEADING', headingLevel: 1, text: '   ' }),
    ])

    expect(outline[0].label).toBe('Untitled section')
  })

  it('returns an empty outline when there are no headings', () => {
    expect(
      buildOutlineItems([node({ id: 'p', text: 'just a paragraph' })]),
    ).toEqual([])
  })
})
