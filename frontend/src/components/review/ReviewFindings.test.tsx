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
  fixtureFailedReviewSummary,
  fixtureGenerativeFindingDetail,
  fixturePolicyMissingFindingDetail,
} from './testFixtures'
import { fixtureHumanReview } from './workflowFixtures'

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

/**
 * The review's findings, as the list endpoint would actually return them for this review. Every id
 * here has a detail below, so the list and the detail endpoint agree; the workspace only requests
 * a finding it can see in this review's own list.
 */
const absenceFinding = (id: string, concept: string) => ({
  ...fixtureAbsenceFindingSummary,
  id,
  absenceConcept: concept,
  concept,
})

const findingsForThisReview = {
  items: [
    fixtureClauseFindingSummary,
    fixtureAbsenceFindingSummary,
    absenceFinding('f-3', 'GOVERNING_LAW'),
    absenceFinding('f-4', 'CONFIDENTIALITY'),
    absenceFinding('f-5', 'INDEMNIFICATION'),
    absenceFinding('f-6', 'TERM_TERMINATION'),
  ],
  truncated: false,
}

function renderWorkspace(initialEntry = '/reviews/c1/analysis/r-1', options: StubOptions = {}) {
  const api = stubContractApi({
    reviewSummary: fixtureCompletedReviewSummary,
    findings: findingsForThisReview,
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

/** The workspace's three regions. A closed command palette also holds role=option nodes, so the
 *  Findings list is always reached through its own listbox. */
const findingsList = () => within(screen.getByRole('listbox', { name: 'Findings list' }))
async function findFindingsList() {
  return within(await screen.findByRole('listbox', { name: 'Findings list' }))
}
const detailPane = () => screen.getByRole('complementary', { name: 'Finding detail' })
const contractPane = () => screen.getByRole('main', { name: 'Contract' })

/** A section of the detail panel. Its header is plain text, not a heading. */
function section(title: string): HTMLElement {
  const header = within(detailPane()).getByText(title)
  return header.closest('.rv-ws-sec') as HTMLElement
}

/** Waits for the detail panel to show the finding with this title. */
async function findDetailTitle(title: string | RegExp) {
  // The pane is looked up again on every attempt: it is redrawn as the finding loads.
  return waitFor(() => within(detailPane()).getByRole('heading', { level: 2, name: title }))
}

/** Waits for a section header to appear in the detail panel. */
async function findDetailText(text: string) {
  return waitFor(() => within(detailPane()).getByText(text))
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
    const { router } = renderWorkspace('/reviews/c1/analysis/r-1', {
      humanReview: fixtureHumanReview({ actionableFindings: 6, remainingFindings: 6 }),
    })

    const clauseRow = await (await findFindingsList()).findByRole('option', {
      name: /6\. Limitation of Liability/,
    })
    // Decision state shows only once the server's decisions and human review have loaded.
    await waitFor(() => expect(clauseRow).toHaveTextContent('Needs decision · Liability cap deviation'))

    const absenceRow = findingsList().getByRole('option', { name: /Missing: Limitation of liability/ })
    expect(absenceRow).toHaveTextContent('Missing required provision')
    expect(absenceRow).toHaveTextContent('Not in contract')
    expect(clauseRow).not.toHaveTextContent('Not in contract')

    // The navigator counts what the server says is unresolved.
    expect(
      within(screen.getByRole('complementary', { name: 'Findings' })).getByText('6 unresolved'),
    ).toBeInTheDocument()

    await userEvent.click(absenceRow)

    expect(await findDetailTitle('Missing: Limitation of liability')).toBeInTheDocument()
    expect(router.state.location.search).toContain('finding=f-2')
    expect(absenceRow).toHaveAttribute('aria-selected', 'true')
    expect(clauseRow).toHaveAttribute('aria-selected', 'false')
  })

  it('selects the first finding when the URL names none, and records it in the URL', async () => {
    const { router } = renderWorkspace()

    expect(await findDetailTitle('6. Limitation of Liability')).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.search).toContain('finding=f-1'))
    expect(
      findingsList().getByRole('option', { name: /6\. Limitation of Liability/ }),
    ).toHaveAttribute('aria-selected', 'true')
  })

  it('does not show decision state until the server state it comes from has loaded', async () => {
    // No human-review state is available, so the list names the issue but claims no decision state.
    renderWorkspace()

    const clauseRow = await (await findFindingsList()).findByRole('option', {
      name: /6\. Limitation of Liability/,
    })
    expect(clauseRow).toHaveTextContent('Liability cap deviation')
    expect(clauseRow).not.toHaveTextContent(/Needs decision|Accepted|Rejected|Escalated|Informational/)
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

    const rows = await (await findFindingsList()).findAllByRole('option', { name: /6\. Limitation of Liability/ })
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Liability cap deviation')
    expect(rows[1]).toHaveTextContent('Liability cap uncertainty')
  })

  it('requests the review findings with a fixed limit and never forwards URL filters', async () => {
    // Severity, classification and concept filters no longer exist in the workspace. A stale link
    // carrying them must not reach the API.
    const { api } = renderWorkspace(
      '/reviews/c1/analysis/r-1?severity=HIGH&classification=BLOCKING&concept=CONFIDENTIALITY',
    )

    await (await findFindingsList()).findByRole('option', { name: /6\. Limitation of Liability/ })
    const listCalls = api.requests.filter((request) => request.url.includes('/findings?'))
    expect(listCalls.length).toBeGreaterThan(0)
    for (const call of listCalls) {
      const params = new URL(call.url).searchParams
      expect(params.get('limit')).toBe('100')
      expect(params.has('severity')).toBe(false)
      expect(params.has('classification')).toBe(false)
      expect(params.has('concept')).toBe(false)
    }
    expect(screen.queryByLabelText('Severity')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Classification')).not.toBeInTheDocument()
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

  it('renders a safe error state when findings cannot be loaded', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', { findingsStatus: 500 })

    expect(
      await screen.findByText('Findings could not be loaded.', {}, { timeout: 4000 }),
    ).toBeInTheDocument()
  })

  it('renders a safe error state when analysis detail cannot be loaded', async () => {
    // The analysis lifecycle panel sits in the centre column for a review that is not COMPLETED.
    renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: fixtureFailedReviewSummary,
      reviewProgressStatus: 500,
    })

    await userEvent.click(await screen.findByText('Analysis detail'))
    expect(await screen.findByText('Analysis detail is unavailable.')).toBeInTheDocument()
  })

  it('attempts findings for an unrecognized review state and says so', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: { ...fixtureCompletedReviewSummary, status: 'CANCELLED' },
    })

    expect(await screen.findByText(/does not recognise/)).toBeInTheDocument()
    await (await findFindingsList()).findByRole('option', { name: /6\. Limitation of Liability/ })
    expect(api.requests.filter((request) => request.url.includes('/findings?'))).toHaveLength(1)
    // No decision controls are offered for a review the server has not reported as COMPLETED.
    expect(
      within(detailPane()).getByText('Decisions open when the automated analysis completes.'),
    ).toBeInTheDocument()
  })
})

