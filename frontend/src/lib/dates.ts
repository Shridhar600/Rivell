/**
 * Dates are rendered from real API timestamps only. No relative guesses ("2m", "just now"): a
 * relative time needs a timezone and a reference clock, and the prototype invented both.
 */

function parse(value: string): Date | null {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

/** Short form for list rows, e.g. "2 Oct". Empty when the timestamp is unusable. */
export function shortDate(value: string): string {
  const date = parse(value)
  if (!date) return ''
  return date.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })
}

/** Long form for a details panel, e.g. "2 Oct 2026". Empty when the timestamp is unusable. */
export function longDate(value: string): string {
  const date = parse(value)
  if (!date) return ''
  return date.toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' })
}

/** Date and time for a moment the browser itself observed, e.g. "3 Oct, 14:20". */
export function dateTime(value: string): string {
  const date = parse(value)
  if (!date) return ''
  return date.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

const TIME = { hour: '2-digit', minute: '2-digit' } as const

/**
 * Review-table time, e.g. "Today, 09:12", "Yesterday, 16:40", "Tue, 15:10", "27 Sept, 08:43".
 * Relative to the browser's own clock, so it is exact rather than a guess. `now` is injectable
 * for tests. Empty when the timestamp is unusable.
 */
export function rowDate(value: string, now: Date = new Date()): string {
  const date = parse(value)
  if (!date) return ''
  const time = date.toLocaleTimeString(undefined, TIME)
  const days = Math.round(
    (new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime() -
      new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()) /
      86_400_000,
  )
  if (days === 0) return `Today, ${time}`
  if (days === 1) return `Yesterday, ${time}`
  if (days > 1 && days < 7) return `${date.toLocaleDateString(undefined, { weekday: 'short' })}, ${time}`
  return `${shortDate(value)}, ${time}`
}
