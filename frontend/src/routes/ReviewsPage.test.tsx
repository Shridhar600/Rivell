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

const CONTEXT = { contractType: 'MSA', ourSideRole: 'CUSTOMER', paperSource: 'COUNTERPARTY_PAPER', jurisdiction: 'UK' }

function human(overrides: Record<string, unknown> = {}) {
  return {
    status: 'IN_PROGRESS',
    actionableFindings: 8,
    resolvedFindings: 5,
    remainingFindings: 3,
    escalated: 0,
    readyToFinalize: false,
    blockers: ['UNRESOLVED_REVIEW_REQUIRED_FINDINGS'],
    ...overrides,
  }
}

function review(id: string, status: string, filename: string, overrides: Record<string, unknown> = {}) {
  return {
    id,
    contractId: `con-${id}`,
    contractVersionNumber: 1,
    contractFilename: filename,
    uploadedAt: '2026-10-02T07:30:00Z',
    context: CONTEXT,
    humanReview: null,
    finalization: null,
    redline: null,
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
  review('r1', 'COMPLETED', 'Atlas Mutual NDA.docx', {
    findings: 8,
    humanReview: human(),
    context: { ...CONTEXT, contractType: 'NDA' },
  }),
  review('r2', 'ANALYSIS_QUEUED', 'Beacon Data Processing Addendum.docx', {
    humanReview: human({ status: 'NOT_STARTED', actionableFindings: 0, resolvedFindings: 0, blockers: ['ANALYSIS_NOT_COMPLETE'] }),
  }),
  review('r3', 'COMPLETED', 'Northstar Vendor MSA.docx', {
    findings: 7,
    humanReview: human({ status: 'COMPLETED', actionableFindings: 7, resolvedFindings: 7, remainingFindings: 0, blockers: [] }),
    finalization: { finalizedAt: '2026-10-02T09:30:00Z', approvedChanges: 7 },
  }),
]

describe('Reviews page', () => {
  it('lists real reviews as a flat table in one organization-wide request', async () => {
    const urls = stubApi({ first: { items: SEEDED } })
    renderReviews()
    expect(await screen.findByRole('heading', { name: 'Contract reviews' })).toBeInTheDocument()
    const rows = await screen.findAllByRole('link', { name: /\.docx/ })
    expect(rows).toHaveLength(3)
    expect(within(rows[0]).getByText('Atlas Mutual NDA.docx')).toBeInTheDocument()
    for (const heading of ['Contract', 'Status', 'Progress', 'Uploaded']) {
      expect(screen.getByText(heading)).toBeInTheDocument()
    }
    expect(screen.queryByText('Created')).not.toBeInTheDocument()

    expect(urls).toHaveLength(1)
    const url = new URL(urls[0])
    expect(url.searchParams.has('contractId')).toBe(false)
    expect(url.searchParams.get('limit')).toBe('25')
  })

  it('opens the real review route from the whole row', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    const row = await screen.findByRole('link', { name: /Atlas Mutual NDA\.docx/ })
    expect(row).toHaveAttribute('href', '/reviews/con-r1/overview/r1')
  })

  it('shows the declared context as chips and as the canonical second line', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    const row = await screen.findByRole('link', { name: /Atlas Mutual NDA\.docx/ })
    expect(within(row).getByText('v1')).toBeInTheDocument()
    expect(within(row).getByText('NDA')).toBeInTheDocument()
    expect(within(row).getByText('Customer side, counterparty paper, United Kingdom')).toBeInTheDocument()
  })

  it('names only what was declared, and not an undeclared jurisdiction', async () => {
    stubApi({
      first: {
        items: [
          review('r9', 'COMPLETED', 'Bare.docx', {
            context: { contractType: 'OTHER', ourSideRole: null, paperSource: null, jurisdiction: 'UNSPECIFIED' },
            humanReview: human({ status: 'NOT_STARTED', resolvedFindings: 0 }),
          }),
        ],
      },
    })
    renderReviews()
    const row = await screen.findByRole('link', { name: /Bare\.docx/ })
    expect(within(row).getByText('Other')).toBeInTheDocument()
    expect(row).not.toHaveTextContent(/side|paper|Not specified|Unspecified/i)
  })

  it('shows when the contract version was uploaded, not when the review was made', async () => {
    stubApi({
      first: {
        items: [review('r1', 'COMPLETED', 'Old.docx', { uploadedAt: '2020-03-04T07:30:00Z', createdAt: '2026-10-02T08:00:00Z' })],
      },
    })
    renderReviews()
    const row = await screen.findByRole('link', { name: /Old\.docx/ })
    expect(row).toHaveTextContent(/Mar/)
    expect(row).not.toHaveTextContent(/Oct/)
  })

  it('draws the real lifecycle, decision progress and finalized count, never a percentage', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    const inReview = await screen.findByRole('link', { name: /Atlas Mutual NDA\.docx/ })
    expect(inReview).toHaveTextContent('In review')
    expect(inReview).toHaveTextContent('5 of 8 decided')
    const queued = screen.getByRole('link', { name: /Beacon Data Processing/ })
    expect(queued).toHaveTextContent('Queued')
    expect(queued).not.toHaveTextContent(/decided|Finding/)
    const finalized = screen.getByRole('link', { name: /Northstar Vendor MSA\.docx/ })
    expect(finalized).toHaveTextContent('Finalized')
    expect(finalized).toHaveTextContent('7 approved changes')
    expect(document.body.textContent).not.toMatch(/%/)
  })

  it('shows check progress for a running review through its status, and a failed analysis as failed', async () => {
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
    expect(await screen.findByRole('link', { name: /Run\.docx.*Analyzing/ })).toBeInTheDocument()
    const failed = screen.getByRole('link', { name: /Broken\.docx.*Analysis failed/ })
    expect(failed).toHaveTextContent('—')
  })

  it('does not call a queued or analyzing review human attention because ANALYSIS_NOT_COMPLETE is a blocker', async () => {
    stubApi({
      first: {
        items: [
          review('q', 'ANALYSIS_QUEUED', 'Queued.docx', { humanReview: human({ blockers: ['ANALYSIS_NOT_COMPLETE'], status: 'NOT_STARTED' }) }),
          review('a', 'ANALYZING', 'Analyzing.docx', { humanReview: human({ blockers: ['ANALYSIS_NOT_COMPLETE'], status: 'NOT_STARTED' }) }),
          review('d', 'COMPLETED', 'Decide.docx', { humanReview: human({ status: 'NOT_STARTED', resolvedFindings: 0 }) }),
        ],
      },
    })
    renderReviews()
    await screen.findAllByRole('link', { name: /\.docx/ })
    expect(screen.getByRole('button', { name: /Needs you/ })).toHaveTextContent('1')
    await userEvent.click(screen.getByRole('button', { name: /Needs you/ }))
    expect(screen.getAllByRole('link', { name: /\.docx/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: /Decide\.docx.*Ready for review/ })).toBeInTheDocument()
  })

  it('enables the canonical filters and counts each group', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    await screen.findAllByRole('link', { name: /\.docx/ })
    const all = screen.getByRole('button', { name: /^All/ })
    expect(all).toHaveTextContent('3')
    expect(all).toHaveAttribute('aria-pressed', 'true')
    for (const name of [/Needs you/, /Running/, /Finalized/]) {
      expect(screen.getByRole('button', { name })).toBeEnabled()
    }
    expect(screen.getByRole('button', { name: /Needs you/ })).toHaveTextContent('1')

    await userEvent.click(screen.getByRole('button', { name: /Running/ }))
    expect(screen.getAllByRole('link', { name: /\.docx/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: /Beacon/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Finalized/ }))
    expect(screen.getAllByRole('link', { name: /\.docx/ })).toHaveLength(1)
    expect(screen.getByRole('link', { name: /Northstar/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Needs you/ }))
    expect(screen.getByRole('link', { name: /Atlas/ })).toBeInTheDocument()
  })

  it('says so when a filter has no loaded reviews', async () => {
    stubApi({ first: { items: [SEEDED[2]] } })
    renderReviews()
    await screen.findAllByRole('link', { name: /\.docx/ })
    await userEvent.click(screen.getByRole('button', { name: /Needs you/ }))
    expect(screen.getByText('No loaded reviews need you.')).toBeInTheDocument()
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
    expect(screen.getAllByRole('link', { name: 'Start review' })[0]).toHaveAttribute('href', '/reviews/new')
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

  it('opens the canonical Start Review page from Start review', async () => {
    stubApi({ first: { items: SEEDED } })
    renderReviews()
    await screen.findAllByRole('link', { name: /\.docx/ })
    await userEvent.click(screen.getAllByRole('link', { name: 'Start review' })[0])
    expect(await screen.findByRole('heading', { name: 'Start a contract review' })).toBeInTheDocument()
  })
})