describe('finding detail', () => {
  it('separates observation, policy basis and recommendation with approved language', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    const found = section('Rivell found')
    expect(found).toHaveTextContent(/liability cap above the organization’s preferred position/)
    expect(found).toHaveTextContent('Liability cap above the preferred position')
    expect(found).toHaveTextContent(
      'Policy basis: Published policy prefers a cap of two times the fees paid over a twelve-month period.',
    )
    expect(found).toHaveTextContent(
      'Recommendation: Use the approved fallback wording. Replace the clause with the approved fallback wording for the liability cap.',
    )

    // The proposed wording is labelled with where it came from.
    const proposed = section('Proposed language')
    expect(proposed).toHaveTextContent(/two times the fees paid in the twelve months/)
    expect(proposed).toHaveTextContent('Approved organizational drafting')
    expect(proposed).toHaveTextContent('Publication 2')
    expect(within(detailPane()).queryByText('Machine-proposed')).not.toBeInTheDocument()

    // The finding's policy position is shown on its own, apart from what Rivell concluded.
    expect(section('Against your position')).toHaveTextContent(
      'Liability must be capped at fees paid in the preceding 12 months.',
    )
  })

  it('shows the exact clause text under "In the contract" and in the document', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    // The clause is its heading and the top-level passages after it, up to the next heading.
    await waitFor(() =>
      expect(section('In the contract')).toHaveTextContent(
        '6.3 The exclusions in Section 6.2 do not apply to breaches of Section 8.',
      ),
    )
    const source = section('In the contract')
    expect(source).toHaveTextContent('6. Limitation of Liability')
    expect(source).not.toHaveTextContent('1. Definitions')
    expect(source).not.toHaveTextContent('5. Termination')
    expect(source).not.toHaveTextContent('Schedule A')
    expect(within(source).getByRole('button', { name: 'Show in document' })).toBeInTheDocument()

    // The same clause is marked as selected in the centre document, and only that clause.
    const selected = contractPane().querySelector('[data-sel="1"]') as HTMLElement
    expect(selected).not.toBeNull()
    expect(selected).toHaveTextContent('6. Limitation of Liability')
    expect(selected).toHaveTextContent('6.3 The exclusions in Section 6.2')
    expect(selected).not.toHaveTextContent('5. Termination')
    expect(selected).not.toHaveTextContent('Schedule A')
    expect(contractPane().querySelectorAll('[data-sel="1"]')).toHaveLength(1)
  })

  it('says so when the clause text for this version is not available', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      findingDetailResponse: () =>
        jsonResponse(envelope({ ...fixtureClauseFindingDetail, subjectHeadingNodeId: 'node-missing' })),
    })

    await findDetailText('Rivell found')
    expect(section('In the contract')).toHaveTextContent(
      'The clause text for this version is not available.',
    )
    expect(
      within(section('In the contract')).queryByRole('button', { name: 'Show in document' }),
    ).not.toBeInTheDocument()
  })

  it('reads the contract version pinned by the review, never the latest version', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      contractSummary: {
        ...fixtureContractSummary,
        latestVersion: { ...fixtureContractSummary.latestVersion, versionNumber: 2 },
      },
    })

    await findDetailTitle('6. Limitation of Liability')
    await waitFor(() => expect(section('In the contract')).toHaveTextContent('6.3 The exclusions'))
    const structureCalls = api.requests.filter((request) => request.url.includes('/structure'))
    expect(structureCalls).toHaveLength(1)
    expect(structureCalls[0].url).toContain('/versions/1/structure')

    expect(
      await screen.findByText(/This review is pinned to v1; the contract.s current version is v2/),
    ).toBeInTheDocument()
  })

  it('represents an absence finding without a document location', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-2')

    await findDetailTitle('Missing: Limitation of liability')
    const pane = within(detailPane())
    expect(detailPane()).toHaveTextContent('No location in contract')
    expect(section('Rivell found')).toHaveTextContent('Required provision missing')
    expect(section('Rivell found')).toHaveTextContent(
      'No limitation of liability provision was found in this contract.',
    )
    expect(section('In the contract')).toHaveTextContent(
      'No source passage. This provision appears absent in this contract version.',
    )
    expect(pane.queryByRole('button', { name: 'Show in document' })).not.toBeInTheDocument()
    // No evidence was recorded, so no Sources are listed and no source is invented.
    expect(pane.queryByText('Sources')).not.toBeInTheDocument()

    // In the centre, the absent provision is a card, not a place in the text.
    expect(contractPane()).toHaveTextContent('Expected provision not found: Limitation of liability')
    expect(contractPane().querySelectorAll('[data-sel="1"]')).toHaveLength(1)
    expect(contractPane().querySelector('[data-sel="1"]')).toHaveTextContent('Expected provision not found')
  })

  it('never fabricates a clause source for an absence finding with a stale node id', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-2', {
      findingDetailResponse: () =>
        jsonResponse(
          envelope({ ...fixtureAbsenceFindingDetail, subjectHeadingNodeId: 'node-6' }),
        ),
    })

    await findDetailTitle('Missing: Limitation of liability')
    await waitFor(() =>
      expect(section('In the contract')).toHaveTextContent('No source passage.'),
    )
    expect(section('In the contract')).not.toHaveTextContent('6.3 The exclusions')
    expect(
      within(detailPane()).queryByRole('button', { name: 'Show in document' }),
    ).not.toBeInTheDocument()
  })

  it('labels generative language as machine-proposed, never approved', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-5')

    await findDetailText('Proposed language')
    const proposed = section('Proposed language')
    expect(proposed).toHaveTextContent(/Neither party’s aggregate liability/)
    expect(proposed).toHaveTextContent('Machine-proposed')
    expect(proposed).toHaveTextContent(/not approved organizational language/)
    expect(within(detailPane()).queryByText('Approved organizational drafting')).not.toBeInTheDocument()
    expect(section('Rivell found')).toHaveTextContent('AI-proposed explanation:')
  })

  it('keeps policy-missing findings honest about absent policy', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-3')

    await findDetailText('Rivell found')
    expect(section('Rivell found')).toHaveTextContent(
      /No organizational policy covers this clause.*does not establish policy\./,
    )
    // There is no policy to put against the contract, and no wording to propose.
    expect(within(detailPane()).queryByText('Against your position')).not.toBeInTheDocument()
    expect(within(detailPane()).queryByText('Proposed language')).not.toBeInTheDocument()
  })

  it('preserves policy ambiguity without selecting a winner', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-4')

    await findDetailText('Rivell found')
    const found = section('Rivell found')
    expect(found).toHaveTextContent(/Rivell did not choose between them/)
    expect(found).toHaveTextContent(/More than one published policy applies/)
    expect(found).toHaveTextContent('The applicable policies disagree; no rule was chosen.')
    // Both policies stay reachable, told apart by number, so neither is presented as the answer.
    expect(within(found).getByRole('button', { name: 'Policy 1' })).toBeInTheDocument()
    expect(within(found).getByRole('button', { name: 'Policy 2' })).toBeInTheDocument()
  })

  it('renders uncertainty factors without a confidence percentage', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-3')

    await findDetailText('Rivell found')
    expect(section('Sources')).toHaveTextContent('Policy evidence missing')
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  it('shows a safe state when the selected finding cannot be loaded', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', { findingDetailStatus: 404 })

    expect(await screen.findByText('This finding could not be loaded.')).toBeInTheDocument()
  })

  it('renders a cross-clause finding without faking counterpart navigation', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-6')

    await findDetailText('Rivell found')
    expect(section('Rivell found')).toHaveTextContent(/not covered by a survival provision/)
    expect(section('In the contract')).toHaveTextContent('6. Limitation of Liability')
    expect(
      within(detailPane()).queryByRole('button', { name: /related|corresponding|counterpart/i }),
    ).not.toBeInTheDocument()
    expect(
      within(detailPane()).queryByRole('link', { name: /related|corresponding|counterpart/i }),
    ).not.toBeInTheDocument()
  })

  it('does not claim approved language when the drafting evidence id is absent', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      findingDetailResponse: () =>
        jsonResponse(
          envelope({ ...fixtureClauseFindingDetail, approvedDraftingEvidenceId: null }),
        ),
    })

    await findDetailText('Rivell found')
    expect(
      within(detailPane()).queryByText('Approved organizational drafting'),
    ).not.toBeInTheDocument()
  })
})

