import { use, type ReactNode } from 'react'
import { IconButton } from '../ui/Button'
import { EdgeChevron } from '../ui/Icon'
import { SearchIcon } from '../workspace/WorkspaceIcons'
import { PanelResizeHandle } from './PanelResizeHandle'
import { INSPECTOR_WIDTH, ShellContext, useShell } from './ShellContext'

export const INSPECTOR_ID = 'rv-inspector'

interface TopBarProps {
  /** A page name, or a breadcrumb trail for a page one level down. */
  title: ReactNode
  /** A control that sits before the title, such as the toggle for the page's own list panel. */
  leading?: ReactNode
  /** Real, working controls for this surface. */
  actions?: ReactNode
}

/**
 * The same bar on every page: where you are on the left, search in the middle, this page's own
 * actions on the right. Neither shell panel toggle lives here: each sits with its own panel.
 */
export function TopBar({ title, leading, actions }: TopBarProps) {
  return (
    <header className="rv-topbar">
      <div className="rv-topbar__lead">
        {leading}
        {typeof title === 'string' ? (
          <span className="rv-topbar__title">{title}</span>
        ) : (
          <div className="rv-topbar__title">{title}</div>
        )}
      </div>
      <SearchButton />
      <div className="rv-topbar__actions">{actions}</div>
    </header>
  )
}

export function SearchButton() {
  // Outside the shell (a component rendered on its own) there is no search menu to open.
  const shell = use(ShellContext)
  if (!shell) return <span />
  const { openSearch } = shell
  return (
    <button type="button" className="rv-topbar__search" aria-label="Search" aria-keyshortcuts="Meta+K" onClick={openSearch}>
      <SearchIcon />
      <span>Search</span>
      <kbd>⌘K</kbd>
    </button>
  )
}

interface InspectorProps {
  /** One short line announced when the selection changes. Never the whole panel. */
  status: string
  children: ReactNode
}

export function Inspector({ status, children }: InspectorProps) {
  const { inspectorCollapsed, toggleInspector, inspectorWidth, resizeInspector } = useShell()
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
      {inspectorCollapsed ? null : (
        <PanelResizeHandle className="rv-insp__resize" label="Resize details panel" edge="left" width={inspectorWidth} limits={INSPECTOR_WIDTH} onResize={resizeInspector} />
      )}
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
