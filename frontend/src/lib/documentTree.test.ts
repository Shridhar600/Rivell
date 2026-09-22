import { describe, expect, it } from 'vitest'
import type { components } from '../api/schema'
import { buildDocumentTree, buildOutlineItems, isParseFailed } from './documentTree'

type DocumentNode = components['schemas']['DocumentNode']

function node(partial: Partial<DocumentNode> & { id: string }): DocumentNode {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    rawStyleId: null,
    sourceSpan: { partPath: 'word/document.xml', bodyIndex: 0 },
    ...partial,
  }
}

describe('buildDocumentTree', () => {
  it('nests children under parents and sorts by ordinal', () => {
    const flat = [
      node({ id: 'p2', nodeType: 'PARAGRAPH', ordinal: 1, text: 'second' }),
      node({ id: 'h1', nodeType: 'HEADING', ordinal: 0, headingLevel: 1, text: 'One' }),
      node({ id: 'p1', parentId: 'h1', ordinal: 1, text: 'under h1' }),
      node({ id: 'p0', parentId: 'h1', ordinal: 0, text: 'first under h1' }),
    ]

    const tree = buildDocumentTree(flat)

    expect(tree.map((n) => n.id)).toEqual(['h1', 'p2'])
    expect(tree[0].children.map((n) => n.id)).toEqual(['p0', 'p1'])
  })

  it('returns empty array for empty input', () => {
    expect(buildDocumentTree([])).toEqual([])
  })
})

describe('buildOutlineItems', () => {
  it('collects TITLE and HEADING nodes with depth', () => {
    const flat = [
      node({ id: 't', nodeType: 'TITLE', text: 'Agreement' }),
      node({ id: 'h', parentId: 't', nodeType: 'HEADING', headingLevel: 2, text: 'Definitions' }),
      node({ id: 'sub', parentId: 'h', nodeType: 'HEADING', headingLevel: 3, text: 'Terms' }),
      node({ id: 'p', parentId: 'sub', nodeType: 'PARAGRAPH', text: 'body' }),
    ]

    const outline = buildOutlineItems(flat)

    expect(outline).toEqual([
      { id: 't', label: 'Agreement', depth: 0 },
      { id: 'h', label: 'Definitions', depth: 1 },
      { id: 'sub', label: 'Terms', depth: 2 },
    ])
  })

  it('skips outline nodes without text and uses Untitled fallback', () => {
    const flat = [
      node({ id: 'h', nodeType: 'HEADING', headingLevel: 1, text: null }),
    ]

    expect(buildOutlineItems(flat)).toEqual([
      { id: 'h', label: 'Untitled section', depth: 0 },
    ])
  })
})

describe('isParseFailed', () => {
  it('detects failure in parseStatus or parseQuality case-insensitively', () => {
    expect(isParseFailed('FAILED', 'OK')).toBe(true)
    expect(isParseFailed('COMPLETED', 'failed')).toBe(true)
    expect(isParseFailed('COMPLETED', 'OK')).toBe(false)
    expect(isParseFailed('failed', null)).toBe(true)
  })
})
