import type { ReactNode } from 'react'
import { IconButton } from '../ui/Button'
import { PanelRightIcon } from '../ui/Icon'
import { useShell } from './ShellContext'

export const INSPECTOR_ID = 'rv-inspector'

/**
 * Holds only what belongs to the current surface. Neither panel toggle lives here: each sits on
 * its own panel's edge, so this bar is just the surface title.
 */
export function TopBar({ title }: { title: string }) {
  return (
    <header className="rv-topbar">
      <span className="rv-topbar__title">{title}</span>
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
        className="rv-edge rv-edge--inspector"
        label="Details panel"
        expanded={!inspectorCollapsed}
        controls={INSPECTOR_ID}
        title={inspectorCollapsed ? 'Show details ]' : 'Hide details ]'}
        onClick={toggleInspector}
      >
        <PanelRightIcon className="rv-icon" />
      </IconButton>
    </aside>
  )
}
