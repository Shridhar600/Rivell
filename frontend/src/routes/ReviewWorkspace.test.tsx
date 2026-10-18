import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { fixtureContractSummary, fixtureSemantics, fixtureStructure, stubContractApi, type StubOptions } from '../components/contract/testFixtures'
import {
  fixtureAbsenceFindingSummary,
  fixtureClauseFindingDetail,
  fixtureClauseFindingSummary,
  fixtureClauseMarks,
  fixtureCompletedReviewSummary,
  fixtureFindingList,
  fixtureUserFindingDetail,
  fixtureUserFindingSummary,
} from '../components/review/testFixtures'
import { fixtureChangeSet, fixtureDecision, fixtureHumanReview } from '../components/review/workflowFixtures'

function options(overrides: StubOptions = {}): StubOptions {
  return {
    reviewSummary: fixtureCompletedReviewSummary,
    findings: fixtureFindingList,
    findingDetail: fixtureClauseFindingDetail,
    decisions: { items: [], truncated: false },
    humanReview: fixtureHumanReview({ actionableFindings: 2, remainingFindings: 2 }),
    ...overrides,
  }
}

function renderWorkspace(api: ReturnType<typeof stubContractApi>, path = '/reviews/c1/analysis/r-1') {
  void api
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return router
}

const detail = () => screen.findByRole('complementary', { name: 'Finding detail' })
const topBar = () => screen.findByRole('banner')

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
  localStorage.clear()
})
afterEach(() => vi.unstubAllGlobals())

describe('review identity', () => {
  it('says when the contract file was repaired or not fully read', async () => {
    renderWorkspace(
      stubContractApi(
        options({
          structure: { ...fixtureStructure, parseWarnings: ['DAMAGED_PACKAGE_REPAIRED', 'FOOTNOTES_NOT_REPRESENTED'] },
        }),
      ),
    )
    expect(await screen.findByText(/The file was damaged. Rivell repaired it/)).toBeInTheDocument()
    expect(screen.getByText('Footnotes contain content that is not represented.')).toBeInTheDocument()
  })

  it('treats a review from another contract as not found', async () => {
    renderWorkspace(
      stubContractApi(options({ reviewSummary: { ...fixtureCompletedReviewSummary, id: 'r-9', contractId: 'c2' } })),
      '/reviews/c1/analysis/r-9',
    )

    expect(await screen.findByText(/This review could not be found/)).toBeInTheDocument()
  })
})

