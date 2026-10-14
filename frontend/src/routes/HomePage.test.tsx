import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../app/router'
import { LIFECYCLE_ROWS, humanWire, reviewWire } from '../test/reviewListFixtures'

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-10-04T09:00:00Z' }
}

/** Serves the Review list, records every path asked for, and answers Knowledge with data Home must ignore. */
function stubApi(options: { reviews?: unknown[]; reviewsStatus?: number; nextCursor?: string | null } = {}) {
  const paths: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = new URL((input instanceof Request ? input : new Request(String(input))).url)
      paths.push(url.pathname)
      if (url.pathname === '/api/reviews') {
        if (options.reviewsStatus && options.reviewsStatus !== 200) {
          return new Response('{}', { status: options.reviewsStatus })
        }
        return new Response(
          JSON.stringify(
            envelope({
              items: options.reviews ?? [],
              truncated: Boolean(options.nextCursor),
              nextCursor: options.nextCursor ?? undefined,
            }),
          ),
        )
      }
      if (url.pathname === '/api/knowledge/artifacts') {
        return new Response(
          JSON.stringify(
            envelope([
              {
                id: 'k1',
                artifactType: 'PLAYBOOK',
                filename: 'Compiling Playbook.docx',
                contentType: 'x',
                sizeBytes: 1,
                sha256: 's',
                ingestStatus: 'EXTRACTING',
                createdAt: '2026-10-04T08:00:00Z',
              },
            ]),
          ),
        )
      }
      return new Response(JSON.stringify(envelope([])))
    }),
  )
  return paths
}

function renderHome() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] })
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, router }
}

const everyState = Object.values(LIFECYCLE_ROWS)

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('Home starts work', () => {
  it('offers Start review and Add knowledge in the top bar', async () => {
    stubApi()
    renderHome()
    const bar = document.querySelector('.rv-topbar') as HTMLElement
    expect(within(bar).getByRole('link', { name: 'Start review' })).toHaveAttribute('href', '/reviews/new')
    expect(within(bar).getByRole('link', { name: 'Add knowledge' })).toHaveAttribute('href', '/knowledge?add=1')
    await screen.findByText('No reviews yet')
  })

  it('goes to the Start review page', async () => {
    stubApi()
    const { router } = renderHome()
    await userEvent.click((await screen.findAllByRole('link', { name: 'Start review' }))[0]!)
    expect(router.state.location.pathname).toBe('/reviews/new')
    expect(await screen.findByRole('heading', { name: 'Start a contract review' })).toBeInTheDocument()
  })

  it('opens the real Add knowledge dialog on the Knowledge overview, not the retired page', async () => {
    stubApi()
    const { router } = renderHome()
    await userEvent.click(await screen.findByRole('link', { name: 'Add knowledge' }))
    expect(router.state.location.pathname).toBe('/knowledge')
    expect(router.state.location.search).toBe('?add=1')
    expect(await screen.findByRole('dialog', { name: 'Add knowledge' })).toBeInTheDocument()
  })
})

describe('Home is about Reviews only', () => {
  it('reads one bounded Review list and makes no Knowledge request', async () => {
    const paths = stubApi({ reviews: [LIFECYCLE_ROWS.unresolved] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(paths.filter((path) => path.startsWith('/api/knowledge'))).toEqual([])
    expect(paths).toEqual(['/api/reviews'])
  })

  it('shows no Knowledge compilation, candidate, publication or source work', async () => {
    stubApi({ reviews: everyState })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByText('Compiling Playbook.docx')).not.toBeInTheDocument()
    const main = document.querySelector('.rv-scroll') as HTMLElement
    expect(main.textContent).not.toMatch(/compil|publication|extracted|playbook|clause library|candidate/i)
    for (const name of ['Extracted', 'Completed', 'Failed']) {
      expect(screen.queryByRole('region', { name })).not.toBeInTheDocument()
    }
  })
})

describe('Home sections', () => {
  it('puts failures, blockers, readiness and open decisions under Needs you', async () => {
    stubApi({ reviews: everyState })
    renderHome()
    const needs = await screen.findByRole('region', { name: 'Needs you' })
    for (const [name, state] of [
      ['Failed DPA.docx', 'Analysis failed'],
      ['Redline Failed Vendor.docx', 'Redline failed'],
      ['Unresolved MSA.docx', 'In review'],
      ['Fresh NDA.docx', 'Ready for review'],
      ['Escalated Order Form.docx', 'Escalation blocking'],
      ['Ready SOW.docx', 'Ready to finalize'],
    ]) {
      const row = within(needs).getByRole('option', { name: new RegExp(name.replace('.', '\\.')) })
      expect(row).toHaveTextContent(state!)
    }
  })

  it('keeps queued and analyzing Reviews out of Needs you', async () => {
    stubApi({ reviews: everyState })
    renderHome()
    const needs = await screen.findByRole('region', { name: 'Needs you' })
    expect(within(needs).queryByText(/Queued NDA|Analyzing MSA/)).not.toBeInTheDocument()
    const running = screen.getByRole('region', { name: 'Running' })
    await userEvent.click(within(running).getByRole('button', { name: /Running/ }))
    expect(within(running).getByRole('option', { name: /Queued NDA\.docx/ })).toHaveTextContent('Queued')
    expect(within(running).getByRole('option', { name: /Analyzing MSA\.docx/ })).toHaveTextContent('Analyzing')
  })

  it('lists finalized, verified and no-change Reviews as recent results', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.finalized, LIFECYCLE_ROWS.finalizedNoChange, LIFECYCLE_ROWS.verified] })
    renderHome()
    const results = await screen.findByRole('region', { name: 'Recently completed' })
    const rows = within(results).getAllByRole('option')
    expect(rows.map((row) => row.textContent)).toEqual([
      expect.stringContaining('Verified Procurement.docx'),
      expect.stringContaining('Finalized Services.docx'),
      expect.stringContaining('No Change NDA.docx'),
    ])
    expect(rows[0]).toHaveTextContent('Verified redline')
    expect(rows[1]).toHaveTextContent('Finalized')
    expect(rows[2]).toHaveTextContent('Review completed')
  })

  it('does not call a Review recent that is merely finished, and says how many more there are', async () => {
    const many = Array.from({ length: 8 }, (_, index) =>
      reviewWire(`d${index}`, 'COMPLETED', `Done ${index}.docx`, {
        humanReview: humanWire({ status: 'COMPLETE', blockers: [] }),
        finalization: { finalizedAt: `2026-09-0${index + 1}T10:00:00Z`, approvedChanges: 2 },
      }),
    )
    stubApi({ reviews: many })
    renderHome()
    const results = await screen.findByRole('region', { name: 'Recently completed' })
    expect(within(results).getAllByRole('option')).toHaveLength(5)
    expect(results).toHaveTextContent('3 more finished reviews are on the Reviews page.')
  })

  it('summarises only what exists: needs you, analyzing and queued, never at zero', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.unresolved, LIFECYCLE_ROWS.escalated, LIFECYCLE_ROWS.analyzing] })
    renderHome()
    expect(await screen.findByRole('button', { name: '2 need you' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '1 analyzing' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /queued/ })).not.toBeInTheDocument()
  })

  it('opens Running from its count when it is collapsed', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.unresolved, LIFECYCLE_ROWS.queued] })
    renderHome()
    const running = await screen.findByRole('region', { name: 'Running' })
    expect(within(running).getByRole('button', { name: /Running/ })).toHaveAttribute('aria-expanded', 'false')
    Element.prototype.scrollIntoView = vi.fn()
    await userEvent.click(screen.getByRole('button', { name: '1 queued' }))
    expect(within(running).getByRole('button', { name: /Running/ })).toHaveAttribute('aria-expanded', 'true')
  })

  it('opens the first non-empty list when nothing needs the reviewer', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.analyzing, LIFECYCLE_ROWS.verified] })
    renderHome()
    const running = await screen.findByRole('region', { name: 'Running' })
    expect(within(running).getByRole('button', { name: /Running/ })).toHaveAttribute('aria-expanded', 'true')
    expect(within(screen.getByRole('region', { name: 'Recently completed' })).getByRole('button', { name: /Recently completed/ })).toHaveAttribute('aria-expanded', 'false')
  })
})

