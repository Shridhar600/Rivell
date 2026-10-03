import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../app/router'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-10-02T09:00:00Z' }
}

function artifact(id: string, ingestStatus: string, filename: string) {
  return {
    id,
    artifactType: 'PLAYBOOK',
    filename,
    contentType: 'application/octet-stream',
    sizeBytes: 2048,
    sha256: `sha-${id}`,
    ingestStatus,
    createdAt: '2026-10-02T08:00:00Z',
  }
}

function publication(id: string, publicationNumber: number) {
  return { id, organizationId: 'org-1', publicationNumber, note: '', items: [], createdAt: '2026-10-01T08:00:00Z' }
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
    completedAt: status === 'COMPLETED' ? '2026-10-02T09:00:00Z' : null,
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

function stubApi(options: {
  artifacts?: unknown[]
  publications?: unknown[]
  reviews?: unknown[]
  artifactsStatus?: number
  reviewsStatus?: number
  nextCursor?: string | null
}) {
  const urls: string[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(String(input))
      const url = request.url
      urls.push(url)
      if (url.includes('/api/knowledge/artifacts')) {
        if (options.artifactsStatus && options.artifactsStatus !== 200) {
          return jsonResponse({}, options.artifactsStatus)
        }
        return jsonResponse(envelope(options.artifacts ?? []))
      }
      if (url.includes('/api/knowledge/publications')) {
        return jsonResponse(envelope(options.publications ?? []))
      }
      if (url.includes('/api/reviews')) {
        if (options.reviewsStatus && options.reviewsStatus !== 200) {
          return jsonResponse({}, options.reviewsStatus)
        }
        return jsonResponse(
          envelope({
            items: options.reviews ?? [],
            truncated: Boolean(options.nextCursor),
            nextCursor: options.nextCursor ?? undefined,
          }),
        )
      }
      return jsonResponse({}, 404)
    }),
  )
  return urls
}

function renderHome() {
  // The knowledge layer supplies its own retry predicate, so the delay is zeroed here rather than
  // weakening production retry behaviour.
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, retryDelay: 0 } },
  })
  const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] })
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

describe('Home page data honesty', () => {
  it('reads reviews and knowledge in exactly three bounded requests', async () => {
    const urls = stubApi({ artifacts: [], publications: [], reviews: [] })
    renderHome()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    const calls = urls
      .filter((u) => u.includes('/api/'))
      .map((u) => new URL(u).pathname)
      .sort()
    expect(calls).toEqual(['/api/knowledge/artifacts', '/api/knowledge/publications', '/api/reviews'])
  })

  it('bounds the knowledge reads rather than asking for an unbounded list', async () => {
    const urls = stubApi({ artifacts: [], publications: [], reviews: [] })
    renderHome()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    for (const url of urls.filter((u) => u.includes('/api/knowledge/'))) {
      expect(new URL(url).searchParams.get('limit')).toBe('20')
    }
  })

  it('never enumerates contracts or fans out one review read per contract', async () => {
    const urls = stubApi({
      artifacts: [],
      publications: [],
      reviews: [review('r1', 'COMPLETED', 'Atlas Mutual NDA.docx'), review('r2', 'ANALYSIS_QUEUED', 'Northstar Vendor MSA.docx')],
    })
    renderHome()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    expect(urls.filter((u) => u.includes('/api/contracts'))).toEqual([])
    expect(urls.filter((u) => u.includes('/api/reviews'))).toHaveLength(1)
  })

  it('names a review by its real contract filename', async () => {
    stubApi({
      artifacts: [],
      publications: [],
      reviews: [review('r1', 'COMPLETED', 'Atlas Mutual NDA.docx')],
    })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Completed' })
    expect(within(group).getByRole('option', { name: /Atlas Mutual NDA\.docx/i })).toBeInTheDocument()
  })

  it('admits it is showing only the newest reviews when more exist', async () => {
    stubApi({
      artifacts: [],
      publications: [],
      reviews: [review('r1', 'ANALYSIS_QUEUED', 'Northstar Vendor MSA.docx')],
      nextCursor: '2026-10-02T08:00:00Z',
    })
    renderHome()
    expect(await screen.findByText(/newest 20 reviews/i)).toBeInTheDocument()
  })
})

