export function SourceRegionLinks({
  nodeIds,
  labelByNodeId,
  onNavigateToNode,
  prefix,
  emptyLabel,
}: {
  nodeIds: string[]
  labelByNodeId: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  prefix?: string
  emptyLabel?: string
}) {
  if (nodeIds.length === 0) {
    return emptyLabel ? <span className="source-links">{emptyLabel}</span> : null
  }

  return (
    <span className="source-links">
      {prefix ? <span className="source-links__prefix">{prefix}</span> : null}
      {nodeIds.map((nodeId, index) => {
        const label = labelByNodeId.get(nodeId)
        return (
          <span key={nodeId}>
            {index > 0 ? ' · ' : ''}
            {label ? (
              <button
                type="button"
                className="link-button"
                onClick={() => onNavigateToNode(nodeId)}
              >
                {label}
              </button>
            ) : (
              <span className="source-links__unknown">Unknown region</span>
            )}
          </span>
        )
      })}
    </span>
  )
}