describe('Home inspector', () => {
  it('shows real facts for the selected Review and opens its overview', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.unresolved] })
    const { router } = renderHome()
    await userEvent.click(await screen.findByRole('option', { name: /Unresolved MSA\.docx/ }))
    const details = screen.getByRole('complementary', { name: 'Details' })
    expect(within(details).getByRole('heading', { name: 'Unresolved MSA.docx' })).toBeInTheDocument()
    expect(details).toHaveTextContent('5 of 8')
    await userEvent.click(within(details).getByRole('link', { name: 'Open review' }))
    expect(router.state.location.pathname).toBe('/reviews/con-u1/overview/u1')
  })

  it('moves the selection with the arrow keys', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.unresolved, LIFECYCLE_ROWS.escalated] })
    renderHome()
    const first = await screen.findByRole('option', { name: /Unresolved MSA\.docx/ })
    await userEvent.click(first)
    await userEvent.keyboard('{ArrowDown}')
    expect(screen.getByRole('option', { name: /Escalated Order Form\.docx/ })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('Home states', () => {
  it('shows an empty state only after a successful empty read', async () => {
    stubApi({ reviews: [] })
    renderHome()
    expect(await screen.findByText('No reviews yet')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Needs you' })).not.toBeInTheDocument()
  })

  it('keeps starting work possible, and offers a retry, when the Review list fails', async () => {
    const paths = stubApi({ reviewsStatus: 500 })
    renderHome()
    expect(await screen.findByRole('alert')).toHaveTextContent(/review work could not be loaded/i)
    expect(screen.queryByText('No reviews yet')).not.toBeInTheDocument()
    const before = paths.filter((path) => path === '/api/reviews').length
    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    await waitFor(() => expect(paths.filter((path) => path === '/api/reviews').length).toBeGreaterThan(before))
    expect(screen.getAllByRole('link', { name: 'Start review' }).length).toBeGreaterThan(0)
  })

  it('treats a refused read as view-only, not as a failure', async () => {
    stubApi({ reviewsStatus: 403 })
    renderHome()
    expect(await screen.findByText('View only')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /try again/i })).not.toBeInTheDocument()
  })

  it('says when Home shows only a page of the Reviews', async () => {
    stubApi({ reviews: [LIFECYCLE_ROWS.unresolved], nextCursor: 'more' })
    renderHome()
    const note = await screen.findByText(/Showing the newest 20 reviews/)
    expect(within(note.closest('p')!).getByRole('link', { name: /Open Reviews/ })).toHaveAttribute('href', '/reviews')
  })

  it('does not hide a Review it cannot place', async () => {
    stubApi({ reviews: [reviewWire('p', 'COMPLETED', 'No state.docx')] })
    renderHome()
    expect(await screen.findByText(/1 review has no state Home can place/)).toBeInTheDocument()
  })

  it('never leaks a raw server body', async () => {
    stubApi({ reviewsStatus: 500 })
    renderHome()
    await screen.findByRole('alert')
    expect(document.body.textContent).not.toMatch(/Exception|stack|\{\}/)
  })
})
