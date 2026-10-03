import type { ReactNode } from 'react'

/** The glyphs the canonical review workspace uses that the shared icon set does not have. */
interface IconProps {
  className?: string
}

function Svg({ className = 'rv-icon', children }: IconProps & { children: ReactNode }) {
  return (
    <svg viewBox="0 0 16 16" aria-hidden="true" focusable="false" className={className}>
      {children}
    </svg>
  )
}

export function BackIcon(props: IconProps) {
  return <Svg {...props}><path d="M10 3 5 8l5 5" /></Svg>
}
export function CheckIcon(props: IconProps) {
  return <Svg {...props}><path d="m3.5 8.3 3 3 6-6.5" /></Svg>
}
export function EditIcon(props: IconProps) {
  return <Svg {...props}><path d="M10.5 3 13 5.5 5.5 13H3v-2.5z" /></Svg>
}
export function CloseIcon(props: IconProps) {
  return <Svg {...props}><path d="m4 4 8 8m0-8-8 8" /></Svg>
}
export function EscalateIcon(props: IconProps) {
  return <Svg {...props}><path d="M8 13V3m0 0L4.5 6.5M8 3l3.5 3.5" /></Svg>
}
export function LockIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="3.5" y="7" width="9" height="6.5" rx="1.5" />
      <path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" />
    </Svg>
  )
}
export function SearchIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="7" cy="7" r="4.5" />
      <path d="m10.5 10.5 3 3" />
    </Svg>
  )
}
export function ClauseIcon(props: IconProps) {
  return <Svg {...props}><path d="M3 4h10M3 7.5h10M3 11h6" /></Svg>
}

/** Finding-state glyphs: open, accepted or edited, escalated, rejected. */
export function GlyphOpen(props: IconProps) {
  return <Svg {...props}><circle cx="8" cy="8" r="6" /></Svg>
}
export function GlyphResolved(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6" fill="currentColor" />
      <path d="m5.5 8.2 1.8 1.8 3.2-3.6" stroke="var(--bg)" />
    </Svg>
  )
}
export function GlyphEscalated(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 11.5V5m0 0L5.8 7.2M8 5l2.2 2.2" />
    </Svg>
  )
}
export function GlyphRejected(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="6" />
      <path d="m6 6 4 4m0-4-4 4" />
    </Svg>
  )
}