describe('workspace layout', () => {
  it('draws the three canonical regions inside the app sidebar', async () => {
    renderWorkspace(stubContractApi(options()))
    await waitFor(() =>
      expect(within(screen.getByRole('complementary', { name: 'Findings' })).getByRole('option', { name: /6\. Limitation of Liability/ })).toBeInTheDocument(),
    )
    expect(screen.getByRole('main', { name: 'Contract' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument()
    const crumbs = within(await topBar()).getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Reviews' })).toHaveAttribute('href', '/reviews')
    expect(crumbs).toHaveTextContent('Master Services Agreement')
    expect(crumbs).not.toHaveTextContent('Home')
  })

  it('lets the reader resize the findings list and the detail panel, and remembers both widths', async () => {
    renderWorkspace(stubContractApi(options()))
    const findings = await screen.findByRole('separator', { name: 'Resize findings list' })
    const detail = screen.getByRole('separator', { name: 'Resize detail panel' })
    findings.focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(findings).toHaveAttribute('aria-valuenow', '288')
    detail.focus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(detail).toHaveAttribute('aria-valuenow', '396')
    expect(globalThis.localStorage?.getItem('rv-ws-findings-width')).toBe('288')
    expect(globalThis.localStorage?.getItem('rv-ws-detail-width')).toBe('396')
  })

  it('opens on the first finding that needs a decision and names it in the URL', async () => {
    const router = renderWorkspace(stubContractApi(options()))
    await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })
    await waitFor(() => expect(router.state.location.search).toBe('?finding=f-1'))
  })

  it('draws the contract with the clause as received, and shows the proposal on the selected open finding', async () => {
    renderWorkspace(stubContractApi(options()))
    const page = await screen.findByRole('article')
    await waitFor(() => expect(within(page).getByText(/6\.3 The exclusions/)).toBeInTheDocument())
    await waitFor(() => expect(page.querySelector('ins')).toHaveTextContent(/two times the fees paid/))
    await userEvent.click(screen.getByRole('tab', { name: 'Original' }))
    expect(page.querySelector('ins')).toBeNull()
    expect(page.querySelector('del')).toBeNull()
  })

  it('shows an edited decision as the reviewer’s wording in the preview, not as a verified file', async () => {
    renderWorkspace(
      stubContractApi(
        options({
          decisions: {
            items: [fixtureDecision({ action: 'EDIT', approvedLanguage: 'Cap is two times fees.' })],
            truncated: false,
          },
          humanReview: fixtureHumanReview({ actionableFindings: 2, resolvedFindings: 1, remainingFindings: 1, edited: 1 }),
        }),
      ),
    )
    await userEvent.click(await screen.findByRole('tab', { name: 'Preview' }))
    // What the view contains is the tab's tooltip, not a line beside the tabs.
    expect(screen.getByRole('tab', { name: 'Preview' })).toHaveAttribute(
      'title',
      'The contract with your 1 decided change. Open findings keep the original text.',
    )
    const page = screen.getByRole('article')
    await waitFor(() => expect(within(page).getByText('Cap is two times fees.')).toBeInTheDocument())
    expect(screen.queryByText(/verified (redline|document|file)|tracked DOCX/i)).not.toBeInTheDocument()
  })

  it('draws a finding about a missing provision as a card with no invented location', async () => {
    renderWorkspace(stubContractApi(options()))
    const page = await screen.findByRole('article')
    expect(await within(page).findByText('Expected provision not found: Limitation of liability')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('tab', { name: 'Original' }))
    expect(within(page).queryByText(/Expected provision not found/)).not.toBeInTheDocument()
  })
})

describe('finalize is the server’s decision', () => {
  it('is disabled with one compact status while the server reports blockers, and the exact reason is on the finalize control', async () => {
    renderWorkspace(stubContractApi(options()))
    const finalize = await within(await topBar()).findByRole('button', { name: 'Finalize review' })
    expect(finalize).toBeDisabled()
    expect(finalize.closest('.rv-ws-fin')).toHaveAttribute(
      'title',
      '1 finding that requires review still needs a decision.',
    )
    expect(await topBar()).toHaveTextContent('0 of 2 decided')
    // The unresolved count is not repeated on the button, and the blocker is not a clipped line.
    expect(await topBar()).not.toHaveTextContent(/unresolved/)
    const progress = (await topBar()).querySelector('.rv-ws-prog') as HTMLElement
    expect(progress).toHaveAttribute('title', 'Not ready to finalize')
    // The old disclosure is gone; the reason lives on the finalize control instead.
    expect(document.querySelector('details.rv-ws-why')).not.toBeInTheDocument()
    expect(await topBar()).not.toHaveTextContent('1 finding that requires review still needs a decision.')
  })

  it('is enabled only when the server says ready, and finalizes after one confirmation', async () => {
    const api = stubContractApi(
      options({
        humanReview: fixtureHumanReview({ readyToFinalize: true, blockers: [], resolvedFindings: 2, remainingFindings: 0, accepted: 1, rejected: 1, actionableFindings: 2 }),
        decisions: { items: [fixtureDecision()], truncated: false },
        changeSet: fixtureChangeSet(),
      }),
    )
    renderWorkspace(api)
    const finalize = await within(await topBar()).findByRole('button', { name: 'Finalize review' })
    await waitFor(() => expect(finalize).toBeEnabled())
    const progress = (await topBar()).querySelector('.rv-ws-prog') as HTMLElement
    expect(progress).toHaveAttribute('title', 'Ready to finalize')
    await userEvent.click(finalize)
    const dialog = await screen.findByRole('dialog', { name: 'Finalize this review?' })
    expect(dialog).toHaveTextContent('1 approved change (1 accepted, 0 edited)')
    expect(dialog).toHaveTextContent('1 rejected Finding; no approved change created')
    expect(dialog).toHaveTextContent('After you finalize, you generate the redline separately.')
    expect(api.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/finalize'))).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Finalize review' }))
    await waitFor(() => expect(api.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/finalize'))).toHaveLength(1))
  })

  it('keeps the frozen state and the review summary reachable when the selected finding cannot load', async () => {
    renderWorkspace(
      stubContractApi(
        options({
          findingDetailStatus: 500,
          humanReview: fixtureHumanReview({ status: 'COMPLETED', readyToFinalize: false, changeSetId: 'cs-1', finalizedAt: '2026-09-26T11:00:00Z' }),
          decisions: { items: [fixtureDecision()], truncated: false },
          changeSet: fixtureChangeSet(),
        }),
      ),
    )
    expect(await within(await detail()).findByText('This finding could not be loaded.')).toBeInTheDocument()
    expect(within(await detail()).getByText('Approved changes frozen')).toBeInTheDocument()
    expect(within(await detail()).getByRole('link', { name: 'View review summary' })).toBeInTheDocument()
    expect(within(await topBar()).getByRole('button', { name: 'Finalized' })).toBeDisabled()
  })
})

const readyToFinalize = () =>
  options({
    humanReview: fixtureHumanReview({ readyToFinalize: true, blockers: [], resolvedFindings: 2, remainingFindings: 0, accepted: 1, rejected: 1, actionableFindings: 2 }),
    decisions: { items: [fixtureDecision()], truncated: false },
    changeSet: fixtureChangeSet(),
    finalizeResponse: () =>
      new Response(JSON.stringify({ data: fixtureChangeSet(), status: 200, timestamp: '2026-09-26T14:00:00Z' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
  })

async function finalizeFromDialog() {
  const finalize = await within(await topBar()).findByRole('button', { name: 'Finalize review' })
  await waitFor(() => expect(finalize).toBeEnabled())
  await userEvent.click(finalize)
  const dialog = await screen.findByRole('dialog', { name: 'Finalize this review?' })
  await userEvent.click(within(dialog).getByRole('button', { name: 'Finalize review' }))
}

describe('after Finalize the Workspace hands over to the review summary', () => {
  it('shows the frozen state and a link to the review summary, and nothing that generates a redline', async () => {
    const api = stubContractApi(readyToFinalize())
    renderWorkspace(api)
    await finalizeFromDialog()

    const link = await within(await detail()).findByRole('link', { name: 'View review summary' })
    expect(link).toHaveAttribute('href', '/reviews/c1/overview/r-1')
    expect(within(await detail()).getByText('Approved changes frozen')).toBeInTheDocument()
    expect(within(await detail()).getByText(/1 approved change in the immutable approved change set/)).toBeInTheDocument()
    expect(await topBar()).toHaveTextContent('Approved changes frozen')

    // Redline is not run, offered, or even read from here.
    expect(screen.queryByRole('button', { name: /redline/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Redline execution' })).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Redline' })).not.toBeInTheDocument()
    expect(api.requests.filter((r) => r.url.includes('/redline'))).toHaveLength(0)
  })

  it('moves keyboard focus to the review summary link once the review is finalized', async () => {
    renderWorkspace(stubContractApi(readyToFinalize()))
    await finalizeFromDialog()
    const link = await within(await detail()).findByRole('link', { name: 'View review summary' })
    await waitFor(() => expect(link).toHaveFocus())
    expect(screen.queryByRole('dialog', { name: 'Finalize this review?' })).not.toBeInTheDocument()
  })

  it('puts focus on the frozen status instead when the detail panel is closed, so it is never lost', async () => {
    renderWorkspace(stubContractApi(readyToFinalize()))
    await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })
    await userEvent.click(within(await topBar()).getByRole('button', { name: 'Toggle detail panel' }))
    await finalizeFromDialog()
    await waitFor(() => expect(within(screen.getByRole('banner')).getByText('Approved changes frozen')).toHaveFocus())
    expect(document.body).not.toHaveFocus()
  })

  it('does not move focus when the review was already finalized on arrival', async () => {
    renderWorkspace(
      stubContractApi(
        options({
          humanReview: fixtureHumanReview({ status: 'COMPLETED', readyToFinalize: false, changeSetId: 'cs-1', finalizedAt: '2026-09-26T11:00:00Z' }),
          decisions: { items: [fixtureDecision()], truncated: false },
          changeSet: fixtureChangeSet(),
        }),
      ),
    )
    const link = await within(await detail()).findByRole('link', { name: 'View review summary' })
    expect(link).not.toHaveFocus()
  })

  it('does not hand over when Finalize fails', async () => {
    renderWorkspace(
      stubContractApi({
        ...readyToFinalize(),
        finalizeResponse: () => new Response(JSON.stringify({ code: '409', title: 'Conflict', detail: 'server detail' }), { status: 409, headers: { 'Content-Type': 'application/json' } }),
      }),
    )
    await finalizeFromDialog()
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'View review summary' })).not.toBeInTheDocument()
  })
})