describe('Home failure is scoped to the domain that failed', () => {
  it('still renders review work when knowledge access is refused', async () => {
    stubApi({
      artifacts: [],
      publications: [],
      reviews: [review('r1', 'COMPLETED', 'Atlas Mutual NDA.docx')],
      artifactsStatus: 403,
    })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Completed' })
    expect(within(group).getByRole('option', { name: /Atlas Mutual NDA\.docx/i })).toBeInTheDocument()
    expect(screen.getByText(/view only/i)).toBeInTheDocument()
  })

  it('still renders knowledge work when the review read is refused', async () => {
    stubApi({
      artifacts: [artifact('a1', 'EXTRACTED', 'Rivell Demo Playbook.docx')],
      publications: [],
      reviews: [],
      reviewsStatus: 403,
    })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Extracted' })
    expect(within(group).getByRole('option', { name: /Rivell Demo Playbook\.docx/i })).toBeInTheDocument()
  })

  it('does not show the empty state while one domain failed', async () => {
    stubApi({ artifacts: [], publications: [], reviews: [], reviewsStatus: 500 })
    renderHome()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    expect(screen.queryByText(/nothing needs your attention/i)).not.toBeInTheDocument()
  })
})

describe('Home real work states', () => {
  it('puts extracted knowledge in its own neutral group, not in Needs action', async () => {
    stubApi({ artifacts: [artifact('a1', 'EXTRACTED', 'msa-playbook.docx')], publications: [] })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Extracted' })
    expect(within(group).getByRole('option', { name: /msa-playbook\.docx/i })).toBeInTheDocument()
    // Nothing proves a human is needed, so Needs action must stay empty.
    expect(screen.queryByRole('region', { name: 'Needs action' })).not.toBeInTheDocument()
  })

  it('shows compiling knowledge under Running with a real running glyph', async () => {
    stubApi({ artifacts: [artifact('a1', 'EXTRACTING', 'busy.docx')], publications: [] })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Running' })
    const row = within(group).getByRole('option', { name: /busy\.docx/i })
    expect(row).toHaveTextContent(/compiling/i)
  })

  it('keeps failed work visible', async () => {
    stubApi({ artifacts: [artifact('a1', 'FAILED', 'broken.docx')], publications: [] })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Failed' })
    expect(within(group).getByRole('option', { name: /broken\.docx/i })).toBeInTheDocument()
  })

  it('never labels the completed group with a time window it cannot compute', async () => {
    stubApi({ artifacts: [], publications: [publication('p1', 12)] })
    renderHome()
    await screen.findByRole('region', { name: 'Completed' })
    expect(screen.queryByText(/recently/i)).not.toBeInTheDocument()
  })

  it('shows publications under Completed', async () => {
    stubApi({ artifacts: [], publications: [publication('p1', 12)] })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Completed' })
    expect(within(group).getByRole('option', { name: /knowledge publication 12/i })).toBeInTheDocument()
  })

  it('omits an empty group rather than rendering noise', async () => {
    stubApi({ artifacts: [artifact('a1', 'EXTRACTING', 'busy.docx')], publications: [] })
    renderHome()
    await screen.findByRole('region', { name: 'Running' })
    expect(screen.queryByRole('region', { name: 'Failed' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Needs action' })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Completed' })).not.toBeInTheDocument()
  })
})

describe('Home never fabricates', () => {
  it('renders no percentage progress anywhere', async () => {
    stubApi({
      artifacts: [artifact('a1', 'EXTRACTING', 'busy.docx'), artifact('a2', 'EXTRACTED', 'ready.docx')],
      publications: [publication('p1', 3)],
    })
    const { container } = renderHome()
    await screen.findByRole('region', { name: 'Running' })
    expect(container.textContent ?? '').not.toMatch(/\d\s*%/)
    expect(container.textContent ?? '').not.toMatch(/progress/i)
  })

  it('renders no ownership or queue language', async () => {
    stubApi({ artifacts: [artifact('a1', 'EXTRACTED', 'ready.docx')], publications: [] })
    renderHome()
    await screen.findByRole('region', { name: 'Extracted' })
    const text = document.body.textContent ?? ''
    for (const forbidden of [
      'your decision',
      'needs your',
      'assigned',
      'owner',
      'next in queue',
      'queue position',
      'waiting for you',
      'reviewer fallback',
    ]) {
      expect(text.toLowerCase()).not.toContain(forbidden)
    }
  })

  it('never shows a sample id', async () => {
    stubApi({ artifacts: [], publications: [] })
    const { container } = renderHome()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
    expect(container.textContent ?? '').not.toMatch(/\b[0-9a-f]{8}-[0-9a-f]{4}/i)
  })
})