describe('finding evidence amendment', () => {
  it('lists the evidence as Sources, and opens each claim reference on the exact item', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    const sources = section('Sources')
    // At most three sources are shown; the rest are one click away.
    expect(within(sources).getByRole('button', { name: /^Policy/ })).toHaveTextContent(
      'Liability must be capped at fees paid',
    )
    expect(within(sources).getByRole('button', { name: /^Approved drafting/ })).toBeInTheDocument()
    expect(within(sources).getByRole('button', { name: /^Current contract/ })).toHaveTextContent(
      '6. Limitation of Liability',
    )
    expect(within(sources).queryByRole('button', { name: /^Precedent/ })).not.toBeInTheDocument()
    await userEvent.click(within(sources).getByRole('button', { name: 'Show all 4 sources' }))
    expect(within(sources).getByRole('button', { name: /^Precedent/ })).toBeInTheDocument()
    expect(within(sources).getByRole('button', { name: 'Show fewer sources' })).toBeInTheDocument()

    // A claim names the evidence it rests on, and the reference opens exactly that item.
    const found = section('Rivell found')
    await userEvent.click(within(found).getByRole('button', { name: 'Policy' }))
    const sheet = screen.getByRole('dialog', { name: 'Source' })
    expect(sheet).toHaveTextContent('Liability must be capped at fees paid in the preceding 12 months.')
    expect(sheet).not.toHaveTextContent('The aggregate liability of each party shall not exceed')

    await userEvent.click(within(found).getByRole('button', { name: 'Approved drafting' }))
    const drafting = screen.getByRole('dialog', { name: 'Source' })
    expect(drafting).toHaveTextContent(
      'The aggregate liability of each party shall not exceed the fees paid in the twelve months preceding the claim.',
    )
    expect(drafting).not.toHaveTextContent('Liability must be capped at fees paid')
  })

  it('opens a Source row as the full evidence card the analysis recorded', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Policy/ }))

    const sheet = screen.getByRole('dialog', { name: 'Source' })
    const card = within(sheet).getByRole('article')
    expect(card).toHaveTextContent('Policy')
    // The full card carries what a one-line row cannot: positions, approval and escalation.
    expect(card).toHaveTextContent('Approval required')
    expect(card).toHaveTextContent('Uncapped liability or consequential damages')
  })

  it('tells several sources of one authority apart by number', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-4')

    await findDetailText('Rivell found')
    const found = section('Rivell found')
    await userEvent.click(within(found).getByRole('button', { name: 'Policy 2' }))
    expect(screen.getByRole('dialog', { name: 'Source' })).toHaveTextContent(
      'Liability must be capped at fees paid',
    )
  })

  it('opens a related clause from the source identity the backend recorded', async () => {
    const { router } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-6')

    await findDetailText('Sources')
    // Two current-contract sources: the clause itself and the related one (cl5 -> node-5).
    const rows = within(section('Sources')).getAllByRole('button', { name: /^Current contract/ })
    expect(rows).toHaveLength(2)
    await userEvent.click(rows[1])

    const sheet = screen.getByRole('dialog', { name: 'Source' })
    expect(sheet).toHaveTextContent('3. Fees. Fees are invoiced monthly and payable within 30 days.')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Open clause semantics' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c1'))
    const search = new URLSearchParams(router.state.location.search)
    expect(search.get('view')).toBe('semantics')
    expect(search.get('clause')).toBe('node-5')
    expect(router.state.location.search).not.toContain('node-63')
  })

  it('opens a source region in the contract document when the review is on the current version', async () => {
    const { router } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Current contract/ }))
    const sheet = screen.getByRole('dialog', { name: 'Source' })
    await userEvent.click(within(sheet).getByRole('button', { name: '6. Limitation of Liability' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c1'))
    expect(router.state.location.search).toBe('?view=document&node=node-6')
  })

  it('offers no deep link into the document when the review is pinned to an older version', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      contractSummary: {
        ...fixtureContractSummary,
        latestVersion: { ...fixtureContractSummary.latestVersion, versionNumber: 2 },
      },
    })

    await findDetailTitle('6. Limitation of Liability')
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Current contract/ }))
    const sheet = screen.getByRole('dialog', { name: 'Source' })
    expect(within(sheet).queryByText('View in document:')).not.toBeInTheDocument()
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

    await findDetailText('Rivell found')
    const found = section('Rivell found')
    expect(found).toHaveTextContent(
      /Some statements cite evidence this response does not include\. They are not shown as grounded\./,
    )
    expect(found).toHaveTextContent('unavailable in this response')
    // The missing reference is text, never a button that opens something else.
    expect(within(found).queryByRole('button', { name: /missing/i })).not.toBeInTheDocument()
    // References the response does hold remain usable.
    expect(within(found).getByRole('button', { name: 'Approved drafting' })).toBeInTheDocument()
  })

  it('shows no missing-evidence warning when every claim reference resolves', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailText('Rivell found')
    expect(within(detailPane()).queryByText(/cite evidence this response does not include/)).not.toBeInTheDocument()
    expect(within(detailPane()).queryByText('unavailable in this response')).not.toBeInTheDocument()
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

    await findDetailText('Rivell found')
    expect(
      within(detailPane()).queryByText('Approved organizational drafting'),
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

    // The centre column carries the limitation as a note; the navigator reports what was recorded.
    expect(await screen.findByText(/Review coverage is limited for:/)).toBeInTheDocument()
    expect(
      await screen.findByText(/No findings\. The automated review recorded none/),
    ).toBeInTheDocument()
    expect(
      screen.queryByText('Every planned check completed without findings.'),
    ).not.toBeInTheDocument()
  })

  it('says the pack context is unavailable when the snapshot is missing', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1', {
      reviewSummary: { ...fixtureCompletedReviewSummary, snapshot: null },
    })

    // The frozen context lives in the Review notes sheet.
    await userEvent.click(await screen.findByRole('button', { name: 'Review coverage and notes' }))
    const sheet = screen.getByRole('dialog', { name: 'Review notes' })
    const heading = within(sheet).getByText('Contract pack')
    const row = heading.closest('.data-list__row') as HTMLElement
    expect(within(row).getByText('Pack context unavailable')).toBeInTheDocument()
  })

  it('uses only resolved evidence and never rebuilds retrieval', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    // Opening a source reads the evidence already in the finding. It requests nothing.
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Policy/ }))
    expect(screen.getByRole('dialog', { name: 'Source' })).toHaveTextContent('Liability must be capped')

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

    await userEvent.click(await screen.findByRole('button', { name: 'Review coverage and notes' }))
    const sheet = screen.getByRole('dialog', { name: 'Review notes' })
    const heading = within(sheet).getByText('Review state')
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

    await userEvent.click(await screen.findByRole('button', { name: 'Review coverage and notes' }))
    const sheet = within(screen.getByRole('dialog', { name: 'Review notes' }))
    expect(await sheet.findByRole('heading', { name: 'Review coverage' })).toBeInTheDocument()
    expect(sheet.getByText('Reviewed as a primary clause')).toBeInTheDocument()
    expect(sheet.getByText('Detected as a secondary mention only')).toBeInTheDocument()
    expect(sheet.getByText('Not found in the contract')).toBeInTheDocument()
    expect(sheet.getByText('Coverage not established')).toBeInTheDocument()
    expect(sheet.getByText(/Review coverage is limited for:/)).toBeInTheDocument()
    expect(
      await screen.findByText(/No findings\. The automated review recorded none/),
    ).toBeInTheDocument()
    expect(api.requests.filter((r) => r.url.includes('/findings?')).length).toBeGreaterThan(0)
  })
})

