import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { stubContractApi, type StubOptions } from '../contract/testFixtures'
import type { FindingDetail, FindingSummary } from '../../api/reviews'
import type { HumanDecision, HumanReview } from '../../api/workflow'
import { fixtureCompletedReviewSummary } from './testFixtures'
import {
  fixtureAcceptableFindingDetail,
  fixtureAbsenceFindingForDecision,
  fixtureChangeSet,
  fixtureDecision,
  fixtureGeneratedFindingDetail,
  fixtureHumanReview,
} from './workflowFixtures'

// Decisions are recorded in the review workspace. These tests drive the real page (the decide bar,
// DecisionComposer, useDecisionFlow and the History sheet) against the stubbed API and assert on what
// the API receives and on what the page claims about it.

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-26T10:00:00Z' }
}

function summaryOf(detail: FindingDetail): FindingSummary {
  return {
    id: detail.id,
    subjectKind: detail.subjectKind,
    subjectHeadingNodeId: detail.subjectHeadingNodeId,
    absenceConcept: detail.absenceConcept,
    concept: detail.concept,
    secondaryConcept: detail.secondaryConcept,
    issueFamily: detail.issueFamily,
    observationKind: detail.observationKind,
    severity: detail.severity,
    reviewClassification: detail.reviewClassification,
    proposedAction: detail.proposedAction,
    proposedLanguageSource: detail.proposedLanguageSource,
    relatedPosition: detail.relatedPosition,
    partlyWithinFallback: detail.partlyWithinFallback,
    decidedBy: detail.decidedBy,
    addedBy: detail.addedBy,
    firstReviewCategory: detail.firstReviewCategory,
    positionLabel: detail.positionLabel,
    createdAt: detail.createdAt,
  }
}

interface Overrides {
  finding?: FindingDetail
  currentDecision?: HumanDecision | null
  humanReview?: HumanReview
  api?: StubOptions
}

async function renderWorkspace(overrides: Overrides = {}) {
  const finding = overrides.finding ?? fixtureAcceptableFindingDetail
  const api = stubContractApi({
    reviewSummary: fixtureCompletedReviewSummary,
    findings: { items: [summaryOf(finding)], truncated: false },
    findingDetailResponse: () => jsonResponse(envelope(finding)),
    decisions: { items: overrides.currentDecision ? [overrides.currentDecision] : [], truncated: false },
    humanReview: overrides.humanReview ?? fixtureHumanReview(),
    ...overrides.api,
  })
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [`/reviews/c1/analysis/r-1?finding=${finding.id}`],
  })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  // The detail pane is replaced while the finding loads, so queries are scoped only once it is stable.
  await screen.findByRole('complementary', { name: 'Finding detail' })
  await waitFor(() => expect(detailPane().getByRole('heading', { level: 2 })).toBeInTheDocument())
  return { api }
}

function detailPane() {
  return within(screen.getByRole('complementary', { name: 'Finding detail' }))
}

/** Waits until the decide bar is live: analysis complete, human review and decisions loaded. */
async function decideBar(name: 'Accept' | 'Edit' | 'Keep as drafted' | 'Dismiss' | 'Escalate' | 'Resolve' = 'Accept') {
  return detailPane().findByRole('button', { name })
}

function puts(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter((request) => request.method === 'PUT')
}

function putBody(api: ReturnType<typeof stubContractApi>, index = 0) {
  return puts(api)[index]?.body as Record<string, unknown>
}

function historyRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter((request) => /\/findings\/f-1\/decisions/.test(request.url))
}

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('workspace decisions — ACCEPT', () => {
  it('records an executable recommendation with the current version and no extra fields', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Accept'))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('ACCEPT')
    expect(body.expectedVersion).toBe(0)
    expect(body.clientRequestId).toMatch(UUID_V4)
    expect(body).not.toHaveProperty('approvedAction')
    expect(body).not.toHaveProperty('approvedLanguage')
    // Nothing is shown as saved until the server's decision is in: the box appears from its response.
    expect(await detailPane().findByText('Accepted')).toBeInTheDocument()
    expect(detailPane().getByText(/Decision 1/)).toBeInTheDocument()
    expect(detailPane().getByRole('button', { name: 'Accept' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('offers the honest actions without Accept when the recommendation is not executable', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({ finding: fixtureAbsenceFindingForDecision })
    expect(await decideBar('Keep as drafted')).toBeEnabled()
    expect(detailPane().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(detailPane().queryByText(/Accept unavailable/)).not.toBeInTheDocument()
    expect(detailPane().getByRole('button', { name: 'Edit' })).toBeEnabled()
    expect(detailPane().getByRole('button', { name: 'Dismiss' })).toBeEnabled()
    expect(detailPane().getByRole('button', { name: 'Escalate' })).toBeEnabled()
    expect(puts(api)).toHaveLength(0)
    await user.click(detailPane().getByRole('button', { name: 'Edit' }))
    expect(screen.getByRole('form', { name: 'Edit decision' })).toBeInTheDocument()
  })

  it('refuses Accept when the action is not executable even if language exists', async () => {
    const { api } = await renderWorkspace({
      finding: {
        ...fixtureAcceptableFindingDetail,
        proposedAction: 'ADD_REQUIRED_PROVISION',
        proposedLanguage: 'Some language that must not be acceptable as-is.',
      },
    })
    await decideBar('Keep as drafted')
    expect(detailPane().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(puts(api)).toHaveLength(0)
  })

  it('labels generative wording honestly and never as approved organizational language', async () => {
    await renderWorkspace({ finding: fixtureGeneratedFindingDetail })
    await decideBar('Accept')
    expect(detailPane().getByText('Proposed by Rivell')).toBeInTheDocument()
    expect(detailPane().getByText(/not approved organizational language/)).toBeInTheDocument()
    expect(detailPane().queryByText('Approved organizational drafting')).not.toBeInTheDocument()
  })
})

describe('workspace decisions — EDIT', () => {
  it('submits human language byte-exact without trimming', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    const language = form.getByRole('textbox', { name: 'Approved language' })
    // The composer puts the cursor in the language field.
    expect(language).toHaveFocus()
    await user.clear(language)
    await user.click(language)
    await user.paste('  Padded clause text.\n\nSecond paragraph.  ')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    expect(putBody(api).approvedLanguage).toBe('  Padded clause text.\n\nSecond paragraph.  ')
    // The composer closes once the server has recorded the decision.
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Edit decision' })).not.toBeInTheDocument())
    expect(await detailPane().findByText('Edited')).toBeInTheDocument()
  })

  it('requires human language for REPLACE and submits it without a drafting evidence id', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    await user.clear(form.getByRole('textbox', { name: 'Approved language' }))
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    expect(await form.findByText(/Enter the final approved language/)).toBeInTheDocument()
    expect(form.getByRole('textbox', { name: 'Approved language' })).toHaveAttribute('aria-invalid', 'true')
    expect(puts(api)).toHaveLength(0)
    await user.type(form.getByRole('textbox', { name: 'Approved language' }), 'Human-authored liability cap text.')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('EDIT')
    expect(body.approvedAction).toBe('REPLACE')
    expect(body.approvedLanguage).toBe('Human-authored liability cap text.')
    expect(body.expectedVersion).toBe(0)
    expect(body).not.toHaveProperty('approvedDraftingEvidenceId')
    expect(body).not.toHaveProperty('comment')
  })

  it('prefills the replacement with the clause as received and copies the machine proposal only on request', async () => {
    const user = userEvent.setup()
    await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    const language = form.getByRole('textbox', { name: 'Approved language' })
    expect((language as HTMLTextAreaElement).value).toContain('6.3 The exclusions in Section 6.2 do not apply to breaches of Section 8.')
    expect((language as HTMLTextAreaElement).value).not.toContain('6. Limitation of Liability')
    await user.click(form.getByRole('button', { name: 'Use proposed language' }))
    expect(language).toHaveValue('Liability is capped at two times the fees paid.')
  })

  it('refuses saving replacement text that has not been changed', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    expect(await form.findByText('Change the wording before you save.')).toBeInTheDocument()
    expect(puts(api)).toHaveLength(0)
  })

  it('offers DELETE without a language field for clause findings', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    expect(form.getByRole('option', { name: 'Replace clause' })).toBeInTheDocument()
    await user.selectOptions(form.getByRole('combobox', { name: 'Change type' }), 'DELETE')
    expect(form.queryByRole('textbox', { name: 'Approved language' })).not.toBeInTheDocument()
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('EDIT')
    expect(body.approvedAction).toBe('DELETE')
    expect(body).not.toHaveProperty('approvedLanguage')
  })

  it('offers only INSERT for absence findings', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({ finding: fixtureAbsenceFindingForDecision })
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    // A missing provision has nothing to replace or delete, so the change type is fixed.
    expect(form.queryByRole('combobox', { name: 'Change type' })).not.toBeInTheDocument()
    expect(form.getByText('INSERT')).toBeInTheDocument()
    await user.type(form.getByRole('textbox', { name: 'Approved language' }), 'Inserted text.')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.approvedAction).toBe('INSERT')
    expect(body.approvedLanguage).toBe('Inserted text.')
  })

  it('sends an optional note only when one is written', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    await user.clear(form.getByRole('textbox', { name: 'Approved language' }))
    await user.type(form.getByRole('textbox', { name: 'Approved language' }), 'Edited text.')
    await user.type(form.getByRole('textbox', { name: 'Note (optional)' }), 'Agreed with counsel.')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    expect(putBody(api).comment).toBe('Agreed with counsel.')
  })

  it('submits DELETE even when a stale oversized language draft exists', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Edit'))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    await user.click(form.getByRole('textbox', { name: 'Approved language' }))
    await user.paste('x'.repeat(20001))
    await user.selectOptions(form.getByRole('combobox', { name: 'Change type' }), 'DELETE')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.approvedAction).toBe('DELETE')
    expect(body).not.toHaveProperty('approvedLanguage')
  })
})

