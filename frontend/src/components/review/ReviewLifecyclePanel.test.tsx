import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { ReviewLifecyclePanel } from './ReviewLifecyclePanel'
import { fixtureCleanReviewSummary } from './testFixtures'
import type { ReviewSummary } from '../../api/reviews'

function renderPanel(summary: ReviewSummary) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
  return render(
    <ReviewLifecyclePanel
      reviewId="r-1"
      summary={summary}
      coverageLimited={false}
      onRefresh={() => undefined}
    />,
    { wrapper },
  )
}

describe('ReviewLifecyclePanel', () => {
  it('never claims every planned check completed when none were planned', () => {
    renderPanel({
      ...fixtureCleanReviewSummary,
      plannedChecks: 0,
      completedChecks: 0,
      findings: 0,
      degradedChecks: 0,
      failedChecks: 0,
    } as unknown as ReviewSummary)

    expect(
      screen.queryByText('Every planned check completed without findings.'),
    ).not.toBeInTheDocument()
    expect(screen.getByText(/No checks were planned/)).toBeInTheDocument()
  })

  it('still says every planned check completed when checks were planned', () => {
    renderPanel({
      ...fixtureCleanReviewSummary,
      plannedChecks: 2,
      completedChecks: 2,
      findings: 0,
      degradedChecks: 0,
      failedChecks: 0,
    } as unknown as ReviewSummary)

    expect(
      screen.getByText('Every planned check completed without findings.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/No checks were planned/)).not.toBeInTheDocument()
  })
})
