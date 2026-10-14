import type { ReactNode } from 'react'
import { Button, LinkButton } from './Button'
import { InfoIcon } from './Icon'

/** Loading state. Announced politely; never a spinner without real in-progress work behind it. */
export function LoadingState({ label = 'Loading' }: { label?: string }) {
  return (
    <p role="status" className="rv-state">
      <span className="rv-state__body">{label.replace(/(…|\.\.\.)$/, '')}…</span>
    </p>
  )
}

/** One sentence plus the next action. Never an illustration. */
export function EmptyState({ title, message, action }: { title: string; message: string; action?: ReactNode }) {
  return (
    <div className="rv-state">
      <h2 className="rv-state__title">{title}</h2>
      <p className="rv-state__body">{message}</p>
      {action}
    </div>
  )
}

interface ErrorStateProps {
  message: string
  /** Defaults to a neutral failure heading. A permission boundary is not a failure. */
  title?: string
  tone?: 'danger' | 'neutral'
  onRetry?: () => void
}

/** Says what failed, what is intact, and what the user can do. */
export function ErrorState({ message, title = 'Something went wrong', tone = 'danger', onRetry }: ErrorStateProps) {
  return (
    <div role="alert" className={`rv-state${tone === 'danger' ? ' rv-state--error' : ''}`}>
      <h2 className="rv-state__title">{title}</h2>
      <p className="rv-state__body">{message}</p>
      {onRetry ? (
        <Button onClick={onRetry} compact>
          Try again
        </Button>
      ) : null}
    </div>
  )
}

/**
 * A capability that is not readable yet. States the reason plainly instead of showing an empty
 * state that would read as "you have no work".
 */
export function UnavailableNotice({ title, children, to, actionLabel }: { title: string; children: ReactNode; to?: string; actionLabel?: string }) {
  return (
    <div className="rv-notice">
      <InfoIcon className="rv-icon rv-notice__glyph" />
      <span>
        <strong>{title}</strong> {children}
      </span>
      {to && actionLabel ? (
        <LinkButton to={to} compact className="rv-notice__action">
          {actionLabel}
        </LinkButton>
      ) : null}
    </div>
  )
}