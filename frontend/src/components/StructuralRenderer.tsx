import {
  createElement,
  type KeyboardEvent,
  type MouseEvent,
  type ReactNode,
} from 'react'
import {
  buildDocumentTree,
  flattenDocumentNodes,
  type StructureNode,
  type TreeNode,
} from '../lib/documentTree'

const HEADING_OFFSET = 2

function headingTag(level: number | null): 'h3' | 'h4' | 'h5' | 'h6' {
  const htmlLevel = Math.min((level ?? 1) + HEADING_OFFSET, 6)
  return `h${htmlLevel}` as 'h3' | 'h4' | 'h5' | 'h6'
}

function nodeContent(treeNode: TreeNode): ReactNode {
  const label = treeNode.numberingLabel
  if (label && treeNode.text) return `${label} ${treeNode.text}`
  return label ?? treeNode.text
}

function NodeView({
  treeNode,
  selectedId,
  activeId,
}: {
  treeNode: TreeNode
  selectedId: string | null
  activeId: string | null
}) {
  const selected = treeNode.id === selectedId
  const className = selected ? 'doc-node doc-node--selected' : 'doc-node'
  const common = {
    'data-node-id': treeNode.id,
    id: `node-${treeNode.id}`,
    className,
    tabIndex: treeNode.id === activeId ? 0 : -1,
  }

  const children = treeNode.children.map((child) => (
    <NodeView
      key={child.id}
      treeNode={child}
      selectedId={selectedId}
      activeId={activeId}
    />
  ))

  switch (treeNode.nodeType) {
    case 'TITLE':
      return (
        <h2 {...common}>
          {nodeContent(treeNode)}
          {children}
        </h2>
      )
    case 'HEADING':
      return createElement(
        headingTag(treeNode.headingLevel),
        common,
        nodeContent(treeNode),
        children,
      )
    case 'PARAGRAPH':
      return (
        <p {...common}>
          {nodeContent(treeNode)}
          {children}
        </p>
      )
    case 'TABLE':
      return (
        <table {...common} className={`${className} doc-table`}>
          <tbody>{children}</tbody>
        </table>
      )
    case 'TABLE_ROW':
      return <tr {...common}>{children}</tr>
    case 'TABLE_CELL':
      return (
        <td {...common}>
          {nodeContent(treeNode)}
          {children}
        </td>
      )
    default:
      return (
        <div {...common}>
          {nodeContent(treeNode)}
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
  nodes: StructureNode[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const tree = buildDocumentTree(nodes)
  const selectable = flattenDocumentNodes(nodes).filter(
    (node) => node.nodeType !== 'BODY',
  )
  const activeId =
    selectedId && selectable.some((node) => node.id === selectedId)
      ? selectedId
      : (selectable[0]?.id ?? null)

  const nodeElementFrom = (target: EventTarget | null) =>
    target instanceof Element
      ? target.closest<HTMLElement>('[data-node-id]')
      : null

  const handleClick = (event: MouseEvent<HTMLElement>) => {
    const nodeId = nodeElementFrom(event.target)?.dataset.nodeId
    if (nodeId) onSelect(nodeId)
  }

  const handleKeyDown = (event: KeyboardEvent<HTMLElement>) => {
    const nodeId = nodeElementFrom(event.target)?.dataset.nodeId
    if (!nodeId) return

    const index = selectable.findIndex((node) => node.id === nodeId)
    if (index === -1) return

    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      onSelect(nodeId)
      return
    }

    let nextIndex: number
    switch (event.key) {
      case 'ArrowDown':
        nextIndex = Math.min(index + 1, selectable.length - 1)
        break
      case 'ArrowUp':
        nextIndex = Math.max(index - 1, 0)
        break
      case 'Home':
        nextIndex = 0
        break
      case 'End':
        nextIndex = selectable.length - 1
        break
      default:
        return
    }

    if (nextIndex === index) return
    event.preventDefault()
    document.getElementById(`node-${selectable[nextIndex].id}`)?.focus()
  }

  return (
    <article
      className="doc-renderer"
      aria-label="Contract structure"
      aria-describedby="doc-renderer-hint"
      onClick={handleClick}
      onKeyDown={handleKeyDown}
    >
      <p id="doc-renderer-hint" className="visually-hidden">
        Click a region, or move with the arrow keys and press Enter, to view
        its source mapping.
      </p>
      {tree.map((treeNode) => (
        <NodeView
          key={treeNode.id}
          treeNode={treeNode}
          selectedId={selectedId}
          activeId={activeId}
        />
      ))}
    </article>
  )
}
