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
import { fixtureAcceptableFinding } from './workflowFixtures'

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

describe('human review progress in the workspace', () => {
  it('renders server-owned status, counts and blockers with finding navigation', async () => {
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

    const progress = await screen.findByRole('region', { name: 'Human review' })
    expect(progress).toHaveTextContent('Human review in progress')
    expect(progress).toHaveTextContent('Findings needing a decision')
    expect(progress).toHaveTextContent('Informational findings decided')
    expect(progress).toHaveTextContent('1 of 2')
    expect(progress).toHaveTextContent(
      '1 finding that requires review still needs a decision.',
    )
    await userEvent.click(
      screen.getByRole('button', {
        name: 'Open finding: 6. Limitation of Liability',
      }),
    )
    await waitFor(() => expect(router.state.location.search).toContain('finding=f-1'))
  })

  it('shows the machine analysis and human review states separately', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)
    expect(await screen.findByRole('heading', { name: 'Human review' })).toBeInTheDocument()
    expect(screen.queryByText('Human review completed')).not.toBeInTheDocument()
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

    await userEvent.click(
      await screen.findByRole('button', { name: /^6\. Limitation of Liability/ }),
    )
    const findingListRequestsBeforeSave = stub.requests.filter((request) =>
      /\/findings\?/.test(request.url),
    ).length
    await screen.findByRole('heading', { name: 'Human decision' })
    await userEvent.click(screen.getByRole('button', { name: 'Record decision' }))
    await userEvent.click(screen.getByRole('button', { name: 'Accept recommendation' }))

    expect(await screen.findByText('Decision recorded.')).toBeInTheDocument()
    const put = stub.requests.find((request) => request.method === 'PUT')
    const body = put?.body as Record<string, unknown>
    expect(body).toMatchObject({ action: 'ACCEPT', expectedVersion: 0 })
    const decisionSection = screen.getByRole('region', { name: 'Human decision' })
    expect(await within(decisionSection).findByText('Accepted')).toBeInTheDocument()
    expect(await screen.findByText('Ready to finalize')).toBeInTheDocument()
    const progress = screen.getByRole('region', { name: 'Human review' })
    const openLabel = within(progress).getByText('Still open')
    expect(openLabel.parentElement).toHaveTextContent('0')
    const findingRow = within(screen.getByRole('region', { name: 'Findings' })).getByRole(
      'button',
      { name: /^6\. Limitation of Liability/ },
    )
    expect(findingRow).toHaveTextContent('Accepted')
    expect(
      stub.requests.filter((request) => /\/findings\?/.test(request.url)),
    ).toHaveLength(findingListRequestsBeforeSave)
  })

  it('checks the evidence endpoint is never called from decision surfaces', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)
    await screen.findByRole('heading', { name: 'Human review' })
    expect(
      api.requests.filter((request) => request.url.includes('/api/retrieval/')),
    ).toHaveLength(0)
  })
})

