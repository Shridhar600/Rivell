import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter, matchRoutes } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../../app/router'
import { allNavigationTargets, CREATE_ACTIONS, isActive, openSections, TOP_LEVEL_NAVIGATION } from './navigation'

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

describe('navigation model', () => {
  it('defines exactly Home, Reviews and Knowledge at the top level', () => {
    expect(TOP_LEVEL_NAVIGATION.map((item) => [item.label, item.to])).toEqual([
      ['Home', '/'],
      ['Reviews', '/reviews'],
      ['Knowledge', '/knowledge'],
    ])
  })

  it('gives only Knowledge contextual sections, and they are the four Knowledge areas', () => {
    expect(TOP_LEVEL_NAVIGATION.filter((item) => item.children).map((item) => item.id)).toEqual(['knowledge'])
    const knowledge = TOP_LEVEL_NAVIGATION.find((item) => item.id === 'knowledge')
    expect(knowledge?.children?.map((child) => [child.label, child.to])).toEqual([
      ['Overview', '/knowledge'],
      ['Playbooks', '/knowledge/playbooks'],
      ['Clause library', '/knowledge/clauses'],
      ['Sources', '/knowledge/sources'],
    ])
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
    const [home, reviews, knowledge] = TOP_LEVEL_NAVIGATION as [(typeof TOP_LEVEL_NAVIGATION)[number], ...(typeof TOP_LEVEL_NAVIGATION)[number][]]
    expect(isActive(home, '/')).toBe(true)
    expect(isActive(home, '/reviews')).toBe(false)
    expect(isActive(reviews!, '/reviews/c1/overview/r1')).toBe(true)
    expect(isActive(reviews!, '/knowledge')).toBe(false)
    expect(isActive(knowledge!, '/knowledge/sources/a1')).toBe(true)
    expect(isActive(knowledge!, '/knowledgebase')).toBe(false)
  })

  it('opens Knowledge sections only inside Knowledge', () => {
    expect(openSections('/').map((item) => item.id)).toEqual([])
    expect(openSections('/reviews').map((item) => item.id)).toEqual([])
    expect(openSections('/knowledge/playbooks/a1').map((item) => item.id)).toEqual(['knowledge'])
  })
})

describe('sidebar', () => {
  it.each(['/', '/reviews', '/reviews/new', '/knowledge', '/knowledge/playbooks'])(
    'shows the same three destinations on %s',
    (path) => {
      stubApi()
      renderAt(path)
      const links = primary().getAllByRole('link')
      expect(links.map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
        ['Home', '/'],
        ['Reviews', '/reviews'],
        ['Knowledge', '/knowledge'],
      ])
    },
  )

  it.each([
    ['/', 'Home'],
    ['/reviews', 'Reviews'],
    ['/reviews/new', 'Reviews'],
    ['/reviews/con-1/overview/rev-1', 'Reviews'],
    ['/knowledge', 'Knowledge'],
    ['/knowledge/clauses/LIMITATION_OF_LIABILITY', 'Knowledge'],
    ['/knowledge/sources/a1', 'Knowledge'],
  ])('marks exactly one destination current on %s: %s', (path, current) => {
    stubApi()
    renderAt(path)
    const marked = primary().getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
    expect(marked.map((link) => link.textContent?.trim())).toEqual([current])
  })

  it.each(['/', '/reviews', '/reviews/new', '/reviews/con-1/overview/rev-1'])(
    'carries no Knowledge sections outside Knowledge, on %s',
    (path) => {
      stubApi()
      renderAt(path)
      expect(knowledgeNav()).not.toBeInTheDocument()
      for (const name of ['Overview', 'Playbooks', 'Clause library', 'Sources']) {
        expect(within(sidebar()).queryByRole('link', { name })).not.toBeInTheDocument()
      }
    },
  )

  it.each([
    ['/knowledge', 'Overview'],
    ['/knowledge/playbooks', 'Playbooks'],
    ['/knowledge/playbooks/a1', 'Playbooks'],
    ['/knowledge/clauses', 'Clause library'],
    ['/knowledge/sources', 'Sources'],
  ])('shows the four Knowledge sections inside Knowledge, current on %s: %s', (path, current) => {
    stubApi()
    renderAt(path)
    const section = within(knowledgeNav()!)
    expect(section.getAllByRole('link').map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['Overview', '/knowledge'],
      ['Playbooks', '/knowledge/playbooks'],
      ['Clause library', '/knowledge/clauses'],
      ['Sources', '/knowledge/sources'],
    ])
    const marked = section.getAllByRole('link').filter((link) => link.getAttribute('aria-current') === 'page')
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

  it('keeps a tooltip on every destination when the sidebar is collapsed', () => {
    globalThis.localStorage?.setItem('rv-sidebar', 'c')
    stubApi()
    renderAt('/knowledge')
    for (const link of within(sidebar()).getAllByRole('link').filter((entry) => entry.className.includes('rv-nav__link'))) {
      expect(link).toHaveAttribute('title', link.textContent?.trim())
    }
  })

  it('links the brand to Home', () => {
    stubApi()
    renderAt('/reviews')
    expect(within(sidebar()).getByRole('link', { name: 'Rivell' })).toHaveAttribute('href', '/')
  })
})
