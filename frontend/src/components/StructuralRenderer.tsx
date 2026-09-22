import { createElement, type ElementType } from 'react'
import type { components } from '../api/schema'
import { buildDocumentTree, type TreeNode } from '../lib/documentTree'

type DocumentNode = components['schemas']['DocumentNode']

const HEADING_TAGS: Record<number, ElementType> = {
  2: 'h2',
  3: 'h3',
  4: 'h4',
  5: 'h5',
  6: 'h6',
}

function headingTag(level: number | null): ElementType {
  if (level != null && level >= 2 && level <= 6) return HEADING_TAGS[level]
  return 'h2'
}

function NodeView({
  treeNode,
  selectedId,
  onSelect,
}: {
  treeNode: TreeNode
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const selected = treeNode.id === selectedId
  const className = selected ? 'doc-node doc-node--selected' : 'doc-node'
  const common = {
    'data-node-id': treeNode.id,
    className,
    onClick: () => onSelect(treeNode.id),
  }

  const children = treeNode.children.map((child) => (
    <NodeView
      key={child.id}
      treeNode={child}
      selectedId={selectedId}
      onSelect={onSelect}
    />
  ))

  switch (treeNode.nodeType) {
    case 'TITLE':
      return (
        <h1 {...common} id={`node-${treeNode.id}`}>
          {treeNode.text}
        </h1>
      )
    case 'HEADING':
      return createElement(
        headingTag(treeNode.headingLevel),
        { ...common, id: `node-${treeNode.id}` },
        treeNode.text,
      )
    case 'PARAGRAPH':
      return (
        <p {...common} id={`node-${treeNode.id}`}>
          {treeNode.text}
        </p>
      )
    case 'TABLE':
      return (
        <div {...common} id={`node-${treeNode.id}`}>
          <table className="doc-table">
            <tbody>{children}</tbody>
          </table>
        </div>
      )
    case 'TABLE_ROW':
      return (
        <tr {...common} id={`node-${treeNode.id}`}>
          {children}
        </tr>
      )
    case 'TABLE_CELL':
      return (
        <td {...common} id={`node-${treeNode.id}`}>
          {treeNode.text}
        </td>
      )
    case 'BODY':
    default:
      return (
        <div {...common} id={`node-${treeNode.id}`}>
          {children}
        </div>
      )
  }
}

export function StructuralRenderer({
  nodes,
  selectedId,
  onSelect,
}: {
  nodes: DocumentNode[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const tree = buildDocumentTree(nodes)

  return (
    <article
      className="doc-renderer"
      aria-label="Contract structure"
      aria-describedby="doc-renderer-hint"
    >
      <p id="doc-renderer-hint" className="visually-hidden">
        Select a region to view its source mapping.
      </p>
      {tree.map((treeNode) => (
        <NodeView
          key={treeNode.id}
          treeNode={treeNode}
          selectedId={selectedId}
          onSelect={onSelect}
        />
      ))}
    </article>
  )
}
