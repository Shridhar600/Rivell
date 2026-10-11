import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { stubContractApi, type StubOptions } from '../components/contract/testFixtures'
import {
  fixtureClauseFindingDetail,
  fixtureCompletedReviewSummary,
  fixtureFindingList,
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

describe('workspace layout', () => {
  it('draws the three canonical regions, with no sidebar around them', async () => {
    renderWorkspace(stubContractApi(options()))
    await waitFor(() =>
      expect(within(screen.getByRole('complementary', { name: 'Findings' })).getByRole('option', { name: /6\. Limitation of Liability/ })).toBeInTheDocument(),
    )
    expect(screen.getByRole('main', { name: 'Contract' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })).toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(within(await topBar()).getByRole('link', { name: 'Home' })).toHaveAttribute('href', '/')
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

  it('shows an edited decision as the reviewer’s wording in the decision preview, not as a verified file', async () => {
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
    await userEvent.click(await screen.findByRole('tab', { name: 'Decision preview' }))
    const page = screen.getByRole('article')
    await waitFor(() => expect(within(page).getByText('Cap is two times fees.')).toBeInTheDocument())
    expect(screen.getByRole('main', { name: 'Contract' })).toHaveTextContent(
      '1 current executable decision shown; 1 unresolved Finding remains as received.',
    )
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
  it('is disabled with one compact status while the server reports blockers, and the exact reason is in a disclosure', async () => {
    renderWorkspace(stubContractApi(options()))
    const finalize = await within(await topBar()).findByRole('button', { name: 'Finalize review' })
    expect(finalize).toBeDisabled()
    expect(finalize).toHaveAttribute('title', '1 finding that requires review still needs a decision.')
    expect(await topBar()).toHaveTextContent('0 of 2 resolved')
    // The unresolved count is not repeated on the button, and the blocker is not a clipped line.
    expect(await topBar()).not.toHaveTextContent(/unresolved/)
    expect(await topBar()).toHaveTextContent('Not ready to finalize')
    await userEvent.click(within(await topBar()).getByText('Not ready to finalize'))
    expect(await topBar()).toHaveTextContent('1 finding that requires review still needs a decision.')
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
    expect(await topBar()).toHaveTextContent('Ready to finalize')
    await userEvent.click(finalize)
    const dialog = await screen.findByRole('dialog', { name: 'Finalize this review?' })
    expect(dialog).toHaveTextContent('1 approved change (1 accepted, 0 edited)')
    expect(dialog).toHaveTextContent('1 rejected Finding; no approved change created')
    expect(dialog).toHaveTextContent('Redline generation is a separate downstream step.')
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
    expect(within(await detail()).getByText(/1 executable change in the immutable approved change set/)).toBeInTheDocument()
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

  it('lists only commands that can run, and no global entity search', async () => {
    renderWorkspace(stubContractApi(options()))
    await screen.findByRole('heading', { level: 2, name: '6. Limitation of Liability' })
    await userEvent.click(within(await topBar()).getByRole('button', { name: 'Open command menu' }))
    const menu = await screen.findByRole('dialog', { name: 'Command menu' })
    const labels = within(menu).getAllByRole('option').map((option) => option.textContent)
    expect(labels.join('|')).toMatch(/Edit finding/)
    expect(labels.join('|')).toMatch(/Show original document/)
    expect(labels.join('|')).not.toMatch(/Finalize review/)
    expect(labels.join('|')).not.toMatch(/Generate redline|Start a contract review|Add legal knowledge/)
    await userEvent.type(within(menu).getByRole('textbox'), 'exclusions')
    await userEvent.click(within(menu).getByRole('option', { name: /Find “exclusions” in contract/ }))
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
