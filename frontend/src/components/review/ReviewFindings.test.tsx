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
  fixtureEscalatedFallbackFindingDetail,
  fixtureFailedReviewSummary,
  fixtureGenerativeFindingDetail,
  fixtureNotCoveredFindingDetail,
  fixtureNotCoveredFindingSummary,
  fixturePolicyMissingFindingDetail,
  fixtureRelatedPositionFindingDetail,
  fixtureRelatedPositionFindingSummary,
  fixtureWithinFallbackFindingDetail,
  fixtureWithinFallbackFindingSummary,
  fixtureNeedsYourLookFindingDetail,
  fixtureNeedsYourLookFindingSummary,
  fixtureSecondOpinionFindingDetail,
  fixtureSecondOpinionFindingSummary,
  fixtureWalkAwayLookFindingDetail,
  fixtureWalkAwayLookFindingSummary,
  fixturePlaybookSilentFindingDetail,
  fixturePlaybookSilentFindingSummary,
  fixtureOmissionFindingDetail,
  fixtureOmissionFindingSummary,
  fixtureOmissionUnsureFindingDetail,
  fixtureOmissionUnsureFindingSummary,
  fixtureSearchFoundFindingDetail,
  fixtureSearchFoundFindingSummary,
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
  'f-7': fixtureWithinFallbackFindingDetail,
  'f-8': fixtureEscalatedFallbackFindingDetail,
  'f-9': fixtureRelatedPositionFindingDetail,
  'f-10': fixtureNotCoveredFindingDetail,
  'f-11': fixtureNeedsYourLookFindingDetail,
  'f-12': fixtureSecondOpinionFindingDetail,
  'f-13': fixtureWalkAwayLookFindingDetail,
  'f-14': fixturePlaybookSilentFindingDetail,
  'f-15': fixtureOmissionFindingDetail,
  'f-16': fixtureOmissionUnsureFindingDetail,
  'f-17': fixtureSearchFoundFindingDetail,
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
    absenceFinding('f-4', 'CONFIDENTIALITY_OBLIGATIONS'),
    absenceFinding('f-5', 'INDEMNIFICATION'),
    absenceFinding('f-6', 'TERM'),
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
    await waitFor(() => expect(clauseRow).toHaveTextContent('Needs decision · Beyond our fallbacks'))

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
    expect(clauseRow).toHaveTextContent('Beyond our fallbacks')
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
            issueFamily: 'POSITION_UNCERTAIN',
            observationKind: 'UNCERTAINTY:bounded_decision_unavailable',
            severity: 'MEDIUM',
          },
        ],
        truncated: false,
      },
    })

    const rows = await (await findFindingsList()).findAllByRole('option', { name: /6\. Limitation of Liability/ })
    expect(rows).toHaveLength(2)
    expect(rows[0]).toHaveTextContent('Beyond our fallbacks')
    expect(rows[1]).toHaveTextContent('Could not be assessed')
  })

  it('requests the review findings with a fixed limit and never forwards URL filters', async () => {
    // Severity, classification and concept filters no longer exist in the workspace. A stale link
    // carrying them must not reach the API.
    const { api } = renderWorkspace(
      '/reviews/c1/analysis/r-1?severity=HIGH&classification=BLOCKING&concept=CONFIDENTIALITY_OBLIGATIONS',
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
    expect(found).toHaveTextContent(/worse for us than every fallback the position lists/)
    expect(found).toHaveTextContent('Beyond our fallbacks')
    expect(found).toHaveTextContent(
      'Policy basis: Published policy lists a fallback at two times the fees paid over a twelve-month period.',
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

    // The policy position is shown on its own, apart from what Rivell concluded, under the
    // truthful heading: the frozen policy evidence, not a single authored "your position".
    expect(within(detailPane()).queryByText('Against your position')).not.toBeInTheDocument()
    const position = section('Policy position')
    const cell = (label: string) => within(position).getByRole('group', { name: label })
    expect(cell('Contract')).toHaveTextContent('3×')
    expect(cell('Contract')).toHaveTextContent('Liability cap')
    expect(cell('Contract')).toHaveTextContent('Preceding 12-month fees')
    expect(cell('Preferred')).toHaveTextContent('Liability must be capped at fees paid in the preceding 12 months.')
    expect(cell('Fallback')).toHaveTextContent('Liability must be capped at fees paid in the preceding 12 months.')
    expect(position).toHaveTextContent('Escalate: Uncapped liability or consequential damages')
  })

  it('shows a short clause excerpt under "In the contract" and marks the clause in the document', async () => {
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

    // "Show in document" reveals the same clause in place, exactly as an evidence jump does.
    await userEvent.click(within(source).getByRole('button', { name: 'Show in document' }))
    const revealed = contractPane().querySelectorAll('[data-focus="1"]')
    expect(revealed).toHaveLength(1)
    expect(revealed[0]).toHaveTextContent('6.3 The exclusions in Section 6.2')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
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
    // There is no policy to put against the contract, and no wording to propose. The contract has
    // typed facts, but a lone contract slot is not a policy position, so the section is not drawn.
    expect(within(detailPane()).queryByText('Policy position')).not.toBeInTheDocument()
    expect(within(detailPane()).queryByRole('group', { name: 'Preferred' })).not.toBeInTheDocument()
    expect(within(detailPane()).queryByRole('group', { name: 'Fallback' })).not.toBeInTheDocument()
    expect(within(detailPane()).queryByText('Proposed language')).not.toBeInTheDocument()
  })

  it('labels a clause no playbook covers as a review item, with the model’s confidence as an estimate', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-10', {
      findings: { items: [...findingsForThisReview.items, fixtureNotCoveredFindingSummary], truncated: false },
    })

    await findDetailText('Rivell found')
    const pane = within(detailPane())
    expect(pane.getAllByText(/Unusual clause, not covered by your playbook/).length).toBeGreaterThan(0)
    expect(pane.queryByText('Policy unavailable')).not.toBeInTheDocument()
    expect(pane.getByText(/No playbook position addresses this clause/)).toBeInTheDocument()
    expect(pane.getByText(/Model confidence 90%/)).toBeInTheDocument()
    expect(pane.getByText(/not a certainty/)).toBeInTheDocument()
    // The list names the same kind, not the generic policy-unavailable family.
    expect(
      await (await findFindingsList()).findByRole('option', { name: /Unusual clause, not covered by your playbook/ }),
    ).toBeInTheDocument()
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
    // Every cited item that holds a role is kept: nothing is dropped to the first.
    const position = section('Policy position')
    expect(within(within(position).getByRole('group', { name: 'Preferred' })).getAllByText(/capped at fees paid/)).toHaveLength(2)
    expect(within(within(position).getByRole('group', { name: 'Fallbacks' })).getAllByText(/capped at fees paid/)).toHaveLength(2)
    expect(within(position).getAllByText(/Escalate: Uncapped liability/)).toHaveLength(2)
    // The source of each one stays reachable.
    expect(within(position).getAllByRole('button', { name: /Open source: Policy, publication 2/ }).length).toBeGreaterThanOrEqual(4)
    // No contract slot was sent, so there is no Contract cell.
    expect(within(position).queryByRole('group', { name: 'Contract' })).not.toBeInTheDocument()
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

describe('position findings', () => {
  const listWith = (
    ...extra: (typeof fixtureWithinFallbackFindingSummary | typeof fixtureRelatedPositionFindingSummary)[]
  ) => ({
    items: [...findingsForThisReview.items, ...extra],
    truncated: false,
  })

  it('shows the comparison’s category and the model’s confidence as an estimate, not certainty', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    const pane = within(detailPane())
    expect(pane.getByText(/Beyond every listed fallback/)).toBeInTheDocument()
    expect(pane.getByText(/Model confidence 87%/)).toBeInTheDocument()
    expect(pane.getByText(/not a certainty/)).toBeInTheDocument()
  })

  it('shows no assessment line when the finding carries no comparison quality', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-2')

    await findDetailTitle('Missing: Limitation of liability')
    expect(within(detailPane()).queryByText(/Model confidence/)).not.toBeInTheDocument()
  })

  it('reads a within-fallback finding as a note to accept and record, not a problem', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-7', {
      findings: listWith(fixtureWithinFallbackFindingSummary),
    })

    await findDetailTitle('6. Limitation of Liability')
    const pane = within(detailPane())
    expect(pane.getByText(/note, not a problem/i)).toBeInTheDocument()
    expect(pane.getByText(/accept and record/i)).toBeInTheDocument()
  })

  it('reads a secondary-topic position as a related note, not a breach', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-9', {
      findings: listWith(fixtureRelatedPositionFindingSummary),
    })

    await findDetailTitle('6. Limitation of Liability')
    const pane = within(detailPane())
    const label = pane.getByText('Related position')
    expect(label).toHaveClass('rv-ws-typ--related')
    expect(pane.getByText(/not a breach/i)).toBeInTheDocument()
    expect(pane.getByText(/governs a topic the clause only touches/i)).toBeInTheDocument()
    // The list reads the server's relatedPosition flag, not the severity.
    expect(
      await (await findFindingsList()).findByRole('option', { name: /Related position/ }),
    ).toBeInTheDocument()
  })

  it('says approval is needed and shows the escalation condition when the position names an approver', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-8', {
      findings: listWith({
        ...fixtureWithinFallbackFindingSummary,
        id: 'f-8',
        reviewClassification: 'REVIEW_REQUIRED',
        proposedAction: 'ESCALATE_TO_HUMAN',
      }),
    })

    await findDetailTitle('6. Limitation of Liability')
    const note = detailPane().querySelector('.rv-ws-note--approval')
    expect(note).not.toBeNull()
    // The note itself carries the approval statement and the condition, not only the comparison grid.
    expect(note).toHaveTextContent('Approval is needed.')
    expect(note).toHaveTextContent('Escalate when: Uncapped liability or consequential damages.')
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

  it('links a cited source to its published knowledge page, never to the old publication record', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Policy/ }))
    const policy = screen.getByRole('dialog', { name: 'Source' })
    expect(within(policy).getByRole('link', { name: 'Open in Playbooks' })).toHaveAttribute(
      'href',
      '/knowledge/playbooks/a-playbook?position=k-rule',
    )
    expect(within(policy).getByText('Published v2')).toBeInTheDocument()
    expect(policy.querySelector('a[href^="/knowledge/publications"]')).toBeNull()

    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Approved drafting/ }))
    const drafting = screen.getByRole('dialog', { name: 'Source' })
    expect(within(drafting).getByRole('link', { name: 'Open the source' })).toHaveAttribute(
      'href',
      '/knowledge/sources/a-clauses?position=k-variant',
    )

    // Contract evidence has no knowledge page: the sheet offers the document instead.
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Current contract/ }))
    const contract = screen.getByRole('dialog', { name: 'Source' })
    expect(within(contract).queryByRole('link', { name: /^Open/ })).not.toBeInTheDocument()
    expect(within(contract).getByRole('button', { name: '6. Limitation of Liability' })).toBeInTheDocument()
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

  it('keeps a source region inside the workspace and highlights the clause it came from', async () => {
    const { router } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1')

    await findDetailTitle('6. Limitation of Liability')
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Current contract/ }))
    const sheet = screen.getByRole('dialog', { name: 'Source' })
    await userEvent.click(within(sheet).getByRole('button', { name: '6. Limitation of Liability' }))

    // The legacy contract route is not opened: the evidence targets the workspace's own document.
    expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Source' })).not.toBeInTheDocument())
    // The target clause is marked, and only it.
    const highlighted = contractPane().querySelectorAll('[data-focus="1"]')
    expect(highlighted).toHaveLength(1)
    expect(highlighted[0]).toHaveTextContent('6. Limitation of Liability')
    expect(highlighted[0]).toHaveTextContent('6.3 The exclusions')
    expect(highlighted[0]).not.toHaveTextContent('5. Termination')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })

  it('reveals the clause in the pinned workspace document and keeps latest-version links off', async () => {
    const { router } = renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', {
      contractSummary: {
        ...fixtureContractSummary,
        latestVersion: { ...fixtureContractSummary.latestVersion, versionNumber: 2 },
      },
    })

    await findDetailTitle('6. Limitation of Liability')
    await userEvent.click(within(section('Sources')).getByRole('button', { name: /^Current contract/ }))
    const sheet = screen.getByRole('dialog', { name: 'Source' })
    // Clause semantics open the latest version, so a pinned older review does not offer them.
    expect(
      within(sheet).queryByRole('button', { name: 'Open clause semantics' }),
    ).not.toBeInTheDocument()

    // "View in document" still works: the workspace document is the version the review pinned.
    await userEvent.click(within(sheet).getByRole('button', { name: '6. Limitation of Liability' }))
    expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Source' })).not.toBeInTheDocument())
    const highlighted = contractPane().querySelectorAll('[data-focus="1"]')
    expect(highlighted).toHaveLength(1)
    expect(highlighted[0]).toHaveTextContent('6.3 The exclusions in Section 6.2')
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
    const row = heading.parentElement as HTMLElement
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
          { concept: 'CONFIDENTIALITY_OBLIGATIONS', expectation: 'EXPECTED', coverage: 'PRESENT_SECONDARY_ONLY' },
          { concept: 'TERM', expectation: 'REQUIRED', coverage: 'ABSENT' },
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