describe('Home states', () => {
  it('says what failed, that work is intact, and keeps the page usable', async () => {
    stubApi({ artifacts: [], publications: [], artifactsStatus: 500 })
    renderHome()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/could not complete/i)
    expect(alert).toHaveTextContent(/unchanged/i)
    expect(alert).not.toHaveTextContent(/\{\{|undefined|null/)
    const starts = screen.getAllByRole('link', { name: /start a review/i })
    expect(starts.length).toBeGreaterThan(0)
    for (const link of starts) expect(link).toHaveAttribute('href', '/reviews/new')
  })

  it('retries the failed reads on request', async () => {
    const urls = stubApi({ artifacts: [], publications: [], artifactsStatus: 500 })
    renderHome()
    const before = urls.length
    const retry = await screen.findByRole('button', { name: /try again/i })
    await userEvent.click(retry)
    await waitFor(() => expect(urls.length).toBeGreaterThan(before))
    expect(urls.filter((u) => u.includes('/api/knowledge/artifacts')).length).toBeGreaterThan(1)
  })

  it('never leaks a raw server error body into the page', async () => {
    stubApi({ artifacts: [], publications: [], artifactsStatus: 500 })
    renderHome()
    const alert = await screen.findByRole('alert')
    expect(alert.textContent ?? '').not.toMatch(/exception|at org\.|java\.|\b500\b/)
  })

  it('shows the empty state once every read returns empty, because that claim is now provable', async () => {
    // With a working review read, "nothing needs your attention" is a real statement about all
    // review work and knowledge, not a guess about work Rivell cannot see.
    stubApi({ artifacts: [], publications: [], reviews: [] })
    renderHome()
    expect(await screen.findByText(/nothing needs your attention/i)).toBeInTheDocument()
  })
})

describe('Home selection and inspector', () => {
  it('fills the details panel when a row is selected', async () => {
    stubApi({ artifacts: [artifact('a1', 'EXTRACTED', 'msa-playbook.docx')], publications: [] })
    renderHome()
    const row = await screen.findByRole('option', { name: /msa-playbook\.docx/i })
    await userEvent.click(row)
    const details = screen.getByRole('complementary', { name: 'Details' })
    expect(within(details).getByRole('heading', { level: 2 })).toHaveTextContent('msa-playbook.docx')
  })

  it('moves selection with the arrow keys', async () => {
    stubApi({
      artifacts: [artifact('a1', 'EXTRACTED', 'one.docx'), artifact('a2', 'EXTRACTED', 'two.docx')],
      publications: [],
    })
    renderHome()
    const first = await screen.findByRole('option', { name: /one\.docx/i })
    await userEvent.click(first)
    // The focused option owns arrow handling, so the keys go to it rather than the list.
    expect(first).toHaveFocus()
    await userEvent.keyboard('{ArrowDown}')
    await waitFor(() =>
      expect(screen.getByRole('option', { name: /two\.docx/i })).toHaveAttribute('aria-selected', 'true'),
    )
    await userEvent.keyboard('{ArrowUp}')
    await waitFor(() =>
      expect(screen.getByRole('option', { name: /one\.docx/i })).toHaveAttribute('aria-selected', 'true'),
    )
  })
})
describe('Home loading state', () => {
  it('announces loading politely and does not claim there is no work', async () => {
    let release: (() => void) | undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        await gate
        return jsonResponse(envelope([]))
      }),
    )
    renderHome()
    const loading = await screen.findByRole('status')
    expect(loading).toHaveTextContent(/loading/i)
    expect(loading).not.toHaveTextContent(/nothing needs/i)
    expect(screen.queryByText(/nothing needs your attention/i)).not.toBeInTheDocument()
    release?.()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
  })

  it('shows no empty state while the reads are still in flight', async () => {
    let release: (() => void) | undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        await gate
        return jsonResponse(envelope([]))
      }),
    )
    renderHome()
    await waitFor(() =>
      expect(screen.getAllByRole('status').some((el) => /loading/i.test(el.textContent ?? ''))).toBe(true),
    )
    expect(screen.queryByText(/nothing needs your attention/i)).not.toBeInTheDocument()
    release?.()
    await waitFor(() =>
      expect(screen.queryByRole('status')).not.toBeInTheDocument(),
    )
  })
})