describe('keyboard and command menu', () => {
  it('moves between findings with j and k', async () => {
    const router = renderWorkspace(stubContractApi(options()))
    await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })
    await userEvent.keyboard('j')
    await waitFor(() => expect(router.state.location.search).toBe('?finding=f-2'))
    await userEvent.keyboard('k')
    await waitFor(() => expect(router.state.location.search).toBe('?finding=f-1'))
  })

  it('lists the page’s own commands first, then the global places, and can find text in the contract', async () => {
    renderWorkspace(stubContractApi(options()))
    // The shell's one search menu reads the global lists when it opens.
    const inner = globalThis.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const request = input instanceof Request ? input : new Request(String(input), init)
        const url = new URL(request.url)
        if (request.method === 'GET' && url.pathname === '/api/reviews') {
          return new Response(JSON.stringify({ data: { items: [], truncated: false }, status: 200, timestamp: '2026-10-02T09:00:00Z' }))
        }
        if (
          request.method === 'GET' &&
          (url.pathname === '/api/knowledge/artifacts' || url.pathname === '/api/knowledge/publications')
        ) {
          return new Response(JSON.stringify({ data: [], status: 200, timestamp: '2026-10-02T09:00:00Z' }))
        }
        return inner(request)
      }),
    )
    await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })
    await userEvent.keyboard('/')
    const menu = await screen.findByRole('dialog', { name: 'Search' })
    const labels = within(menu).getAllByRole('option').map((option) => option.textContent)
    expect(labels.join('|')).toMatch(/Edit finding/)
    expect(labels.join('|')).toMatch(/Show original document/)
    expect(labels.join('|')).not.toMatch(/Finalize review/)
    // The page's own commands come first; the global places follow.
    const groups = Array.from(menu.querySelectorAll('.rv-ws-pal-h')).map((heading) => heading.textContent)
    expect(groups[0]).toBe('Findings')
    expect(groups).toContain('Create')
    expect(groups).toContain('Go to')
    // The old workspace "Go to" entries are gone: the global group replaces them.
    expect(labels.join('|')).not.toMatch(/Contract reviews/)
    await userEvent.type(within(menu).getByRole('textbox', { name: 'Search' }), 'exclusions')
    await userEvent.click(within(menu).getByRole('option', { name: /Find “exclusions” in this contract/ }))
    await waitFor(() => expect(screen.getByRole('main', { name: 'Contract' })).toHaveTextContent('Matches for “exclusions”'))
    expect(screen.getByRole('tab', { name: 'Original' })).toHaveAttribute('aria-selected', 'true')
  })

  it('collapses a side panel and remembers it on this device', async () => {
    renderWorkspace(stubContractApi(options()))
    await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })
    await userEvent.click(within(await topBar()).getByRole('button', { name: 'Toggle Findings list' }))
    expect(localStorage.getItem('rv-l')).toBe('c')
    expect(within(await topBar()).getByRole('button', { name: 'Toggle Findings list' })).toHaveAttribute('aria-pressed', 'false')
  })
})

