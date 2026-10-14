/**
 * Glyphs the lifecycle surface needs beyond the shared set, ported from the `<symbol>` sheet in
 * `rivell-reviews-v1.html`. Kept here, next to their only user, so the shared Icon file stays as
 * F1 shipped it.
 */
interface IconProps {
  className?: string
}

const base = {
  viewBox: '0 0 16 16',
  'aria-hidden': true as const,
  focusable: 'false' as const,
}

/** `g-prog`: a human has work in progress. */
export function ProgressIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 4.5A3.5 3.5 0 0 1 11.5 8 3.5 3.5 0 0 1 8 11.5" strokeWidth="3" />
    </svg>
  )
}

/** `g-esc`: an up arrow in a circle. Failure and escalation both use it in the canonical file. */
export function EscalateIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 11.5V5m0 0L5.8 7.2M8 5l2.2 2.2" />
    </svg>
  )
}

export function TickIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="m3.5 8.3 3 3 6-6.5" />
    </svg>
  )
}

export function CrossIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="m4 4 8 8m0-8-8 8" />
    </svg>
  )
}

export function PageIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M4 1.5h5L12.5 5v9.5H4zM9 1.5V5h3.5" />
    </svg>
  )
}

export function DownloadIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M8 2.5v8m0 0L5 7.5m3 3 3-3M3 13.5h10" />
    </svg>
  )
}

export function ArrowIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M3 8h10m0 0L9 4m4 4-4 4" />
    </svg>
  )
}

export function PadlockIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  )
}