describe('review workspace authority and network guards', () => {
  it('reads persisted state only: no create, no evidence, no trace requests on load', async () => {
    const { api } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')

    expect(api.requests.filter((request) => request.method === 'POST')).toHaveLength(0)
    expect(api.requests.filter((request) => request.url.includes('/retrieval/'))).toHaveLength(0)
  })

  it('offers no decision controls and no provider vocabulary without the server workflow state', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    expect(
      within(detailPane()).getByText(
        'Decision controls are unavailable until the review workflow state is available.',
      ),
    ).toBeInTheDocument()
    expect(
      within(detailPane()).queryByRole('button', { name: /^(Accept|Edit|Reject|Escalate)$/ }),
    ).not.toBeInTheDocument()

    const text = document.body.textContent ?? ''
    expect(text).toMatch(/Approved organizational drafting/)
    expect(text).not.toMatch(/ApprovedChange|Mark as approved/)
    expect(text).not.toMatch(/\bJev\b|OpenAI|OpenRouter|embedding|rerank/i)
    // Progress and finalize readiness are the server's. With no human review they are not drawn.
    expect(screen.queryByText(/ of \d+ resolved/)).not.toBeInTheDocument()
  })

  it('does not call machine-proposed language approved', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-5')

    await findDetailText('Proposed language')
    const text = document.body.textContent ?? ''
    expect(text).toMatch(/Machine-proposed/)
    expect(text).not.toMatch(/Approved organizational drafting/)
    expect(text).not.toMatch(/\bJev\b|OpenAI|OpenRouter|embedding|rerank/i)
  })
})
