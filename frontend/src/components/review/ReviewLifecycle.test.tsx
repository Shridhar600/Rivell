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

/** Opens the Review notes sheet from the notes banner, once the workspace has loaded. */
async function openReviewNotes() {
  await userEvent.click(await screen.findByRole('button', { name: 'Review coverage and notes' }))
  return screen.findByRole('dialog', { name: 'Review notes' })
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

  it('shows completed state with humanized degradation warnings in the review notes', async () => {
    renderReview(fixtureCompletedReviewSummary)

    // A completed review opens the workspace, not the lifecycle panel: warnings are summarised in the
    // notes banner and listed in the Review notes sheet.
    expect(await screen.findByText('1 analysis warning.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Analysis state' })).not.toBeInTheDocument()

    const sheet = await openReviewNotes()
    expect(
      within(sheet).getByText('An external analysis provider was unavailable for this check.'),
    ).toBeInTheDocument()
    const check = within(sheet).getByText('Liability cap · Limitation of liability')
    expect(check).toBeInTheDocument()
  })

  it('renders a technical failure distinctly and never leaks the raw reason', async () => {
    renderReview(fixtureFailedReviewSummary)

    const failure = await screen.findByRole('alert')
    expect(failure).toHaveTextContent(/The automated review could not be completed/)
    expect(screen.queryByText(/IllegalStateException/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Analysis warnings/)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to review history' })).toHaveAttribute(
      'href',
      '/reviews/c1/analysis',
    )
  })

  it('does not claim a failed review recorded no findings', async () => {
    renderReview(fixtureFailedReviewSummary)

    await screen.findByRole('alert')
    const findings = screen.getByRole('complementary', { name: 'Findings' })
    await waitFor(() =>
      expect(within(findings).queryByText(/Loading findings/)).not.toBeInTheDocument(),
    )
    expect(within(findings).queryByText(/recorded none/)).not.toBeInTheDocument()
    expect(within(findings).getByText(/did not complete an analysis/)).toBeInTheDocument()
  })

  it('never claims every check completed when checks failed', async () => {
    renderReview({
      ...fixtureCompletedReviewSummary,
      findings: 0,
      degradedChecks: 0,
      failedChecks: 2,
      analysisWarnings: [
        {
          checkKey: 'LIABILITY_CAP:LIMITATION_OF_LIABILITY',
          status: 'FAILED',
          reasonCode: 'CHECK_FAILED',
          message: '',
        },
      ],
    })

    expect(await screen.findByText('1 analysis warning.')).toBeInTheDocument()
    const sheet = await openReviewNotes()
    expect(
      within(sheet).getByText('This check did not complete; its result is missing.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Every planned check completed without findings.'),
    ).not.toBeInTheDocument()
  })

  it('never echoes an unknown warning reason to the user', async () => {
    renderReview({
      ...fixtureCompletedReviewSummary,
      analysisWarnings: [
        {
          checkKey: 'CARVE_OUT:INDEMNIFICATION',
          status: 'DEGRADED',
          reasonCode: 'SOMETHING_NEW',
          message: '',
          reason: 'IllegalStateException: provider com.acme.Model exploded at line 42',
        },
      ],
    })

    const sheet = await openReviewNotes()
    expect(within(sheet).getByText(/This check ran in a degraded mode/)).toBeInTheDocument()
    expect(screen.queryByText(/com\.acme\.Model/)).not.toBeInTheDocument()
  })

  it('offers a manual refresh when the review status is unknown', async () => {
    const api = renderReview({ ...fixtureQueuedReviewSummary, status: 'REANALYZING' })
    const summaryCalls = () =>
      api.requests.filter(
        (request) =>
          request.method === 'GET' && /\/api\/reviews\/r-1$/.test(request.url),
      ).length

    expect(await screen.findByText(/does not recognise\. It may still be running/)).toBeInTheDocument()
    const before = summaryCalls()

    await userEvent.click(screen.getByRole('button', { name: 'Check again' }))

    await waitFor(() => expect(summaryCalls()).toBeGreaterThan(before))
  })

  it('gives a clean completed review a meaningful state', async () => {
    renderReview(fixtureCleanReviewSummary)

    const findings = await screen.findByRole('complementary', { name: 'Findings' })
    expect(
      await within(findings).findByText(
        'No findings. The automated review recorded none for this contract version.',
      ),
    ).toBeInTheDocument()
    // Nothing is claimed beyond what the server said, and nothing is flagged as an error.
    expect(screen.queryByText('Every planned check completed without findings.')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  // A completed review no longer renders the lifecycle panel, so the "newer analysis attempt" note
  // survives only on the panel's non-completed states. See the report: it is a product gap.
  it('notes when a newer analysis attempt exists', async () => {
    renderReview({
      ...fixtureFailedReviewSummary,
      latestRunStatus: 'FAILED',
      activeRun: { id: 'run-1', runNumber: 1, status: 'COMPLETED' },
    })

    expect(await screen.findByText(/A newer analysis attempt exists/)).toBeInTheDocument()
    expect(screen.getByText(/Run failed/)).toBeInTheDocument()
  })
})
