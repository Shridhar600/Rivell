import { afterEach, describe, expect, it } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { vi } from 'vitest'
import { stubContractApi } from '../contract/testFixtures'
import type { FindingDetail } from '../../api/reviews'
import type { HumanDecision, HumanReview } from '../../api/workflow'
import {
  fixtureAcceptableFindingDetail,
  fixtureAbsenceFindingForDecision,
  fixtureDecision,
  fixtureGeneratedFindingDetail,
  fixtureHumanReview,
} from './workflowFixtures'
import { HumanDecisionControls } from './HumanDecisionControls'

function renderControls(
  overrides: {
    finding?: FindingDetail
    currentDecision?: HumanDecision | null
    humanReview?: HumanReview
    isFinalized?: boolean
    api?: Parameters<typeof stubContractApi>[0]
  } = {},
) {
  const api = stubContractApi({
    decisions: { items: overrides.currentDecision ? [overrides.currentDecision] : [], truncated: false },
    humanReview: overrides.humanReview ?? fixtureHumanReview(),
    ...overrides.api,
  })
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, retryDelay: 0 },
      mutations: { retry: false },
    },
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
  const view = render(
    <HumanDecisionControls
      reviewId="r-1"
      finding={overrides.finding ?? fixtureAcceptableFindingDetail}
      currentDecision={overrides.currentDecision ?? null}
      humanReview={overrides.humanReview ?? fixtureHumanReview()}
      isFinalized={overrides.isFinalized ?? false}
      canDecide
      analysisComplete
      decisionsLoaded
      decisionsTruncated={false}
    />,
    { wrapper },
  )
  return { api, view }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-26T10:00:00Z' }
}

function wrapperFor() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, retryDelay: 0 },
      mutations: { retry: false },
    },
  })
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  )
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('human decision controls — ACCEPT', () => {
  it('records an executable recommendation with the current version and no extra fields', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    expect(screen.getByRole('radio', { name: 'Accept recommendation' })).toBeChecked()
    expect(screen.getByText('Approved organizational language')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Accept recommendation' }))
    const put = api.requests.find((request) => request.method === 'PUT')
    expect(put).toBeDefined()
    const body = put?.body as Record<string, unknown>
    expect(body.action).toBe('ACCEPT')
    expect(body.expectedVersion).toBe(0)
    expect(body.clientRequestId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    )
    expect(body).not.toHaveProperty('approvedAction')
    expect(body).not.toHaveProperty('approvedLanguage')
    expect(await screen.findByRole('status')).toHaveTextContent('Decision recorded')
  })

  it('offers ACCEPT but not as usable when the recommendation is not executable', async () => {
    const user = userEvent.setup()
    renderControls({ finding: fixtureAbsenceFindingForDecision })
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    expect(screen.getByRole('radio', { name: 'Accept recommendation' })).toBeDisabled()
    expect(screen.getByText(/Use Edit to author the change/)).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Edit change' })).toBeChecked()
  })

  it('refuses ACCEPT when the action is not executable even if language exists', async () => {
    const user = userEvent.setup()
    renderControls({
      finding: {
        ...fixtureAcceptableFindingDetail,
        proposedAction: 'ADD_REQUIRED_PROVISION',
        proposedLanguage: 'Some language that must not be acceptable as-is.',
      },
    })
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    expect(screen.getByRole('radio', { name: 'Accept recommendation' })).toBeDisabled()
  })

  it('submits human language byte-exact without trimming', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    const language = screen.getByLabelText(/Final approved language/)
    await user.click(language)
    await user.paste('  Padded clause text.\n\nSecond paragraph.  ')
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.approvedLanguage).toBe('  Padded clause text.\n\nSecond paragraph.  ')
  })

  it('marks the required language field and focuses the initial action on open', async () => {
    const user = userEvent.setup()
    renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    expect(screen.getByRole('radio', { name: 'Accept recommendation' })).toHaveFocus()
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    expect(screen.getByLabelText(/Final approved language/)).toHaveAttribute(
      'aria-required',
      'true',
    )
  })

  it('moves focus to the decision heading after a successful save', async () => {
    const user = userEvent.setup()
    renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('button', { name: 'Accept recommendation' }))
    expect(await screen.findByText('Decision recorded.')).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Human decision' })).toHaveFocus(),
    )
  })

  it('labels generative acceptance honestly and never as approved language', async () => {
    const user = userEvent.setup()
    renderControls({ finding: fixtureGeneratedFindingDetail })
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    expect(
      screen.getByText('AI-proposed, human-approved for this contract'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Approved organizational language')).not.toBeInTheDocument()
  })
})

