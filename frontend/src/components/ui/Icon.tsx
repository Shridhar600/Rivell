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

/** Play mark in a circle: pick up where the reviewer left off. Not a status glyph. */
export function ResumeIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M6.9 5.6 10.4 8l-3.5 2.4z" />
    </svg>
  )
}

/** Single chevron used by the panel edge handles; the direction is set by CSS rotation. */
export function EdgeChevron({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="m10 4-4 4 4 4" />
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

/** Padlock: a frozen (finalized) review. */
export function LockIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </svg>
  )
}

/** Tick: a statement the product keeps. */
export function CheckIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="m3.5 8.3 3 3 6-6.5" />
    </svg>
  )
}

/** Arrow into a tray: choose a file to upload. */
export function UploadIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M8 11V3m0 0L5 6m3-3 3 3M3 13h10" />
    </svg>
  )
}

/** Page with a folded corner: a chosen file. */
export function FileIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M4 1.5h5L12.5 5v9.5H4zM9 1.5V5h3.5" />
    </svg>
  )
}

/** Circle with an upward arrow: a caution that needs the reader's eye. */
export function CautionIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 11.5V5m0 0L5.8 7.2M8 5l2.2 2.2" />
    </svg>
  )
}


export function OverviewIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M2.5 3.5h4.5v4.5H2.5zM9 3.5h4.5v4.5H9zM2.5 10h4.5v2.5H2.5zM9 10h4.5v2.5H9z" />
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

export function ArrowRightIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M3 8h10m0 0L9 4m4 4-4 4" />
    </svg>
  )
}

export function CopyIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <rect x="5.5" y="5.5" width="8" height="8" rx="1.5" />
      <path d="M10.5 5.5v-2a1 1 0 0 0-1-1h-6a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2" />
    </svg>
  )
}

export function BackIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M10 3 5 8l5 5" />
    </svg>
  )
}

export function CloseIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="m4 4 8 8m0-8-8 8" />
    </svg>
  )
}

export function EditIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <path d="M10.5 3 13 5.5 5.5 13H3v-2.5z" />
    </svg>
  )
}

/** Review state glyphs for a candidate row: open, approved, needs a closer look, rejected. */
export function StateOpenIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
    </svg>
  )
}

export function StateApprovedIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" fill="currentColor" />
      <path d="m5.5 8.2 1.8 1.8 3.2-3.6" stroke="var(--bg)" />
    </svg>
  )
}

export function StateRejectedIcon({ className }: IconProps) {
  return (
    <svg {...base} className={className}>
      <circle cx="8" cy="8" r="6" />
      <path d="m6 6 4 4m0-4-4 4" />
    </svg>
  )
}
