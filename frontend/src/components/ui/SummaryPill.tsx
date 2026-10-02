import { Link } from 'react-router'

interface SummaryPillProps {
  count: number
  label: string
  tone: 'attention' | 'active' | 'danger' | 'neutral'
  /** Jump target. Omitted when the group is not rendered. */
  to: string
  /** Expands the target group, which may be collapsed. */
  onReveal?: () => void
}

/**
 * A count of real work that actually exists. Never rendered at zero, so a pill can never imply
 * work Rivell cannot see.
 */
export function SummaryPill({ count, label, tone, to, onReveal }: SummaryPillProps) {
  if (count <= 0) return null
  return (
    <Link to={to} className="rv-pill" onClick={onReveal}>
      <i className={`rv-dot rv-dot--${tone}`} />
      <b>{count}</b>
      {label}
    </Link>
  )
}