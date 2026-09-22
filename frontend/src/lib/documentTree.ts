import type { components } from '../api/schema'

export type DocumentNode = components['schemas']['NodeView']

export interface TreeNode extends DocumentNode {
  children: TreeNode[]
}

export interface OutlineItem {
  id: string
  label: string
  depth: number
}

export function buildDocumentTree(nodes: DocumentNode[]): TreeNode[] {
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
    list.sort((a, b) => a.ordinal - b.ordinal)
    for (const child of list) sortRec(child.children)
  }
  sortRec(roots)

  return roots
}

export function buildOutlineItems(nodes: DocumentNode[]): OutlineItem[] {
  const items: OutlineItem[] = []

  const walk = (list: TreeNode[], depth: number) => {
    for (const treeNode of list) {
      const isOutline =
        treeNode.nodeType === 'TITLE' || treeNode.nodeType === 'HEADING'
      if (isOutline) {
        items.push({
          id: treeNode.id,
          label: treeNode.text?.trim() || 'Untitled section',
          depth,
        })
        walk(treeNode.children, depth + 1)
      } else {
        walk(treeNode.children, depth)
      }
    }
  }

  walk(buildDocumentTree(nodes), 0)
  return items
}

export function isParseFailed(parseStatus: string | null | undefined): boolean {
  return parseStatus === 'FAILED'
}
