import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, waitFor } from '@testing-library/react'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { stubContractApi } from '../components/contract/testFixtures'
import { fixtureCompletedReviewSummary } from '../components/review/testFixtures'
import { clearResume, readResume, writeResume } from '../lib/resume'

function renderAppRoute(entry: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [entry] })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  clearResume()
})

describe('the Review route remembers where the reviewer was', () => {
  it('saves the Review, its contract and its filename once the Review has loaded', async () => {
    stubContractApi({ reviewSummary: fixtureCompletedReviewSummary })
    renderAppRoute('/reviews/c1/analysis/r-1')
    await waitFor(() => expect(readResume()).not.toBeNull())
    expect(readResume()).toMatchObject({
      reviewId: 'r-1',
      contractId: 'c1',
      filename: 'msa.docx',
      versionNumber: 1,
    })
  })

  it('saves nothing when the Review never loads', async () => {
    stubContractApi({ reviewSummary: fixtureCompletedReviewSummary, reviewSummaryStatus: 500 })
    renderAppRoute('/reviews/c1/analysis/r-1')
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(readResume()).toBeNull()
  })

  it('does not save a Review that belongs to a different contract than the route names', async () => {
    stubContractApi({ reviewSummary: { ...fixtureCompletedReviewSummary, contractId: 'other' } })
    renderAppRoute('/reviews/c1/analysis/r-1')
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(readResume()).toBeNull()
  })

  it('clears the saved place when that Review turns out not to exist', async () => {
    writeResume({
      reviewId: 'r-1',
      contractId: 'c1',
      filename: 'msa.docx',
      versionNumber: 1,
      visitedAt: '2026-10-03T09:00:00.000Z',
    })
    stubContractApi({ reviewSummaryStatus: 404 })
    renderAppRoute('/reviews/c1/analysis/r-1')
    await waitFor(() => expect(readResume()).toBeNull())
  })

  it('leaves a different saved Review alone when an earlier one is missing', async () => {
    writeResume({
      reviewId: 'r-other',
      contractId: 'c1',
      filename: 'other.docx',
      versionNumber: 1,
      visitedAt: '2026-10-03T09:00:00.000Z',
    })
    stubContractApi({ reviewSummaryStatus: 404 })
    renderAppRoute('/reviews/c1/analysis/r-1')
    await new Promise((resolve) => setTimeout(resolve, 150))
    expect(readResume()?.reviewId).toBe('r-other')
  })
})
