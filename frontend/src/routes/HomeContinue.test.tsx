import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { appRoutes } from '../app/router'
import { RESUME_KEY, readResume, writeResume, type ResumeRecord } from '../lib/resume'
import { humanWire, reviewWire } from '../test/reviewListFixtures'

/**
 * "Continue where you left off" is device-local state shown beside real reads. These tests pin the
 * honesty rules: it appears only for a saved place that is still valid, it carries only facts the
 * browser observed, and it never invents progress.
 */

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-10-03T09:00:00Z' }
}

function review(id: string, filename: string, status = 'COMPLETED', findings = 3) {
  return reviewWire(id, status, filename, { findings, humanReview: humanWire() })
}

function stubApi(options: { reviews?: unknown[]; nextCursor?: string | null; reviewsStatus?: number }) {
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL) => {
      const url = (input instanceof Request ? input : new Request(String(input))).url
      if (url.includes('/api/knowledge/')) return new Response(JSON.stringify(envelope([])))
      if (url.includes('/api/reviews')) {
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
      return new Response('{}', { status: 404 })
    }),
  )
}

function renderHome() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, retryDelay: 0 } } })
  const router = createMemoryRouter(appRoutes, { initialEntries: ['/'] })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
}

const RECORD: ResumeRecord = {
  reviewId: 'r1',
  contractId: 'con-r1',
  filename: 'Atlas Mutual NDA.docx',
  versionNumber: 1,
  visitedAt: '2026-10-03T09:00:00.000Z',
}

afterEach(() => {
  vi.unstubAllGlobals()
  globalThis.localStorage?.clear()
})

describe('Continue where you left off', () => {
  it('is absent on a first visit, when nothing was saved', async () => {
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByRole('region', { name: 'Continue where you left off' })).not.toBeInTheDocument()
    expect(screen.queryByText(/continue where you left off/i)).not.toBeInTheDocument()
  })

  it('offers the saved Review by its real filename and links back to the real route', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(within(card).getByRole('heading', { name: /Atlas Mutual NDA\.docx/ })).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Continue review' })).toHaveAttribute(
      'href',
      '/reviews/con-r1/analysis/r1',
    )
  })

  it('says plainly that this is this device, not the account', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(card).toHaveTextContent(/last opened on this device/i)
  })

  it('shows the Review state Home just read, not a stale saved one', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx', 'ANALYZING', 0)] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(card).toHaveTextContent('Analyzing')
  })

  it('never fabricates decision progress, a next step or a position when the server reported none', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [reviewWire('r1', 'COMPLETED', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    const text = card.textContent ?? ''
    expect(text).not.toMatch(/\d+ of \d+/)
    expect(text).not.toMatch(/decided|unresolved|next:|fallback|preferred|open\b.*finding/i)
    expect(card.querySelector('[role="img"]')).toBeNull()
  })

  it('still works when the Review read fails, because the saved place is local', async () => {
    writeResume(RECORD)
    stubApi({ reviewsStatus: 500 })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(within(card).getByRole('heading', { name: /Atlas Mutual NDA\.docx/ })).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Continue review' })).toBeInTheDocument()
  })

  it('disappears and is cleared when a complete read proves the Review is gone', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('someone-else', 'Other.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByRole('region', { name: 'Continue where you left off' })).not.toBeInTheDocument()
    await waitFor(() => expect(readResume()).toBeNull())
  })

  it('keeps the saved place when the list is truncated, because absence then proves nothing', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('someone-else', 'Other.docx')], nextCursor: 'more' })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(card).toBeInTheDocument()
    expect(readResume()).toEqual(RECORD)
  })

  it('ignores corrupt saved state instead of breaking Home', async () => {
    globalThis.localStorage.setItem(RESUME_KEY, '{"v":1,"reviewId":"../../x"}')
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByRole('region', { name: 'Continue where you left off' })).not.toBeInTheDocument()
    expect(globalThis.localStorage.getItem(RESUME_KEY)).toBeNull()
  })

  it('can be dismissed, which clears the saved place', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Continue where you left off' })
    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByRole('region', { name: 'Continue where you left off' })).not.toBeInTheDocument()
    expect(readResume()).toBeNull()
  })

  it('is the more prominent element: it sits above every group', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    const group = await screen.findByRole('region', { name: 'Needs you' })
    expect(card.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows the live decision progress the server reported, and nothing the browser made up', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(card).toHaveTextContent('5 of 8 decided')
    expect(card).toHaveTextContent('In review')
  })
})

describe('Continue keeps only a valid destination', () => {
  const STALE_ADDRESSES = [
    '/knowledge/artifacts/art-1',
    '/knowledge/new',
    '/knowledge/compilations/art-1',
    'rivell-review-v5.html',
    'https://evil.example/',
    '//evil.example/reviews',
    '/reviews/con-r1/analysis',
    '#',
  ]

  it('retains a saved Review and sends Continue to its workspace', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    expect(within(card).getByRole('link', { name: 'Continue review' })).toHaveAttribute('href', '/reviews/con-r1/analysis/r1')
    expect(readResume()).toEqual(RECORD)
  })

  it.each(STALE_ADDRESSES)('discards a saved place that carries the retired address %s', async (address) => {
    globalThis.localStorage.setItem(RESUME_KEY, JSON.stringify({ v: 1, ...RECORD, route: address }))
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByRole('region', { name: 'Continue where you left off' })).not.toBeInTheDocument()
    expect(globalThis.localStorage.getItem(RESUME_KEY)).toBeNull()
  })

  it.each([
    '/reviews',
    '/reviews/con-r1/overview/r1',
    '/reviews/con-r1/overview/r1/findings',
    '/reviews/con-r1/analysis/r1',
  ])('accepts a saved address that is still a Review destination: %s', async (address) => {
    globalThis.localStorage.setItem(RESUME_KEY, JSON.stringify({ v: 1, ...RECORD, route: address }))
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    const card = await screen.findByRole('region', { name: 'Continue where you left off' })
    // Whatever was stored, the link is rebuilt from the ids, never followed from storage.
    expect(within(card).getByRole('link', { name: 'Continue review' })).toHaveAttribute('href', '/reviews/con-r1/analysis/r1')
  })

  it('discards a saved place from a retired record version', async () => {
    globalThis.localStorage.setItem(RESUME_KEY, JSON.stringify({ v: 0, ...RECORD }))
    stubApi({ reviews: [review('r1', 'Atlas Mutual NDA.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByText(/continue where you left off/i)).not.toBeInTheDocument()
    expect(globalThis.localStorage.getItem(RESUME_KEY)).toBeNull()
  })

  it('goes nowhere when the saved Review is gone, so Continue never opens a dead page', async () => {
    writeResume(RECORD)
    stubApi({ reviews: [review('other', 'Other.docx')] })
    renderHome()
    await screen.findByRole('region', { name: 'Needs you' })
    expect(screen.queryByRole('link', { name: 'Continue review' })).not.toBeInTheDocument()
    await waitFor(() => expect(readResume()).toBeNull())
  })
})

