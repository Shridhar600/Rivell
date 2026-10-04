import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { stubContractApi, type StubOptions } from '../contract/testFixtures'
import {
  fixtureAnalyzingReviewSummary,
  fixtureClauseFindingDetail,
  fixtureCompletedReviewSummary,
  fixtureFindingList,
} from './testFixtures'
import {
  fixtureChangeSet,
  fixtureDecision,
  fixtureHumanReview,
  fixtureMixedChangeSet,
} from './workflowFixtures'
import { fixtureAbsenceFindingForDecision, fixtureAcceptableFinding } from './workflowFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-26T10:00:00Z' }
}

function renderRouter(
  initialEntry: string,
  api: ReturnType<typeof stubContractApi>,
) {
  void api
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] })
  render(
    <StrictMode>
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>
    </StrictMode>,
  )
  return { router }
}

function baseOptions(options: StubOptions = {}): StubOptions {
  return {
    reviewSummary: fixtureCompletedReviewSummary,
    findings: fixtureFindingList,
    findingDetailResponse: (findingId: string) => {
      const details: Record<string, unknown> = {
        'f-1': fixtureClauseFindingDetail,
        'f-2': fixtureAbsenceFindingForDecision,
      }
      return jsonResponse(envelope(details[findingId] ?? null))
    },
    decisions: { items: [], truncated: false },
    humanReview: fixtureHumanReview(),
    ...options,
  }
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

/** The three regions of the workspace, found by their accessible names. */
const findingsNav = () => within(screen.getByRole('complementary', { name: 'Findings' }))
const findingDetail = () => within(screen.getByRole('complementary', { name: 'Finding detail' }))
const topBar = () => within(screen.getByRole('banner'))
const finalizeButton = () => topBar().getByRole('button', { name: /^(Finalize review|Finalized)/ })
const FINDING_TITLE = '6. Limitation of Liability'

async function findSelectedFindingDetail() {
  return screen.findByRole('heading', { level: 2, name: FINDING_TITLE })
}

/** The workspace draws only once the review and contract have loaded. */
async function workspaceReady() {
  await screen.findByRole('banner')
  await screen.findByRole('complementary', { name: 'Finding detail' })
}

function openFinalizeDialog() {
  return screen.getByRole('dialog', { name: 'Finalize this review?' })
}

describe('human review progress in the workspace', () => {
  it('renders server-owned counts, blocker and unresolved total, and lets a finding be opened from the list', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: fixtureHumanReview({
          accepted: 1,
          resolvedFindings: 1,
          remainingFindings: 1,
          actionableFindings: 2,
          informationalFindings: 2,
          informationalDecided: 1,
          blockers: [
            {
              code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
              message: '1 finding that requires review still needs a decision.',
              findingIds: ['f-1'],
            },
          ],
        }),
      }),
    )
    const { router } = renderRouter('/reviews/c1/analysis/r-1', api)
    await workspaceReady()

    expect(await topBar().findByText('1 of 2 resolved')).toBeInTheDocument()
    expect(topBar().getByText('1 finding that requires review still needs a decision.')).toBeInTheDocument()
    expect(finalizeButton()).toHaveTextContent('Finalize review · 1 unresolved')
    expect(finalizeButton()).toBeDisabled()
    expect(
      within(screen.getByRole('complementary', { name: 'Findings' })).getByText('1 unresolved'),
    ).toBeInTheDocument()

    const list = findingsNav().getByRole('listbox', { name: 'Findings list' })
    await userEvent.click(within(list).getByRole('option', { name: /Limitation of Liability/ }))
    await waitFor(() => expect(router.state.location.search).toContain('finding=f-1'))
  })

  it('does not claim the review is finalized or frozen when only the machine analysis has completed', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)

    await findSelectedFindingDetail()
    expect(topBar().getByText('0 of 1 resolved')).toBeInTheDocument()
    expect(finalizeButton()).toHaveTextContent('Finalize review · 1 unresolved')
    expect(topBar().queryByText('Approved changes frozen')).not.toBeInTheDocument()
    expect(topBar().queryByText('Ready to finalize')).not.toBeInTheDocument()
    expect(screen.queryByText('Human review completed')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Approved preview' })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Decision preview' })).toBeInTheDocument()
  })
})

