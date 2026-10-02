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

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-10-02T09:00:00Z' }
}

function stubApi(artifacts: unknown[] = [], publications: unknown[] = []) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = (input instanceof Request ? input : new Request(String(input))).url
      if (url.includes('/api/knowledge/artifacts')) return new Response(JSON.stringify(envelope(artifacts)))
      if (url.includes('/api/knowledge/publications')) return new Response(JSON.stringify(envelope(publications)))
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
  it('puts the control on the sidebar, not in the top bar', () => {
    stubApi()
    renderAt('/')
    const sidebar = screen.getByRole('complementary', { name: 'Sidebar' })
    const topbar = document.querySelector('.rv-topbar') as HTMLElement
    expect(within(sidebar).getByRole('button', { name: /collapse sidebar/i })).toBeInTheDocument()
    expect(within(topbar).queryByRole('button', { name: /sidebar/i })).not.toBeInTheDocument()
  })

  it('is reachable by Tab and operable from the keyboard', async () => {
    stubApi()
    const { container } = renderAt('/')
    const toggle = screen.getByRole('button', { name: /collapse sidebar/i })

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
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument()

    await userEvent.keyboard('{Enter}')
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).not.toHaveAttribute('data-sidebar', 'collapsed'),
    )
  })

  it('puts every destination in the tab order before the collapse control', async () => {
    stubApi()
    renderAt('/')
    const nav = screen.getByRole('navigation', { name: 'Primary' })
    const destinations = within(nav).getAllByRole('link')

    const visited: Element[] = []
    for (let i = 0; i < 12; i++) {
      await userEvent.tab()
      const active = document.activeElement
      if (active?.getAttribute('aria-label') === 'Collapse sidebar') break
      visited.push(active as Element)
    }

    // The loop stops at the collapse control, so everything above it was reached first.
    for (const destination of destinations) {
      expect(visited).toContain(destination)
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
  it('toggles from the top bar and keeps its grid column', async () => {
    stubApi([
      { id: 'a1', artifactType: 'PLAYBOOK', filename: 'p.docx', contentType: '', sizeBytes: 1, sha256: '', ingestStatus: 'EXTRACTED', createdAt: '2026-10-02T08:00:00Z' },
    ])
    const { container } = renderAt('/')
    const toggle = screen.getByRole('button', { name: /hide details panel/i })
    await userEvent.click(toggle)
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    expect(screen.getByRole('button', { name: /show details panel/i })).toBeInTheDocument()
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

  it('keeps the legacy content wrapper so existing layout is preserved', () => {
    stubApi()
    const { container } = renderAt('/reviews')
    expect(container.querySelector('.rv-legacy')).toBeInTheDocument()
  })

  it('lets long legacy pages scroll inside the fixed-height shell', () => {
    // The shell is 100vh with overflow:hidden, so without this the long Reviews and
    // Knowledge pages would be clipped and unscrollable.
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    const legacy = css.slice(css.indexOf('.rv-legacy {'))
    expect(legacy.slice(0, 200)).toContain('overflow: auto')
    expect(legacy.slice(0, 200)).toContain('height: 100vh')
  })
})
describe('responsive shell structure', () => {
  it('keeps the collapsed inspector in the grid rather than removing the column', async () => {
    stubApi()
    const { container } = renderAt('/')
    expect(container.querySelector('.rv-insp')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /hide details panel/i }))
    await waitFor(() =>
      expect(container.querySelector('.rv-shell')).toHaveAttribute('data-inspector', 'collapsed'),
    )
    // Still mounted: the grid column collapses to 0, the panel is not unmounted.
    expect(container.querySelector('.rv-insp')).toBeInTheDocument()
  })

  it('declares the breakpoints the design system requires', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    expect(css).toContain('@media (max-width: 1180px)')
    expect(css).toContain('@media (max-width: 760px)')
    // The inspector hides below 1180 and the sidebar below 760.
    const wide = css.slice(css.indexOf('max-width: 1180px'))
    expect(wide.slice(0, 400)).toContain('.rv-insp')
    const narrow = css.slice(css.indexOf('max-width: 760px'))
    expect(narrow.slice(0, 400)).toContain('.rv-side')
  })

  it('honours prefers-reduced-motion', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    expect(css).toContain('@media (prefers-reduced-motion: reduce)')
    expect(css).toContain('animation: none')
    expect(css).toContain('transition: none')
  })

  it('uses no literal hex colour in the M10B stylesheet', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    const hexes = css.match(/#[0-9a-f]{3,8}\b/gi) ?? []
    expect(hexes).toEqual([])
  })

  it('keeps every M10B class prefixed so the legacy stylesheet is untouched', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles/m10b.css'), 'utf8')
    // The negative lookbehind keeps the `.css` inside `m10b.css` from matching as a selector.
    const classNames = [...css.matchAll(/(?<![a-z0-9_-])\.([a-z][a-z0-9_-]*)/g)].map((m) => m[1])
    const unprefixed = classNames.filter((c) => !c.startsWith('rv-') && !c.startsWith('m10b'))
    expect(unprefixed).toEqual([])
  })
})
