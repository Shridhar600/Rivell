/// <reference types="node" />
import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { join, relative, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { matchRoutes } from 'react-router'
import { appRoutes } from './router'

/**
 * A static audit of every in-app address the product's source can send a reader to. It reads the
 * source, so it covers pages that are hard to render in a test (a Review workspace, a compilation)
 * and fails the day someone links to a page that does not exist or has been retired.
 */

const SRC = resolve(__dirname, '..')
const SKIP_DIRS = new Set(['api', 'test', 'assets', 'styles'])

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return SKIP_DIRS.has(name) ? [] : sourceFiles(path)
    return /\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name) ? [path] : []
  })
}

/** Comments name old pages on purpose, so they are not user-facing addresses. */
function withoutComments(code: string): string {
  return code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:'"`\\])\/\/.*$/gm, '$1')
}

const LITERAL = /(['"`])((?:\\.|(?!\1)[^\\\n])*)\1/g

interface Link {
  file: string
  address: string
  path: string
}

function linksIn(file: string): Link[] {
  const code = withoutComments(readFileSync(file, 'utf8'))
  const found: Link[] = []
  for (const match of code.matchAll(LITERAL)) {
    const address = match[2] ?? ''
    if (!/^\/(reviews|knowledge)([/?#]|$)/.test(address)) continue
    // A template's expressions stand for ids, so a representative id stands in for each.
    const path = address.replace(/\$\{[^}]*\}/g, 'x').split(/[?#]/)[0]!.replace(/\/$/, '') || '/'
    found.push({ file: relative(SRC, file), address, path })
  }
  for (const match of code.matchAll(/\bto=["']\/["']/g)) {
    found.push({ file: relative(SRC, file), address: match[0], path: '/' })
  }
  return found
}

const FILES = sourceFiles(SRC)
const LINKS = FILES.flatMap(linksIn)

describe('route audit', () => {
  it('finds the links it is meant to audit', () => {
    expect(LINKS.length).toBeGreaterThan(40)
    expect(LINKS.some((link) => link.path === '/reviews/new')).toBe(true)
    expect(LINKS.some((link) => link.path.startsWith('/knowledge/compilations/'))).toBe(true)
  })

  it('sends every in-app address to a page the router has', () => {
    const dead = LINKS.filter((link) => matchRoutes(appRoutes, link.path) === null)
    expect(dead.map((link) => `${link.file}: ${link.address}`)).toEqual([])
  })

  it('links to no retired Knowledge page', () => {
    const retired = LINKS.filter((link) => /^\/knowledge\/(new|artifacts)(\/|$)/.test(link.path))
    expect(retired.map((link) => `${link.file}: ${link.address}`)).toEqual([])
  })

  it('has no placeholder link or prototype filename in user-facing code', () => {
    const offenders: string[] = []
    for (const file of FILES) {
      const code = withoutComments(readFileSync(file, 'utf8'))
      if (/\b(href|to)=["']#["']/.test(code)) offenders.push(`${relative(SRC, file)}: placeholder link`)
      if (/['"`][^'"`\n]*rivell-[a-z-]+\.html/.test(code)) offenders.push(`${relative(SRC, file)}: prototype filename`)
      if (/['"`][^'"`\n]*\.html[?#'"`]/.test(code) && !/index\.html/.test(code)) offenders.push(`${relative(SRC, file)}: html address`)
    }
    expect(offenders).toEqual([])
  })

  it('writes the route matrix when asked, for the report', () => {
    const out = process.env.ROUTE_MATRIX_OUT
    if (!out) return
    const rows = [...new Set(LINKS.map((link) => `${link.path}\t${matchRoutes(appRoutes, link.path)?.at(-1)?.route.path ?? '(index)'}\t${link.file}`))].sort()
    writeFileSync(out, `${rows.join('\n')}\n`)
  })
})
