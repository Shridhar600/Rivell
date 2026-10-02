interface IconProps {
  className?: string
}

const base = {
  viewBox: '0 0 16 16',
  'aria-hidden': true as const,
  focusable: 'false' as const,
}

export function HomeIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M2.5 7 8 2.5 13.5 7v6.5h-11z" />
    </svg>
  )
}

export function DocumentIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M4 1.5h5L12.5 5v9.5H4zM9 1.5V5h3.5" />
    </svg>
  )
}

export function BookIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M3 2.5h8.5A1.5 1.5 0 0 1 13 4v9.5H4.5A1.5 1.5 0 0 1 3 12zM3 12a1.5 1.5 0 0 1 1.5-1.5H13" />
    </svg>
  )
}

export function ClauseIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M3 4h10M3 7.5h10M3 11h6" />
    </svg>
  )
}

export function SourceIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M2.5 4.5h4l1.5 1.5h5.5v7h-11z" />
    </svg>
  )
}

export function PlusIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M8 3.5v9M3.5 8h9" />
    </svg>
  )
}

export function ChevronIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="m4 6 4 4 4-4" />
    </svg>
  )
}

export function PanelLeftIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <rect x="2" y="3" width="12" height="10" rx="2" />
      <path d="M6 3v10" />
    </svg>
  )
}

export function PanelRightIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <rect x="2" y="3" width="12" height="10" rx="2" />
      <path d="M10 3v10" />
    </svg>
  )
}

/** Half-filled circle: work a human has in progress. */
export function ProgressIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 4.5A3.5 3.5 0 0 1 11.5 8 3.5 3.5 0 0 1 8 11.5" strokeWidth="3" />
    </svg>
  )
}

/** Circle with "!": needs a human. */
export function NeedsHumanIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 5v3.5" />
      <circle cx="8" cy="11" r="0.7" fill="currentColor" stroke="none" />
    </svg>
  )
}

/** Open arc: real work running server-side. */
export function RunningIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" opacity="0.25" />
      <path d="M8 2a6 6 0 0 1 6 6" />
    </svg>
  )
}

/** Dashed circle: queued, not yet started. */
export function QueuedIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" strokeDasharray="2.2 2.2" />
    </svg>
  )
}

/** Check circle: complete. */
export function CompleteIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="m5.5 8.2 1.8 1.8 3.2-3.6" />
    </svg>
  )
}

/** Circle with "x": failed. */
export function FailedIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="m6 6 4 4M10 6l-4 4" />
    </svg>
  )
}

/** Hollow circle: state not recognised. */
export function UnknownIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
    </svg>
  )
}

/** Plain info marker for inline notices. */
export function InfoIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 7.5V11" />
      <circle cx="8" cy="5.2" r="0.7" fill="currentColor" stroke="none" />
    </svg>
  )
}