describe('recording a decision in the workspace', () => {
  it('saves the decision, shows it as current and refreshes human review progress', async () => {
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        decisionResponse: (body) => {
          const request = body as { clientRequestId?: string }
          const decision = fixtureDecision({
            id: 'd-9',
            revision: 1,
            clientRequestId: request.clientRequestId ?? null,
          })
          stub.setDecisions({ items: [decision], truncated: false })
          stub.setHumanReview(
            fixtureHumanReview({
              status: 'READY_FOR_COMPLETION',
              accepted: 1,
              resolvedFindings: 1,
              remainingFindings: 0,
              blockers: [],
              readyToFinalize: true,
            }),
          )
          return jsonResponse(envelope(decision))
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)

    await findSelectedFindingDetail()
    const findingListRequestsBeforeSave = stub.requests.filter((request) =>
      /\/findings\?/.test(request.url),
    ).length
    await userEvent.click(await findingDetail().findByRole('button', { name: 'Accept' }))

    await waitFor(() =>
      expect(stub.requests.find((request) => request.method === 'PUT')).toBeDefined(),
    )
    const put = stub.requests.find((request) => request.method === 'PUT')
    expect(put?.body as Record<string, unknown>).toMatchObject({
      action: 'ACCEPT',
      expectedVersion: 0,
    })

    // The server's refreshed human-review state drives the top bar.
    expect(await topBar().findByText('Ready to finalize')).toBeInTheDocument()
    expect(topBar().getByText('1 of 1 resolved')).toBeInTheDocument()
    expect(finalizeButton()).toBeEnabled()

    // The saved decision is shown as current in the list and in the detail.
    const row = await findingsNav().findByRole('option', { name: /Limitation of Liability/ })
    expect(row).toHaveTextContent('Accepted')
    await userEvent.click(row)
    const detail = findingDetail()
    expect(await detail.findByText('Revision 1', { exact: false })).toBeInTheDocument()
    expect(detail.getByRole('button', { name: 'Accept' })).toHaveAttribute('aria-pressed', 'true')

    expect(
      stub.requests.filter((request) => /\/findings\?/.test(request.url)),
    ).toHaveLength(findingListRequestsBeforeSave)
  })

  it('checks the evidence endpoint is never called from decision surfaces', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)
    await findSelectedFindingDetail()
    await findingDetail().findByRole('button', { name: 'Accept' })
    expect(
      api.requests.filter((request) => request.url.includes('/api/retrieval/')),
    ).toHaveLength(0)
  })
})