describe('engine v2: who decided', () => {
  const withV2 = {
    reviewSummary: {
      ...fixtureCompletedReviewSummary,
      snapshot: { ...fixtureCompletedReviewSummary.snapshot, engine: 'V2' },
    },
    findings: {
      items: [
        fixtureNeedsYourLookFindingSummary,
        ...findingsForThisReview.items,
        fixtureSecondOpinionFindingSummary,
      ],
      truncated: false,
    },
  }

  it('keeps "needs your look" items in their own group, collapsed until opened', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', withV2)

    const toggle = await screen.findByRole('button', { name: 'Needs your look (1)' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(findingsList().queryByRole('option', { name: /two reviewers disagree/ })).not.toBeInTheDocument()

    await userEvent.click(toggle)

    const group = within(screen.getByRole('listbox', { name: 'Needs your look list' }))
    expect(group.getByRole('option', { name: /two reviewers disagree/ })).toBeInTheDocument()
  })

  it('shows both views on a "needs your look" item, with the quoted words and no provider name', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-11', withV2)

    await findDetailText('Two views: you decide')
    const views = within(screen.getByTestId('second-opinion-views'))
    expect(views.getByText('First review')).toBeInTheDocument()
    expect(views.getByText(/Beyond every listed fallback/)).toBeInTheDocument()
    expect(views.getByText(/issue estimate 68%/)).toBeInTheDocument()
    expect(views.getByText(/Meets the preferred position/)).toBeInTheDocument()
    expect(views.getByText(/liability shall not exceed the fees paid/)).toBeInTheDocument()
    expect(within(detailPane()).getByTestId('finding-decided-by')).toHaveTextContent('Needs your look')
    // the selected item's group opens by itself
    expect(screen.getByRole('button', { name: 'Needs your look (1)' })).toHaveAttribute('aria-expanded', 'true')
    expect(document.body.textContent ?? '').not.toMatch(/\bJev\b|OpenAI|OpenRouter/i)
  })

  it('says when an agreeing second opinion confirmed a finding', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-12', withV2)

    await findDetailText('Rivell found')
    expect(within(detailPane()).getByTestId('finding-decided-by')).toHaveTextContent(
      'Decided with a second opinion',
    )
    expect(screen.queryByTestId('second-opinion-views')).not.toBeInTheDocument()
  })

  it('names the review engine and the route counts in the review notes', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', withV2)

    // the frozen context lives in the Review notes sheet
    await userEvent.click(await screen.findByRole('button', { name: 'Review coverage and notes' }))
    const sheet = screen.getByRole('dialog', { name: 'Review notes' })
    const row = within(sheet).getByText('Review engine').parentElement as HTMLElement
    expect(row).toHaveTextContent(
      'v2: a second opinion when the first review is unsure · 1 decided with a second opinion · 1 needs your look',
    )
  })
})