function apiResponse(data: unknown, status = 200): Response {
  return new Response(JSON.stringify({ data, status, timestamp: '2026-09-24T05:00:00Z' }), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

/** The clause block that holds a heading: the unit the marks and the add action belong to. */
async function clauseOf(heading: string): Promise<HTMLElement> {
  const page = await screen.findByRole('article')
  // the marks arrive after the contract text, from their own request
  return waitFor(() => {
    const clause = within(page).getByText(heading).closest<HTMLElement>('[data-mark]')
    if (!clause) throw new Error(`no marked clause around ${heading}`)
    return clause
  })
}

describe('the whole contract under your control', () => {
  it('marks every clause with what Rivell did with it, and counts the marks', async () => {
    renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks })))
    expect(within(await clauseOf('1. Definitions')).getByText('Checked, no issue found')).toBeInTheDocument()
    expect(within(await clauseOf('5. Termination')).getByText('Not covered by your playbook')).toBeInTheDocument()
    expect(within(await clauseOf('6. Limitation of Liability')).getByText('Flagged')).toBeInTheDocument()
    expect(
      within(await clauseOf('Schedule A — Services')).getByText('Not analysed: the check did not finish'),
    ).toBeInTheDocument()
    // a heading the review did not plan: Rivell did not look at it, and says so differently
    expect(
      within(await clauseOf('Northwind Customer Group LLC')).getByText('Not reviewed: outside the review plan'),
    ).toBeInTheDocument()
    expect(screen.getByRole('main', { name: 'Contract' })).toHaveTextContent(
      '1 flagged · 1 checked · 1 not covered · 1 not analysed · 1 not reviewed',
    )
  })

  it('shows no marks before an analysis completed', async () => {
    renderWorkspace(stubContractApi(options()))
    const page = await screen.findByRole('article')
    await within(page).findByText('1. Definitions')
    expect(within(page).queryByText('Checked, no issue found')).not.toBeInTheDocument()
    expect(within(page).queryByText(/Not analysed|Not reviewed/)).not.toBeInTheDocument()
  })

  it('lets you add a finding on a clause Rivell passed, and opens it selected', async () => {
    let added = false
    const api = stubContractApi(
      options({
        clauseMarks: fixtureClauseMarks,
        findingsResponse: () =>
          apiResponse({
            items: added
              ? [fixtureClauseFindingSummary, fixtureAbsenceFindingSummary, fixtureUserFindingSummary]
              : [fixtureClauseFindingSummary, fixtureAbsenceFindingSummary],
            truncated: false,
          }),
        findingDetailResponse: (findingId) =>
          apiResponse(findingId === 'f-user' ? fixtureUserFindingDetail : fixtureClauseFindingDetail),
        addFindingResponse: () => {
          added = true
          return apiResponse({ findingId: 'f-user' }, 201)
        },
      }),
    )
    const router = renderWorkspace(api)
    const clause = await clauseOf('1. Definitions')
    await userEvent.click(within(clause).getByRole('button', { name: 'Add a finding' }))

    await userEvent.click(within(clause).getByRole('button', { name: 'Add finding' }))
    expect(within(clause).getByText('Say what Rivell missed.')).toBeInTheDocument()
    expect(api.requests.some((request) => request.method === 'POST' && request.url.endsWith('/findings'))).toBe(false)

    await userEvent.type(
      within(clause).getByRole('textbox', { name: 'What did Rivell miss?' }),
      'The definition of Services has no carve-out for third-party software.',
    )
    await userEvent.selectOptions(within(clause).getByRole('combobox', { name: 'Severity' }), 'HIGH')
    await userEvent.click(within(clause).getByRole('button', { name: 'Add finding' }))

    await waitFor(() => expect(router.state.location.search).toBe('?finding=f-user'))
    const post = api.requests.find((request) => request.method === 'POST' && request.url.endsWith('/findings'))
    expect(post?.body).toEqual({
      headingNodeId: 'node-1',
      severity: 'HIGH',
      note: 'The definition of Services has no carve-out for third-party software.',
    })
    const panel = await detail()
    expect(await within(panel).findByText('The definition of Services has no carve-out for third-party software.')).toBeInTheDocument()
    // the note is the reviewer's, never presented as Rivell's analysis or recommendation
    expect(within(panel).getByText('Reviewer\u2019s note')).toBeInTheDocument()
    expect(within(panel).queryByText('Rivell found')).not.toBeInTheDocument()
    expect(within(panel).queryByText('Recommendation:')).not.toBeInTheDocument()
    expect(within(panel).queryByText('Rivell\u2019s recommendation is kept separately')).not.toBeInTheDocument()
    expect(within(panel).getAllByText(/Added by a reviewer/)).toHaveLength(1)
  })

  it('offers no add on a heading outside the review plan, where the server would refuse it', async () => {
    renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks })))
    const unplanned = await clauseOf('Northwind Customer Group LLC')
    expect(within(unplanned).getByText('Not reviewed: outside the review plan')).toBeInTheDocument()
    expect(within(unplanned).queryByRole('button', { name: 'Add a finding' })).not.toBeInTheDocument()
    expect(within(await clauseOf('5. Termination')).getByRole('button', { name: 'Add a finding' })).toBeInTheDocument()
  })

  it('warns that two changes on one clause cannot both be approved', async () => {
    renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks })))
    const clause = await clauseOf('6. Limitation of Liability')
    await userEvent.click(within(clause).getByRole('button', { name: 'Add a finding' }))
    expect(
      within(clause).getByText('This clause already has a finding. Two changes on one clause cannot both be approved.'),
    ).toBeInTheDocument()
  })

  it('offers no add once the review is finalized', async () => {
    renderWorkspace(
      stubContractApi(
        options({
          clauseMarks: fixtureClauseMarks,
          humanReview: fixtureHumanReview({ status: 'COMPLETED', readyToFinalize: false, changeSetId: 'cs-1', finalizedAt: '2026-09-26T11:00:00Z' }),
          changeSet: fixtureChangeSet(),
        }),
      ),
    )
    const clause = await clauseOf('1. Definitions')
    expect(within(clause).getByText('Checked, no issue found')).toBeInTheDocument()
    expect(within(clause).queryByRole('button', { name: 'Add a finding' })).not.toBeInTheDocument()
  })
})