describe('workspace decisions — KEEP, DISMISS and ESCALATE', () => {
  it('keeps the clause as drafted without creating a change', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Keep as drafted'))
    const form = within(screen.getByRole('form', { name: 'Keep as drafted' }))
    expect(form.getByText('The clause stays as written. No approved change will be created.')).toBeInTheDocument()
    expect(form.getByRole('textbox', { name: 'Note (optional)' })).toHaveFocus()
    await user.click(form.getByRole('button', { name: 'Keep as drafted' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('REJECT')
    expect(body.rejectReason).toBe('KEEP_AS_DRAFTED')
    expect(body.expectedVersion).toBe(0)
    expect(body).not.toHaveProperty('approvedAction')
    expect(body).not.toHaveProperty('approvedLanguage')
    expect(body).not.toHaveProperty('comment')
  })

  it('dismisses a finding with its reason and explains that no change is created', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Dismiss'))
    const form = within(screen.getByRole('form', { name: 'Dismiss finding' }))
    expect(form.getByText('No approved change will be created from this finding.')).toBeInTheDocument()
    expect(form.getByRole('textbox', { name: 'Note (optional)' })).toHaveFocus()
    await user.click(form.getByRole('button', { name: 'Dismiss finding' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('REJECT')
    expect(body.rejectReason).toBe('FINDING_WRONG')
    expect(body.expectedVersion).toBe(0)
    expect(body).not.toHaveProperty('approvedAction')
    expect(body).not.toHaveProperty('approvedLanguage')
    expect(body).not.toHaveProperty('comment')
  })

  it('sends a note on a dismissed finding only when one is written', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Dismiss'))
    const form = within(screen.getByRole('form', { name: 'Dismiss finding' }))
    await user.type(form.getByRole('textbox', { name: 'Note (optional)' }), 'The clause is standard.')
    await user.click(form.getByRole('button', { name: 'Dismiss finding' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    expect(putBody(api).comment).toBe('The clause is standard.')
  })

  it('requires a rationale for escalation and states that it blocks finalization', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Escalate'))
    const form = within(screen.getByRole('form', { name: 'Escalate finding' }))
    expect(form.getByText(/will remain unresolved and block finalization/)).toBeInTheDocument()
    await user.click(form.getByRole('button', { name: 'Escalate' }))
    expect(await form.findByText(/comment is required/)).toBeInTheDocument()
    expect(puts(api)).toHaveLength(0)
    await user.type(form.getByRole('textbox', { name: 'Reason' }), 'Needs specialist review.')
    await user.click(form.getByRole('button', { name: 'Escalate' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('ESCALATE')
    expect(body.comment).toBe('Needs specialist review.')
  })

  it('resolves an escalation with the approver name and shows it', async () => {
    const user = userEvent.setup()
    const current = fixtureDecision({
      action: 'ESCALATE',
      approvedAction: null,
      approvedLanguage: null,
      languageSource: null,
      approvedDraftingEvidenceId: null,
      comment: 'Needs GC review.',
    })
    const { api } = await renderWorkspace({ currentDecision: current })
    expect(await detailPane().findByText('Escalated · unresolved')).toBeInTheDocument()
    await user.click(await decideBar('Resolve'))
    const form = within(screen.getByRole('form', { name: 'Resolve escalation' }))
    await user.click(form.getByRole('button', { name: 'Resolve' }))
    expect(await form.findByText('Add the name of the person who approved this clause.')).toBeInTheDocument()
    expect(puts(api)).toHaveLength(0)
    await user.type(form.getByRole('textbox', { name: 'Approved by' }), 'Jane Doe')
    await user.type(form.getByRole('textbox', { name: 'Note (optional)' }), 'Approved by the GC.')
    await user.click(form.getByRole('button', { name: 'Resolve' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.action).toBe('RESOLVE')
    expect(body.approvedBy).toBe('Jane Doe')
    expect(body.comment).toBe('Approved by the GC.')
    expect(await detailPane().findByText('Approved by Jane Doe')).toBeInTheDocument()
  })

  it('cancels a composer without sending anything', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace()
    await user.click(await decideBar('Dismiss'))
    const form = within(screen.getByRole('form', { name: 'Dismiss finding' }))
    await user.type(form.getByRole('textbox', { name: 'Note (optional)' }), 'Half-written.')
    await user.click(form.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('form', { name: 'Dismiss finding' })).not.toBeInTheDocument()
    expect(puts(api)).toHaveLength(0)
  })
})

describe('workspace decisions — current decision and revision', () => {
  it('shows the current decision and revises it with the server revision as expectedVersion', async () => {
    const user = userEvent.setup()
    const current = fixtureDecision({ revision: 3, action: 'REJECT', comment: 'Earlier reason.' })
    const { api } = await renderWorkspace({ currentDecision: current })
    expect(await detailPane().findByText('Rejected')).toBeInTheDocument()
    expect(detailPane().getByText(/Decision 3/)).toBeInTheDocument()
    expect(detailPane().getByText('Earlier reason.')).toBeInTheDocument()
    expect(detailPane().getByRole('button', { name: 'Dismiss' })).toHaveAttribute('aria-pressed', 'false')
    await user.click(detailPane().getByRole('button', { name: 'Change decision' }))
    await user.click(detailPane().getByRole('button', { name: 'Edit' }))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    await user.clear(form.getByRole('textbox', { name: 'Approved language' }))
    await user.type(form.getByRole('textbox', { name: 'Approved language' }), 'Revised human text.')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    const body = putBody(api)
    expect(body.expectedVersion).toBe(3)
    expect(body.action).toBe('EDIT')
    expect(body.approvedLanguage).toBe('Revised human text.')
    expect(await detailPane().findByText(/Decision 4/)).toBeInTheDocument()
  })

  it('shows a kept clause by its honest label', async () => {
    const kept = fixtureDecision({
      action: 'REJECT',
      rejectReason: 'KEEP_AS_DRAFTED',
      comment: null,
    })
    await renderWorkspace({ currentDecision: kept })
    expect(await detailPane().findByText('Kept as drafted')).toBeInTheDocument()
    expect(detailPane().getByRole('button', { name: 'Keep as drafted' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('shows a dismissed finding by its honest label', async () => {
    const dismissed = fixtureDecision({
      action: 'REJECT',
      rejectReason: 'FINDING_WRONG',
      comment: null,
    })
    await renderWorkspace({ currentDecision: dismissed })
    expect((await detailPane().findAllByText('Dismissed')).length).toBeGreaterThan(0)
    expect(detailPane().getByRole('button', { name: 'Dismiss' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('does not retry a conflicting submission and shows a safe conflict notice', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({
      currentDecision: fixtureDecision({ revision: 2 }),
      api: {
        decisionResponse: () =>
          jsonResponse(
            {
              code: '409',
              title: 'Invalid state',
              detail: 'Finding f-1 decision expected version 2 but the current revision is 3',
            },
            409,
          ),
      },
    })
    await user.click(await decideBar('Dismiss'))
    const form = within(screen.getByRole('form', { name: 'Dismiss finding' }))
    await user.click(form.getByRole('button', { name: 'Dismiss finding' }))
    expect(await form.findByRole('alert')).toHaveTextContent(/changed elsewhere|not saved/)
    expect(screen.queryByText(/expected version/)).not.toBeInTheDocument()
    expect(puts(api)).toHaveLength(1)
    expect(putBody(api).expectedVersion).toBe(2)
  })

  it('explains a stale revision from the server’s current decision, not from the error text', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    const { api } = await renderWorkspace({
      currentDecision: fixtureDecision({ revision: 2 }),
      api: {
        decisionResponse: () => {
          // Someone else recorded revision 3 first; the refused write makes the page re-read it.
          stub.setDecisions({
            items: [fixtureDecision({ id: 'd-3', revision: 3, action: 'ESCALATE', comment: 'Theirs.' })],
            truncated: false,
          })
          return jsonResponse({ code: '409', title: 'Invalid state', detail: 'revision 3' }, 409)
        },
      },
    })
    stub = api
    await user.click(await decideBar('Dismiss'))
    const form = within(screen.getByRole('form', { name: 'Dismiss finding' }))
    await user.click(form.getByRole('button', { name: 'Dismiss finding' }))
    expect(await form.findByRole('alert')).toHaveTextContent(/changed elsewhere/)
    await waitFor(() => expect(detailPane().getByText(/Decision 3/)).toBeInTheDocument())
    expect(puts(api)).toHaveLength(1)
    expect(putBody(api).expectedVersion).toBe(2)
  })

  it('locks decision controls after finalization while keeping the decision visible', async () => {
    await renderWorkspace({
      currentDecision: fixtureDecision(),
      humanReview: fixtureHumanReview({
        status: 'COMPLETED',
        readyToFinalize: true,
        blockers: [],
        changeSetId: 'cs-1',
        finalizedAt: '2026-09-26T11:00:00Z',
      }),
      api: { changeSet: fixtureChangeSet() },
    })
    expect(await detailPane().findByText('Decision final: Accepted')).toBeInTheDocument()
    expect(detailPane().getByText('Accepted')).toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Keep as drafted' })).not.toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Dismiss' })).not.toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Resolve' })).not.toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Escalate' })).not.toBeInTheDocument()
    expect(detailPane().getByText('Decision final: Accepted')).toBeInTheDocument()
    expect(detailPane().queryByText(/review is finalized/i)).not.toBeInTheDocument()
  })

  it('says why decisions are unavailable until the review workflow state is available', async () => {
    await renderWorkspace({ api: { humanReviewStatus: 500 } })
    expect(
      await detailPane().findByText('Decisions are unavailable right now.', undefined, {
        timeout: 4000,
      }),
    ).toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
  })
})

describe('decision surface honesty', () => {
  it('discloses a truncated decision set instead of implying no decision exists', async () => {
    await renderWorkspace({ api: { decisions: { items: [], truncated: true } } })
    await decideBar('Accept')
    expect(detailPane().getByText(/Earlier decisions may not be shown/)).toBeInTheDocument()
  })
})

describe('workspace decisions — request identity and permission', () => {
  it('reuses the idempotency key for an unchanged retry and regenerates it on a change', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({
      api: {
        decisionResponse: () => jsonResponse({ code: '500', title: 'Error', detail: 'x' }, 500),
      },
    })
    await user.click(await decideBar('Accept'))
    expect(await detailPane().findByText('The review workflow is unavailable right now.')).toBeInTheDocument()
    await user.click(detailPane().getByRole('button', { name: 'Accept' }))
    await waitFor(() => expect(puts(api)).toHaveLength(2))
    const ids = puts(api).map((request) => (request.body as { clientRequestId?: string }).clientRequestId)
    expect(ids[1]).toBe(ids[0])
    await user.click(detailPane().getByRole('button', { name: 'Edit' }))
    const form = within(screen.getByRole('form', { name: 'Edit decision' }))
    await user.clear(form.getByRole('textbox', { name: 'Approved language' }))
    await user.type(form.getByRole('textbox', { name: 'Approved language' }), 'New text.')
    await user.click(form.getByRole('button', { name: 'Save decision' }))
    await waitFor(() => expect(puts(api)).toHaveLength(3))
    const third = putBody(api, 2)
    expect(third.clientRequestId).toMatch(UUID_V4)
    expect(third.clientRequestId).not.toBe(ids[0])
  })

  it('goes read-only when the server denies permission', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({
      api: {
        decisionResponse: () =>
          jsonResponse({ code: '403', title: 'Forbidden', detail: 'Not authorized for this operation.' }, 403),
      },
    })
    await user.click(await decideBar('Accept'))
    expect(await detailPane().findByText(/do not have permission/)).toBeInTheDocument()
    expect(detailPane().getByText('Decision controls are read-only for this account.')).toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: 'Accept' })).not.toBeInTheDocument()
    expect(puts(api)).toHaveLength(1)
  })
})

describe('workspace decision history', () => {
  it('shows no history entry for a first revision', async () => {
    const { api } = await renderWorkspace({ currentDecision: fixtureDecision({ revision: 1 }) })
    expect(await detailPane().findByText(/Decision 1/)).toBeInTheDocument()
    expect(detailPane().queryByRole('button', { name: /History/ })).not.toBeInTheDocument()
    expect(historyRequests(api)).toHaveLength(0)
  })

  it('loads revisions only when the History sheet is opened', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({
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
    const open = await detailPane().findByRole('button', { name: 'History (2)' })
    expect(historyRequests(api)).toHaveLength(0)
    await user.click(open)
    const sheet = within(screen.getByRole('dialog', { name: 'History' }))
    const second = await sheet.findByText(/Decision 2/)
    const first = sheet.getByText(/Decision 1/)
    expect(historyRequests(api)).toHaveLength(1)
    expect(second).toHaveTextContent('Edited')
    expect(first).toHaveTextContent('Accepted')
    expect(sheet.getByText('Second.')).toBeInTheDocument()
    // Newest first.
    expect(second.compareDocumentPosition(first) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows the new revision when History is opened again after a decision is recorded', async () => {
    const user = userEvent.setup()
    let stub: ReturnType<typeof stubContractApi>
    const { api } = await renderWorkspace({
      currentDecision: fixtureDecision({ id: 'd-2', revision: 2, action: 'EDIT' }),
      api: {
        decisionHistory: {
          items: [
            fixtureDecision({ id: 'd-2', revision: 2, action: 'EDIT' }),
            fixtureDecision({ id: 'd-1', revision: 1, action: 'ACCEPT' }),
          ],
          truncated: false,
        },
        decisionResponse: (body) => {
          const request = body as { clientRequestId?: string }
          const recorded = fixtureDecision({
            id: 'd-3',
            revision: 3,
            action: 'REJECT',
            rejectReason: 'FINDING_WRONG',
            approvedAction: null,
            approvedLanguage: null,
            languageSource: null,
            comment: 'Reason.',
            clientRequestId: request.clientRequestId ?? null,
          })
          stub.setDecisionHistory({
            items: [
              recorded,
              fixtureDecision({ id: 'd-2', revision: 2, action: 'EDIT' }),
              fixtureDecision({ id: 'd-1', revision: 1, action: 'ACCEPT' }),
            ],
            truncated: false,
          })
          return jsonResponse(envelope(recorded))
        },
      },
    })
    stub = api
    await user.click(await detailPane().findByRole('button', { name: 'History (2)' }))
    const first = within(screen.getByRole('dialog', { name: 'History' }))
    await first.findByText(/Decision 2/)
    expect(first.queryByText(/Decision 3/)).not.toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'History' })).not.toBeInTheDocument())

    await user.click(detailPane().getByRole('button', { name: 'Dismiss' }))
    const form = within(screen.getByRole('form', { name: 'Dismiss finding' }))
    await user.type(form.getByRole('textbox', { name: 'Note (optional)' }), 'Reason.')
    await user.click(form.getByRole('button', { name: 'Dismiss finding' }))
    await waitFor(() => expect(puts(api)).toHaveLength(1))
    expect(putBody(api).expectedVersion).toBe(2)

    await user.click(await detailPane().findByRole('button', { name: 'History (3)' }))
    const reopened = within(screen.getByRole('dialog', { name: 'History' }))
    expect(await reopened.findByText(/Decision 3/)).toHaveTextContent('Dismissed')
    expect(reopened.getByText(/Decision 1/)).toBeInTheDocument()
  })

  it('offers a retry when history fails that refetches', async () => {
    const user = userEvent.setup()
    const { api } = await renderWorkspace({
      currentDecision: fixtureDecision({ revision: 2 }),
      api: { decisionHistoryStatus: 500 },
    })
    await user.click(await detailPane().findByRole('button', { name: 'History (2)' }))
    const sheet = within(screen.getByRole('dialog', { name: 'History' }))
    expect(
      await sheet.findByText(/Decision history could not be loaded/, undefined, { timeout: 4000 }),
    ).toBeInTheDocument()
    const beforeRetry = historyRequests(api).length
    await user.click(sheet.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(historyRequests(api).length).toBeGreaterThan(beforeRetry))
  })
})
