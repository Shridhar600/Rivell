import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Link } from 'react-router'

type Variant = 'default' | 'primary'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant
  compact?: boolean
  children: ReactNode
}

export function Button({ variant = 'default', compact, className, children, ...rest }: ButtonProps) {
  const classes = ['rv-btn', variant === 'primary' && 'rv-btn--primary', compact && 'rv-btn--compact', className]
    .filter(Boolean)
    .join(' ')
  return (
    <button type="button" className={classes} {...rest}>
      {children}
    </button>
  )
}

interface LinkButtonProps {
  to: string
  variant?: Variant
  compact?: boolean
  className?: string
  children: ReactNode
}

export function LinkButton({ to, variant = 'default', compact, className, children }: LinkButtonProps) {
  const classes = ['rv-btn', variant === 'primary' && 'rv-btn--primary', compact && 'rv-btn--compact', className]
    .filter(Boolean)
    .join(' ')
  return (
    <Link to={to} className={classes}>
      {children}
    </Link>
  )
}

interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Required: an icon alone carries no accessible name. */
  label: string
  pressed?: boolean
  children: ReactNode
}

export function IconButton({ label, pressed, children, className, ...rest }: IconButtonProps) {
  const classes = ['rv-iconbtn', className].filter(Boolean).join(' ')
  return (
    <button type="button" className={classes} aria-label={label} aria-pressed={pressed} {...rest}>
      {children}
    </button>
  )
}