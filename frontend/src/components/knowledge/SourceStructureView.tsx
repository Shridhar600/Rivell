import type { KnowledgeNode } from '../../api/knowledge'
import {
  buildSheetViews,
  isSpreadsheetStructure,
  mergedRegionLabel,
  toStructureNodes,
} from '../../lib/knowledgeTree'
import { StructuralRenderer } from '../StructuralRenderer'

function SpreadsheetStructure({ nodes }: { nodes: KnowledgeNode[] }) {
  const sheets = buildSheetViews(nodes)

  if (sheets.length === 0) {
    return (
      <p className="meta">No sheets were extracted from this workbook.</p>
    )
  }

  return (
    <div className="sheet-stack">
      {sheets.map(({ sheet, rows }) => {
        const mergedRegions = rows
          .flatMap((row) => row.cells)
          .map((cell) => cell.mergedRegion)
          .filter((region): region is NonNullable<typeof region> => region !== null)
        return (
          <section
            key={sheet.id}
            className="sheet"
            aria-labelledby={`sheet-${sheet.id}`}
          >
            <h3 id={`sheet-${sheet.id}`}>{sheet.sheetName ?? 'Sheet'}</h3>
            {rows.length === 0 ? (
              <p className="meta">No rows were extracted from this sheet.</p>
            ) : (
              <table className="sheet-table">
                <caption className="visually-hidden">
                  Sheet {sheet.sheetName ?? 'contents'}
                </caption>
                <tbody>
                  {rows.map(({ row, cells }) => (
                    <tr key={row.id}>
                      {cells.map((cell) => (
                        <td key={cell.id}>{cell.text ?? ''}</td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {mergedRegions.length > 0 ? (
              <p className="meta">
                Merged regions:{' '}
                {mergedRegions.map((region) => mergedRegionLabel(region)).join('; ')}
              </p>
            ) : null}
          </section>
        )
      })}
    </div>
  )
}

export function SourceStructureView({ nodes }: { nodes: KnowledgeNode[] }) {
  if (nodes.length === 0) {
    return (
      <p className="meta">
        No structure was extracted from this source. The original file remains
        available above.
      </p>
    )
  }

  if (isSpreadsheetStructure(nodes)) {
    return <SpreadsheetStructure nodes={nodes} />
  }

  return (
    <StructuralRenderer
      nodes={toStructureNodes(nodes)}
      selectedId={null}
      onSelect={() => undefined}
      selectable={false}
      label="Knowledge source structure"
    />
  )
}
