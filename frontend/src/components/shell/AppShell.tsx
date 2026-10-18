import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { matchPath, Outlet, useLocation } from 'react-router'
import {
  INSPECTOR_KEY,
  SIDEBAR_KEY,
  ShellContext,
  readPanelFlag,
  readSidebarWidth,
  writePanelFlag,
  writeSidebarWidth,
  type PageCommands,
  type ShellState,
} from './ShellContext'
import { GlobalSearch } from './GlobalSearch'
import { Sidebar } from './Sidebar'
import { useMediaQuery } from '../../lib/useMediaQuery'

/**
 * Work surfaces with a list panel of their own. The app sidebar starts as the icon rail there, so
 * two full sidebars never stand side by side. Expanding it lasts until the reader leaves.
 */
const WORK_SURFACES = ['/reviews/:contractId/analysis/:reviewId', '/knowledge/compilations/:artifactId']

export function AppShell() {
  const { pathname } = useLocation()
  const onWorkSurface = WORK_SURFACES.some((pattern) => matchPath(pattern, pathname) !== null)
  // The page the reader expanded the sidebar on. Leaving it brings the rail back.
  const [expandedOn, setExpandedOn] = useState<string | null>(null)
  const workSurfaceExpanded = onWorkSurface && expandedOn === pathname

  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => readPanelFlag(SIDEBAR_KEY, true))
  const [inspectorCollapsed, setInspectorCollapsed] = useState(() => readPanelFlag(INSPECTOR_KEY, true))

  const toggleSidebar = useCallback(() => {
    if (onWorkSurface) {
      setExpandedOn((page) => (page === pathname ? null : pathname))
      return
    }
    setSidebarCollapsed((value) => {
      writePanelFlag(SIDEBAR_KEY, !value)
      return !value
    })
  }, [onWorkSurface, pathname])

  const [searchOpen, setSearchOpen] = useState(false)
  const [readPageCommands, setReadPageCommands] = useState<(() => PageCommands) | null>(null)
  const openSearch = useCallback(() => setSearchOpen(true), [])
  // A function in state must be stored through an updater, or React would call it.
  const setPageCommands = useCallback((read: (() => PageCommands) | null) => setReadPageCommands(() => read), [])
  useSearchShortcut(setSearchOpen)

  const [sidebarWidth, setSidebarWidth] = useState(readSidebarWidth)
  const [resizing, setResizing] = useState(false)
  const resizeSidebar = useCallback((width: number) => {
    setSidebarWidth(width)
    writeSidebarWidth(width)
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
  const railed = narrow || (onWorkSurface ? !workSurfaceExpanded : sidebarCollapsed)

  usePanelShortcut(narrow ? () => {} : toggleSidebar, toggleInspector)

  const state = useMemo<ShellState>(
    () => ({ sidebarCollapsed: railed, toggleSidebar, inspectorCollapsed, toggleInspector, searchOpen, openSearch, setPageCommands }),
    [railed, toggleSidebar, inspectorCollapsed, toggleInspector, searchOpen, openSearch, setPageCommands],
  )

  return (
    <ShellContext value={state}>
      <div
        className="rv-shell"
        style={{ '--rv-sidebar-open': `${sidebarWidth}px` } as CSSProperties}
        data-sidebar={railed ? 'collapsed' : undefined}
        data-inspector={inspectorCollapsed ? 'collapsed' : undefined}
        data-resizing={resizing ? '' : undefined}
      >
        <Sidebar collapsed={railed} onToggle={toggleSidebar} width={sidebarWidth} onResize={resizeSidebar} onResizing={setResizing} />
        <div className="rv-stage">
          <Outlet />
        </div>
        {searchOpen ? <GlobalSearch page={readPageCommands?.() ?? null} onClose={() => setSearchOpen(false)} /> : null}
      </div>
    </ShellContext>
  )
}

function usePanelShortcut(toggleSidebar: () => void, toggleInspector: () => void) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return
      // A page with panels of its own (the review workspace, a compilation) uses [ and ] for them.
      if (document.querySelector('[data-panel-keys="own"]')) return
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

function useSearchShortcut(setOpen: (update: (open: boolean) => boolean) => void) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && !event.altKey && event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setOpen((open) => !open)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [setOpen])
}
