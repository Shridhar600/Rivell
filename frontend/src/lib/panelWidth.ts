export interface WidthLimits {
  min: number
  max: number
  initial: number
}

export function clampWidth(width: number, limits: WidthLimits): number {
  return Math.round(Math.min(limits.max, Math.max(limits.min, width)))
}

/** A saved panel width, clamped, or the panel's initial width. Storage failures never break a page. */
export function readWidth(key: string, limits: WidthLimits): number {
  try {
    const saved = Number(globalThis.localStorage?.getItem(key))
    return saved ? clampWidth(saved, limits) : limits.initial
  } catch {
    return limits.initial
  }
}

export function writeWidth(key: string, width: number): void {
  try {
    globalThis.localStorage?.setItem(key, String(width))
  } catch {
    // Ignored on purpose: a blocked storage quota must not break the page.
  }
}