describe('selection, pills and groups', () => {
  const busy = [artifact('a1', 'EXTRACTING', 'busy.docx')]

  it('highlights only the selected item, never one row per group', async () => {
    stubApi({
      artifacts: [
        artifact('a1', 'EXTRACTED', 'one.docx'),
        artifact('a2', 'EXTRACTED', 'two.docx'),
        artifact('a3', 'EXTRACTING', 'busy.docx'),
        artifact('a4', 'FAILED', 'bad.docx'),
      ],
      publications: [publication('p1', 1)],
    })
    renderHome()
    await screen.findByRole('option', { name: /one\.docx/i })

    const selectedCount = () =>
      screen.getAllByRole('option').filter((r) => r.getAttribute('aria-selected') === 'true').length

    // Nothing is auto-selected, so no group highlights its own first row.
    expect(selectedCount()).toBe(0)

    await userEvent.click(screen.getByRole('option', { name: /two\.docx/i }))
    expect(selectedCount()).toBe(1)
    const details = screen.getByRole('complementary', { name: 'Details' })
    expect(within(details).getByRole('heading', { level: 2 })).toHaveTextContent('two.docx')
  })

  it('expands a collapsed group when its summary pill is used', async () => {
    // Running is the first non-empty group, so it opens by default and Extracted starts collapsed.
    stubApi({ artifacts: [...busy, artifact('a9', 'EXTRACTED', 'done.docx')] })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Extracted' })
    const head = within(group).getByRole('button', { name: /^Extracted/ })
    expect(head).toHaveAttribute('aria-expanded', 'false')

    await userEvent.click(screen.getByRole('button', { name: /^1 extracted$/ }))
    await waitFor(() => expect(head).toHaveAttribute('aria-expanded', 'true'))
  })

  it('opens Failed by default so failed work is never hidden', async () => {
    stubApi({ artifacts: [artifact('a1', 'FAILED', 'bad.docx')] })
    renderHome()
    const group = await screen.findByRole('region', { name: 'Failed' })
    expect(within(group).getByRole('button')).toHaveAttribute('aria-expanded', 'true')
  })

  it('never renders a pill whose count is zero', async () => {
    stubApi({ artifacts: busy })
    renderHome()
    await screen.findByRole('region', { name: 'Running' })
    expect(screen.queryByRole('button', { name: /needs? action/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /failed/i })).not.toBeInTheDocument()
  })

  it('states where work stands once every read has resolved', async () => {
    // The headline was previously withheld because the review read could not resolve. It resolves
    // now, so the claim is safe and is made.
    stubApi({
      artifacts: [artifact('a1', 'FAILED', 'bad.docx')],
      publications: [],
      reviews: [review('r1', 'ANALYSIS_QUEUED', 'Northstar Vendor MSA.docx')],
    })
    renderHome()
    expect(await screen.findByRole('button', { name: /1 failed/i })).toBeInTheDocument()
    expect(await screen.findByText(/here is where your work stands/i)).toBeInTheDocument()
  })

  it('does not claim work state before the reads resolve', async () => {
    let release: (() => void) | undefined
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        await gate
        return jsonResponse(envelope([]))
      }),
    )
    renderHome()
    await screen.findByRole('status')
    expect(screen.queryByText(/here is where your work stands/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /needs? action|running|failed/i })).not.toBeInTheDocument()
    release?.()
    await waitFor(() => expect(screen.queryByRole('status')).not.toBeInTheDocument())
  })

  it('shows a real date in the row time column, never a relative guess', async () => {
    stubApi({ artifacts: [artifact('a1', 'EXTRACTED', 'one.docx')] })
    renderHome()
    const row = await screen.findByRole('option', { name: /one\.docx/i })
    const time = within(row).getByLabelText(/^Added /)
    expect(time).toHaveTextContent(/\w{3}/)
    expect(time.textContent ?? '').not.toMatch(/ago|just now|\d+\s*(m|min|h)\b/i)
  })
})
