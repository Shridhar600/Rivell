import type { ReactNode } from 'react'
import type { StructureResponse } from '../api/contracts'
import { buildOutlineItems } from '../lib/documentTree'
import { OutlineTree } from './OutlineTree'
import { SourceSpanPanel } from './SourceSpanPanel'
import { StructuralRenderer } from './StructuralRenderer'

export function DocumentViewer({
  structure,
  selectedId,
  onSelect,
  selectedRegionExtra,
}: {
  structure: StructureResponse
  selectedId: string | null
  onSelect: (id: string) => void
  selectedRegionExtra?: ReactNode
}) {
  const selectedNode = structure.nodes.find((node) => node.id === selectedId)

  return (
    <div className="workspace__grid">
      <OutlineTree
        items={buildOutlineItems(structure.nodes)}
        selectedId={selectedId}
        onSelect={onSelect}
      />
      <div>
        <StructuralRenderer
          nodes={structure.nodes}
          selectedId={selectedId}
          onSelect={onSelect}
        />
        {selectedId && !selectedNode ? (
          <p className="source-span" role="status">
            The selected region is not part of this document version.
          </p>
        ) : null}
        <SourceSpanPanel
          label="Selected region"
          span={selectedNode?.sourceSpan ?? null}
        />
        {selectedRegionExtra}
      </div>
    </div>
  )
}
