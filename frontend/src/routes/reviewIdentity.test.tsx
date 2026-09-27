import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, renderHook, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { stubContractApi } from '../components/contract/testFixtures'
import { useContractQuery } from '../api/contracts'
import { ReviewWorkspacePage } from './ReviewWorkspacePage'
import { EvidenceInspectorPage } from './EvidenceInspectorPage'
import {
  fixtureCompletedReviewSummary,
  fixtureFindingList,
} from '../components/review/testFixtures'

/**
 * M10A.1 / DW-045: a URL that is missing the identity it needs must not produce a request against an
 * empty or foreign identifier.
 *
 * The cross-review case was live: `?finding=` went straight to the review-scoped detail endpoint, so
 * a selection carried from another review asked for a finding that cannot exist in this one.
 *
 * The empty-identity case is defence in depth rather than a live URL path — the router does not
 * match an empty segment, so `/reviews//analysis` is simply an unmatched route. The contract read is
 * still guarded, because the hole was in the hook: it had no `enabled` and no `skipToken`, so any
 * caller passing an empty id issued `GET /api/contracts/`. The page-level guards below are reached by
 * rendering the real pages on a route that supplies no identity at all.
 */

function renderAppRoute(entry: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
}

/** Renders a real page on a route that carries no :contractId, so useParams yields none. */
function renderPageWithoutParams(element: React.ReactElement) {
  const router = createMemoryRouter(
    [{ path: '/no-identity', element }],
    { initialEntries: ['/no-identity'] },
  )
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
}

function findingDetailRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter(
    (request) =>
      request.method === 'GET' && /\/api\/reviews\/[^/?]+\/findings\/[^/?]+(\?|$)/.test(request.url),
  )
}

describe('cross-review finding selection', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('does not request a finding that belongs to another review', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-from-another-review')

    // The list for this review resolves first; the guard can only decide once it has.
    await waitFor(() =>
      expect(api.requests.some((r) => r.url.includes('/api/reviews/r-9/findings'))).toBe(true),
    )
    // Let any follow-up effect that would issue the detail read run.
    await waitFor(() => expect(findingDetailRequests(api)).toEqual([]))

    expect(findingDetailRequests(api)).toEqual([])
  })

  it('still requests a finding that belongs to this review', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-1')

    await waitFor(() => expect(findingDetailRequests(api).length).toBe(1))
    expect(findingDetailRequests(api)[0].url).toContain('/api/reviews/r-9/findings/f-1')
  })
})

describe('missing route identity', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('issues no request for a contract read without an id', () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)

    renderHook(() => useContractQuery(''), { wrapper: AppProviders })

    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('shows a local error state on the review workspace when the route carries no identity', async () => {
    const api = stubContractApi({})

    renderPageWithoutParams(<ReviewWorkspacePage />)

    expect(await screen.findByText(/missing contract or review id/i)).toBeTruthy()
    // Nothing at all may be requested, including the structure and semantics reads, which are
    // version-gated rather than id-gated and are the sibling hole this guards against reopening.
    expect(api.requests).toEqual([])
  })

  it('shows a local error state on the evidence inspector when the route carries no identity', async () => {
    const api = stubContractApi({})

    renderPageWithoutParams(<EvidenceInspectorPage />)

    expect(await screen.findByText(/missing contract id/i)).toBeTruthy()
    expect(api.requests).toEqual([])
  })
})
