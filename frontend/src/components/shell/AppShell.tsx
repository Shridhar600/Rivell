import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { matchPath, Outlet, useLocation } from 'react-router'
import {
  INSPECTOR_KEY,
  SIDEBAR_KEY,
  ShellContext,
  readPanelFlag,
  INSPECTOR_WIDTH,
  INSPECTOR_WIDTH_KEY,
  SIDEBAR_WIDTH,
  SIDEBAR_WIDTH_KEY,
  writePanelFlag,
  type PageCommands,
  type ShellState,
} from './ShellContext'
import { GlobalSearch } from './GlobalSearch'
import { readWidth, writeWidth } from '../../lib/panelWidth'
import { Sidebar, SIDEBAR_ID, SidebarToggle } from './Sidebar'
import { useTooltips } from './useTooltips'
import { useMediaQuery } from '../../lib/useMediaQuery'

/**
 * Work surfaces with a list panel of their own. The app sidebar starts closed there, so two
 * sidebars never stand side by side. Opening it lasts until the reader leaves the page.
 */
const WORK_SURFACES = ['/reviews/:contractId/analysis/:reviewId', '/knowledge/compilations/:artifactId']

export function AppShell() {
  const { pathname } = useLocation()
  const onWorkSurface = WORK_SURFACES.some((pattern) => matchPath(pattern, pathname) !== null)
  // The page the reader opened the sidebar on. Leaving it closes the sidebar again.
  const [expandedOn, setExpandedOn] = useState<string | null>(null)
  const workSurfaceExpanded = onWorkSurface && expandedOn === pathname

  const [sidebarCollapsed, setSidebarCollapsed] = useState(() => readPanelFlag(SIDEBAR_KEY, true))
  const [inspectorCollapsed, setInspectorCollapsed] = useState(() => readPanelFlag(INSPECTOR_KEY, true))

  // On a phone the sidebar slides over the page; it closes again when the reader moves on.
  const narrow = useMediaQuery('(max-width: 760px)')
  const [overlayOn, setOverlayOn] = useState<string | null>(null)

  const toggleSidebar = useCallback(() => {
    // Closing the sidebar makes it inert, which drops focus held inside it; the toggle takes it.
    const side = document.getElementById(SIDEBAR_ID)
    if (side?.contains(document.activeElement)) {
      requestAnimationFrame(() => document.querySelector<HTMLElement>('.rv-side-toggle')?.focus())
    }
    if (narrow) {
      setOverlayOn((page) => (page === pathname ? null : pathname))
      return
    }
    if (onWorkSurface) {
      setExpandedOn((page) => (page === pathname ? null : pathname))
      return
    }
    setSidebarCollapsed((value) => {
      writePanelFlag(SIDEBAR_KEY, !value)
      return !value
    })
  }, [narrow, onWorkSurface, pathname])

  const [inspectorMounted, setInspectorMounted] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [readPageCommands, setReadPageCommands] = useState<(() => PageCommands) | null>(null)
  const openSearch = useCallback(() => setSearchOpen(true), [])
  // A function in state must be stored through an updater, or React would call it.
  const setPageCommands = useCallback((read: (() => PageCommands) | null) => setReadPageCommands(() => read), [])
  useSearchShortcut(setSearchOpen)
  useTooltips()

  const [sidebarWidth, setSidebarWidth] = useState(() => readWidth(SIDEBAR_WIDTH_KEY, SIDEBAR_WIDTH))
  const resizeSidebar = useCallback((width: number) => {
    setSidebarWidth(width)
    writeWidth(SIDEBAR_WIDTH_KEY, width)
  }, [])
  const [inspectorWidth, setInspectorWidth] = useState(() => readWidth(INSPECTOR_WIDTH_KEY, INSPECTOR_WIDTH))
  const resizeInspector = useCallback((width: number) => {
    setInspectorWidth(width)
    writeWidth(INSPECTOR_WIDTH_KEY, width)
  }, [])

  // Below 1180px the details panel opens over the page instead of taking a column. It starts closed
  // there and closes when the reader moves on; the saved preference applies again on a wide window.
  const compactInspector = useMediaQuery('(max-width: 1180px)')
  const [inspectorOverlayOn, setInspectorOverlayOn] = useState<string | null>(null)
  const toggleInspector = useCallback(() => {
    if (compactInspector) {
      setInspectorOverlayOn((page) => (page === pathname ? null : pathname))
      return
    }
    setInspectorCollapsed((value) => {
      writePanelFlag(INSPECTOR_KEY, !value)
      return !value
    })
  }, [compactInspector, pathname])
  const inspectorClosed = compactInspector ? inspectorOverlayOn !== pathname : inspectorCollapsed

  // Panels opened for one page close when the reader leaves it, so coming back starts fresh.
  const [visited, setVisited] = useState(pathname)
  if (visited !== pathname) {
    setVisited(pathname)
    setExpandedOn(null)
    setOverlayOn(null)
    setInspectorOverlayOn(null)
  }

  // The saved preference is left alone on a phone and on work surfaces, and applies again elsewhere.
  const railed = narrow ? overlayOn !== pathname : onWorkSurface ? !workSurfaceExpanded : sidebarCollapsed

  usePanelShortcut(toggleSidebar, toggleInspector)
  useEscapeToClose(narrow && !railed, toggleSidebar)

  const state = useMemo<ShellState>(
    () => ({ sidebarCollapsed: railed, toggleSidebar, inspectorCollapsed: inspectorClosed, inspectorOverlay: compactInspector, toggleInspector, inspectorMounted, setInspectorMounted, inspectorWidth, resizeInspector, searchOpen, openSearch, setPageCommands }),
    [railed, toggleSidebar, inspectorClosed, compactInspector, toggleInspector, inspectorMounted, setInspectorMounted, inspectorWidth, resizeInspector, searchOpen, openSearch, setPageCommands],
  )

  return (
    <ShellContext value={state}>
      <div
        className="rv-shell"
        style={{ '--rv-sidebar-open': `${sidebarWidth}px`, '--rv-w-inspector': `${inspectorWidth}px` } as CSSProperties}
        data-sidebar={railed ? 'collapsed' : undefined}
        data-inspector={inspectorClosed ? 'collapsed' : undefined}
      >
        <SidebarToggle collapsed={railed} onToggle={toggleSidebar} />
        <Sidebar collapsed={railed} width={sidebarWidth} onResize={resizeSidebar} />
        {/* On a phone the open sidebar covers the page; a tap beside it closes it. */}
        {narrow && !railed ? <div className="rv-scrim" aria-hidden="true" onClick={toggleSidebar} /> : null}
        {/* While the phone drawer is open it is modal: the page behind it is out of reach. */}
        <div className="rv-stage" inert={narrow && !railed}>
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
      // Never behind an open dialog (search, Add knowledge, Finalize).
      if (document.querySelector('dialog[open]')) return
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
        // Search never stacks on top of another dialog.
        if (document.querySelector('dialog[open]:not(.rv-cmd)')) return
        event.preventDefault()
        setOpen((open) => !open)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [setOpen])
}

function useEscapeToClose(open: boolean, close: () => void) {
  useEffect(() => {
    if (!open) return
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') close()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, close])
}
