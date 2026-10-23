import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b-compile.css'), 'utf8')

/** The declarations of every rule whose selector is exactly `selector`. */
function rules(selector: string): string[] {
  const out: string[] = []
  const pattern = new RegExp(`(^|\\n)\\s*${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`, 'g')
  for (const match of css.matchAll(pattern)) out.push(match[2] ?? '')
  return out
}

describe('the draft entry form', () => {
  it('never turns the full-width arrow box, which would lay an invisible strip over the form', () => {
    for (const body of rules('.rv-kc-xarrow')) expect(body).not.toMatch(/transform/)
  })

  it('lets clicks pass through the arrow between the two cards', () => {
    expect(rules('.rv-kc-xarrow').some((body) => /pointer-events:\s*none/.test(body))).toBe(true)
  })
})
