import type { components } from '../api/schema'

type SourceSpan = components['schemas']['SourceSpanView']

function formatIndex(
  label: string,
  value: number | null | undefined,
): string | null {
  if (value == null) return null
  return `${label}[\u00A0${value}\u00A0]`
}

export function SourceSpanPanel({
  label,
  span,
}: {
  label: string
  span: SourceSpan | null
}) {
  if (!span) return null

  const parts = [
    span.partPath,
    formatIndex('body', span.bodyIndex),
    formatIndex('paragraph', span.paragraphIndex),
    formatIndex('table', span.tableIndex),
    formatIndex('row', span.rowIndex),
    formatIndex('cell', span.cellIndex),
    formatIndex('runStart', span.runStart),
    formatIndex('runEnd', span.runEnd),
  ].filter((part): part is string => part != null)

  return (
    <p className="source-span" role="status">
      <strong>{label}:</strong> <code>{parts.join(' · ')}</code>
    </p>
  )
}
