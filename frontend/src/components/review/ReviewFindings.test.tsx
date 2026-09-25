import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { stubContractApi, type StubOptions } from '../contract/testFixtures'
import {
  fixtureAbsenceFindingDetail,
  fixtureAmbiguousPolicyFindingDetail,
  fixtureAnalyzingReviewSummary,
  fixtureClauseFindingDetail,
  fixtureClauseFindingSummary,
  fixtureCompletedReviewSummary,
  fixtureCrossClauseFindingDetail,
  fixtureFindingList,
  fixtureGenerativeFindingDetail,
  fixturePolicyMissingFindingDetail,
} from './testFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-25T10:00:00Z' }
}

const detailById: Record<string, unknown> = {
  'f-1': fixtureClauseFindingDetail,
  'f-2': fixtureAbsenceFindingDetail,
  'f-3': fixturePolicyMissingFindingDetail,
  'f-4': fixtureAmbiguousPolicyFindingDetail,
  'f-5': fixtureGenerativeFindingDetail,
  'f-6': fixtureCrossClauseFindingDetail,
}

function renderWorkspace(initialEntry = '/reviews/c1/analysis/r-1', options: StubOptions = {}) {
  const api = stubContractApi({
    reviewSummary: fixtureCompletedReviewSummary,
    findings: fixtureFindingList,
    ...(options.findingDetailResponse || options.findingDetailStatus || options.findingDetail
      ? {}
      : {
          findingDetailResponse: (findingId: string) =>
            jsonResponse(envelope(detailById[findingId] ?? null)),
        }),
    ...options,
  })
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { api, router }
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('findings list', () => {
  it('lists findings with distinguishable subjects and opens one', async () => {
    renderWorkspace()

    const clauseRow = await screen.findByRole('button', {
      name: /6\. Limitation of Liability/,
    })
    expect(clauseRow).toHaveTextContent('Liability cap deviation')
    expect(clauseRow).toHaveTextContent('High')
    expect(clauseRow).toHaveTextContent('Review required')

    expect(
      screen.getByRole('button', { name: /Missing: Limitation of liability/ }),
    ).toHaveTextContent('Missing required provision')

    await userEvent.click(clauseRow)

    expect(
      await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' }),
    ).toBeInTheDocument()
  })

  it('filters through the API and keeps the filter in the URL', async () => {
    const { api, router } = renderWorkspace('/reviews/c1/analysis/r-1', {
      findingsResponse: (url) =>
        jsonResponse(
          envelope(
            url.includes('severity=HIGH')
              ? { items: [fixtureClauseFindingSummary], truncated: false }
              : fixtureFindingList,
          ),
        ),
    })

    expect(await screen.findByRole('button', { name: /Missing: Limitation/ })).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Severity'), 'HIGH')

    await waitFor(() =>
      expect(router.state.location.search).toContain('severity=HIGH'),
    )
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Missing: Limitation/ })).not.toBeInTheDocument(),
    )
    const listCalls = api.requests.filter((request) => request.url.includes('/findings?'))
    expect(listCalls.some((request) => request.url.includes('severity=HIGH'))).toBe(true)
  })

  it('keeps two findings on the same clause distinct', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', {
      findings: {
        items: [
          fixtureClauseFindingSummary,
          {
            ...fixtureClauseFindingSummary,
            id: 'f-9',
            issueFamily: 'LIABILITY_CAP_UNCERTAIN',
            severity: 'MEDIUM',
          },
        ],
        truncated: false,
      },
    })

    const rows = await screen.findAllByRole('button', { name: /6\. Limitation of Liability/ })
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Liability cap deviation')
    expect(rows[1]).toHaveTextContent('Liability cap uncertainty')
  })

  it('does not request findings until the review is terminal', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: fixtureAnalyzingReviewSummary,
    })

    expect(
      await screen.findByText('Findings appear here when the automated analysis completes.'),
    ).toBeInTheDocument()
    expect(api.requests.filter((request) => request.url.includes('/findings'))).toHaveLength(0)
  })
})

