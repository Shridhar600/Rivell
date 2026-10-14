/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../../app/router'
import { LIFECYCLE_ROWS, reviewWire } from '../../test/reviewListFixtures'

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-10-02T09:00:00Z' }
}

function stubApi(reviews: unknown[] = []) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL((input instanceof Request ? input : new Request(String(input))).url)
      if (url.pathname === '/api/reviews') {
        return new Response(JSON.stringify(envelope({ items: reviews, truncated: false })))
      }
      if (url.pathname.startsWith('/api/knowledge/')) return new Response(JSON.stringify(envelope([])))
      return new Response('{}', { status: 404 })
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

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('M10B shell', () => {
  it('renders Home at the root', async () => {
    stubApi()
    renderAt('/')
    expect(await screen.findByRole('heading', { name: 'Rivell' })).toBeInTheDocument()
  })

  it('no longer redirects the root to Reviews', () => {
    stubApi()
    renderAt('/')
    expect(screen.queryByRole('heading', { name: /^reviews$/i })).not.toBeInTheDocument()
  })

  it('exposes exactly the three M10B destinations', () => {
    stubApi()
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    const links = within(nav).getAllByRole('link').map((l) => l.textContent?.trim())
    expect(links).toEqual(['Home', 'Reviews', 'Knowledge'])
  })

  it('has no Contracts, Inbox, Tasks or Admin destination', () => {
    stubApi()
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    for (const forbidden of ['contract', 'inbox', 'task', 'admin', 'dashboard']) {
      expect(nav.textContent?.toLowerCase() ?? '').not.toContain(forbidden)
    }
  })

  it('shows no organisation name, user menu or greeting', () => {
    stubApi()
    renderAt('/')
    expect(screen.queryByText(/good morning/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/signed-in/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /account|profile|sign out|workspace/i })).not.toBeInTheDocument()
  })

  it('marks the current destination for assistive technology', () => {
    stubApi()
    renderAt('/reviews')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    expect(within(nav).getByRole('link', { name: 'Reviews' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Home' })).not.toHaveAttribute('aria-current')
  })
})

describe('sidebar owns its own collapse control', () => {
  it('puts the control in the sidebar header, not in the top bar', () => {
    stubApi()
    renderAt('/')
    const sidebar = screen.getByRole('complementary', { name: 'Sidebar' })
    const topbar = document.querySelector('.rv-topbar') as HTMLElement
    const toggle = within(sidebar).getByRole('button', { name: 'Sidebar' })
    expect(toggle).toHaveClass('rv-side__toggle')
    expect(toggle.closest('.rv-side__head')).not.toBeNull()
    expect(toggle).toHaveAttribute('title', expect.stringContaining('Collapse sidebar'))
    expect(within(topbar).queryByRole('button')).not.toBeInTheDocument()
  })

  it('keeps a stable accessible name and uses aria-expanded, not aria-pressed', () => {
    stubApi()
    renderAt('/')
    const toggle = screen.getByRole('button', { name: 'Sidebar' })
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).toHaveAttribute('aria-controls', 'rv-sidebar')
    expect(toggle).not.toHaveAttribute('aria-pressed')
  })

  it('keeps the selection status line in the tree when the inspector is collapsed', async () => {
    stubApi([LIFECYCLE_ROWS.unresolved])
    renderAt('/')
    const details = screen.getByRole('complementary', { name: 'Details' })
    await userEvent.click(within(details).getByRole('button', { name: 'Details panel' }))
    await waitFor(() =>
      expect(document.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    await userEvent.click(await screen.findByRole('option', { name: /Unresolved MSA\.docx/i }))
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent(/Unresolved MSA\.docx/)
  })

  it('is reachable by Tab and operable from the keyboard', async () => {
    stubApi()
    const { container } = renderAt('/')
    const toggle = screen.getByRole('button', { name: 'Sidebar' })

    let reached = false
    for (let i = 0; i < 12 && !reached; i++) {
      await userEvent.tab()
      reached = document.activeElement === toggle
    }
    expect(reached).toBe(true)

    await userEvent.keyboard('{Enter}')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-sidebar', 'collapsed'),
    )
    // The name does not change with state, so the button is still findable.
    expect(screen.getByRole('button', { name: 'Sidebar' })).toHaveAttribute('aria-expanded', 'false')

    await userEvent.keyboard('{Enter}')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).not.toHaveAttribute('data-sidebar', 'collapsed'),
    )
  })

  it('puts every sidebar control in the tab order', async () => {
    stubApi()
    renderAt('/')
    const sidebar = screen.getByRole('complementary', { name: 'Sidebar' })
    const nav = within(sidebar).getByRole('navigation', { name: 'Primary' })
    const expected = [
      within(sidebar).getByRole('link', { name: 'Rivell' }),
      within(sidebar).getByRole('button', { name: 'Sidebar' }),
      ...within(nav).getAllByRole('link'),
    ]

    const visited: Element[] = []
    for (let i = 0; i < 6; i++) {
      await userEvent.tab()
      visited.push(document.activeElement as Element)
    }
    for (const control of expected) {
      expect(visited).toContain(control)
    }
  })

  it('collapses from the [ shortcut', async () => {
    stubApi()
    const { container } = renderAt('/')
    await userEvent.keyboard('[[')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-sidebar', 'collapsed'),
    )
  })

  it('keeps a tooltip on collapsed navigation items', async () => {
    stubApi()
    const { container } = renderAt('/')
    await userEvent.keyboard('[[')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-sidebar', 'collapsed'),
    )
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    for (const name of ['Home', 'Reviews', 'Knowledge']) {
      expect(within(nav).getByRole('link', { name })).toHaveAttribute('title', name)
    }
  })

  it('remembers the collapsed state per browser', async () => {
    globalThis.localStorage?.setItem('rv-sidebar', 'c')
    stubApi()
    const { container } = renderAt('/')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-sidebar', 'collapsed'),
    )
  })
})

