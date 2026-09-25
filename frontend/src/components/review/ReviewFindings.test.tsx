import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import {
  fixtureContractSummary,
  fixtureSemantics,
  stubContractApi,
  type StubOptions,
} from '../contract/testFixtures'
import {
  fixtureAbsenceFindingDetail,
  fixtureAbsenceFindingSummary,
  fixtureAmbiguousPolicyFindingDetail,
  fixtureAnalyzingReviewSummary,
  fixtureClauseFindingDetail,
  fixtureClauseFindingSummary,
  fixtureCompletedReviewSummary,
  fixtureCrossClauseFindingDetail,
  fixtureFindingList,
  fixtureGenerativeFindingDetail,
  fixturePolicyMissingFindingDetail,
  fixtureQueuedReviewSummary,
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
    <StrictMode>
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>
    </StrictMode>,
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

  it('maps filters to real API query parameters', async () => {
    const { api, router } = renderWorkspace('/reviews/c1/analysis/r-1', {
      findingsResponse: (url) => {
        const both =
          url.includes('severity=HIGH') && url.includes('classification=BLOCKING')
        return jsonResponse(
          envelope(
            both
              ? { items: [fixtureAbsenceFindingSummary], truncated: false }
              : fixtureFindingList,
          ),
        )
      },
    })

    expect(await screen.findByRole('button', { name: /Missing: Limitation/ })).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Severity'), 'HIGH')
    await userEvent.selectOptions(screen.getByLabelText('Classification'), 'BLOCKING')

    await waitFor(() =>
      expect(router.state.location.search).toContain('severity=HIGH'),
    )
    await waitFor(() =>
      expect(router.state.location.search).toContain('classification=BLOCKING'),
    )
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: /Liability cap deviation/ })).not.toBeInTheDocument(),
    )

    const listCalls = api.requests.filter((request) => request.url.includes('/findings?'))
    const lastCall = listCalls[listCalls.length - 1]
    const params = new URL(lastCall.url).searchParams
    expect(params.get('severity')).toBe('HIGH')
    expect(params.get('classification')).toBe('BLOCKING')
    expect(params.get('limit')).toBe('100')
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

  it('clears the selected finding when filters change', async () => {
    const { router } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-2')
    expect(await screen.findByRole('heading', { name: 'Missing provision' })).toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Severity'), 'HIGH')

    await waitFor(() => expect(router.state.location.search).toContain('severity=HIGH'))
    expect(router.state.location.search).not.toContain('finding=')
    expect(
      await screen.findByText(/Select a finding to see its observation/),
    ).toBeInTheDocument()
  })

  it('ignores filter values the API cannot express', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?severity=BOGUS')

    expect(await screen.findByLabelText('Severity')).toHaveValue('')
    const listCalls = api.requests.filter((request) => request.url.includes('/findings?'))
    expect(listCalls[0].url).not.toContain('severity=')
  })

  it('renders a safe error state when findings cannot be loaded', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', { findingsStatus: 500 })

    expect(
      await screen.findByText('Findings could not be loaded.', {}, { timeout: 4000 }),
    ).toBeInTheDocument()
  })

  it('renders a safe error state when analysis detail cannot be loaded', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', { reviewProgressStatus: 500 })

    await userEvent.click(await screen.findByText('Analysis detail'))
    expect(await screen.findByText('Analysis detail is unavailable.')).toBeInTheDocument()
  })

  it('attempts findings for an unrecognized review state and says so', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: { ...fixtureCompletedReviewSummary, status: 'CANCELLED' },
    })

    expect(
      await screen.findByText(/does not recognise\. Findings below are the latest persisted/),
    ).toBeInTheDocument()
    expect(api.requests.filter((request) => request.url.includes('/findings?'))).toHaveLength(1)
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

    expect(
      screen.getByRole('heading', { level: 3, name: 'Approved language' }),
    ).toBeInTheDocument()
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
    expect(source).not.toHaveTextContent('1. Definitions')
    expect(source).not.toHaveTextContent('5. Termination')
    expect(source).not.toHaveTextContent('Schedule A')
    expect(source).not.toHaveTextContent('6.3 The exclusions')
    expect(
      screen.getByRole('link', { name: 'View clause in contract' }),
    ).toHaveAttribute('href', '/reviews/c1?view=document&node=node-6')
  })

  it('reads the contract version pinned by the review, never the latest version', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      contractSummary: {
        ...fixtureContractSummary,
        latestVersion: { ...fixtureContractSummary.latestVersion, versionNumber: 2 },
      },
    })

    expect(await screen.findByRole('heading', { name: 'Contract clause' })).toBeInTheDocument()
    const structureCalls = api.requests.filter((request) => request.url.includes('/structure'))
    expect(structureCalls).toHaveLength(1)
    expect(structureCalls[0].url).toContain('/versions/1/structure')

    expect(
      screen.queryByRole('link', { name: 'View clause in contract' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByText(/This review is pinned to v1; the contract's current version is v2/),
    ).toBeInTheDocument()
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

  it('never fabricates a clause source for an absence finding with a stale node id', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-2', {
      findingDetailResponse: () =>
        jsonResponse(
          envelope({ ...fixtureAbsenceFindingDetail, subjectHeadingNodeId: 'node-6' }),
        ),
    })

    expect(await screen.findByRole('heading', { name: 'Missing provision' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Contract clause' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: 'View clause in contract' }),
    ).not.toBeInTheDocument()
  })

  it('renders a cross-clause finding without faking counterpart navigation', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-6')

    expect(
      await screen.findByText(/not covered by a survival provision/),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Contract clause' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /related|corresponding|counterpart/i })).not.toBeInTheDocument()
  })

  it('does not claim approved language when the drafting evidence id is absent', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      findingDetailResponse: () =>
        jsonResponse(
          envelope({ ...fixtureClauseFindingDetail, approvedDraftingEvidenceId: null }),
        ),
    })

    expect(await screen.findByRole('heading', { name: 'Observation' })).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { level: 3, name: 'Approved language' }),
    ).not.toBeInTheDocument()
    expect(
      screen.queryByText(/two times the fees paid in the twelve months/),
    ).not.toBeInTheDocument()
  })
})

