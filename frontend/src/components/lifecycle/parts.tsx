import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { JourneyStep, LifecycleStatus } from '../../lib/reviewLifecycle'
import { RunningIcon } from '../ui/Icon'
import { PadlockIcon, TickIcon, CrossIcon } from './icons'

/** The canonical `stb` status pill: a lifecycle word, with a spinner or a lock where it applies. */
export function StatusPill({ status }: { status: LifecycleStatus }) {
  const variant = status.variant === 'neutral' ? '' : ` rv-stb--${status.variant}`
  return (
    <span className={`rv-stb${variant}`}>
      {status.glyph === 'spin' ? <RunningIcon className="rv-icon rv-glyph--spin" /> : null}
      {status.glyph === 'lock' ? <PadlockIcon className="rv-icon" /> : null}
      {status.label}
    </span>
  )
}

/** The canonical `.big` tile that leads every state card. */
export function BigGlyph({ tone, children }: { tone?: 'red' | 'amber'; children: ReactNode }) {
  return <span className={`rv-lc-big${tone ? ` rv-lc-big--${tone}` : ''}`}>{children}</span>
}

export function LcCard({ children, label }: { children: ReactNode; label?: string }) {
  return (
    <section className="rv-card" aria-label={label}>
      {children}
    </section>
  )
}

export function LcBody({ children }: { children: ReactNode }) {
  return <div className="rv-lc-cb">{children}</div>
}

export function LcFoot({ children }: { children: ReactNode }) {
  return <div className="rv-lc-ft">{children}</div>
}

export function Spacer({ note }: { note?: string }) {
  return <span className="rv-lc-sp">{note}</span>
}

export interface Stat {
  figure: string | number
  label: string
}

export function Stat3({ stats, label }: { stats: Stat[]; label: string }) {
  return (
    <div className="rv-lc-stat3" role="group" aria-label={label}>
      {stats.map((stat) => (
        <div key={stat.label}>
          <b>{stat.figure}</b>
          <small>{stat.label}</small>
        </div>
      ))}
    </div>
  )
}

/** The canonical `.chk` list of statements the product keeps. */
export function Checks({ items }: { items: { text: string; failed?: boolean }[] }) {
  return (
    <ul className="rv-lc-chk">
      {items.map((item) => (
        <li key={item.text} className={item.failed ? 'rv-lc-chk--failed' : undefined}>
          {item.failed ? <CrossIcon className="rv-icon" /> : <TickIcon className="rv-icon" />}
          {item.text}
        </li>
      ))}
    </ul>
  )
}

/** The canonical `.jr` four-step strip. */
export function Journey({ steps }: { steps: JourneyStep[] }) {
  return (
    <ol className="rv-lc-jr" aria-label="Review progress">
      {steps.map((step) => (
        <li key={step.title} className={`rv-lc-jr__step rv-lc-jr__step--${step.tone}`}>
          <span className="rv-lc-jc" aria-hidden="true">
            {step.tone === 'd' ? <TickIcon className="rv-icon" /> : null}
            {step.tone === 'x' ? <CrossIcon className="rv-icon" /> : null}
            {step.tone === 'a' || step.tone === 'w' ? <i /> : null}
          </span>
          <span>
            <b>{step.title}</b>
            <small>{step.detail}</small>
          </span>
        </li>
      ))}
    </ol>
  )
}

export function PlainLink({ to, children }: { to: string; children: ReactNode }) {
  return (
    <Link className="rv-lc-lnk" to={to}>
      {children}
    </Link>
  )
}
