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
