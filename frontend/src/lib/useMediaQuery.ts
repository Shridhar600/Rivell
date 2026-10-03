import { useSyncExternalStore } from 'react'

/**
 * Tracks a CSS media query. Where `matchMedia` does not exist (a test DOM, a server render) the
 * query reads as false, so the wide-screen composition is the default.
 */
export function useMediaQuery(query: string): boolean {
  return useSyncExternalStore(
    (notify) => {
      const list = globalThis.matchMedia?.(query)
      list?.addEventListener('change', notify)
      return () => list?.removeEventListener('change', notify)
    },
    () => globalThis.matchMedia?.(query).matches ?? false,
    () => false,
  )
}
