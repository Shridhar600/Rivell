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
  it('is disabled and says why while the server reports blockers', async () => {
    renderWorkspace(stubContractApi(options()))
    const finalize = await within(await topBar()).findByRole('button', { name: /Finalize review · 2 unresolved/ })
    expect(finalize).toBeDisabled()
    expect(finalize).toHaveAttribute('title', '1 finding that requires review still needs a decision.')
    expect(await topBar()).toHaveTextContent('1 finding that requires review still needs a decision.')
    expect(await topBar()).toHaveTextContent('0 of 2 resolved')
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
    expect(dialog).toHaveTextContent('1 rejected Finding; no ApprovedChange created')
    expect(dialog).toHaveTextContent('Redline generation is a separate downstream step.')
    expect(api.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/finalize'))).toHaveLength(0)
    await userEvent.click(within(dialog).getByRole('button', { name: 'Finalize review' }))
    await waitFor(() => expect(api.requests.filter((r) => r.method === 'POST' && r.url.endsWith('/finalize'))).toHaveLength(1))
  })

  it('keeps the frozen change set and its redline reachable when the selected finding cannot load', async () => {
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
    expect(within(await detail()).getByRole('button', { name: 'Redline and change report' })).toBeInTheDocument()
    expect(within(await topBar()).getByRole('button', { name: 'Finalized' })).toBeDisabled()
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