describe('human decision controls — EDIT', () => {
  it('requires human language for REPLACE and submits it without a drafting evidence id', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    expect(await screen.findByText(/Enter the final approved language/)).toBeInTheDocument()
    expect(api.requests.filter((request) => request.method === 'PUT')).toHaveLength(0)
    await user.type(
      screen.getByLabelText(/Final approved language/),
      'Human-authored liability cap text.',
    )
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.action).toBe('EDIT')
    expect(body.approvedAction).toBe('REPLACE')
    expect(body.approvedLanguage).toBe('Human-authored liability cap text.')
    expect(body).not.toHaveProperty('approvedDraftingEvidenceId')
  })

  it('copies the machine proposal only on explicit request', async () => {
    const user = userEvent.setup()
    renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    expect(screen.getByLabelText(/Final approved language/)).toHaveValue('')
    await user.click(screen.getByRole('button', { name: 'Copy machine-proposed wording' }))
    expect(screen.getByLabelText(/Final approved language/)).toHaveValue(
      'Liability is capped at two times the fees paid.',
    )
  })

  it('offers DELETE without a language field for clause findings', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    await user.click(screen.getByRole('radio', { name: 'Delete clause' }))
    expect(screen.queryByLabelText('Final approved language')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.approvedAction).toBe('DELETE')
    expect(body).not.toHaveProperty('approvedLanguage')
  })

  it('offers INSERT for absence findings', async () => {
    const user = userEvent.setup()
    const { api } = renderControls({ finding: fixtureAbsenceFindingForDecision })
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    expect(screen.getByRole('radio', { name: 'Insert provision' })).toBeChecked()
    await user.type(screen.getByLabelText(/Final approved language/), 'Inserted text.')
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.approvedAction).toBe('INSERT')
    expect(body.approvedLanguage).toBe('Inserted text.')
  })
})