describe('engine v2: the items P4 adds', () => {
  const withP4 = {
    reviewSummary: {
      ...fixtureCompletedReviewSummary,
      snapshot: { ...fixtureCompletedReviewSummary.snapshot, engine: 'V2' },
    },
    findings: {
      items: [
        fixtureNeedsYourLookFindingSummary,
        fixtureWalkAwayLookFindingSummary,
        fixturePlaybookSilentFindingSummary,
        fixtureOmissionUnsureFindingSummary,
        ...findingsForThisReview.items,
        fixtureOmissionFindingSummary,
        fixtureSearchFoundFindingSummary,
      ],
      truncated: false,
    },
  }

  async function openLookGroup() {
    await userEvent.click(await screen.findByRole('button', { name: 'Needs your look (4)' }))
    return within(screen.getByRole('listbox', { name: 'Needs your look list' }))
  }

  it('puts walk-away items first in needs your look and shows the first review on the row', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', withP4)

    const options = (await openLookGroup()).getAllByRole('option')
    expect(options[0]).toHaveTextContent('First review: Breaches the walk-away')
    // the rest keep the server's order
    expect(options[1]).toHaveTextContent(/two reviewers disagree/)
  })

  it('tells the reviewer what each kind of look item is', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-1', withP4)

    expect(
      await screen.findByText(
        'Rivell did not decide these. Rivell’s first review and a second opinion disagreed, a second opinion was not available, your playbook is silent on the clause, or Rivell is unsure a missing provision is required.',
      ),
    ).toBeInTheDocument()
    const group = await openLookGroup()
    expect(group.getByRole('option', { name: /Not covered by your playbook/ })).toBeInTheDocument()
    expect(group.getByRole('option', { name: /Missing: Data protection/ })).toHaveTextContent('Not in contract')
  })

  it('shows the playbook and the whole-contract read side by side on a playbook-silent item', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-14', withP4)

    await findDetailText('Two views: you decide')
    const views = within(screen.getByTestId('playbook-silent-views'))
    expect(views.getByText(/No position in your playbook addresses this clause/)).toBeInTheDocument()
    expect(views.getByText(/estimate 12%/)).toBeInTheDocument()
    expect(views.getByText(/Picked as high risk/)).toBeInTheDocument()
    expect(views.getByText('Only damages are available to us for a breach.')).toBeInTheDocument()
    // not a disagreement between two reviews, and not a failure
    expect(screen.queryByTestId('second-opinion-views')).not.toBeInTheDocument()
    expect(detailPane()).not.toHaveTextContent(/degraded mode/)
    // the triage estimate is in the views; it is not repeated as a confidence in an issue
    expect(screen.queryByTestId('finding-assessment')).not.toBeInTheDocument()
  })

  it('reads an omission as missing, with the clauses searched', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-15', withP4)

    await findDetailTitle('Missing: Cap Exceptions')
    expect(section('Rivell found')).toHaveTextContent('Missing: Cap Exceptions. Searched 5 clauses; none addresses it.')
    expect(section('Rivell found')).toHaveTextContent('Missing from the contract')
    expect(within(detailPane()).getByTestId('finding-decided-by')).toHaveTextContent(
      'Decided by Rivell’s first review · 92%',
    )
    expect(within(detailPane()).getByText(/No location in contract/)).toBeInTheDocument()
  })

  it('keeps an omission Rivell is unsure about honest: an estimate, not a failure', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-16', withP4)

    await findDetailTitle('Missing: Data protection')
    expect(within(detailPane()).getByTestId('finding-assessment')).toHaveTextContent(
      'Required by your playbook: estimate 62% (an estimate, not a certainty)',
    )
    expect(screen.queryByTestId('second-opinion-views')).not.toBeInTheDocument()
    expect(detailPane()).not.toHaveTextContent(/degraded mode/)
  })

  it('says how a position found by search was found', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-17', withP4)

    await findDetailText('Rivell found')
    expect(within(screen.getByTestId('policy-position')).getByText(
      'Found by searching your playbook for this clause. No position matched by topic addressed it.',
    )).toBeInTheDocument()
  })

  it('writes no label with an em dash', async () => {
    renderWorkspace('/reviews/c1/analysis/r-1?finding=f-14', withP4)

    await findDetailText('Two views: you decide')
    await openLookGroup()
    // Rivell's own words only: the contract pane shows the document as received
    expect(screen.getByRole('complementary', { name: 'Findings' }).textContent ?? '').not.toMatch(/\u2014/)
    expect(detailPane().textContent ?? '').not.toMatch(/\u2014/)
  })
})

