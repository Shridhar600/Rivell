import { createContext, use, useEffect, useRef } from 'react'
import type { PaletteItem } from '../ui/CommandPalette'

/** What the open page adds to the search menu. */
export interface PageCommands {
  items: PaletteItem[]
  onSearch?: (term: string) => void
}

export interface ShellState {
  sidebarCollapsed: boolean
  toggleSidebar: () => void
  inspectorCollapsed: boolean
  toggleInspector: () => void
  /** Whether the open page shows a details panel. */
  inspectorMounted: boolean
  setInspectorMounted: (mounted: boolean) => void
  inspectorWidth: number
  resizeInspector: (width: number) => void
  searchOpen: boolean
  openSearch: () => void
  /** The open page registers a reader for its commands; the menu reads it when it opens. */
  setPageCommands: (read: (() => PageCommands) | null) => void
}

export const ShellContext = createContext<ShellState | null>(null)

export function useShell(): ShellState {
  const state = use(ShellContext)
  if (!state) throw new Error('useShell must be used inside AppShell')
  return state
}

/**
 * Adds the page's own commands to the search menu while the page is mounted. `build` runs when the
 * menu opens, so it always sees the page's current state.
 */
export function usePageCommands(build: () => PageCommands): void {
  const { setPageCommands } = useShell()
  const latest = useRef(build)
  useEffect(() => {
    latest.current = build
  })
  useEffect(() => {
    setPageCommands(() => latest.current())
    return () => setPageCommands(null)
  }, [setPageCommands])
}

export const SIDEBAR_KEY = 'rv-sidebar'
export const INSPECTOR_KEY = 'rv-inspector'

/** Panel state is per browser, and a storage failure must never break the shell. */
export function readPanelFlag(key: string, collapsed: boolean): boolean {
  try {
    return globalThis.localStorage?.getItem(key) === (collapsed ? 'c' : '')
  } catch {
    return false
  }
}

export function writePanelFlag(key: string, collapsed: boolean): void {
  try {
    globalThis.localStorage?.setItem(key, collapsed ? 'c' : '')
  } catch {
    // Ignored on purpose: a blocked storage quota must not break navigation.
  }
}

export const SIDEBAR_WIDTH_KEY = 'rv-sidebar-width'
export const SIDEBAR_WIDTH = { min: 184, max: 320, initial: 220 } as const
export const INSPECTOR_WIDTH_KEY = 'rv-inspector-width'
export const INSPECTOR_WIDTH = { min: 280, max: 560, initial: 340 } as const