describe('clause details in place', () => {
  async function openClauseDetails(heading: string) {
    const clause = await clauseOf(heading)
    await userEvent.click(within(clause).getByRole('button', { name: 'Clause details' }))
    return screen.getByRole('dialog', { name: 'Clause details' })
  }

  it('opens a clause’s semantics beside the document, without leaving the workspace', async () => {
    const router = renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks })))
    const sheet = await openClauseDetails('6. Limitation of Liability')

    expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1')
    expect(within(sheet).getByRole('heading', { name: '6. Limitation of Liability' })).toBeInTheDocument()
    expect(sheet).toHaveTextContent('Limitation of liability')
    expect(sheet).toHaveTextContent('Corrections apply to the next review')
  })

  it('corrects the topic and keeps Rivell’s own reading beside it', async () => {
    const api = stubContractApi(options({ clauseMarks: fixtureClauseMarks }))
    renderWorkspace(api)
    const sheet = await openClauseDetails('6. Limitation of Liability')

    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the topic' }))
    await within(sheet).findByRole('option', { name: 'Indemnification' })
    await userEvent.selectOptions(within(sheet).getByLabelText('Topic'), 'INDEMNIFICATION')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save topic' }))

    await waitFor(() =>
      expect(api.requests.find((request) => request.method === 'POST' && request.url.endsWith('/semantics/clauses/cl6/concepts'))?.body).toEqual({
        primary: 'INDEMNIFICATION',
      }),
    )
    expect(await within(sheet).findByText(/Topic updated/)).toBeInTheDocument()
    const reading = within(sheet).getByText('Rivell read').parentElement as HTMLElement
    expect(reading).toHaveTextContent('Limitation of liability')
    expect(within(sheet).getAllByText('Corrected by you').length).toBeGreaterThan(0)
  })

  it('keeps keyboard focus where it is when the workspace updates behind the sheet', async () => {
    renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks })))
    const sheet = await openClauseDetails('6. Limitation of Liability')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the topic' }))
    await within(sheet).findByRole('option', { name: 'Indemnification' })
    await userEvent.selectOptions(within(sheet).getByLabelText('Topic'), 'INDEMNIFICATION')
    const save = within(sheet).getByRole('button', { name: 'Save topic' })
    await userEvent.click(save)

    // the saved correction re-renders the workspace; focus must not jump to the sheet's Close button
    expect(await within(sheet).findByText(/Topic updated/)).toBeInTheDocument()
    expect(document.activeElement).not.toBe(within(sheet).getAllByRole('button', { name: 'Close' })[0])
    expect(document.activeElement).toBe(save)
  })

  it('checks corrected values before sending them, then sends only what changed', async () => {
    const api = stubContractApi(options({ clauseMarks: fixtureClauseMarks }))
    renderWorkspace(api)
    const sheet = await openClauseDetails('6. Limitation of Liability')

    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the values' }))
    const cap = within(sheet).getByLabelText('Liability cap multiple')
    await userEvent.clear(cap)
    await userEvent.type(cap, '0')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save values' }))
    expect(await within(sheet).findByRole('alert')).toHaveTextContent(/greater than zero/i)
    expect(api.requests.some((request) => request.url.endsWith('/facts'))).toBe(false)

    await userEvent.clear(cap)
    await userEvent.type(cap, '2')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save values' }))
    await waitFor(() =>
      expect(api.requests.find((request) => request.method === 'POST' && request.url.endsWith('/semantics/clauses/cl6/facts'))?.body).toEqual({
        values: { liabilityCapMultiple: '2' },
      }),
    )
  })

  it('says what is intact when the analysis is busy, and offers a refresh', async () => {
    const api = stubContractApi(
      options({
        clauseMarks: fixtureClauseMarks,
        conceptCorrectionResponse: () =>
          new Response(JSON.stringify({ code: '409', title: 'Invalid state', detail: 'Enrichment for contract 9f2a1c44 is already running' }), {
            status: 409,
            headers: { 'Content-Type': 'application/json' },
          }),
      }),
    )
    renderWorkspace(api)
    const sheet = await openClauseDetails('6. Limitation of Liability')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the topic' }))
    await within(sheet).findByRole('option', { name: 'Indemnification' })
    await userEvent.selectOptions(within(sheet).getByLabelText('Topic'), 'INDEMNIFICATION')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Save topic' }))

    expect(await within(sheet).findByText(/analysis of this contract version is not ready/i)).toBeInTheDocument()
    expect(within(sheet).queryByText(/9f2a1c44/)).not.toBeInTheDocument()
    const before = api.requests.filter((request) => request.method === 'GET' && request.url.includes('/semantics')).length
    await userEvent.click(within(sheet).getByRole('button', { name: 'Refresh analysis state' }))
    await waitFor(() =>
      expect(api.requests.filter((request) => request.method === 'GET' && request.url.includes('/semantics')).length).toBeGreaterThan(before),
    )
  })

  it('reads a clause as its heading and the passages under it, for terms and references', async () => {
    const semantics = fixtureSemantics()
    renderWorkspace(
      stubContractApi(
        options({
          clauseMarks: fixtureClauseMarks,
          semantics: {
            ...semantics,
            definedTerms: [
              ...semantics.definedTerms,
              { id: 't9', term: 'Exclusions', definition: 'the exclusions in Section 6.2.', definingNodeId: 'node-1', referenceNodeIds: ['node-63'], duplicate: false },
            ],
          },
        }),
      ),
    )
    const sheet = await openClauseDetails('6. Limitation of Liability')

    // Section 5 is cited in 6.3, a passage under heading 6, not in the heading itself.
    const references = within(sheet).getByText('References it makes').parentElement as HTMLElement
    expect(references).toHaveTextContent('Section 99')
    expect(references).toHaveTextContent('Section 5')
    expect(references).not.toHaveTextContent('Section 6.3')
    const terms = within(sheet).getByText('Defined terms it uses').parentElement as HTMLElement
    expect(within(terms).getByRole('button', { name: 'Exclusions' })).toBeInTheDocument()
  })

  it('disables the topic picker and says so when the topic list cannot be loaded', async () => {
    renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks, taxonomyStatus: 503 })))
    const sheet = await openClauseDetails('6. Limitation of Liability')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the topic' }))

    await waitFor(() => expect(within(sheet).getByLabelText('Topic')).toBeDisabled())
    expect(await within(sheet).findByText(/legal topic list could not be loaded/i, {}, { timeout: 3000 })).toBeInTheDocument()
    expect(within(sheet).getByRole('button', { name: 'Save topic' })).toBeDisabled()
  })

  it('shows facts of an unknown kind without offering to edit them', async () => {
    const unknownFact = { factSchema: 'privacy-facts', schemaVersion: 1, sourceNodeIds: [], values: { retentionDays: '30' } }
    renderWorkspace(
      stubContractApi(
        options({
          clauseMarks: fixtureClauseMarks,
          semantics: fixtureSemantics({
            clauses: [
              {
                id: 'cl9',
                headingNodeId: 'node-9',
                regionKind: 'CLAUSE',
                heading: 'Schedule A — Services',
                primaryConcept: null,
                secondaryConcepts: [],
                correctedPrimaryConcept: null,
                machineFacts: [unknownFact],
                effectiveFacts: [unknownFact],
                interpretations: [],
                corrected: false,
              },
            ],
          }),
        }),
      ),
    )
    const sheet = await openClauseDetails('Schedule A — Services')

    expect(sheet).toHaveTextContent('Retention days')
    expect(sheet).toHaveTextContent('30')
    expect(within(sheet).queryByRole('button', { name: 'Correct the values' })).not.toBeInTheDocument()
  })

  it('never carries an unsaved topic draft to another clause', async () => {
    renderWorkspace(stubContractApi(options({ clauseMarks: fixtureClauseMarks })))
    let sheet = await openClauseDetails('6. Limitation of Liability')
    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the topic' }))
    await within(sheet).findByRole('option', { name: 'Indemnification' })
    await userEvent.selectOptions(within(sheet).getByLabelText('Topic'), 'INDEMNIFICATION')
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Clause details' })).not.toBeInTheDocument())

    sheet = await openClauseDetails('5. Termination')
    expect(within(sheet).getByRole('heading', { level: 2 })).toHaveTextContent('5. Termination')
    expect(within(sheet).queryByLabelText('Topic')).not.toBeInTheDocument()
    await userEvent.click(within(sheet).getByRole('button', { name: 'Correct the topic' }))
    expect(within(sheet).getByLabelText('Topic')).toHaveValue('TERM')
  })

  it('is read-only when the review pinned an older version, and says why', async () => {
    renderWorkspace(
      stubContractApi(
        options({
          clauseMarks: fixtureClauseMarks,
          contractSummary: { ...fixtureContractSummary, latestVersion: { ...fixtureContractSummary.latestVersion, versionNumber: 2 } },
        }),
      ),
    )
    const sheet = await openClauseDetails('6. Limitation of Liability')

    expect(within(sheet).queryByRole('button', { name: 'Correct the topic' })).not.toBeInTheDocument()
    expect(within(sheet).queryByRole('button', { name: 'Correct the values' })).not.toBeInTheDocument()
    expect(sheet).toHaveTextContent('Corrections apply to the latest contract version (version 2). This review uses version 1')
  })
})

