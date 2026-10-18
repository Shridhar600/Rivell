import { createContext, use } from 'react'

export interface ShellState {
  sidebarCollapsed: boolean
  toggleSidebar: () => void
  inspectorCollapsed: boolean
  toggleInspector: () => void
}

export const ShellContext = createContext<ShellState | null>(null)

export function useShell(): ShellState {
  const state = use(ShellContext)
  if (!state) throw new Error('useShell must be used inside AppShell')
  return state
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

const SIDEBAR_WIDTH_KEY = 'rv-sidebar-width'
export const SIDEBAR_WIDTH = { min: 184, max: 320, initial: 220 } as const

export function clampSidebarWidth(width: number): number {
  return Math.round(Math.min(SIDEBAR_WIDTH.max, Math.max(SIDEBAR_WIDTH.min, width)))
}

export function readSidebarWidth(): number {
  try {
    const saved = Number(globalThis.localStorage?.getItem(SIDEBAR_WIDTH_KEY))
    return saved ? clampSidebarWidth(saved) : SIDEBAR_WIDTH.initial
  } catch {
    return SIDEBAR_WIDTH.initial
  }
}

export function writeSidebarWidth(width: number): void {
  try {
    globalThis.localStorage?.setItem(SIDEBAR_WIDTH_KEY, String(width))
  } catch {
    // Ignored on purpose, as for the panel flags.
  }
}
