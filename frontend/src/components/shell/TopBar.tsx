import type { ReactNode } from 'react'
import { IconButton } from '../ui/Button'
import { PanelRightIcon } from '../ui/Icon'
import { useShell } from './ShellContext'

interface TopBarProps {
  title: string
  /** Rendered only on surfaces that actually have an inspector to toggle. */
  hasInspector?: boolean
}

/**
 * Holds only what belongs to the whole surface or the current page. The sidebar toggle lives on
 * the sidebar, so it is deliberately absent here.
 */
export function TopBar({ title, hasInspector = false }: TopBarProps) {
  const { inspectorCollapsed, toggleInspector } = useShell()
  return (
    <header className="rv-topbar">
      <span className="rv-topbar__title">{title}</span>
      <span className="rv-topbar__spacer" />
      {hasInspector ? (
        <IconButton
          label={inspectorCollapsed ? 'Show details panel' : 'Hide details panel'}
          pressed={!inspectorCollapsed}
          onClick={toggleInspector}
        >
          <PanelRightIcon className="rv-icon" />
        </IconButton>
      ) : null}
    </header>
  )
}

export function Inspector({ children }: { children: ReactNode }) {
  return (
    <aside className="rv-insp" aria-label="Details">
      {/* Bounded live region for selection changes; the page itself is never a live region. */}
      <div className="rv-insp__inner" aria-live="polite">
        {children}
      </div>
    </aside>
  )
}