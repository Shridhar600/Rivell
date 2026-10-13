import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, renderHook, screen, waitFor } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { stubContractApi } from '../components/contract/testFixtures'
import { useContractQuery } from '../api/contracts'
import { ReviewWorkspacePage } from './ReviewWorkspacePage'
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

/**
 * The full API envelope, matching what the generated client parses. Returning only `{ data }` made
 * the findings list resolve to something the page could not read, which silently turned the
 * in-flight case into a second "no request" case that passed for the wrong reason.
 */
const jsonBody = (value: unknown) =>
  new Response(
    JSON.stringify({ data: value, status: 200, timestamp: '2026-09-24T05:00:00Z' }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )

const detailRequestFor = (api: ReturnType<typeof stubContractApi>, findingId: string) =>
  findingDetailRequests(api).filter((request) => request.url.includes(`/findings/${findingId}`))

/** A list that does NOT contain the selected finding, so a stand-down is observable. */
const listWithout = (ids: string[], truncated = false) => ({
  items: fixtureFindingList.items.filter((item) => ids.includes(item.id)),
  truncated,
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
})

/**
 * M10A.1 corrective / DW-045: the full request-behaviour matrix.
 *
 * The first version of this file asserted two cases and described the rest in prose. The omitted
 * cases are the ones where the guard *stands down*, and a guard that is only tested in the state
 * where it blocks cannot be told apart from one that blocks unconditionally. So every case below
 * states, in its name, whether it must produce ZERO detail requests or is intentionally allowed to
 * fall back to the backend's own review/run scoping.
 *
 * The exact behaviour, which the earlier report overclaimed, is:
 *
 *   authoritative complete list + member       -> request issued for the named finding
 *   authoritative complete list + non-member   -> ZERO requests for the named id (the workspace
 *                                                 falls back to its own first finding instead)
 *   list truncated                            -> request issued (guard stands down)
 *   list unresolved                           -> ZERO requests
 *   list errored                              -> ZERO requests
 *
 * The filtered-list case no longer exists: the M10B workspace has no list filters, so every list read
 * is either complete or truncated.
 *
 * "List unresolved" blocks because the list is empty, not because the guard knows it is unresolved:
 * with no data, the authoritative test passes and membership fails. Those two states are
 * indistinguishable to this expression, and the natural refactor — standing down while the list is
 * unresolved, to match the filtered and truncated cases — would change behaviour. Asserted as the
 * observable request behaviour rather than as the guard's reasoning, so that refactor fails loudly
 * instead of silently.
 */
describe('cross-review finding selection: full matrix', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('issues exactly one detail request for a member of an authoritative list', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-1')

    await waitFor(() => expect(findingDetailRequests(api)).toHaveLength(1))
    expect(findingDetailRequests(api)[0].url).toContain('/api/reviews/r-9/findings/f-1')
  })

  it('never requests a non-member of an authoritative list, and falls back to the review\'s own finding', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-from-another-review')

    // The workspace settles on the review's own first finding. Waiting for that request proves the
    // list had resolved, so the absence of a request for the foreign id is not an early read.
    await waitFor(() => expect(detailRequestFor(api, 'f-1')).toHaveLength(1))
    expect(detailRequestFor(api, 'f-from-another-review')).toEqual([])
    expect(findingDetailRequests(api).every((r) => r.url.includes('/api/reviews/r-9/findings/f-1'))).toBe(true)
  })

  it('never requests a finding absent from an authoritative list, even when the URL still names it', async () => {
    // Same state, framed as staleness rather than cross-review: an id the URL still names after the
    // review's findings changed.
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: listWithout(['f-1']),
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-2')

    await waitFor(() => expect(detailRequestFor(api, 'f-1')).toHaveLength(1))
    expect(detailRequestFor(api, 'f-2')).toEqual([])
  })

  it('requests the named member rather than the default finding', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-2')

    await waitFor(() => expect(detailRequestFor(api, 'f-2')).toHaveLength(1))
    expect(detailRequestFor(api, 'f-1')).toEqual([])
  })

  it('ALLOWS the request when the list is truncated, because a truncated absence proves nothing', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: listWithout([], true),
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-1')

    await waitFor(() => expect(findingDetailRequests(api)).toHaveLength(1))
  })

  it('issues ZERO detail requests while the list is unresolved, then decides from it', async () => {
    let releaseList: (() => void) | undefined
    const listGate = new Promise<void>((resolve) => {
      releaseList = resolve
    })
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findingsResponse: async () => {
        await listGate
        return jsonBody(fixtureFindingList)
      },
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-1')

    // The list request is in flight and has not answered, so no decision can be made yet.
    await waitFor(() =>
      expect(api.requests.some((r) => r.url.includes('/api/reviews/r-9/findings'))).toBe(true),
    )
    expect(findingDetailRequests(api)).toEqual([])

    releaseList?.()

    // Once the list resolves and contains the finding, the read proceeds.
    await waitFor(() => expect(findingDetailRequests(api)).toHaveLength(1))
  })

  it('issues ZERO detail requests when the list read fails', async () => {
    const api = stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findingsStatus: 500,
    })

    renderAppRoute('/reviews/c1/analysis/r-9?finding=f-1')

    await waitFor(() =>
      expect(api.requests.some((r) => r.url.includes('/api/reviews/r-9/findings'))).toBe(true),
    )
    await waitFor(() => expect(findingDetailRequests(api)).toEqual([]))
  })
})
