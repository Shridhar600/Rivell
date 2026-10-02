interface SummaryPillProps {
  count: number
  label: string
  tone: 'attention' | 'active' | 'danger' | 'neutral'
  /** Id of the group this pill counts. Used to expand it and scroll it into view. */
  targetId: string
  /** Expands the target group, which may be collapsed. */
  onReveal?: () => void
}

/**
 * A count of real work that actually exists. Never rendered at zero, so a pill can never imply
 * work Rivell cannot see.
 */
export function SummaryPill({ count, label, tone, targetId, onReveal }: SummaryPillProps) {
  if (count <= 0) return null
  return (
    <button
      type="button"
      className="rv-pill"
      onClick={() => {
        onReveal?.()
        // The scroll container is .rv-scroll, not the document, so anchor navigation alone
        // would leave the group off screen.
        globalThis.document?.getElementById(targetId)?.scrollIntoView({ block: 'nearest' })
      }}
    >
      <i className={`rv-dot rv-dot--${tone}`} />
      <b>{count}</b> {label}
    </button>
  )
}