describe('finding evidence amendment', () => {
  it('groups resolved evidence by authority and links claims to exact items', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    expect(await screen.findByRole('heading', { name: 'Evidence' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Policy' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Approved drafting' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Current contract' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Precedent' })).toBeInTheDocument()

    expect(document.getElementById('finding-evidence-e-contract')).not.toBeNull()
    expect(document.getElementById('finding-evidence-e-drafting')).not.toBeNull()

    expect(document.querySelectorAll('a[href="#finding-evidence-e-policy"]').length).toBeGreaterThan(0)
    expect(document.querySelectorAll('a[href="#finding-evidence-e-drafting"]').length).toBeGreaterThan(0)
  })

  it('navigates cross-clause counterparts from backend-provided source identity', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-6')

    expect(await screen.findByRole('heading', { name: 'Related provisions' })).toBeInTheDocument()
    const counterpart = document.querySelector(
      'a[href="/reviews/c1?view=document&node=node-5"]',
    )
    expect(counterpart).not.toBeNull()
    expect(document.querySelector('a[href*="node-63"]')).toBeNull()
  })

  it('fails safely when a claim references evidence missing from the response', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      findingDetailResponse: () =>
        jsonResponse(
          envelope({
            ...fixtureClauseFindingDetail,
            claims: fixtureClauseFindingDetail.claims.map((claim, index) =>
              index === 1 ? { ...claim, evidenceItemIds: ['e-missing'] } : claim,
            ),
          }),
        ),
    })

    expect(
      await screen.findByText(/not available in this response; they are not shown as grounded/),
    ).toBeInTheDocument()
    expect(screen.getByText('unavailable in this response')).toBeInTheDocument()
    expect(document.querySelector('a[href="#finding-evidence-e-missing"]')).toBeNull()
  })

  it('does not claim approved language when the drafting evidence is unresolvable', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      findingDetailResponse: () =>
        jsonResponse(
          envelope({
            ...fixtureClauseFindingDetail,
            approvedDraftingEvidenceId: 'e-dangling',
          }),
        ),
    })

    expect(await screen.findByRole('heading', { name: 'Observation' })).toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { level: 3, name: 'Approved language' }),
    ).not.toBeInTheDocument()
  })

  it('never shows the clean-completion badge when coverage is limited', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: {
        ...fixtureCompletedReviewSummary,
        findings: 0,
        degradedChecks: 0,
        analysisWarnings: [],
        coverage: [
          {
            concept: 'LIMITATION_OF_LIABILITY',
            expectation: 'REQUIRED',
            coverage: 'PRESENT_SECONDARY_ONLY',
          },
        ],
      },
      findings: { items: [], truncated: false },
    })

    expect(
      await screen.findByText(/No findings were recorded, but review coverage is limited/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Every planned check completed without findings.'),
    ).not.toBeInTheDocument()
  })

  it('says the pack context is unavailable when the snapshot is missing', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: { ...fixtureQueuedReviewSummary, snapshot: null },
    })

    const heading = await screen.findByText('Contract pack')
    const row = heading.closest('.data-list__row') as HTMLElement
    expect(within(row).getByText('Pack context unavailable')).toBeInTheDocument()
  })

  it('uses only resolved evidence and never rebuilds retrieval', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await screen.findByRole('heading', { name: 'Evidence' })
    expect(api.requests.filter((request) => request.url.includes('/api/retrieval/'))).toHaveLength(0)
    expect(
      api.requests.filter(
        (request) => request.method === 'POST' && request.url.includes('/api/retrieval/evidence'),
      ),
    ).toHaveLength(0)
  })

  it('shows the frozen review profile, not current contract profile data', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', {
      semantics: {
        ...fixtureSemantics(),
        reviewProfile: {
          contractType: 'MSA',
          ourSideRole: 'SUPPLIER',
          paperSource: 'OUR_PAPER',
          jurisdiction: 'US',
        },
      },
    })

    const heading = await screen.findByText('Review state')
    const context = heading.closest('dl') as HTMLElement
    expect(within(context).getByText('United Kingdom')).toBeInTheDocument()
    expect(within(context).getByText('Customer')).toBeInTheDocument()
    expect(within(context).queryByText('United States')).not.toBeInTheDocument()
  })

  it('presents every coverage state distinctly and never as clean', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: {
        ...fixtureCompletedReviewSummary,
        findings: 0,
        coverage: [
          { concept: 'LIMITATION_OF_LIABILITY', expectation: 'REQUIRED', coverage: 'PRESENT_PRIMARY' },
          { concept: 'CONFIDENTIALITY', expectation: 'EXPECTED', coverage: 'PRESENT_SECONDARY_ONLY' },
          { concept: 'TERM_TERMINATION', expectation: 'REQUIRED', coverage: 'ABSENT' },
          { concept: 'INDEMNIFICATION', expectation: 'OPTIONAL', coverage: 'UNKNOWN' },
        ],
      },
      findings: { items: [], truncated: false },
    })

    expect(await screen.findByRole('heading', { name: 'Review coverage' })).toBeInTheDocument()
    expect(screen.getByText('Reviewed as a primary clause')).toBeInTheDocument()
    expect(screen.getByText('Detected as a secondary mention only')).toBeInTheDocument()
    expect(screen.getByText('Not found in the contract')).toBeInTheDocument()
    expect(screen.getByText('Coverage not established')).toBeInTheDocument()
    expect(screen.getByText(/Review coverage is limited for:/)).toBeInTheDocument()
    expect(
      await screen.findByText(/recorded no findings, but review coverage is limited/),
    ).toBeInTheDocument()
    expect(api.requests.filter((r) => r.url.includes('/findings?')).length).toBeGreaterThan(0)
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
    await screen.findByRole('heading', { level: 3, name: 'Approved language' })
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
    await screen.findByRole('heading', { level: 3, name: 'AI-proposed language' })
    const generativeText = document.body.textContent ?? ''
    expect(generativeText).not.toMatch(/Approved language/)
  })
})