describe('contract analysis in place', () => {
  async function openContractAnalysis() {
    await userEvent.click(await screen.findByRole('button', { name: 'Contract analysis' }))
    return screen.getByRole('dialog', { name: 'Contract analysis' })
  }

  async function openGroup(sheet: HTMLElement, name: string) {
    const group = within(sheet).getByRole('region', { name })
    await userEvent.click(within(group).getByRole('button', { name: new RegExp(`^${name}`) }))
    return group
  }

  it('lists defined terms, flags a term defined twice, and opens the defining clause in the document', async () => {
    const router = renderWorkspace(stubContractApi(options()))
    const sheet = await openContractAnalysis()
    const terms = await openGroup(sheet, 'Defined terms')

    expect(terms).toHaveTextContent('the managed cloud services described in Schedule A.')
    expect(terms).toHaveTextContent('Used in 2 places')
    expect(within(terms).getByText('Defined more than once')).toBeInTheDocument()
    await userEvent.click(within(terms).getByRole('button', { name: 'Services' }))
    expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Contract analysis' })).not.toBeInTheDocument())
  })

  it('shows each cross-reference’s state, broken ones first, and never invents a target', async () => {
    renderWorkspace(stubContractApi(options()))
    const sheet = await openContractAnalysis()
    const refs = await openGroup(sheet, 'Cross-references')

    const rows = within(refs).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('Section 99')
    expect(within(rows[0]).queryByRole('button')).not.toBeInTheDocument()
    const ambiguous = rows.find((row) => row.textContent?.includes('Section 5')) as HTMLElement
    expect(ambiguous).toHaveTextContent('Possible targets')
    const resolved = rows.find((row) => row.textContent?.includes('Section 6.3')) as HTMLElement
    expect(within(resolved).getAllByRole('button')).toHaveLength(1)
  })

  it('says how each clause relationship was found', async () => {
    renderWorkspace(stubContractApi(options()))
    const sheet = await openContractAnalysis()
    const related = await openGroup(sheet, 'Related clauses')

    expect(related).toHaveTextContent('depends on')
    expect(related).toHaveTextContent('Inferred from the clause texts')
    expect(related).toHaveTextContent('Stated in the clause text')
  })

  it('corrects a party’s roles and keeps Rivell’s own reading', async () => {
    const api = stubContractApi(options())
    renderWorkspace(api)
    const sheet = await openContractAnalysis()
    const parties = await openGroup(sheet, 'Parties')
    const party = within(parties).getByText('Pallas Cloud Services GmbH').closest('.rv-ws-ca-item') as HTMLElement

    await userEvent.click(within(party).getByRole('button', { name: 'Correct the roles' }))
    await userEvent.click(within(party).getByLabelText('Supplier'))
    await userEvent.click(within(party).getByRole('button', { name: 'Save roles' }))
    expect(await within(party).findByRole('alert')).toHaveTextContent('Choose at least one role')
    expect(api.requests.some((request) => request.url.endsWith('/role'))).toBe(false)

    await userEvent.click(within(party).getByLabelText('Customer'))
    await userEvent.click(within(party).getByRole('button', { name: 'Save roles' }))
    await waitFor(() =>
      expect(api.requests.find((request) => request.method === 'POST' && request.url.endsWith('/parties/p2/role'))?.body).toEqual({
        roles: ['CUSTOMER'],
      }),
    )
  })

  it('is read-only when the review pinned an older version', async () => {
    renderWorkspace(
      stubContractApi(
        options({ contractSummary: { ...fixtureContractSummary, latestVersion: { ...fixtureContractSummary.latestVersion, versionNumber: 2 } } }),
      ),
    )
    const sheet = await openContractAnalysis()
    await openGroup(sheet, 'Parties')
    expect(within(sheet).queryByRole('button', { name: 'Correct the roles' })).not.toBeInTheDocument()
    expect(sheet).toHaveTextContent('This review uses version 1, so this view is read-only.')
  })

  it('never shows probabilities or provider plumbing', async () => {
    renderWorkspace(stubContractApi(options()))
    const sheet = await openContractAnalysis()
    for (const name of ['Parties', 'Defined terms', 'Cross-references', 'Related clauses']) await openGroup(sheet, name)
    expect(sheet.textContent).not.toMatch(/probability|0\.\d|provider|budget|token/i)
  })
})
