import type { ReactNode } from 'react'
import { IconButton } from '../ui/Button'
import { EdgeChevron } from '../ui/Icon'
import { useShell } from './ShellContext'

export const INSPECTOR_ID = 'rv-inspector'

interface TopBarProps {
  /** A page name, or a breadcrumb trail for a page one level down. */
  title: ReactNode
  /** Real, working controls for this surface. Nothing decorative: no search, no identity. */
  actions?: ReactNode
}

/**
 * Holds only what belongs to the current surface: its title and its own actions. Neither panel
 * toggle lives here: each sits with its own panel.
 */
export function TopBar({ title, actions }: TopBarProps) {
  return (
    <header className="rv-topbar">
      {typeof title === 'string' ? (
        <span className="rv-topbar__title">{title}</span>
      ) : (
        <div className="rv-topbar__title">{title}</div>
      )}
      {actions ? <div className="rv-topbar__actions">{actions}</div> : null}
    </header>
  )
}

interface InspectorProps {
  /** One short line announced when the selection changes. Never the whole panel. */
  status: string
  children: ReactNode
}

export function Inspector({ status, children }: InspectorProps) {
  const { inspectorCollapsed, toggleInspector } = useShell()
  return (
    <aside className="rv-insp" id={INSPECTOR_ID} aria-label="Details">
      {/*
        Selection changes are announced through one short status line, not by making the whole
        panel a live region: arrowing down a list would otherwise read out every node.
      */}
      {status ? (
        <p className="rv-visually-hidden" role="status">
          {status}
        </p>
      ) : null}
      <div className="rv-insp__inner">{children}</div>
      <IconButton
        className={`rv-edge rv-edge--inspector${inspectorCollapsed ? ' rv-edge--open' : ''}`}
        label="Details panel"
        expanded={!inspectorCollapsed}
        controls={INSPECTOR_ID}
        title={inspectorCollapsed ? 'Show details ]' : 'Hide details ]'}
        onClick={toggleInspector}
      >
        <EdgeChevron className="rv-icon" />
      </IconButton>
    </aside>
  )
}
