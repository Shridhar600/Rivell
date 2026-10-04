import type { KeyboardEvent } from 'react'

/**
 * Arrow keys, Home and End on a tab move to another tab and select it, as in the ARIA tabs
 * pattern. The selected tab is the only one in the tab order, so this is how the others are reached.
 * Put it on each tab, so the handler sits on a focusable element.
 */
export function onTabKeys(event: KeyboardEvent<HTMLElement>) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  const tabs = Array.from(event.currentTarget.parentElement?.querySelectorAll<HTMLElement>('[role="tab"]') ?? [])
  const current = tabs.indexOf(event.currentTarget)
  if (current < 0) return
  event.preventDefault()
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? tabs.length - 1
        : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
  const target = tabs[next]
  target?.focus()
  target?.click()
}