describe('workflow state availability and conflict truth', () => {
  it('keeps the frozen state and the review summary reachable when the human-review read fails', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReviewStatus: 500,
        changeSet: fixtureChangeSet(),
        redlineStatus: 404,
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    expect(
      await screen.findByText(/Human review state could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    // A readable change set is proof of finalization, so the frozen card is still reachable.
    const card = (await findingDetail().findByText('Approved changes frozen', {}, { timeout: 3000 })).closest(
      '.rv-ws-phase',
    ) as HTMLElement
    expect(card).toHaveTextContent('1 executable change in the immutable approved change set.')
    // Redline belongs to the review summary. The Workspace only points there.
    expect(findingDetail().getByRole('link', { name: 'View review summary' })).toHaveAttribute(
      'href',
      '/reviews/c1/overview/r-1',
    )
    expect(screen.queryByRole('button', { name: /Generate redline/i })).not.toBeInTheDocument()
    expect(api.requests.filter((r) => r.url.includes('/redline'))).toHaveLength(0)

    const changeSetCalls = api.requests.filter((r) => r.url.includes('/approved-change-set'))
    expect(changeSetCalls).toHaveLength(1)
  })

  it('fails the decision read closed on a malformed row instead of showing a blank list', async () => {
    const api = stubContractApi(
      baseOptions({
        decisions: {
          items: [
            {
              id: 'd-1',
              findingId: 'f-1',
              revision: 1,
              action: 'ACCEPT',
              approvedAction: null,
              approvedLanguage: null,
              languageSource: null,
              approvedDraftingEvidenceId: null,
              comment: null,
              actorId: null,
              decidedAt: '2026-09-26T10:00:00Z',
              clientRequestId: null,
              supersedesDecisionId: null,
            },
          ],
          truncated: false,
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    expect(
      await screen.findByText(/Decisions could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    await findSelectedFindingDetail()
    expect(findingDetail().getByText(/Decision controls are unavailable/)).toBeInTheDocument()
    expect(findingDetail().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(findingDetail().queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })

  it('states a permission failure as a permission failure, not a load failure', async () => {
    const api = stubContractApi(baseOptions({ reviewSummaryStatus: 403 }))
    renderRouter('/reviews/c1/analysis/r-1', api)

    const alert = await screen.findByRole('alert', {}, { timeout: 3000 })
    expect(alert).toHaveTextContent(/do not have permission/i)
    expect(alert).not.toHaveTextContent(/could not be loaded/i)
    expect(
      screen.queryByRole('button', { name: 'Try again' }),
    ).not.toBeInTheDocument()
  })

  it('invents nothing when the human-review read fails and no change set exists', async () => {
    const api = stubContractApi(
      baseOptions({ humanReviewStatus: 500, changeSetStatus: 404 }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    const banner = await screen.findByText(/Human review state could not be loaded/, {}, { timeout: 3000 })
    expect(banner).toHaveTextContent(/decisions and finalization cannot be confirmed/i)
    await findSelectedFindingDetail()
    await waitFor(() =>
      expect(api.requests.filter((r) => r.url.includes('/approved-change-set')).length).toBe(1),
    )
    expect(findingDetail().queryByText('Approved changes frozen')).not.toBeInTheDocument()
    expect(
      findingDetail().queryByRole('button', { name: 'Redline and change report' }),
    ).not.toBeInTheDocument()
    expect(topBar().queryByText('Approved changes frozen')).not.toBeInTheDocument()
    expect(topBar().queryByText('Ready to finalize')).not.toBeInTheDocument()
    expect(finalizeButton()).toBeDisabled()
    expect(finalizeButton()).not.toHaveTextContent('Finalized')
    expect(
      screen.queryByText(/The approved change set could not be loaded/),
    ).not.toBeInTheDocument()
  })

  it('does not read the change set when human-review state loads and the review is not finalized', async () => {
    const api = stubContractApi(
      baseOptions({ humanReview: fixtureHumanReview({ status: 'IN_PROGRESS' }) }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    await workspaceReady()
    await topBar().findByText('0 of 1 resolved')
    await findSelectedFindingDetail()
    expect(api.requests.filter((r) => r.url.includes('/approved-change-set'))).toHaveLength(0)
  })

  it('treats a missing change set as not finalized rather than a load failure', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: fixtureHumanReview({ status: 'COMPLETED' }),
        changeSetStatus: 404,
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    await workspaceReady()
    // The server says COMPLETED, so the review is shown as finalized and read-only. The change set
    // is simply not available: its size is not claimed and no load failure is reported.
    const card = (await findingDetail().findByText('Approved changes frozen')).closest(
      '.rv-ws-phase',
    ) as HTMLElement
    expect(card).toHaveTextContent('The approved change set is immutable.')
    expect(card).not.toHaveTextContent(/executable change/)
    expect(findingDetail().getByText(/Decision frozen/)).toBeInTheDocument()
    await waitFor(() =>
      expect(api.requests.filter((r) => r.url.includes('/approved-change-set')).length).toBeGreaterThan(0),
    )

    expect(findingDetail().getByRole('link', { name: 'View review summary' })).toBeInTheDocument()
    expect(api.requests.filter((r) => r.url.includes('/redline'))).toHaveLength(0)
    expect(
      screen.queryByText(/The approved change set could not be loaded/),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument()
  })

  it('hides decision controls and explains when human-review state cannot be loaded', async () => {
    const api = stubContractApi(baseOptions({ humanReviewStatus: 500 }))
    renderRouter('/reviews/c1/analysis/r-1', api)
    expect(
      await screen.findByText(/Human review state could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    await findSelectedFindingDetail()
    const detail = findingDetail()
    expect(detail.getByText(/Decision controls are unavailable/)).toBeInTheDocument()
    expect(detail.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(detail.queryByRole('button', { name: 'Change decision' })).not.toBeInTheDocument()
  })

  it('never claims no decision and disables controls when decisions cannot be loaded', async () => {
    const api = stubContractApi(baseOptions({ decisionsStatus: 500 }))
    renderRouter('/reviews/c1/analysis/r-1', api)
    expect(
      await screen.findByText(/Decisions could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    await findSelectedFindingDetail()
    const detail = findingDetail()
    expect(detail.queryByText(/No human decision recorded yet/)).not.toBeInTheDocument()
    expect(detail.getByText(/Decision controls are unavailable/)).toBeInTheDocument()
    expect(detail.queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    // No row claims a decision state (not even "Needs decision") while decisions are unknown.
    const list = findingsNav().getByRole('listbox', { name: 'Findings list' })
    expect(within(list).queryByText(/Needs decision/)).not.toBeInTheDocument()
    expect(within(list).queryByText(/Accepted|Rejected|Edited|Escalated/)).not.toBeInTheDocument()
  })

  it('reports a stale revision as changed elsewhere after refetching server state', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        decisions: {
          items: [fixtureDecision({ id: 'd-2', revision: 2 })],
          truncated: false,
        },
        decisionResponse: () => {
          stub.setDecisions({
            items: [fixtureDecision({ id: 'd-3', revision: 3, action: 'ACCEPT' })],
            truncated: false,
          })
          return jsonResponse(
            {
              code: '409',
              title: 'Invalid state',
              detail: 'Finding f-1 decision expected version 2 but the current revision is 3',
            },
            409,
          )
        },
      }),
    )
    // f-1 already has a decision, so name it: with no ?finding= the page would open the next open one.
    renderRouter('/reviews/c1/analysis/r-1?finding=f-1', stub)
    await findSelectedFindingDetail()
    await user.click(await findingDetail().findByRole('button', { name: 'Change decision' }))
    await user.click(findingDetail().getByRole('button', { name: 'Reject' }))
    const form = screen.getByRole('form', { name: 'Reject Finding' })
    await user.type(within(form).getByLabelText('Reason'), 'Second opinion.')
    await user.click(within(form).getByRole('button', { name: 'Reject' }))

    expect(await within(form).findByRole('alert')).toHaveTextContent(/changed elsewhere/)
    expect(screen.queryByText(/expected version/)).not.toBeInTheDocument()
    const put = stub.requests.find((request) => request.method === 'PUT')
    expect(put?.body).toMatchObject({ action: 'REJECT', expectedVersion: 2 })
  })
})

function readyReview() {
  return fixtureHumanReview({
    status: 'READY_FOR_COMPLETION',
    accepted: 1,
    resolvedFindings: 1,
    remainingFindings: 0,
    blockers: [],
    readyToFinalize: true,
  })
}

function completedHumanReview(overrides: Parameters<typeof fixtureHumanReview>[0] = {}) {
  return fixtureHumanReview({
    status: 'COMPLETED',
    readyToFinalize: true,
    blockers: [],
    changeSetId: 'cs-1',
    finalizedAt: '2026-09-26T11:00:00Z',
    ...overrides,
  })
}

describe('finalization', () => {
  it('blocks finalization until the server says the review is ready', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)
    await workspaceReady()
    await topBar().findByText('1 finding that requires review still needs a decision.')
    expect(finalizeButton()).toBeDisabled()
    expect(finalizeButton()).toHaveTextContent('Finalize review · 1 unresolved')

    // A disabled control cannot open the dialog, so nothing is sent.
    await userEvent.click(finalizeButton())
    expect(screen.queryByRole('dialog', { name: 'Finalize this review?' })).not.toBeInTheDocument()
    expect(api.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(0)
  })

  it('requires explicit confirmation and freezes exactly once', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        humanReview: readyReview(),
        finalizeResponse: () => {
          stub.setHumanReview(
            completedHumanReview({ accepted: 1, resolvedFindings: 1, remainingFindings: 0 }),
          )
          return jsonResponse(envelope(fixtureChangeSet()))
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)

    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    const dialog = openFinalizeDialog()
    expect(dialog).toHaveTextContent('frozen')
    expect(dialog).toHaveTextContent('1 approved change (1 accepted, 0 edited)')
    expect(stub.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(0)
    await user.click(within(dialog).getByRole('button', { name: 'Keep reviewing' }))
    expect(screen.queryByRole('dialog', { name: 'Finalize this review?' })).not.toBeInTheDocument()
    expect(stub.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(0)

    await user.click(finalizeButton())
    await user.click(within(openFinalizeDialog()).getByRole('button', { name: 'Finalize review' }))
    expect(await findingDetail().findByText('Approved changes frozen')).toBeInTheDocument()
    expect(stub.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(1)
    expect(screen.queryByRole('dialog', { name: 'Finalize this review?' })).not.toBeInTheDocument()
    expect(finalizeButton()).toHaveTextContent('Finalized')
    expect(finalizeButton()).toBeDisabled()
    expect(topBar().getByText('Approved changes frozen')).toBeInTheDocument()
  })

  it('states a degraded analysis honestly and offers no recovery control', async () => {
    const api = stubContractApi(
      baseOptions({
        reviewSummary: { ...fixtureCompletedReviewSummary, findings: 0, degradedChecks: 4 },
        findings: { items: [], truncated: false },
        humanReview: fixtureHumanReview({
          status: 'NOT_STARTED',
          readyToFinalize: false,
          actionableFindings: 0,
          remainingFindings: 0,
          blockers: [
            {
              code: 'ANALYSIS_DEGRADED',
              message:
                'No planned check completed a trustworthy analysis, so this review has no machine findings to decide on and cannot be finalized. Create a new review against the current semantics to continue.',
              findingIds: [],
            },
          ],
        }),
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    await workspaceReady()
    const blocker = await topBar().findByText(/No planned check completed a trustworthy analysis/)
    expect(blocker).toHaveTextContent(/new review/i)
    expect(finalizeButton()).toBeDisabled()
    expect(
      screen.queryByText('Every planned check completed without findings.'),
    ).not.toBeInTheDocument()

    for (const name of [/retry/i, /recompute/i, /run again/i, /re-run/i, /re-analy/i]) {
      expect(screen.queryByRole('button', { name })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
  })

  it('refreshes blockers and does not retry when finalization is rejected', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        humanReview: readyReview(),
        finalizeResponse: () => {
          stub.setHumanReview(
            fixtureHumanReview({
              status: 'IN_PROGRESS',
              readyToFinalize: false,
              blockers: [
                {
                  code: 'APPROVED_CHANGE_CONFLICT',
                  message: '2 approved changes target the same contract location.',
                  findingIds: [],
                },
              ],
            }),
          )
          return jsonResponse(
            { code: '409', title: 'Invalid state', detail: 'review is not finalizable' },
            409,
          )
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    await user.click(within(openFinalizeDialog()).getByRole('button', { name: 'Finalize review' }))

    expect(await within(openFinalizeDialog()).findByRole('alert')).toHaveTextContent(
      'cannot be finalized yet',
    )
    expect(await topBar().findByText(/same contract location/)).toBeInTheDocument()
    expect(stub.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(1)
  })

  it('uses finalization-specific copy for transport failures', async () => {
    const user = userEvent.setup()
    const api = stubContractApi(
      baseOptions({
        humanReview: readyReview(),
        finalizeResponse: () => {
          throw new TypeError('Failed to fetch')
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    await user.click(within(openFinalizeDialog()).getByRole('button', { name: 'Finalize review' }))
    expect(await within(openFinalizeDialog()).findByRole('alert')).toHaveTextContent(
      /could not be finalized/,
    )
    expect(screen.queryByText(/Failed to fetch/)).not.toBeInTheDocument()
  })
})

describe('approved change set', () => {
  const completedReview = () =>
    completedHumanReview({
      accepted: 2,
      edited: 1,
      resolvedFindings: 3,
      remainingFindings: 0,
    })

  const changeSetDecisions = {
    items: [
      fixtureDecision({ id: 'd-2', findingId: 'f-1', revision: 1, action: 'EDIT' }),
      fixtureDecision({
        id: 'd-3',
        findingId: 'f-2',
        revision: 1,
        action: 'ACCEPT',
        approvedAction: 'INSERT',
        approvedLanguage: 'Confidential information shall be protected for five years.',
        languageSource: 'GENERATIVE',
      }),
      fixtureDecision({
        id: 'd-4',
        findingId: 'f-3',
        revision: 1,
        action: 'EDIT',
        approvedAction: 'DELETE',
        approvedLanguage: null,
        languageSource: null,
      }),
    ],
    truncated: false,
  }

  function threeFindings() {
    return {
      items: [
        fixtureAcceptableFinding,
        {
          ...fixtureAcceptableFinding,
          id: 'f-2',
          subjectKind: 'ABSENCE',
          subjectHeadingNodeId: null,
          absenceConcept: 'CONFIDENTIALITY',
          concept: 'CONFIDENTIALITY',
          issueFamily: 'MISSING_REQUIRED_PROVISION',
          proposedAction: 'ADD_REQUIRED_PROVISION',
          proposedLanguageSource: 'NONE',
        },
        {
          ...fixtureAcceptableFinding,
          id: 'f-3',
          subjectHeadingNodeId: 'node-5',
          concept: 'TERM_TERMINATION',
          issueFamily: 'EXPECTED_PROVISION_ABSENT',
        },
      ],
      truncated: false,
    }
  }

  it('does not request the frozen set before finalization', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)
    await findSelectedFindingDetail()
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(
      api.requests.filter((request) => request.url.includes('/approved-change-set')),
    ).toHaveLength(0)
  })

  it('shows the frozen set size from the approved change set and previews the approved changes', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: completedReview(),
        decisions: changeSetDecisions,
        findings: threeFindings(),
        changeSet: fixtureMixedChangeSet(),
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)
    await workspaceReady()

    const card = (await findingDetail().findByText('Approved changes frozen')).closest(
      '.rv-ws-phase',
    ) as HTMLElement
    await waitFor(() =>
      expect(card).toHaveTextContent('3 executable changes in the immutable approved change set.'),
    )
    expect(finalizeButton()).toHaveTextContent('Finalized')

    await userEvent.click(screen.getByRole('tab', { name: 'Approved preview' }))
    const contract = within(screen.getByRole('main', { name: 'Contract' }))
    expect(
      contract.getByText(/3 frozen executable changes shown; INSERT placement remains unresolved until execution/),
    ).toBeInTheDocument()
    // Replace: the approved language stands in for the clause.
    expect(contract.getByText('Liability is capped at two times the fees paid.')).toBeInTheDocument()
    // Insert: the language is shown, but no location is claimed for it.
    expect(contract.getByText('Approved insertion: Confidentiality')).toBeInTheDocument()
    expect(
      contract.getByText(/Confidential information shall be protected for five years\./),
    ).toBeInTheDocument()
    expect(contract.getByText(/Placement will be resolved by deterministic redline execution/)).toBeInTheDocument()
    // Delete: the removed clause is not part of the approved document.
    expect(contract.queryByText(/Termination/)).not.toBeInTheDocument()
  })

  it('renders an available frozen set even when the decisions read fails', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: completedReview(),
        decisionsStatus: 500,
        changeSet: fixtureMixedChangeSet(),
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)
    await workspaceReady()
    const card = (await findingDetail().findByText('Approved changes frozen')).closest(
      '.rv-ws-phase',
    ) as HTMLElement
    await waitFor(() =>
      expect(card).toHaveTextContent('3 executable changes in the immutable approved change set.'),
    )
    expect(
      await screen.findByText(/Decisions could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
  })

  it('renders the frozen set from the finalize response without refetching it', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        humanReview: readyReview(),
        changeSet: fixtureMixedChangeSet(),
        finalizeResponse: () => {
          stub.setHumanReview(completedHumanReview())
          return jsonResponse(envelope(fixtureMixedChangeSet()))
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    await user.click(within(openFinalizeDialog()).getByRole('button', { name: 'Finalize review' }))
    const card = (await findingDetail().findByText('Approved changes frozen')).closest(
      '.rv-ws-phase',
    ) as HTMLElement
    expect(card).toHaveTextContent('3 executable changes in the immutable approved change set.')
    expect(
      stub.requests.filter((request) => request.url.includes('/approved-change-set')),
    ).toHaveLength(0)
  })

  it('selects the source finding when its clause is clicked in the document', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: completedReview(),
        decisions: changeSetDecisions,
        findings: threeFindings(),
        changeSet: fixtureMixedChangeSet(),
      }),
    )
    const { router } = renderRouter('/reviews/c1/analysis/r-1', api)
    await workspaceReady()
    await findingDetail().findByText('Approved changes frozen')
    const contract = screen.getByRole('main', { name: 'Contract' })
    // The deleted clause is struck through in the Changes view and belongs to finding f-3.
    const region = await waitFor(() => {
      const found = contract.querySelector<HTMLElement>('[data-findings="f-3"]')
      expect(found).not.toBeNull()
      return found as HTMLElement
    })
    await userEvent.click(region)
    await waitFor(() => expect(router.state.location.search).toContain('finding=f-3'))
  })
})

describe('workflow request scoping', () => {
  it('does not request decisions or human review while analysis is incomplete', async () => {
    const api = stubContractApi(
      baseOptions({ reviewSummary: fixtureAnalyzingReviewSummary }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)
    await screen.findByRole('heading', { name: 'Analysis state' })
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(
      api.requests.filter(
        (request) =>
          request.url.includes('/human-review') ||
          /\/decisions/.test(request.url) ||
          request.url.includes('/approved-change-set'),
      ),
    ).toHaveLength(0)
  })
})
