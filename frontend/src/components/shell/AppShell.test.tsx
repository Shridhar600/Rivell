/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../../app/router'
import { stubContractApi } from '../contract/testFixtures'
import { fixtureCompletedReviewSummary, fixtureFindingList } from '../review/testFixtures'
import { fixtureHumanReview } from '../review/workflowFixtures'
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
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, router }
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

  it('exposes Home and Reviews at the top level and the four Knowledge areas below', () => {
    stubApi()
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    const links = within(nav).getAllByRole('link').map((l) => l.textContent?.trim())
    expect(links).toEqual(['Home', 'Reviews'])
    const knowledge = screen.getByRole('navigation', { name: 'Knowledge' })
    expect(within(knowledge).getAllByRole('link').map((l) => l.textContent?.trim())).toEqual(['Overview', 'Playbooks', 'Clause library', 'Sources'])
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
  it('keeps one toggle in a fixed place, outside both the sidebar and the top bar', async () => {
    stubApi()
    renderAt('/')
    const sidebar = screen.getByRole('complementary', { name: 'Sidebar' })
    const topbar = document.querySelector('.rv-topbar') as HTMLElement
    const toggle = screen.getByRole('button', { name: 'Sidebar' })
    expect(toggle).toHaveClass('rv-side-toggle')
    expect(sidebar).not.toContainElement(toggle)
    expect(topbar).not.toContainElement(toggle)
    expect(toggle).toHaveAttribute('title', expect.stringContaining('Hide sidebar'))
    await userEvent.click(toggle)
    // The same control stays where it was, so the reader finds it again to reopen the sidebar.
    expect(screen.getByRole('button', { name: 'Sidebar' })).toBe(toggle)
    expect(within(topbar).getByRole('button', { name: 'Search' })).toBeInTheDocument()
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
    await userEvent.click(await screen.findByRole('option', { name: /Unresolved MSA\.docx/i }))
    expect(screen.getByRole('complementary', { name: 'Details' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Details panel' }))
    await waitFor(() =>
      expect(document.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent(/Unresolved MSA\.docx/)
  })

  it('shows no details panel on Home until a row is chosen, and opens a closed panel on choice', async () => {
    globalThis.localStorage?.setItem('rv-inspector', 'c')
    stubApi([LIFECYCLE_ROWS.unresolved])
    renderAt('/')
    const row = await screen.findByRole('option', { name: /Unresolved MSA\.docx/i })
    expect(screen.queryByRole('complementary', { name: 'Details' })).not.toBeInTheDocument()
    await userEvent.click(row)
    expect(screen.getByRole('complementary', { name: 'Details' })).toBeInTheDocument()
    await waitFor(() => expect(document.querySelector('.rv-shell')).not.toHaveAttribute('data-inspector', 'collapsed'))
  })

  it('resizes the details panel from its left edge and remembers the width', async () => {
    stubApi()
    renderAt('/reviews/new')
    const handle = screen.getByRole('separator', { name: 'Resize details panel' })
    expect(handle).toHaveAttribute('aria-valuenow', '340')
    handle.focus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(handle).toHaveAttribute('aria-valuenow', '356')
    expect(globalThis.localStorage?.getItem('rv-inspector-width')).toBe('356')
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
      screen.getByRole('button', { name: 'Sidebar' }),
      within(sidebar).getByRole('link', { name: 'Rivell' }),
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

  it('gives focus to the toggle when the sidebar closes around it', async () => {
    stubApi()
    renderAt('/')
    const home = within(screen.getByRole('navigation', { name: 'Primary' })).getByRole('link', { name: 'Home' })
    home.focus()
    await userEvent.keyboard('[[')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Sidebar' })).toHaveFocus())
  })

  it('takes a closed sidebar out of the tab order and the accessibility tree', async () => {
    stubApi()
    const { container } = renderAt('/')
    const side = container.querySelector('#rv-sidebar') as HTMLElement
    expect(side).not.toHaveAttribute('inert')
    await userEvent.keyboard('[[')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-sidebar', 'collapsed'),
    )
    expect(side).toHaveAttribute('inert')
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

describe('sidebar width', () => {
  const handle = () => screen.getByRole('separator', { name: 'Resize sidebar' })
  const width = (container: HTMLElement) => (container.querySelector('.rv-shell') as HTMLElement).style.getPropertyValue('--rv-sidebar-open')

  it('steps with the arrow keys, stays within its limits and is remembered', async () => {
    stubApi()
    const { container } = renderAt('/')
    expect(handle()).toHaveAttribute('aria-valuenow', '220')
    handle().focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(handle()).toHaveAttribute('aria-valuenow', '236')
    expect(width(container)).toBe('236px')
    expect(globalThis.localStorage?.getItem('rv-sidebar-width')).toBe('236')
    await userEvent.keyboard('{End}{ArrowRight}')
    expect(handle()).toHaveAttribute('aria-valuenow', '320')
    await userEvent.keyboard('{Home}{ArrowLeft}')
    expect(handle()).toHaveAttribute('aria-valuenow', '184')
  })

  it('opens at the saved width, clamped, and resets on double-click', async () => {
    globalThis.localStorage?.setItem('rv-sidebar-width', '900')
    stubApi()
    renderAt('/')
    expect(handle()).toHaveAttribute('aria-valuenow', '320')
    await userEvent.dblClick(handle())
    expect(handle()).toHaveAttribute('aria-valuenow', '220')
  })

  it('has no resize handle while the sidebar is the icon rail', () => {
    globalThis.localStorage?.setItem('rv-sidebar', 'c')
    stubApi()
    renderAt('/')
    expect(screen.queryByRole('separator', { name: 'Resize sidebar' })).not.toBeInTheDocument()
  })
})

describe('inspector panel', () => {
  it('toggles from the end of the top bar and keeps its grid column', async () => {
    stubApi()
    const { container } = renderAt('/reviews/new')
    const topbar = document.querySelector('.rv-topbar') as HTMLElement
    const toggle = screen.getByRole('button', { name: 'Details panel' })
    expect(topbar).toContainElement(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle).toHaveAttribute('aria-controls', 'rv-inspector')
    expect(toggle).not.toHaveAttribute('aria-pressed')

    await userEvent.click(toggle)
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still reachable, otherwise the panel could never be reopened.
    expect(screen.getByRole('button', { name: 'Details panel' })).toHaveAttribute('aria-expanded', 'false')
  })

  it('offers no details toggle on a page without a details panel', () => {
    stubApi()
    renderAt('/')
    const topbar = document.querySelector('.rv-topbar') as HTMLElement
    expect(within(topbar).queryByRole('button', { name: 'Details panel' })).not.toBeInTheDocument()
    expect(within(topbar).getByRole('button', { name: 'Search' })).toBeInTheDocument()
  })
})

describe('the browser tab', () => {
  it.each([
    ['/', 'Home · Rivell'],
    ['/reviews', 'Reviews · Rivell'],
    ['/reviews/new', 'Start review · Rivell'],
  ])('%s is named after the page', async (path, name) => {
    stubApi()
    renderAt(path)
    await screen.findByRole('banner')
    await waitFor(() => expect(document.title).toBe(name))
  })
})

describe('one search menu for the whole app', () => {
  it.each(['/', '/knowledge'])('%s opens the Search dialog with ⌘K', async (path) => {
    stubApi()
    renderAt(path)
    expect(await screen.findByRole('banner')).toBeInTheDocument()
    await userEvent.keyboard('{Meta>}k{/Meta}')
    const dialog = await screen.findByRole('dialog', { name: 'Search' })
    expect(within(dialog).getByRole('textbox', { name: 'Search' })).toBeInTheDocument()
    expect(within(dialog).getByRole('listbox', { name: 'Results' })).toBeInTheDocument()
  })

  it('opens the Search dialog from the top bar button', async () => {
    stubApi()
    renderAt('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Search' }))
    expect(await screen.findByRole('dialog', { name: 'Search' })).toBeInTheDocument()
  })

  it('says when the reviews could not be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = new URL((input instanceof Request ? input : new Request(String(input))).url)
        if (url.pathname === '/api/reviews') return new Response('{}', { status: 500 })
        if (url.pathname.startsWith('/api/knowledge/')) return new Response(JSON.stringify(envelope([])))
        return new Response('{}', { status: 404 })
      }),
    )
    renderAt('/knowledge')
    expect(await screen.findByRole('banner')).toBeInTheDocument()
    await userEvent.keyboard('{Meta>}k{/Meta}')
    const dialog = await screen.findByRole('dialog', { name: 'Search' })
    expect(await within(dialog).findByText('Reviews could not be loaded. Try again later.')).toBeInTheDocument()
  })

  it('lists a review by filename under Reviews and opens its overview', async () => {
    stubApi([reviewWire('r-1', 'COMPLETED', 'Acme MSA.docx', { contractId: 'c9' })])
    const { router } = renderAt('/')
    await screen.findByRole('heading', { name: 'Rivell' })
    await userEvent.keyboard('{Meta>}k{/Meta}')
    const dialog = await screen.findByRole('dialog', { name: 'Search' })
    await userEvent.type(within(dialog).getByRole('textbox', { name: 'Search' }), 'Acme')
    const heading = await within(dialog).findByText('Reviews', { selector: '.rv-ws-pal-h' })
    const group = heading.parentElement as HTMLElement
    await userEvent.click(within(group).getByRole('option', { name: /Acme MSA/ }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c9/overview/r-1'))
  })
})

describe('the workspace starts from the icon rail', () => {
  it('rails the app sidebar without changing the saved preference, and expands it on this page only', async () => {
    globalThis.localStorage?.setItem('rv-sidebar', '')
    stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
      humanReview: fixtureHumanReview(),
    })
    const { container } = renderAt('/reviews/c1/analysis/r-1')
    await screen.findByRole('banner')

    const shell = container.querySelector('.rv-shell') as HTMLElement
    expect(shell).toHaveAttribute('data-sidebar', 'collapsed')
    expect(globalThis.localStorage?.getItem('rv-sidebar')).toBe('')
    const toggle = screen.getByRole('button', { name: 'Sidebar' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')

    await userEvent.click(toggle)
    await waitFor(() => expect(shell).not.toHaveAttribute('data-sidebar', 'collapsed'))
    expect(globalThis.localStorage?.getItem('rv-sidebar')).toBe('')
  })

  it('starts railed again when the reader comes back to the same review', async () => {
    globalThis.localStorage?.setItem('rv-sidebar', '')
    stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
      humanReview: fixtureHumanReview(),
    })
    const { container, router } = renderAt('/reviews/c1/analysis/r-1')
    await screen.findByRole('banner')
    const shell = container.querySelector('.rv-shell') as HTMLElement
    await userEvent.click(screen.getByRole('button', { name: 'Sidebar' }))
    await waitFor(() => expect(shell).not.toHaveAttribute('data-sidebar', 'collapsed'))

    await act(() => router.navigate('/reviews'))
    await act(() => router.navigate('/reviews/c1/analysis/r-1'))

    await waitFor(() => expect(shell).toHaveAttribute('data-sidebar', 'collapsed'))
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

  it('matches the contract id whatever its letter case', async () => {
    stubApi([reviewWire('r-new', 'COMPLETED', 'a.docx', { contractId: '9f2a1c44-ab', createdAt: '2026-10-03T08:00:00Z' })])
    const router = routerAt('/reviews/9F2A1C44-AB')
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/9f2a1c44-ab/overview/r-new'))
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
    const { container } = renderAt('/reviews/new')
    expect(container.querySelector('.rv-insp')).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'Details' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Details panel' }))
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still mounted: the grid column collapses to 0, the panel is not unmounted.
    expect(container.querySelector('.rv-insp')).toBeInTheDocument()
  })

  it('keeps the collapsed panel handle operable', async () => {
    stubApi()
    renderAt('/reviews/new')
    expect(screen.getByRole('complementary', { name: 'Details' })).toBeInTheDocument()
    const handle = screen.getByRole('button', { name: 'Details panel' })
    await userEvent.click(handle)
    await waitFor(() =>
      expect(document.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still operable after collapsing, so the panel can always be reopened.
    await userEvent.click(screen.getByRole('button', { name: 'Details panel' }))
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
    // On a phone the sidebar slides over the page and leaves the screen when closed.
    const narrow = css.slice(css.indexOf('max-width: 760px'))
    expect(narrow).toContain('.rv-side {')
    expect(narrow).toContain('translateX(-100%)')
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
    expect(after).toHaveAttribute('title', expect.stringContaining('Show sidebar'))
  })
})

describe('narrow layouts (stylesheet contract)', () => {
  const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
  const workspace = readFileSync(resolve(process.cwd(), 'src/styles/m10b-workspace.css'), 'utf8')

  it('lets the stacked workspace and draft scroll inside the stage', () => {
    const narrow = css.slice(css.indexOf('@media (max-width: 980px)'))
    expect(narrow.slice(0, 400)).toContain('.rv-stage:has(> .rv-ws)')
    expect(narrow.slice(0, 400)).toContain('overflow-y: auto')
  })

  it('keeps a dragged workspace panel width at medium widths', () => {
    const medium = workspace.slice(workspace.indexOf('@media (max-width: 1240px)'))
    expect(medium.slice(0, 300)).toContain('var(--lw-open, 240px)')
    expect(medium.slice(0, 300)).toContain('var(--rw-open, 340px)')
  })

  it('opens the details panel over the page below 1180px instead of hiding it', () => {
    const medium = css.slice(css.indexOf('@media (max-width: 1180px)'))
    expect(medium.slice(0, 900)).toContain('position: absolute')
    expect(medium.slice(0, 900)).not.toMatch(/\.rv-topbar__insp\s*\{\s*display: none/)
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
