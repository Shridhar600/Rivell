import type { ReactNode } from 'react'
import {
  CompleteIcon,
  FailedIcon,
  NeedsHumanIcon,
  QueuedIcon,
  RunningIcon,
  UnknownIcon,
} from './Icon'
import type { HomeTone } from '../../lib/home'

export function Chip({ children }: { children: ReactNode }) {
  return <span className="rv-chip">{children}</span>
}

/**
 * Status is always a glyph plus a text label. The glyph is decorative and hidden from
 * assistive technology; the label carries the meaning.
 */
export function StatusGlyph({ tone, spin }: { tone: HomeTone; spin?: boolean }) {
  const className = `rv-icon rv-glyph--${tone}${spin ? ' rv-glyph--spin' : ''}`
  if (tone === 'danger') return <FailedIcon className={className} />
  if (tone === 'attention') return <NeedsHumanIcon className={className} />
  if (tone === 'complete') return <CompleteIcon className={className} />
  if (tone === 'active') return spin ? <RunningIcon className={className} /> : <QueuedIcon className={className} />
  return <UnknownIcon className={className} />
}

export function StatusBadge({ tone, label }: { tone: HomeTone; label: string }) {
  return (
    <span className={`rv-badge rv-badge--${tone}`}>
      <StatusGlyph tone={tone} />
      {label}
    </span>
  )
}

export function CountPill({ children }: { children: ReactNode }) {
  return <span className="rv-group__count">{children}</span>
}