describe('finding detail', () => {
  it('separates observation, policy basis and recommendation with approved language', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    expect(await screen.findByRole('heading', { name: 'Observation' })).toBeInTheDocument()
    expect(
      screen.getByText(/liability cap above the organization’s preferred position/),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Why' })).toBeInTheDocument()
    expect(screen.getByText('Policy basis')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Recommended action' })).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: 'Approved language' })).toBeInTheDocument()
    expect(screen.getByText('Approved')).toBeInTheDocument()
    expect(
      screen.getByText(/two times the fees paid in the twelve months/),
    ).toBeInTheDocument()
    expect(screen.queryByText('AI-proposed language')).not.toBeInTheDocument()
    expect(screen.queryByText(/Accept|Reject|Escalate|Resolved/)).not.toBeInTheDocument()
  })

  it('synchronizes a clause finding with its exact contract source', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    expect(await screen.findByRole('heading', { name: 'Contract clause' })).toBeInTheDocument()
    const source = screen.getByRole('article', { name: 'Contract clause source' })
    expect(source).toHaveTextContent('6. Limitation of Liability')
    expect(
      screen.getByRole('link', { name: 'View clause in contract' }),
    ).toHaveAttribute('href', '/reviews/c1?view=document&node=node-6')
  })

  it('represents an absence finding without a document location', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-2')

    expect(await screen.findByRole('heading', { name: 'Missing provision' })).toBeInTheDocument()
    expect(screen.getByText('Required provision missing:')).toBeInTheDocument()
    expect(screen.getByText(/Presence · Limitation of liability/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Contract clause' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /View clause in contract/ })).not.toBeInTheDocument()
  })

  it('labels generative language as machine-proposed, never approved', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-5')

    expect(await screen.findByRole('heading', { name: 'AI-proposed language' })).toBeInTheDocument()
    expect(screen.getByText('Machine-proposed')).toBeInTheDocument()
    expect(
      screen.getByText(/It is not approved organizational language/),
    ).toBeInTheDocument()
    expect(screen.queryByText('Approved')).not.toBeInTheDocument()
  })

  it('keeps policy-missing findings honest about absent policy', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-3')

    expect(
      await screen.findByText(/No organizational policy covers this clause/),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/does not establish policy\./),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Approved language' })).not.toBeInTheDocument()
  })

  it('preserves policy ambiguity without selecting a winner', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-4')

    expect(
      await screen.findByText(/Rivell did not choose between them/),
    ).toBeInTheDocument()
    expect(screen.getByText(/More than one published policy applies/)).toBeInTheDocument()
  })

  it('renders uncertainty factors without a confidence percentage', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-3')

    expect(await screen.findByRole('heading', { name: 'System assessment' })).toBeInTheDocument()
    expect(screen.getByText('Policy evidence missing')).toBeInTheDocument()
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  it('shows a safe state when the selected finding cannot be loaded', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', { findingDetailStatus: 404 })

    expect(await screen.findByText('This finding could not be loaded.')).toBeInTheDocument()
  })
})

describe('review workspace authority and network guards', () => {
  it('reads persisted state only: no create, no evidence, no trace requests on load', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await screen.findByRole('heading', { name: 'Observation' })

    expect(api.requests.filter((request) => request.method === 'POST')).toHaveLength(0)
    expect(api.requests.filter((request) => request.url.includes('/retrieval/'))).toHaveLength(0)
  })

  it('introduces no human-decision or provider vocabulary', async () => {
    stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
      findingDetailResponse: () => jsonResponse(envelope(fixtureClauseFindingDetail)),
    })
    const first = render(
      <AppProviders>
        <RouterProvider
          router={createMemoryRouter(appRoutes, {
            initialEntries: ['/reviews/c1/analysis/r-1?finding=f-1'],
          })}
        />
      </AppProviders>,
    )
    await screen.findByRole('heading', { name: 'Approved language' })
    const approvedText = document.body.textContent ?? ''
    expect(approvedText).toMatch(/Approved language/)
    expect(approvedText).not.toMatch(/ApprovedChange|Mark as approved|Resolved/)
    expect(approvedText).not.toMatch(/\bJev\b|OpenAI|OpenRouter|embedding|rerank/i)
    expect(
      screen.queryByRole('button', { name: /Accept|Reject|Approve|Escalate|Resolve/i }),
    ).not.toBeInTheDocument()
    first.unmount()

    stubContractApi({
      reviewSummary: fixtureCompletedReviewSummary,
      findings: fixtureFindingList,
      findingDetailResponse: () => jsonResponse(envelope(fixtureGenerativeFindingDetail)),
    })
    render(
      <AppProviders>
        <RouterProvider
          router={createMemoryRouter(appRoutes, {
            initialEntries: ['/reviews/c1/analysis/r-1?finding=f-5'],
          })}
        />
      </AppProviders>,
    )
    await screen.findByRole('heading', { name: 'AI-proposed language' })
    const generativeText = document.body.textContent ?? ''
    expect(generativeText).not.toMatch(/Approved language/)
  })
})