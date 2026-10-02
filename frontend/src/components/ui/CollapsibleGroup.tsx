import { useId, type ReactNode } from 'react'
import { ChevronIcon } from './Icon'
import { CountPill } from './Status'

interface CollapsibleGroupProps {
  title: string
  count: number
  /** One-line summary shown only while collapsed. */
  collapsedSummary?: string
  open: boolean
  onToggle: () => void
  children: ReactNode
}

/**
 * Secondary groups collapse so Home stays scannable. The header is a real button, so keyboard
 * and screen-reader users get `aria-expanded`/`aria-controls` for free.
 */
export function CollapsibleGroup({
  title,
  count,
  collapsedSummary,
  open,
  onToggle,
  children,
}: CollapsibleGroupProps) {
  const regionId = useId()
  return (
    <section className="rv-card rv-group" data-collapsed={!open} aria-label={title}>
      <button
        type="button"
        className="rv-group__head"
        aria-expanded={open}
        aria-controls={regionId}
        onClick={onToggle}
      >
        <ChevronIcon className="rv-icon rv-chev" />
        {title}
        <CountPill>{count}</CountPill>
        {collapsedSummary ? <span className="rv-group__summary">{collapsedSummary}</span> : null}
      </button>
      <div id={regionId} className="rv-rows">
        {children}
      </div>
    </section>
  )
}