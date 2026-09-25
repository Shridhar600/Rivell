import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { fixtureSemantics, stubContractApi } from '../components/contract/testFixtures'
import {
  fixtureQueuedReviewSummary,
  fixtureReviewList,
} from '../components/review/testFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-25T10:00:00Z' }
}

function renderRoute(initialEntry: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] })
  const result = render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { router, ...result }
}

function renderReviewRoute(summary: unknown) {
  const api = stubContractApi({ reviewSummary: summary })
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/reviews/c1/analysis/r-9'],
  })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return api
}

function createRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter(
    (request) => request.method === 'POST' && request.url.includes('/reviews'),
  )
}

function listRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter(
    (request) => request.method === 'GET' && request.url.includes('/api/reviews?'),
  )
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('review creation', () => {
  it('confirms the profile, sends one explicit-pack review, and opens the workspace', async () => {
    const api = stubContractApi({
      reviews: fixtureReviewList,
      createReviewSummary: fixtureQueuedReviewSummary,
      reviewSummary: fixtureQueuedReviewSummary,
    })
    const { router } = renderRoute('/reviews/c1/analysis')

    expect(await screen.findByText('Counterparty paper')).toBeInTheDocument()
    expect(screen.getByText('United Kingdom')).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText(/Contract pack/), 'MSA_PACK')
    await userEvent.click(screen.getByRole('button', { name: 'Start review' }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1'),
    )
    const creates = createRequests(api)
    expect(creates).toHaveLength(1)
    expect(creates[0].body).toEqual({ contractPackKey: 'MSA_PACK' })

    expect(await screen.findByRole('heading', { name: 'Master Services Agreement' })).toBeInTheDocument()
    expect(screen.getByText('Analysis queued')).toBeInTheDocument()
  })

  it('creates a pack-less review when no pack is selected', async () => {
    const api = stubContractApi({
      createReviewSummary: fixtureQueuedReviewSummary,
      reviewSummary: fixtureQueuedReviewSummary,
    })
    renderRoute('/reviews/c1/analysis')

    await userEvent.click(await screen.findByRole('button', { name: 'Start review' }))

    await waitFor(() => expect(createRequests(api)).toHaveLength(1))
    expect(createRequests(api)[0].body).toEqual({})
  })

  it('submits only once when the form is submitted twice synchronously', async () => {
    const releaseRef: { current: (() => void) | null } = { current: null }
    const api = stubContractApi({
      createReviewResponse: () =>
        new Promise<Response>((resolve) => {
          releaseRef.current = () => resolve(jsonResponse(envelope(fixtureQueuedReviewSummary)))
        }),
      reviewSummary: fixtureQueuedReviewSummary,
    })
    renderRoute('/reviews/c1/analysis')

    const form = await screen.findByRole('form', { name: 'Start automated review' })
    act(() => {
      fireEvent.submit(form)
      fireEvent.submit(form)
    })

    await waitFor(() => expect(createRequests(api)).toHaveLength(1))

    releaseRef.current?.()
    await waitFor(() => expect(createRequests(api)).toHaveLength(1))
  })

  it('does not retry after a conflict and explains the enrichment state', async () => {
    const api = stubContractApi({ createReviewStatus: 409 })
    const { router } = renderRoute('/reviews/c1/analysis')

    await userEvent.click(await screen.findByRole('button', { name: 'Start review' }))

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(/not ready for an automated review/)).toBeInTheDocument()
    expect(
      within(alert).getByRole('link', { name: "Open the contract's semantic analysis" }),
    ).toHaveAttribute('href', '/reviews/c1?view=semantics')
    expect(createRequests(api)).toHaveLength(1)
    expect(router.state.location.pathname).toBe('/reviews/c1/analysis')
  })

  it('offers an honest recovery path when the outcome is unknown', async () => {
    const api = stubContractApi({
      createReviewResponse: () => Promise.reject(new TypeError('Failed to fetch')),
      reviews: fixtureReviewList,
    })
    renderRoute('/reviews/c1/analysis')

    await userEvent.click(await screen.findByRole('button', { name: 'Start review' }))

    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(/could not confirm whether the review started/)).toBeInTheDocument()

    const listCallsBefore = listRequests(api).length
    await userEvent.click(within(alert).getByRole('button', { name: 'Re-check reviews' }))

    await waitFor(() => expect(listRequests(api).length).toBeGreaterThan(listCallsBefore))
    expect(createRequests(api)).toHaveLength(1)
    expect(await screen.findByText(/Review history refreshed/)).toBeInTheDocument()
  })

  it('blocks another submission until the review history has been re-checked', async () => {
    let fail = true
    const api = stubContractApi({
      createReviewResponse: () => {
        if (fail) return Promise.reject(new TypeError('Failed to fetch'))
        return Promise.resolve(jsonResponse(envelope(fixtureQueuedReviewSummary)))
      },
      reviews: { items: [], truncated: false },
      reviewSummary: fixtureQueuedReviewSummary,
    })
    renderRoute('/reviews/c1/analysis')

    await userEvent.click(await screen.findByRole('button', { name: 'Start review' }))
    await screen.findByRole('alert')

    expect(screen.queryByRole('button', { name: 'Start review' })).not.toBeInTheDocument()
    expect(createRequests(api)).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: 'Re-check reviews' }))

    fail = false
    const resumed = await screen.findByRole('button', { name: 'Start review' })
    await userEvent.click(resumed)

    await waitFor(() => expect(createRequests(api)).toHaveLength(2))
  })

  it('gates creation until semantic analysis is ready', async () => {
    const api = stubContractApi({
      semantics: { ...fixtureSemantics(), enrichmentStatus: 'NOT_ENRICHED' },
    })
    renderRoute('/reviews/c1/analysis')

    expect(await screen.findByText('Semantic analysis is not ready')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Start review' })).not.toBeInTheDocument()
    expect(createRequests(api)).toHaveLength(0)
    expect(
      screen.getByRole('link', { name: 'Open semantic analysis' }),
    ).toHaveAttribute('href', '/reviews/c1?view=semantics')
  })
})