describe('workflow state availability and conflict truth', () => {
  it('hides decision controls and explains when human-review state cannot be loaded', async () => {
    const api = stubContractApi(baseOptions({ humanReviewStatus: 500 }))
    renderRouter('/reviews/c1/analysis/r-1', api)
    expect(
      await screen.findByText(/Human review state could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    await userEvent.click(
      await screen.findByRole('button', { name: /^6\. Limitation of Liability/ }),
    )
    await screen.findByRole('heading', { name: 'Human decision' })
    expect(screen.getByText(/Decision controls are unavailable/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change decision' })).not.toBeInTheDocument()
  })

  it('never claims no decision and disables controls when decisions cannot be loaded', async () => {
    const api = stubContractApi(baseOptions({ decisionsStatus: 500 }))
    renderRouter('/reviews/c1/analysis/r-1', api)
    expect(
      await screen.findByText(/Decisions could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    await userEvent.click(
      await screen.findByRole('button', { name: /^6\. Limitation of Liability/ }),
    )
    await screen.findByRole('heading', { name: 'Human decision' })
    expect(screen.queryByText(/No human decision recorded yet/)).not.toBeInTheDocument()
    expect(screen.getByText(/Decision state could not be loaded/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument()
    const findingsPanel = screen.getByRole('region', { name: 'Findings' })
    expect(within(findingsPanel).queryByText('No decision')).not.toBeInTheDocument()
  })

  it('reports a stale revision as changed elsewhere after refetching server state', async () => {
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
    renderRouter('/reviews/c1/analysis/r-1', stub)
    await userEvent.click(
      await screen.findByRole('button', { name: /^6\. Limitation of Liability/ }),
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Change decision' }))
    await userEvent.click(screen.getByRole('radio', { name: 'Reject recommendation' }))
    await userEvent.type(screen.getByLabelText(/Comment/), 'Second opinion.')
    await userEvent.click(screen.getByRole('button', { name: 'Reject recommendation' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere/)
    expect(screen.queryByText(/expected version/)).not.toBeInTheDocument()
  })

  it('discloses blocker findings beyond the first three links', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: fixtureHumanReview({
          blockers: [
            {
              code: 'UNRESOLVED_REVIEW_REQUIRED_FINDINGS',
              message: '4 findings that require review still need decisions.',
              findingIds: ['f-1', 'f-2', 'f-3', 'f-4'],
            },
          ],
        }),
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)
    const progress = await screen.findByRole('region', { name: 'Human review' })
    expect(progress).toHaveTextContent('and 1 more affected finding(s)')
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

describe('finalization', () => {
  it('blocks finalization until the server says the review is ready', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter('/reviews/c1/analysis/r-1', api)
    const panel = await screen.findByRole('region', { name: 'Finalization' })
    expect(panel).toHaveTextContent('Accepted: 0')
    expect(panel).toHaveTextContent('The review cannot be finalized yet.')
    expect(
      screen.queryByRole('button', { name: 'Finalize review' }),
    ).not.toBeInTheDocument()
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
            fixtureHumanReview({
              status: 'COMPLETED',
              accepted: 1,
              resolvedFindings: 1,
              remainingFindings: 0,
              blockers: [],
              readyToFinalize: true,
              changeSetId: 'cs-1',
              finalizedAt: '2026-09-26T11:00:00Z',
            }),
          )
          return jsonResponse(envelope(fixtureChangeSet()))
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)

    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    expect(screen.getByRole('group', { name: 'Confirm finalization' })).toHaveTextContent(
      'cannot be undone',
    )
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('group', { name: 'Confirm finalization' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Finalize review' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))
    const finalization = screen.getByRole('region', { name: 'Finalization' })
    expect(
      await within(finalization).findByText(/approved change set is frozen/),
    ).toBeInTheDocument()
    expect(stub.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(1)
    expect(screen.queryByRole('button', { name: 'Confirm finalize' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Finalize review' })).not.toBeInTheDocument()
  })

  it('clears a finalize conflict notice once the review becomes ready', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        humanReview: readyReview(),
        finalizeResponse: () => {
          stub.setHumanReview(readyReview())
          return jsonResponse(
            { code: '409', title: 'Invalid state', detail: 'review is not finalizable' },
            409,
          )
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Confirm finalize' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Finalization' })).toHaveTextContent(
      'All required decisions are recorded',
    )
  })

  it('moves focus into the confirmation and back to the trigger', async () => {
    const user = userEvent.setup()
    const api = stubContractApi(baseOptions({ humanReview: readyReview() }))
    renderRouter('/reviews/c1/analysis/r-1', api)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    expect(screen.getByRole('button', { name: 'Confirm finalize' })).toHaveFocus()
    await user.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.getByRole('button', { name: 'Finalize review' })).toHaveFocus()
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
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'cannot be finalized yet',
    )
    expect(screen.getByRole('region', { name: 'Human review' })).toHaveTextContent(
      'same contract location',
    )
    expect(stub.requests.filter((request) => request.url.includes('/finalize'))).toHaveLength(1)
  })
})

describe('approved change set', () => {
  const completedReview = () =>
    fixtureHumanReview({
      status: 'COMPLETED',
      accepted: 2,
      edited: 1,
      resolvedFindings: 3,
      remainingFindings: 0,
      blockers: [],
      readyToFinalize: true,
      changeSetId: 'cs-1',
      finalizedAt: '2026-09-26T11:00:00Z',
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
    await screen.findByRole('heading', { name: 'Human review' })
    await new Promise((resolve) => setTimeout(resolve, 30))
    expect(
      api.requests.filter((request) => request.url.includes('/approved-change-set')),
    ).toHaveLength(0)
  })

  it('renders the frozen set with action types, provenance and source findings', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: completedReview(),
        decisions: changeSetDecisions,
        findings: threeFindings(),
        changeSet: fixtureMixedChangeSet(),
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', api)

    const changeSet = await screen.findByRole('region', { name: 'Approved change set' })
    expect(changeSet).toHaveTextContent('Frozen')
    expect(changeSet).toHaveTextContent('Version 1')
    const changes = await within(
      screen.getByRole('list', { name: 'Approved changes' }),
    ).findAllByRole('listitem')

    expect(changes[0]).toHaveTextContent('Replace clause — 6. Limitation of Liability')
    expect(changes[0]).toHaveTextContent('Liability is capped at two times the fees paid.')
    expect(changes[0]).toHaveTextContent('Human-authored')
    expect(changes[0]).toHaveTextContent('Decision: Edited · Revision 1')
    expect(changes[0]).toHaveTextContent('From finding: 6. Limitation of Liability')
    expect(changes[0]).toHaveTextContent('View clause in contract')

    expect(changes[1]).toHaveTextContent('Insert provision — Missing provision: Confidentiality')
    expect(changes[1]).toHaveTextContent(
      'Confidential information shall be protected for five years.',
    )
    expect(changes[1]).toHaveTextContent('AI-proposed, human-approved for this contract')
    expect(changes[1]).toHaveTextContent(
      'The insertion location is not part of the approved change set.',
    )

    expect(changes[2]).toHaveTextContent('Delete clause — 5. Termination')
    expect(changes[2]).toHaveTextContent('No replacement text')
    expect(changes[2]).toHaveTextContent('View clause in contract')

    expect(
      within(changeSet).queryByRole('button', { name: /redline|apply|download/i }),
    ).not.toBeInTheDocument()
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
    expect(
      await screen.findByRole('region', { name: 'Approved change set' }),
    ).toBeInTheDocument()
    expect(
      await screen.findByText(/Decisions could not be loaded/, {}, { timeout: 3000 }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('region', { name: 'Human review' }),
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
          stub.setHumanReview(
            fixtureHumanReview({
              status: 'COMPLETED',
              readyToFinalize: true,
              blockers: [],
              changeSetId: 'cs-1',
              finalizedAt: '2026-09-26T11:00:00Z',
            }),
          )
          return jsonResponse(envelope(fixtureMixedChangeSet()))
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))
    expect(
      await screen.findByRole('region', { name: 'Approved change set' }),
    ).toBeInTheDocument()
    expect(
      stub.requests.filter((request) => request.url.includes('/approved-change-set')),
    ).toHaveLength(0)
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
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/could not be finalized/)
    expect(screen.queryByText(/Failed to fetch/)).not.toBeInTheDocument()
  })

  it('moves focus to the completion status after finalization', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi(
      baseOptions({
        humanReview: readyReview(),
        finalizeResponse: () => {
          stub.setHumanReview(
            fixtureHumanReview({
              status: 'COMPLETED',
              readyToFinalize: true,
              blockers: [],
              changeSetId: 'cs-1',
              finalizedAt: '2026-09-26T11:00:00Z',
            }),
          )
          return jsonResponse(envelope(fixtureChangeSet()))
        },
      }),
    )
    renderRouter('/reviews/c1/analysis/r-1', stub)
    await user.click(await screen.findByRole('button', { name: 'Finalize review' }))
    await user.click(screen.getByRole('button', { name: 'Confirm finalize' }))
    const finalization = screen.getByRole('region', { name: 'Finalization' })
    await waitFor(() =>
      expect(
        within(finalization).getByText(/approved change set is frozen/),
      ).toHaveFocus(),
    )
  })

  it('selects the source finding from the frozen preview', async () => {
    const api = stubContractApi(
      baseOptions({
        humanReview: completedReview(),
        decisions: changeSetDecisions,
        findings: threeFindings(),
        changeSet: fixtureMixedChangeSet(),
      }),
    )
    const { router } = renderRouter('/reviews/c1/analysis/r-1', api)
    await screen.findByRole('region', { name: 'Approved change set' })
    await userEvent.click(
      screen.getByRole('button', { name: 'From finding: 5. Termination' }),
    )
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