describe('human decision controls — REJECT and ESCALATE', () => {
  it('requires a rationale and explains that rejection creates no change', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Reject recommendation' }))
    expect(
      screen.getByText('No contract change will be created from this finding.'),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Reject recommendation' }))
    expect(await screen.findByText(/comment is required/)).toBeInTheDocument()
    expect(api.requests.filter((request) => request.method === 'PUT')).toHaveLength(0)
    await user.type(screen.getByLabelText(/Comment/), 'Not acceptable for this deal.')
    await user.click(screen.getByRole('button', { name: 'Reject recommendation' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.action).toBe('REJECT')
    expect(body.comment).toBe('Not acceptable for this deal.')
  })

  it('requires a rationale for escalation and states that it blocks finalization', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Escalate for legal review' }))
    expect(
      screen.getByText(/remains unresolved and the review cannot be finalized/),
    ).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Comment/), 'Needs specialist review.')
    await user.click(screen.getByRole('button', { name: 'Escalate for legal review' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.action).toBe('ESCALATE')
    expect(body.comment).toBe('Needs specialist review.')
  })
})

describe('human decision controls — current decision and revision', () => {
  it('shows the current decision and revises it with the server revision as expectedVersion', async () => {
    const user = userEvent.setup()
    const current = fixtureDecision({ revision: 3, action: 'REJECT', comment: 'Earlier reason.' })
    const { api } = renderControls({ currentDecision: current })
    expect(screen.getByText('Rejected')).toBeInTheDocument()
    expect(screen.getByText(/Revision 3/)).toBeInTheDocument()
    expect(screen.getByText('Earlier reason.')).toBeInTheDocument()
    expect(screen.getByText('Signed-in reviewer')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Change decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    await user.type(screen.getByLabelText(/Final approved language/), 'Revised human text.')
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.expectedVersion).toBe(3)
    expect(body.action).toBe('EDIT')
  })

  it('does not retry a conflicting submission and shows a safe conflict notice', async () => {
    const user = userEvent.setup()
    const { api } = renderControls({
      currentDecision: fixtureDecision({ revision: 2 }),
      api: {
        decisionResponse: () =>
          new Response(
            JSON.stringify({
              code: '409',
              title: 'Invalid state',
              detail: 'Finding f-1 decision expected version 2 but the current revision is 3',
            }),
            { status: 409, headers: { 'Content-Type': 'application/json' } },
          ),
      },
    })
    await user.click(screen.getByRole('button', { name: 'Change decision' }))
    await user.click(screen.getByRole('radio', { name: 'Reject recommendation' }))
    await user.type(screen.getByLabelText(/Comment/), 'Second opinion.')
    await user.click(screen.getByRole('button', { name: 'Reject recommendation' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/changed elsewhere|not saved/)
    expect(screen.queryByText(/expected version/)).not.toBeInTheDocument()
    expect(api.requests.filter((request) => request.method === 'PUT')).toHaveLength(1)
  })

  it('locks decision controls after finalization while keeping the decision visible', () => {
    renderControls({
      currentDecision: fixtureDecision(),
      isFinalized: true,
      humanReview: fixtureHumanReview({
        status: 'COMPLETED',
        readyToFinalize: true,
        blockers: [],
        changeSetId: 'cs-1',
        finalizedAt: '2026-09-26T11:00:00Z',
      }),
    })
    expect(screen.getByText('Accepted')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Change decision' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record decision' })).not.toBeInTheDocument()
    expect(screen.getByText(/finalized/i)).toBeInTheDocument()
  })
})

describe('decision surface honesty', () => {
  it('discloses a truncated decision set instead of implying no decision exists', () => {
    render(
      <HumanDecisionControls
        reviewId="r-1"
        finding={fixtureAcceptableFindingDetail}
        currentDecision={null}
        humanReview={fixtureHumanReview()}
        isFinalized={false}
        canDecide
        analysisComplete
        decisionsLoaded
        decisionsTruncated
      />,
      { wrapper: wrapperFor() },
    )
    expect(screen.getByText(/may\s+not be shown here/)).toBeInTheDocument()
  })
})

describe('human decision history', () => {
  it('loads revisions only when the disclosure is opened', async () => {
    const user = userEvent.setup()
    const { api } = renderControls({
      currentDecision: fixtureDecision({ revision: 2 }),
      api: {
        decisionHistory: {
          items: [
            fixtureDecision({ id: 'd-2', revision: 2, action: 'EDIT', comment: 'Second.' }),
            fixtureDecision({ id: 'd-1', revision: 1, action: 'ACCEPT', comment: null }),
          ],
          truncated: false,
        },
      },
    })
    expect(
      api.requests.filter((request) => request.url.includes('/decisions')),
    ).toHaveLength(0)
    await user.click(screen.getByText('Decision history'))
    const historyRequests = api.requests.filter((request) =>
      /\/findings\/f-1\/decisions/.test(request.url),
    )
    expect(historyRequests).toHaveLength(1)
    const list = await screen.findByRole('list', { name: 'Decision revisions' })
    const items = within(list).getAllByRole('listitem')
    expect(items[0]).toHaveTextContent('Revision 2')
    expect(items[0]).toHaveTextContent('Edited')
    expect(items[1]).toHaveTextContent('Revision 1')
    expect(items[1]).toHaveTextContent('Accepted')
  })

  it('refreshes an open history after a new revision is recorded', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    stub = stubContractApi({
      decisions: { items: [], truncated: false },
      humanReview: fixtureHumanReview(),
      decisionHistory: {
        items: [fixtureDecision({ id: 'd-1', revision: 1, action: 'ACCEPT' })],
        truncated: false,
      },
      decisionResponse: () => {
        stub.setDecisionHistory({
          items: [
            fixtureDecision({ id: 'd-2', revision: 2, action: 'REJECT' }),
            fixtureDecision({ id: 'd-1', revision: 1, action: 'ACCEPT' }),
          ],
          truncated: false,
        })
        return jsonResponse(
          envelope(fixtureDecision({ id: 'd-2', revision: 2, action: 'REJECT' })),
        )
      },
    })
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, retryDelay: 0 }, mutations: { retry: false } },
    })
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    )
    render(
      <HumanDecisionControls
        reviewId="r-1"
        finding={fixtureAcceptableFindingDetail}
        currentDecision={null}
        humanReview={fixtureHumanReview()}
        isFinalized={false}
        canDecide
        analysisComplete
        decisionsLoaded
        decisionsTruncated={false}
      />,
      { wrapper },
    )
    await user.click(screen.getByText('Decision history'))
    const list = await screen.findByRole('list', { name: 'Decision revisions' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Reject recommendation' }))
    await user.type(screen.getByLabelText(/Comment/), 'Reason.')
    await user.click(screen.getByRole('button', { name: 'Reject recommendation' }))
    await waitFor(() =>
      expect(within(list).getAllByRole('listitem')).toHaveLength(2),
    )
    expect(within(list).getAllByRole('listitem')[0]).toHaveTextContent('Revision 2')
  })

  it('submits DELETE even when a stale oversized language draft exists', async () => {
    const user = userEvent.setup()
    const { api } = renderControls()
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    const language = screen.getByLabelText(/Final approved language/)
    await user.click(language)
    await user.paste('x'.repeat(20001))
    await user.click(screen.getByRole('radio', { name: 'Delete clause' }))
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    const body = api.requests.find((request) => request.method === 'PUT')?.body as Record<
      string,
      unknown
    >
    expect(body.approvedAction).toBe('DELETE')
    expect(body).not.toHaveProperty('approvedLanguage')
  })

  it('reuses the idempotency key for an unchanged retry and regenerates it on a change', async () => {
    const user = userEvent.setup()
    const { api } = renderControls({
      api: {
        decisionResponse: () =>
          jsonResponse({ code: '500', title: 'Error', detail: 'x' }, 500),
      },
    })
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('button', { name: 'Accept recommendation' }))
    await screen.findByText(/unavailable right now|could not be saved/)
    await user.click(screen.getByRole('button', { name: 'Accept recommendation' }))
    await waitFor(() =>
      expect(api.requests.filter((request) => request.method === 'PUT')).toHaveLength(2),
    )
    const bodies = api.requests
      .filter((request) => request.method === 'PUT')
      .map((request) => (request.body as { clientRequestId?: string }).clientRequestId)
    expect(bodies[1]).toBe(bodies[0])
    await user.click(screen.getByRole('radio', { name: 'Edit change' }))
    await user.type(screen.getByLabelText(/Final approved language/), 'New text.')
    await user.click(screen.getByRole('button', { name: 'Edit change' }))
    await waitFor(() =>
      expect(api.requests.filter((request) => request.method === 'PUT')).toHaveLength(3),
    )
    const third = api.requests.filter((request) => request.method === 'PUT')[2]
    expect((third.body as { clientRequestId?: string }).clientRequestId).not.toBe(bodies[0])
  })

  it('goes read-only when the server denies permission', async () => {
    const user = userEvent.setup()
    const { api } = renderControls({
      api: {
        decisionResponse: () =>
          new Response(
            JSON.stringify({
              code: '403',
              title: 'Forbidden',
              detail: 'Not authorized for this operation.',
            }),
            { status: 403, headers: { 'Content-Type': 'application/json' } },
          ),
      },
    })
    await user.click(screen.getByRole('button', { name: 'Record decision' }))
    await user.click(screen.getByRole('button', { name: 'Accept recommendation' }))
    expect(await screen.findByText(/do not have permission/)).toBeInTheDocument()
    expect(screen.getByText('Decision controls are read-only for this account.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Accept recommendation' })).not.toBeInTheDocument()
    expect(api.requests.filter((request) => request.method === 'PUT')).toHaveLength(1)
  })

  it('offers a retry when history fails that refetches', async () => {
    const user = userEvent.setup()
    const { api } = renderControls({
      currentDecision: fixtureDecision(),
      api: { decisionHistoryStatus: 500 },
    })
    await user.click(screen.getByText('Decision history'))
    expect(
      await screen.findByText(/Decision history could not be loaded/),
    ).toBeInTheDocument()
    const beforeRetry = api.requests.filter((request) =>
      /\/findings\/f-1\/decisions/.test(request.url),
    ).length
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() =>
      expect(
        api.requests.filter((request) => /\/findings\/f-1\/decisions/.test(request.url))
          .length,
      ).toBeGreaterThan(beforeRetry),
    )
  })
})