describe('review history', () => {
  it('lists reviews and opens the selected one', async () => {
    stubContractApi({
      reviews: fixtureReviewList,
      reviewSummary: fixtureQueuedReviewSummary,
    })
    const { router } = renderRoute('/reviews/c1/analysis')

    expect(await screen.findByText('Analyzing')).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()

    const links = screen.getAllByRole('link', { name: /Open review/ })
    expect(links[0]).toHaveAttribute('href', '/reviews/c1/analysis/r-2')
    expect(links[0]).toHaveAccessibleName(/Analyzing/)

    await userEvent.click(links[1])
    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1'),
    )
  })

  it('shows a meaningful empty history state', async () => {
    stubContractApi({ reviews: { items: [], truncated: false } })
    renderRoute('/reviews/c1/analysis')

    expect(await screen.findByText('No automated reviews yet')).toBeInTheDocument()
  })
})

describe('review workspace integrity', () => {
  it('treats a review from another contract as not found', async () => {
    renderReviewRoute({ ...fixtureQueuedReviewSummary, id: 'r-9', contractId: 'c2' })

    expect(
      await screen.findByText(/This review could not be found/),
    ).toBeInTheDocument()
  })
})

describe('contract workspace entry point', () => {
  it('opens the contract analysis page from the contract workspace', async () => {
    stubContractApi({ reviews: fixtureReviewList })
    const { router } = renderRoute('/reviews/c1')

    await userEvent.click(await screen.findByRole('link', { name: /Reviews & findings/ }))

    await waitFor(() =>
      expect(router.state.location.pathname).toBe('/reviews/c1/analysis'),
    )
  })
})