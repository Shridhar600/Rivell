import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../app/router'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function review(id: string, status: string, filename: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    contractId: `con-${id}`,
    contractVersionNumber: 1,
    contractFilename: filename,
    status,
    failure: null,
    createdAt: '2026-10-02T08:00:00Z',
    startedAt: null,
    completedAt: status === 'COMPLETED' || status === 'FAILED' ? '2026-10-02T09:00:00Z' : null,
    activeRunNumber: null,
    activeRunStatus: null,
    latestRunStatus: null,
    plannedChecks: 6,
    completedChecks: status === 'COMPLETED' ? 6 : 0,
    degradedChecks: 0,
    failedChecks: 0,
    signals: 0,
    findings: 0,
    ...overrides,
  }
}

type Page = { items: unknown[]; nextCursor?: string }

/** A page is picked by the `cursor` the page asks for, so paging is real. `failOn` makes that page fail. */
function stubApi(pages: Record<string, Page>, failOn?: string) {
  const urls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = (input instanceof Request ? input : new Request(String(input))).url
      urls.push(url)
      if (!url.includes('/api/reviews')) return jsonResponse({}, 404)
      const cursor = new URL(url).searchParams.get('cursor') ?? 'first'
      if (cursor === failOn) return jsonResponse({}, 500)
      const page = pages[cursor]
      return jsonResponse({
        data: { items: page.items, truncated: Boolean(page.nextCursor), nextCursor: page.nextCursor },
        status: 200,
        timestamp: '2026-10-02T09:00:00Z',
      })
    }),
  )
  return urls
}

function renderReviews() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(appRoutes, { initialEntries: ['/reviews'] })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

afterEach(() => vi.unstubAllGlobals())

const SEEDED = [
  review('r1', 'COMPLETED', 'Atlas Mutual NDA.docx', { findings: 4 }),
  review('r2', 'ANALYSIS_QUEUED', 'Beacon Data Processing Addendum.docx'),
  review('r3', 'COMPLETED', 'Northstar Vendor MSA.docx', { findings: 0 }),
]

describe('Reviews page', () => {
  it('lists real reviews as a flat table in one organization-wide request', async () => {
    const urls = stubApi({ first: { items: SEEDED } })
    renderReviews()
    expect(await screen.findByRole('heading', { name: 'Contract reviews' })).toBeInTheDocument()
    const rows = await screen.findAllByRole('link', { name: /\.docx/ })
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('Atlas Mutual NDA.docx')).toBeInTheDocument()
    expect(screen.getByText('Progress')).toBeInTheDocument()

    expect(urls).toHaveLength(1)
    const url = new URL(urls[0])
    expect(url.searchParams.has('contractId')).toBe(false)
    expect(url.searchParams.get('limit')).toBe('25')
  })

  it('opens the real review route from the whole row', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    const row = await screen.findByRole('link', { name: /Atlas Mutual NDA\.docx/ })
    expect(row).toHaveAttribute('href', '/reviews/con-r1/analysis/r1')
  })

  it('labels state truthfully: a finding count only for finished analysis, never a percentage', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    const done = await screen.findByRole('link', { name: /Atlas Mutual NDA\.docx/ })
    expect(done).toHaveTextContent('Analysis complete')
    expect(done).toHaveTextContent('4 Findings')
    const queued = screen.getByRole('link', { name: /Beacon Data Processing/ })
    expect(queued).toHaveTextContent('Analysis queued')
    expect(queued).not.toHaveTextContent(/Finding/)
    expect(document.body.textContent).not.toMatch(/%|ready to finalize/i)
  })

  it('shows check progress for a running review and the failure sentence for a failed one', async () => {
    stubApi({
      first: {
        items: [
          review('r4', 'ANALYZING', 'Run.docx', { completedChecks: 2, plannedChecks: 5 }),
          review('r5', 'FAILED', 'Broken.docx', {
            failure: { code: 'ANALYSIS_INTERNAL_ERROR', message: 'Machine analysis could not be completed.' },
          }),
        ],
      },
    })
    renderReviews()
    expect(await screen.findByRole('link', { name: /Run\.docx.*2 of 5 checks complete/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Broken\.docx.*Machine analysis could not.*Analysis failed/ })).toBeInTheDocument()
  })

  it('filters to running reviews, and marks the filters the list cannot support as unavailable', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    await screen.findAllByRole('link', { name: /\.docx/ })
    expect(screen.getByRole('button', { name: /All/ })).toHaveTextContent('3')
    expect(screen.getByRole('button', { name: /Needs you/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Finalized/ })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: /Running/ }))
    expect(screen.getAllByRole('link', { name: /\.docx/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: /Beacon/ })).toBeInTheDocument()
  })

  it('says it is empty', async () => {
    stubApi({ first: { items: [] } })
    renderReviews()
    expect(await screen.findByRole('heading', { name: 'No reviews yet' })).toBeInTheDocument()
  })

  it('reports a load failure and keeps Start review available', async () => {
    stubApi({}, 'first')
    renderReviews()
    expect(await screen.findByRole('heading', { name: 'Reviews could not be loaded' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Start review' })[0]).toBeEnabled()
  })

  it('discloses truncation and loads the next page on request, without repeating rows', async () => {
    const urls = stubApi({
      first: { items: [SEEDED[0], SEEDED[1]], nextCursor: 'page-2' },
      'page-2': { items: [SEEDED[1], SEEDED[2]] },
    })
    renderReviews()
    expect(await screen.findByText(/showing the newest 2 reviews/i)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /load more reviews/i }))
    await waitFor(() => expect(screen.getAllByRole('link', { name: /\.docx/ })).toHaveLength(3))
    expect(new URL(urls[1]).searchParams.get('cursor')).toBe('page-2')
    expect(screen.queryByRole('button', { name: /load more reviews/i })).not.toBeInTheDocument()
  })

  it('keeps loaded rows and offers a retry when a later page fails', async () => {
    stubApi({ first: { items: [SEEDED[0]], nextCursor: 'page-2' } }, 'page-2')
    renderReviews()
    await userEvent.click(await screen.findByRole('button', { name: /load more reviews/i }))
    expect(await screen.findByRole('heading', { name: 'More reviews could not be loaded' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Atlas Mutual NDA\.docx/ })).toBeInTheDocument()
  })

  it('opens the upload form from Start review', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    await screen.findAllByRole('link', { name: /\.docx/ })
    await userEvent.click(screen.getAllByRole('button', { name: 'Start review' })[0])
    expect(screen.getByLabelText(/contract file/i)).toBeInTheDocument()
  })
})