describe('inspector panel', () => {
  it('toggles from its own panel edge and keeps its grid column', async () => {
    stubApi([LIFECYCLE_ROWS.unresolved])
    const { container } = renderAt('/')
    const details = screen.getByRole('complementary', { name: 'Details' })
    const toggle = within(details).getByRole('button', { name: 'Details panel' })
    expect(toggle).toHaveClass('rv-edge--inspector')
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).toHaveAttribute('aria-controls', 'rv-inspector')
    expect(toggle).not.toHaveAttribute('aria-pressed')

    await userEvent.click(toggle)
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still reachable, otherwise the panel could never be reopened.
    expect(within(details).getByRole('button', { name: 'Details panel' })).toBeInTheDocument()
  })

  it('is not in the top bar', () => {
    stubApi()
    renderAt('/')
    const topbar = document.querySelector('.rv-topbar') as HTMLElement
    expect(within(topbar).queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('legacy surfaces still work', () => {
  it('renders Reviews', async () => {
    stubApi()
    renderAt('/reviews')
    expect(await screen.findByRole('heading', { name: /reviews/i })).toBeInTheDocument()
  })

  it('renders Knowledge', async () => {
    stubApi()
    renderAt('/knowledge')
    expect(await screen.findByRole('heading', { name: /knowledge/i })).toBeInTheDocument()
  })


})
describe('retired v1 addresses', () => {
  it.each([
    ['/knowledge/publications', '/knowledge', ''],
    ['/knowledge/publications/p-1', '/knowledge', ''],
    ['/reviews/c1/analysis', '/reviews', ''],
    ['/reviews/c1/evidence?clause=node-6', '/reviews', ''],
  ])('%s opens %s', async (from, to, search) => {
    stubApi()
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
    const router = createMemoryRouter(appRoutes, { initialEntries: [from] })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    await waitFor(() => expect(router.state.location.pathname).toBe(to))
    expect(router.state.location.search).toBe(search)
  })
})

describe('the retired contract page address', () => {
  function routerAt(path: string) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
    const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )
    return router
  }

  it('opens the contract’s latest review', async () => {
    stubApi([
      reviewWire('r-old', 'COMPLETED', 'a.docx', { contractId: 'c7', createdAt: '2026-10-01T08:00:00Z' }),
      reviewWire('r-new', 'COMPLETED', 'a.docx', { contractId: 'c7', createdAt: '2026-10-03T08:00:00Z' }),
    ])
    const router = routerAt('/reviews/c7?view=semantics')
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c7/overview/r-new'))
  })

  it('opens the Reviews list when the contract has no review', async () => {
    stubApi([])
    const router = routerAt('/reviews/c7')
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews'))
  })
})

describe('responsive shell structure', () => {
  it('keeps the collapsed inspector in the grid rather than removing the column', async () => {
    stubApi()
    const { container } = renderAt('/')
    expect(container.querySelector('.rv-insp')).toBeInTheDocument()
    const details = screen.getByRole('complementary', { name: 'Details' })
    await userEvent.click(within(details).getByRole('button', { name: 'Details panel' }))
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still mounted: the grid column collapses to 0, the panel is not unmounted.
    expect(container.querySelector('.rv-insp')).toBeInTheDocument()
  })

  it('keeps the collapsed panel handle operable', async () => {
    stubApi()
    renderAt('/')
    const details = screen.getByRole('complementary', { name: 'Details' })
    const handle = within(details).getByRole('button', { name: 'Details panel' })
    await userEvent.click(handle)
    await waitFor(() =>
      expect(document.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still operable after collapsing, so the panel can always be reopened.
    await userEvent.click(within(details).getByRole('button', { name: 'Details panel' }))
    await waitFor(() =>
      expect(document.querySelector('.rv-shell')).not.toHaveAttribute('data-inspector', 'collapsed'),
    )
  })




})

/**
 * jsdom applies no stylesheet, so these assert the stylesheet's own declarations. They document
 * intent and catch accidental deletion; they cannot catch cascade or overflow behaviour, which is
 * why the edge handle uses a two-class selector rather than relying on source order.
 */
describe('stylesheet contract (documentation only)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')

  it('hides each panel and its own handle at the required breakpoint', () => {
    // The inspector's own handle lives inside the panel, so hiding the panel hides the handle.
    const wide = css.slice(css.indexOf('max-width: 1180px'), css.indexOf('max-width: 760px'))
    expect(wide).toContain('.rv-insp')
    // The sidebar stays as the icon rail at this width, so only its expand control goes.
    const narrow = css.slice(css.indexOf('max-width: 760px'))
    expect(narrow).toContain('.rv-side__toggle')
  })

  it('honours prefers-reduced-motion for every animated property', () => {
    expect(css).toContain('@media (prefers-reduced-motion: reduce)')
    expect(css).toContain('animation: none')
    expect(css).toContain('transition: none')
  })

  it('uses no literal hex colour', () => {
    expect(css.match(/#[0-9a-f]{3,8}\b/gi) ?? []).toEqual([])
  })

})

describe('panel toggles', () => {
  it('uses a single glyph for the sidebar control', () => {
    stubApi()
    renderAt('/')
    const toggle = screen.getByRole('button', { name: 'Sidebar' })
    expect(toggle.querySelectorAll('svg')).toHaveLength(1)
  })

  it('keeps the same accessible name when collapsed and changes only state and tooltip', async () => {
    stubApi()
    renderAt('/')
    await userEvent.click(screen.getByRole('button', { name: 'Sidebar' }))
    await waitFor(() =>
      expect(document.querySelector('.rv-shell')).toHaveAttribute('data-sidebar', 'collapsed'),
    )
    const after = screen.getByRole('button', { name: 'Sidebar' })
    expect(after).toHaveAttribute('aria-expanded', 'false')
    expect(after).toHaveAttribute('title', expect.stringContaining('Expand sidebar'))
  })

  it('styles the inspector handle once, so no stale duplicate rule can win on order', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    expect(css.match(/^\.rv-iconbtn\.rv-edge \{/gm) ?? []).toHaveLength(1)
  })

  it('derives the handle offset from its own width, never a percentage', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    const handle = css.slice(css.indexOf('.rv-iconbtn.rv-edge {'))
    expect(handle.slice(0, 400)).toContain('calc(var(--rv-edge-w) / -2 - 1px)')
    // A percentage here resolves against the 340px panel, not the 20px handle.
    expect(handle.slice(0, 400)).not.toMatch(/-1 \* \(50%/)
  })
})

describe('centre column scroll', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')

  it('bounds the shell grid row so a tall centre column scrolls instead of being clipped', () => {
    // Without an explicit row the implicit `auto` row grows to the tallest child and the shell's
    // overflow:hidden clips it, which is exactly how expanded groups became unreachable.
    const shell = css.slice(css.indexOf('.rv-shell {'), css.indexOf('}', css.indexOf('.rv-shell {')))
    expect(shell).toContain('grid-template-rows: minmax(0, 1fr)')
    expect(shell).toContain('overflow: hidden')
  })

  it('lets the scroll area shrink below its content and scroll vertically', () => {
    const scroll = css.slice(css.indexOf('.rv-scroll {'), css.indexOf('}', css.indexOf('.rv-scroll {')))
    expect(scroll).toContain('min-height: 0')
    expect(scroll).toContain('overflow-y: auto')
    const main = css.slice(css.indexOf('.rv-main {'), css.indexOf('}', css.indexOf('.rv-main {')))
    expect(main).toContain('min-height: 0')
  })
})
