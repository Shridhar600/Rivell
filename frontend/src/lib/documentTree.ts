import type { components } from '../api/schema'

export type DocumentNode = components['schemas']['NodeView']

export type StructureNode = Omit<DocumentNode, 'nodeType'> & {
  nodeType: string
}

export interface TreeNode extends StructureNode {
  children: TreeNode[]
}

export interface OutlineItem {
  id: string
  label: string
  level: number
  children: OutlineItem[]
}

function compareSourceOrder(a: StructureNode, b: StructureNode): number {
  const bodyDiff = a.sourceSpan.bodyIndex - b.sourceSpan.bodyIndex
  if (bodyDiff !== 0) return bodyDiff

  const aRow = a.sourceSpan.rowIndex
  const bRow = b.sourceSpan.rowIndex
  if (aRow != null && bRow != null && aRow !== bRow) return aRow - bRow

  const aCell = a.sourceSpan.cellIndex
  const bCell = b.sourceSpan.cellIndex
  if (aCell != null && bCell != null && aCell !== bCell) return aCell - bCell

  return 0
}

export function buildDocumentTree(nodes: StructureNode[]): TreeNode[] {
  const byId = new Map<string, TreeNode>()
  for (const n of nodes) {
    byId.set(n.id, { ...n, children: [] })
  }

  const roots: TreeNode[] = []
  for (const n of nodes) {
    const treeNode = byId.get(n.id)
    if (!treeNode) continue
    const parent = n.parentId ? byId.get(n.parentId) : undefined
    if (parent) {
      parent.children.push(treeNode)
    } else {
      roots.push(treeNode)
    }
  }

  const sortRec = (list: TreeNode[]) => {
    list.sort(compareSourceOrder)
    for (const child of list) sortRec(child.children)
  }
  sortRec(roots)

  return roots
}

export function flattenDocumentNodes(nodes: StructureNode[]): StructureNode[] {
  const flat: StructureNode[] = []

  const walk = (list: TreeNode[]) => {
    for (const treeNode of list) {
      flat.push(treeNode)
      walk(treeNode.children)
    }
  }

  walk(buildDocumentTree(nodes))
  return flat
}

/** The exact node with the given id plus all its descendants, in source order. */
export function subtreeNodes(nodes: StructureNode[], rootId: string): StructureNode[] {
  const byId = new Map(nodes.map((node) => [node.id, node]))
  if (!byId.has(rootId)) return []

  const isDescendant = (node: StructureNode): boolean => {
    let current: StructureNode | undefined = node
    while (current) {
      if (current.id === rootId) return true
      current = current.parentId ? byId.get(current.parentId) : undefined
    }
    return false
  }

  return flattenDocumentNodes(nodes.filter(isDescendant))
}

function outlineLevel(node: StructureNode): number {
  if (node.nodeType === 'TITLE') return 0
  const level = node.headingLevel
  return level != null && level >= 1 ? level : 1
}

export function buildOutlineItems(nodes: StructureNode[]): OutlineItem[] {
  const roots: OutlineItem[] = []
  const stack: OutlineItem[] = []

  for (const node of flattenDocumentNodes(nodes)) {
    if (node.nodeType !== 'TITLE' && node.nodeType !== 'HEADING') continue

    const level = outlineLevel(node)
    const item: OutlineItem = {
      id: node.id,
      label: node.text?.trim() || 'Untitled section',
      level,
      children: [],
    }

    while (stack.length > 0 && stack[stack.length - 1].level >= level) {
      stack.pop()
    }

    const parent = stack[stack.length - 1]
    if (parent) {
      parent.children.push(item)
    } else {
      roots.push(item)
    }
    stack.push(item)
  }

  return roots
}
