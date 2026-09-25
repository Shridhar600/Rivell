import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { stubContractApi } from '../contract/testFixtures'
import {
  fixtureAnalyzingReviewSummary,
  fixtureCleanReviewSummary,
  fixtureCompletedReviewSummary,
  fixtureFailedReviewSummary,
  fixtureQueuedReviewSummary,
} from './testFixtures'

function renderReview(summary: unknown, options: Record<string, unknown> = {}) {
  const api = stubContractApi({ reviewSummary: summary, ...options })
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/reviews/c1/analysis/r-1'],
  })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return api
}

function progressRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter((request) => request.url.includes('/progress'))
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('review lifecycle states', () => {
  it('shows the queued state and fetches progress only on disclosure', async () => {
    const api = renderReview(fixtureQueuedReviewSummary, {
      reviewProgress: {
        reviewStatus: 'ANALYSIS_QUEUED',
        ...fixtureQueuedReviewSummary,
        pendingChecks: 3,
        runningChecks: 0,
        skippedChecks: 0,
      },
    })

    expect(await screen.findByText(/The review is queued/)).toBeInTheDocument()
    expect(screen.getByText('0 of 3 checks completed (0%)')).toBeInTheDocument()
    expect(progressRequests(api)).toHaveLength(0)

    await userEvent.click(screen.getByText('Analysis detail'))

    await waitFor(() => expect(progressRequests(api)).toHaveLength(1))
    const counts = await screen.findByLabelText('Analysis check counts')
    expect(within(counts).getByText('Waiting').nextElementSibling).toHaveTextContent('3')
  })

  it('shows analyzing progress derived from real counts', async () => {
    renderReview(fixtureAnalyzingReviewSummary)

    expect(await screen.findByText(/analysing this contract version/)).toBeInTheDocument()
    expect(screen.getByText('1 of 3 checks completed (33%)')).toBeInTheDocument()
  })

  it('shows completed state with humanized degradation warnings', async () => {
    renderReview(fixtureCompletedReviewSummary)

    expect(await screen.findByText('2 findings from 3 completed checks.')).toBeInTheDocument()
    expect(
      screen.getByText(
        'A bounded semantic decision was unavailable; the clause was not classified.',
      ),
    ).toBeInTheDocument()
    const check = screen.getByTitle('LIABILITY_CAP:LIMITATION_OF_LIABILITY')
    expect(check).toHaveTextContent('Liability cap · Limitation of liability')
  })

  it('renders a technical failure distinctly and never leaks the raw reason', async () => {
    renderReview(fixtureFailedReviewSummary)

    expect(await screen.findByText('Failed')).toBeInTheDocument()
    expect(
      screen.getByText(/The automated review could not be completed/),
    ).toBeInTheDocument()
    expect(screen.queryByText(/IllegalStateException/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Analysis warnings/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to review history' })).toHaveAttribute(
      'href',
      '/reviews/c1/analysis',
    )
  })

  it('offers a manual refresh when the review status is unknown', async () => {
    const api = renderReview({ ...fixtureQueuedReviewSummary, status: 'REANALYZING' })
    const summaryCalls = () =>
      api.requests.filter(
        (request) =>
          request.method === 'GET' && /\/api\/reviews\/r-1$/.test(request.url),
      ).length

    expect(await screen.findByText(/does not recognise/)).toBeInTheDocument()
    const before = summaryCalls()

    await userEvent.click(screen.getByRole('button', { name: 'Check again' }))

    await waitFor(() => expect(summaryCalls()).toBeGreaterThan(before))
  })

  it('gives a clean completed review a meaningful state', async () => {
    renderReview(fixtureCleanReviewSummary)

    expect(
      await screen.findByText(
        'The automated review found no findings for this contract version.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('Every planned check completed without findings.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('notes when a newer analysis attempt exists', async () => {
    renderReview({
      ...fixtureCompletedReviewSummary,
      latestRunStatus: 'FAILED',
      activeRun: { id: 'run-1', runNumber: 1, status: 'COMPLETED' },
    })

    expect(await screen.findByText(/A newer analysis attempt exists/)).toBeInTheDocument()
    expect(screen.getByText(/Run failed/)).toBeInTheDocument()
  })
})