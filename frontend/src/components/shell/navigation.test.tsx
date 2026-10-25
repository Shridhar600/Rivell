import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, matchRoutes } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../../app/router'
import { allNavigationTargets, CREATE_ACTIONS, isActive, NAV_GROUPS } from './navigation'

function stubApi() {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL((input instanceof Request ? input : new Request(String(input))).url)
      const data = url.pathname === '/api/reviews' ? { items: [], truncated: false } : []
      return new Response(JSON.stringify({ data, status: 200, timestamp: '2026-10-04T09:00:00Z' }))
    }),
  )
}

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

const primary = () => within(screen.getByRole('navigation', { name: 'Primary' }))
const knowledgeNav = () => screen.queryByRole('navigation', { name: 'Knowledge' })
const sidebar = () => screen.getByRole('complementary', { name: 'Sidebar' })

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

const SIDEBAR_LINKS = [
  ['Home', '/'],
  ['Reviews', '/reviews'],
  ['Overview', '/knowledge'],
  ['Playbooks', '/knowledge/playbooks'],
  ['Clause library', '/knowledge/clauses'],
  ['Sources', '/knowledge/sources'],
]

const navLinks = () => within(sidebar()).getAllByRole('link').filter((link) => link.className.includes('rv-nav__link'))

describe('navigation model', () => {
  it('defines a heading-less top level and one Knowledge group', () => {
    expect(NAV_GROUPS.map((group) => [group.id, group.heading])).toEqual([
      ['work', undefined],
      ['knowledge', 'Knowledge'],
    ])
    expect(NAV_GROUPS.flatMap((group) => group.items.map((item) => [item.label, item.to]))).toEqual(SIDEBAR_LINKS)
  })

  it('points every destination at a page the router really has', () => {
    for (const target of allNavigationTargets()) {
      expect(matchRoutes(appRoutes, target), target).not.toBeNull()
    }
    for (const target of Object.values(CREATE_ACTIONS)) {
      expect(matchRoutes(appRoutes, target.split('?')[0]!), target).not.toBeNull()
    }
  })

  it('uses the real creation addresses', () => {
    expect(CREATE_ACTIONS.startReview).toBe('/reviews/new')
    expect(CREATE_ACTIONS.addKnowledge).toBe('/knowledge?add=1')
  })

  it('decides activity by path prefix, and by exact path where asked', () => {
    const item = (id: string) => NAV_GROUPS.flatMap((group) => group.items).find((entry) => entry.id === id)!
    expect(isActive(item('home'), '/')).toBe(true)
    expect(isActive(item('home'), '/reviews')).toBe(false)
    expect(isActive(item('reviews'), '/reviews/c1/overview/r1')).toBe(true)
    expect(isActive(item('reviews'), '/knowledge')).toBe(false)
    expect(isActive(item('knowledge-overview'), '/knowledge/sources/a1')).toBe(false)
    expect(isActive(item('knowledge-sources'), '/knowledge/sources/a1')).toBe(true)
    expect(isActive(item('knowledge-sources'), '/knowledge/sourcesx')).toBe(false)
  })
})

describe('sidebar', () => {
  it.each(['/', '/reviews', '/reviews/new', '/knowledge', '/knowledge/playbooks'])(
    'shows the same destinations, in the same order, on %s',
    (path) => {
      stubApi()
      renderAt(path)
      expect(navLinks().map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual(SIDEBAR_LINKS)
      expect(primary().getAllByRole('link').map((link) => link.textContent?.trim())).toEqual(['Home', 'Reviews'])
      expect(within(knowledgeNav()!).getAllByRole('link')).toHaveLength(4)
    },
  )

  it.each([
    ['/', 'Home'],
    ['/reviews', 'Reviews'],
    ['/reviews/new', 'Reviews'],
    ['/reviews/con-1/overview/rev-1', 'Reviews'],
    ['/knowledge', 'Overview'],
    ['/knowledge/playbooks/a1', 'Playbooks'],
    ['/knowledge/clauses/LIMITATION_OF_LIABILITY', 'Clause library'],
    ['/knowledge/sources/a1', 'Sources'],
  ])('marks exactly one destination current on %s: %s', (path, current) => {
    stubApi()
    renderAt(path)
    const marked = navLinks().filter((link) => link.getAttribute('aria-current') === 'page')
    expect(marked.map((link) => link.textContent?.trim())).toEqual([current])
  })

  it('offers no object-specific or creation destination as navigation', () => {
    stubApi()
    renderAt('/knowledge')
    const text = sidebar().textContent ?? ''
    for (const forbidden of [/workspace/i, /compilation/i, /add knowledge/i, /start (a )?review/i, /playbook:/i]) {
      expect(text).not.toMatch(forbidden)
    }
    const hrefs = within(sidebar()).getAllByRole('link').map((link) => link.getAttribute('href'))
    expect(hrefs.filter((href) => /compilations|\/new|overview\/|analysis\/|\?add=/.test(href ?? ''))).toEqual([])
  })

  it('adds no lifecycle filters under Reviews', () => {
    stubApi()
    renderAt('/reviews')
    for (const name of [/active/i, /completed/i, /failed/i, /finalized/i, /needs you/i, /running/i]) {
      expect(within(sidebar()).queryByRole('link', { name })).not.toBeInTheDocument()
    }
  })

  it('invents no workspace, account, settings, identity or search', () => {
    stubApi()
    renderAt('/')
    const side = sidebar()
    expect(side.textContent).not.toMatch(/workspace|account|settings|signed-in|reviewer|organi[sz]ation|jump to|⌘k/i)
    expect(within(side).queryByRole('button', { name: /account|profile|settings|search|command/i })).not.toBeInTheDocument()
    expect(within(side).queryByRole('searchbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('keeps a closed sidebar out of reach until it is opened again', () => {
    globalThis.localStorage?.setItem('rv-sidebar', 'c')
    stubApi()
    const { container } = renderAt('/knowledge')
    expect(container.querySelector('#rv-sidebar')).toHaveAttribute('inert')
  })

  it('links the brand to Home', () => {
    stubApi()
    renderAt('/reviews')
    expect(within(sidebar()).getByRole('link', { name: 'Rivell' })).toHaveAttribute('href', '/')
  })
})
