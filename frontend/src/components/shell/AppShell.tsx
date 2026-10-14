import { useCallback, useEffect, useMemo, useState } from 'react'
import { Outlet } from 'react-router'
import {
  INSPECTOR_KEY,
  SIDEBAR_KEY,
  ShellContext,
  readPanelFlag,
  writePanelFlag,
  type ShellState,
} from './ShellContext'
import { Sidebar } from './Sidebar'
import { useMediaQuery } from '../../lib/useMediaQuery'

export function AppShell() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => readPanelFlag(SIDEBAR_KEY, true))
  const [inspectorCollapsed, setInspectorCollapsed] = useState(() => readPanelFlag(INSPECTOR_KEY, true))

  const toggleSidebar = useCallback(() => {
    setSidebarCollapsed((value) => {
      writePanelFlag(SIDEBAR_KEY, !value)
      return !value
    })
  }, [])

  const toggleInspector = useCallback(() => {
    setInspectorCollapsed((value) => {
      writePanelFlag(INSPECTOR_KEY, !value)
      return !value
    })
  }, [])

  // Below this width the sidebar is the icon rail and cannot be expanded, so navigation stays
  // reachable. The saved preference is left alone, and returns when the window widens again.
  const narrow = useMediaQuery('(max-width: 760px)')
  const railed = sidebarCollapsed || narrow

  usePanelShortcut(narrow ? () => {} : toggleSidebar, toggleInspector)

  const state = useMemo<ShellState>(
    () => ({ sidebarCollapsed, toggleSidebar, inspectorCollapsed, toggleInspector }),
    [sidebarCollapsed, toggleSidebar, inspectorCollapsed, toggleInspector],
  )

  return (
    <ShellContext value={state}>
      <div className="rv-shell" data-sidebar={railed ? 'collapsed' : undefined} data-inspector={inspectorCollapsed ? 'collapsed' : undefined}>
        <Sidebar collapsed={railed} onToggle={toggleSidebar} />
        <Outlet />
      </div>
    </ShellContext>
  )
}

function usePanelShortcut(toggleSidebar: () => void, toggleInspector: () => void) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
      if (event.metaKey || event.ctrlKey || event.altKey) return
      if (event.key === '[') {
        event.preventDefault()
        toggleSidebar()
      } else if (event.key === ']') {
        event.preventDefault()
        toggleInspector()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [toggleSidebar, toggleInspector])
}
