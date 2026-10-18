import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { stubContractApi, type StubOptions } from '../components/contract/testFixtures'
import {
  fixtureAnalyzingReviewSummary,
  fixtureCompletedReviewSummary,
  fixtureFailedReviewSummary,
  fixtureFindingList,
  fixtureQueuedReviewSummary,
} from '../components/review/testFixtures'
import {
  fixtureFailedRedlineExecution,
  fixtureRedlineChange,
  fixtureRedlineExecution,
} from '../components/review/redlineFixtures'
import {
  fixtureChangeSet,
  fixtureDecision,
  fixtureHumanReview,
  fixtureMixedChangeSet,
} from '../components/review/workflowFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const envelope = (data: unknown) => ({ data, status: 200, timestamp: '2026-09-26T14:00:00Z' })

function renderAt(path: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return router
}

const finalizedReview = () =>
  fixtureHumanReview({
    status: 'COMPLETED',
    readyToFinalize: false,
    blockers: [],
    actionableFindings: 1,
    resolvedFindings: 1,
    remainingFindings: 0,
    accepted: 1,
    changeSetId: 'cs-1',
    finalizedBy: 'u-1',
    finalizedAt: '2026-09-26T11:00:00Z',
  })

function finalizedOptions(options: StubOptions = {}): StubOptions {
  return {
    reviewSummary: fixtureCompletedReviewSummary,
    findings: fixtureFindingList,
    humanReview: finalizedReview(),
    changeSet: fixtureChangeSet(),
    decisions: { items: [fixtureDecision({ action: 'EDIT', revision: 2 })], truncated: false },
    redline: null,
    ...options,
  }
}

const PATH = '/reviews/c1/overview/r-1'

function calls(api: ReturnType<typeof stubContractApi>, fragment: string, method?: string) {
  return api.requests.filter((request) => request.url.includes(fragment) && (!method || request.method === method))
}

beforeEach(() => {
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, writable: true, value: vi.fn(() => 'blob:redline') })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: vi.fn() })
  // jsdom cannot navigate to a blob URL; the click that saves the file is the part under test, not the navigation.
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('analysis states', () => {
  it('queued: says analysis has not started, with no progress claim and no human-review reads', async () => {
    const api = stubContractApi({ reviewSummary: fixtureQueuedReviewSummary })
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Waiting for analysis to start' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('you can leave this page')
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Review Workspace/ })).not.toBeInTheDocument()
    expect(calls(api, '/human-review')).toHaveLength(0)
    expect(calls(api, '/findings')).toHaveLength(0)
    expect(calls(api, '/redline')).toHaveLength(0)
    expect(calls(api, '/semantics')).toHaveLength(0)
  })

  it('analyzing is a different state from queued, and shows only real check counts', async () => {
    stubContractApi({ reviewSummary: fixtureAnalyzingReviewSummary })
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Analyzing the contract' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Waiting for analysis to start' })).not.toBeInTheDocument()
    const counts = screen.getByLabelText('Analysis check counts')
    expect(counts).toHaveTextContent('Planned checks 3')
    expect(counts).toHaveTextContent('Completed 1')
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
    expect(screen.getByText('Analyzing', { selector: '.rv-stb' })).toBeInTheDocument()
  })

  it('failed: says so safely, offers no Retry Analysis, and counts as failure in the journey', async () => {
    stubContractApi({ reviewSummary: fixtureFailedReviewSummary })
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Analysis could not be completed' })).toBeInTheDocument()
    expect(screen.getByText('Analysis failed', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to reviews' })).toHaveAttribute('href', '/reviews')
    expect(screen.getByRole('link', { name: 'Start another review' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/ANALYSIS_INTERNAL_ERROR/)).not.toBeInTheDocument()
    expect(screen.getByLabelText('Review progress')).toHaveTextContent('Failed')
  })
})

describe('human-review states lead to the Workspace and decide nothing', () => {
  function completed(humanReview: unknown, extra: StubOptions = {}) {
    return stubContractApi({ reviewSummary: fixtureCompletedReviewSummary, findings: fixtureFindingList, humanReview, ...extra })
  }

  it('ready for review: orients the reviewer and opens the real Workspace route', async () => {
    const api = completed(fixtureHumanReview({ status: 'NOT_STARTED' }))
    const router = renderAt(PATH)
    expect(await screen.findByRole('heading', { name: '2 Findings are ready for human review' })).toBeInTheDocument()
    expect(screen.getByText('Ready for review', { selector: '.rv-stb' })).toBeInTheDocument()
    const link = screen.getAllByRole('link', { name: 'Open Review Workspace' })[0]
    expect(link).toHaveAttribute('href', '/reviews/c1/analysis/r-1')
    expect(screen.getByLabelText('Findings by classification')).toBeInTheDocument()
    // Rivell's own proposals are orientation only, and the kinds are not interchangeable.
    const proposals = screen.getByLabelText('What Rivell proposes')
    expect(proposals).toHaveTextContent('1Replacement wording')
    expect(proposals).toHaveTextContent('1Missing provisions to draft')
    expect(screen.getByText(/proposes no wording for it; a person authors the clause/)).toBeInTheDocument()
    // Nothing on this page can decide a Finding.
    for (const name of ['Accept', 'Reject', 'Escalate', 'Edit']) {
      expect(screen.queryByRole('button', { name: new RegExp(`^${name}`) })).not.toBeInTheDocument()
    }
    expect(calls(api, '/decision', 'PUT')).toHaveLength(0)
    await userEvent.click(link)
    expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1')
  })

  it('shows no proposals when Rivell proposes nothing, instead of a zero row', async () => {
    completed(fixtureHumanReview({ status: 'NOT_STARTED' }), {
      reviewSummary: { ...fixtureCompletedReviewSummary, proposedChanges: { replace: 0, insert: 0, delete: 0 } },
    })
    renderAt(PATH)
    await screen.findByRole('heading', { name: '2 Findings are ready for human review' })
    expect(screen.queryByLabelText('What Rivell proposes')).not.toBeInTheDocument()
  })

  it('in review: shows the server decided count and a bar of real segments, not a percentage', async () => {
    completed(fixtureHumanReview({ actionableFindings: 8, resolvedFindings: 5, remainingFindings: 3 }))
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: '5 of 8 Findings decided' })).toBeInTheDocument()
    expect(screen.getByText('In review', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.getByRole('img', { name: '5 of 8 Findings decided' }).querySelectorAll('i')).toHaveLength(8)
    expect(screen.getByText(/Next unresolved: Limitation of liability/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Continue in Review Workspace' })).toHaveAttribute('href', '/reviews/c1/analysis/r-1')
    expect(screen.queryByText(/%/)).not.toBeInTheDocument()
  })

  it('escalation blocking: the escalation stays unresolved and blocking', async () => {
    completed(
      fixtureHumanReview({
        escalated: 1,
        resolvedFindings: 4,
        actionableFindings: 5,
        blockers: [{ code: 'ACTIVE_ESCALATION', message: 'x', findingIds: ['f-1'] }],
      }),
    )
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'An escalated Finding is still unresolved' })).toBeInTheDocument()
    expect(screen.getByText('Escalation blocking', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: 'Open Review Workspace' })[0]).toHaveAttribute('href', '/reviews/c1/analysis/r-1')
    expect(screen.queryByRole('heading', { name: 'Ready to finalize' })).not.toBeInTheDocument()
  })

  it('ready to finalize: shows the server readiness and sends Finalize to the Workspace', async () => {
    const api = completed(
      fixtureHumanReview({ readyToFinalize: true, blockers: [], resolvedFindings: 1, remainingFindings: 0, accepted: 1 }),
    )
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Ready to finalize' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Review Workspace to finalize' })).toHaveAttribute('href', '/reviews/c1/analysis/r-1')
    expect(screen.queryByRole('button', { name: /finalize/i })).not.toBeInTheDocument()
    expect(calls(api, '/finalize', 'POST')).toHaveLength(0)
    expect(screen.getByLabelText('Human decisions')).toHaveTextContent('Accepted')
  })

  it('does not decide readiness itself: every Finding decided but a blocker listed is still in review', async () => {
    completed(
      fixtureHumanReview({
        resolvedFindings: 1,
        remainingFindings: 0,
        readyToFinalize: false,
        blockers: [{ code: 'MISSING_APPROVED_LANGUAGE', message: 'x', findingIds: ['f-1'] }],
      }),
    )
    renderAt(PATH)
    expect(await screen.findByText('In review', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Ready to finalize' })).not.toBeInTheDocument()
  })

  it('degraded analysis offers no review', async () => {
    completed(
      fixtureHumanReview({
        status: 'NOT_STARTED',
        blockers: [{ code: 'ANALYSIS_DEGRADED', message: 'x', findingIds: [] }],
      }),
    )
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'The analysis did not produce a usable result' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Review Workspace/ })).not.toBeInTheDocument()
  })

  it('does not guess a state when the human-review read fails', async () => {
    stubContractApi({ reviewSummary: fixtureCompletedReviewSummary, findings: fixtureFindingList, humanReview: null, humanReviewStatus: 500 })
    renderAt(PATH)
    // The read is retried once before it is reported, so the failure takes a moment to appear.
    expect(await screen.findByText(/Human review state could not be loaded/, {}, { timeout: 4000 })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /Findings are ready/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Review Workspace/ })).not.toBeInTheDocument()
  })
})

describe('finalized: the frozen change set and the redline', () => {
  it('finalized is not a generated redline: it offers Generate redline, and does not call it done', async () => {
    const api = stubContractApi(finalizedOptions())
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Generate the redline' })).toBeInTheDocument()
    expect(screen.getByText('Finalized', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.getByLabelText('Review progress')).toHaveTextContent('Not generated')
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Review Workspace/ })).not.toBeInTheDocument()
    expect(calls(api, '/redline', 'POST')).toHaveLength(0)
  })

  it('zero approved changes: no Generate redline, and no artifact is implied', async () => {
    const api = stubContractApi(finalizedOptions({ changeSet: fixtureChangeSet({ changes: [] }) }))
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Review finalized with no approved changes' })).toBeInTheDocument()
    expect(screen.getByText('Review completed', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generate redline' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
    expect(calls(api, '/redline', 'POST')).toHaveLength(0)
  })

  it('generates once: the button locks while the request runs, and a verified result needs the server to say so', async () => {
    let release: (response: Response) => void = () => {}
    const api = stubContractApi(
      finalizedOptions({
        redlineTriggerResponse: () => new Promise<Response>((resolve) => (release = resolve)),
      }),
    )
    renderAt(PATH)
    const button = await screen.findByRole('button', { name: 'Generate redline' })
    await userEvent.click(button)
    expect(await screen.findByRole('heading', { name: 'Generating and verifying the redline' })).toBeInTheDocument()
    expect(screen.getByText('Generating redline', { selector: '.rv-stb' })).toBeInTheDocument()
    // The generate control is gone while the request runs, so a second submission is impossible.
    expect(screen.queryByRole('button', { name: 'Generate redline' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Verified redline' })).not.toBeInTheDocument()
    expect(calls(api, '/redline', 'POST')).toHaveLength(1)

    release(jsonResponse(envelope(fixtureRedlineExecution())))
    expect(await screen.findByRole('heading', { name: 'Verified redline' })).toBeInTheDocument()
    expect(calls(api, '/redline', 'POST')).toHaveLength(1)
  })

  it('verified redline: shows the real counts and downloads the real artifact', async () => {
    const api = stubContractApi(finalizedOptions({ redline: fixtureRedlineExecution() }))
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Verified redline' })).toBeInTheDocument()
    expect(screen.getByText('1 approved change')).toBeInTheDocument()
    expect(screen.getByText('1 applied')).toBeInTheDocument()
    expect(screen.getByText('1 verified')).toBeInTheDocument()
    expect(screen.getByText('acme-msa-v1-redline.docx')).toBeInTheDocument()
    expect(screen.getByText(/produced from msa\.docx v1/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Download tracked DOCX' }))
    await waitFor(() => expect(calls(api, '/redline/download')).toHaveLength(1))
    await waitFor(() => expect(URL.createObjectURL).toHaveBeenCalled())
  })

  it('verified redline: a download that fails is reported and leaves the result intact', async () => {
    stubContractApi(
      finalizedOptions({
        redline: fixtureRedlineExecution(),
        redlineDownloadResponse: () => jsonResponse({ code: '503', title: 'x', detail: 'x' }, 503),
      }),
    )
    renderAt(PATH)
    await userEvent.click(await screen.findByRole('button', { name: 'Download tracked DOCX' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be downloaded')
    expect(screen.getByRole('heading', { name: 'Verified redline' })).toBeInTheDocument()
  })

  it('fails closed when the downloaded bytes do not match the recorded artifact', async () => {
    stubContractApi(
      finalizedOptions({
        redline: fixtureRedlineExecution(),
        redlineDownloadResponse: () =>
          new Response(new Uint8Array(10), {
            status: 200,
            headers: {
              'Content-Type':
                'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            },
          }),
      }),
    )
    renderAt(PATH)
    await userEvent.click(await screen.findByRole('button', { name: 'Download tracked DOCX' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be downloaded')
    expect(URL.createObjectURL).not.toHaveBeenCalled()
  })

  it('failed redline: fails closed with no download, no retry, and no partial-success look', async () => {
    const api = stubContractApi(finalizedOptions({ redline: fixtureFailedRedlineExecution('TARGET_NOT_FOUND') }))
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Redline failed' })).toBeInTheDocument()
    expect(screen.getByText('Redline failed', { selector: '.rv-stb' })).toBeInTheDocument()
    expect(screen.getByText('No verified DOCX is available to download')).toBeInTheDocument()
    expect(screen.getByText('Approved changes remain frozen and intact')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /retry/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Generate redline' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Verified redline' })).not.toBeInTheDocument()
    expect(calls(api, '/redline', 'POST')).toHaveLength(0)
  })

  it('recoverable failure: offers Retry only for approved semantics drift and retries the frozen change set', async () => {
    let api: ReturnType<typeof stubContractApi>
    api = stubContractApi(
      finalizedOptions({
        redline: fixtureFailedRedlineExecution('APPROVED_SEMANTICS_DRIFT'),
        redlineRetryResponse: () => {
          api.setRedline(fixtureRedlineExecution({ attempt: 2 }))
          return jsonResponse(envelope(fixtureRedlineExecution({ attempt: 2 })))
        },
      }),
    )
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Redline failed' })).toBeInTheDocument()
    const retry = screen.getByRole('button', { name: 'Retry redline' })
    await userEvent.click(retry)
    await waitFor(() => expect(calls(api, '/redline/retry', 'POST')).toHaveLength(1))
    expect(await screen.findByRole('heading', { name: 'Verified redline' })).toBeInTheDocument()
    // A retry never falls back to a fresh trigger on the same frozen change set.
    expect(calls(api, '/redline', 'POST').filter((request) => !request.url.includes('/retry'))).toHaveLength(0)
  })

  it('a retry that fails again keeps the recorded failure and shows the attempt, never the server detail', async () => {
    const first = fixtureFailedRedlineExecution('APPROVED_SEMANTICS_DRIFT')
    const second = fixtureFailedRedlineExecution('APPROVED_SEMANTICS_DRIFT', { attempt: 2 })
    let api: ReturnType<typeof stubContractApi>
    api = stubContractApi(
      finalizedOptions({
        redline: first,
        redlineRetryResponse: () => {
          api.setRedline(second)
          return jsonResponse({ code: '409', title: 'x', detail: 'server detail text' }, 409)
        },
      }),
    )
    renderAt(PATH)
    await screen.findByRole('heading', { name: 'Redline failed' })
    await userEvent.click(screen.getByRole('button', { name: 'Retry redline' }))
    await waitFor(() => expect(calls(api, '/redline/retry', 'POST')).toHaveLength(1))
    expect(await screen.findByText(/Attempt 2/)).toBeInTheDocument()
    expect(screen.queryByText(/server detail text/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
  })

  it('a failed generation shows the recorded failure, not a second error', async () => {
    const failed = fixtureFailedRedlineExecution('APPROVED_SEMANTICS_DRIFT')
    let api: ReturnType<typeof stubContractApi>
    api = stubContractApi(
      finalizedOptions({
        redlineTriggerResponse: () => {
          api.setRedline(failed)
          return jsonResponse({ code: '409', title: 'x', detail: 'server detail text' }, 409)
        },
      }),
    )
    renderAt(PATH)
    await userEvent.click(await screen.findByRole('button', { name: 'Generate redline' }))
    expect(await screen.findByRole('heading', { name: 'Redline failed' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/server detail text/)).not.toBeInTheDocument()
  })

  it('a generation that cannot reach the server says so and keeps Generate available', async () => {
    stubContractApi(finalizedOptions({ redlineTriggerResponse: () => jsonResponse({ code: '503', title: 'x', detail: 'x' }, 503) }))
    renderAt(PATH)
    await userEvent.click(await screen.findByRole('button', { name: 'Generate redline' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be stored')
    expect(screen.getByRole('button', { name: 'Generate redline' })).toBeEnabled()
  })

  it('not verified: a completed execution with an unverified change is never shown as success', async () => {
    stubContractApi(
      finalizedOptions({
        redline: fixtureRedlineExecution({ changes: [fixtureRedlineChange({ verification: 'FAILED' })] }),
      }),
    )
    renderAt(PATH)
    expect(await screen.findByRole('heading', { name: 'Redline not verified' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Download/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Verified redline' })).not.toBeInTheDocument()
  })

  it('never shows the server failure detail, only a fixed sentence', async () => {
    stubContractApi(finalizedOptions({ redline: fixtureFailedRedlineExecution('PREEXISTING_REVISIONS_UNSUPPORTED') }))
    renderAt(PATH)
    await screen.findByRole('heading', { name: 'Redline failed' })
    expect(screen.getByText(/already contains tracked changes/)).toBeInTheDocument()
    expect(screen.queryByText('The document contains existing tracked revisions.')).not.toBeInTheDocument()
  })
})

describe('tabs: approved changes and reports', () => {
  it('approved changes are read-only and link back to the Workspace for the Finding', async () => {
    stubContractApi(finalizedOptions({ redline: fixtureRedlineExecution() }))
    const router = renderAt(`${PATH}/changes`)
    expect(await screen.findByText(/Approved change set is frozen/)).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Approved changes' })).toHaveAttribute('aria-selected', 'true')
    const list = screen.getByRole('list', { name: 'Approved changes' })
    expect(within(list).getByText('Limitation of liability')).toBeInTheDocument()
    expect(within(list).getAllByText('Edited').length).toBeGreaterThan(0)
    expect(within(list).getByText(/Decision revision/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /accept|reject|escalate|save|delete|remove|edit change/i })).not.toBeInTheDocument()
    const link = within(list).getByRole('link', { name: /Open Review Workspace/ })
    expect(link).toHaveAttribute('href', '/reviews/c1/analysis/r-1?finding=f-1')
    await userEvent.click(link)
    expect(router.state.location.pathname).toBe('/reviews/c1/analysis/r-1')
  })

  it('shows the frozen clause text as the before and the approved language as the after', async () => {
    stubContractApi(finalizedOptions({ redline: fixtureRedlineExecution() }))
    renderAt(`${PATH}/changes`)
    expect(await screen.findByText('Liability is capped at two times the fees paid.')).toBeInTheDocument()
    expect(await screen.findByText(/6\.3 The exclusions in Section 6\.2/)).toBeInTheDocument()
  })

  it('an insertion does not claim a physical placement', async () => {
    stubContractApi(
      finalizedOptions({
        changeSet: fixtureMixedChangeSet(),
        redline: null,
        findings: fixtureFindingList,
      }),
    )
    renderAt(`${PATH}/changes`)
    await screen.findByText(/Approved change set is frozen/)
    const rows = screen.getAllByRole('button', { expanded: false }).concat(screen.getAllByRole('button', { expanded: true }))
    for (const row of rows.filter((row) => row.textContent?.includes('Missing'))) await userEvent.click(row)
    expect(screen.getAllByText('Placement resolved during redline execution').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/Physical DOCX placement was not chosen by the reviewer/).length).toBeGreaterThan(0)
  })

  it('an empty change set says so, and offers nothing to inspect', async () => {
    stubContractApi(finalizedOptions({ changeSet: fixtureChangeSet({ changes: [] }) }))
    renderAt(`${PATH}/changes`)
    expect(await screen.findByText(/0 executable changes/)).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Approved changes' })).not.toBeInTheDocument()
  })

  it('verified change report: per-change Applied · Verified, and an overall VERIFIED result', async () => {
    stubContractApi(finalizedOptions({ redline: fixtureRedlineExecution() }))
    renderAt(`${PATH}/report`)
    expect(await screen.findByRole('heading', { name: 'Change report' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Change report' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText('Applied · Verified')).toBeInTheDocument()
    expect(screen.getByText(/Overall result: VERIFIED/)).toBeInTheDocument()
  })

  it('diagnostic report: top level FAILED, and no applied change is dressed up as success', async () => {
    const failed = fixtureFailedRedlineExecution('TARGET_NOT_FOUND')
    failed.changes = [
      fixtureRedlineChange({ approvedChangeId: 'ch-1', ordinal: 1, status: 'APPLIED', verification: null }),
      fixtureRedlineChange({ approvedChangeId: 'ch-2', ordinal: 2, status: 'FAILED', verification: null, failureCode: 'TARGET_NOT_FOUND', resolvedTarget: null }),
    ]
    stubContractApi(finalizedOptions({ changeSet: fixtureMixedChangeSet(), redline: failed }))
    renderAt(`${PATH}/report`)
    expect(await screen.findByRole('heading', { name: 'Diagnostic report' })).toBeInTheDocument()
    expect(screen.getByText(/Overall result: FAILED/)).toBeInTheDocument()
    const applied = screen.getByText('Applied before overall failure')
    expect(applied).not.toHaveClass('rv-stb--ok')
    expect(screen.queryByText('Applied · Verified')).not.toBeInTheDocument()
    expect(screen.getByText('Execution failed')).toBeInTheDocument()
    expect(screen.getByText(/could not be found in the frozen document/)).toBeInTheDocument()
  })

  it('a report tab the state does not have falls back to the redline tab', async () => {
    stubContractApi(finalizedOptions())
    renderAt(`${PATH}/report`)
    expect(await screen.findByRole('heading', { name: 'Generate the redline' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /report/i })).not.toBeInTheDocument()
  })

  it('tabs follow the arrow keys and keep one tab in the tab order', async () => {
    stubContractApi(finalizedOptions({ redline: fixtureRedlineExecution() }))
    const router = renderAt(PATH)
    const first = await screen.findByRole('tab', { name: 'Redline' })
    expect(first).toHaveAttribute('tabindex', '0')
    expect(screen.getByRole('tab', { name: 'Approved changes' })).toHaveAttribute('tabindex', '-1')
    first.focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(router.state.location.pathname).toBe(`${PATH}/changes`)
    await userEvent.keyboard('{End}')
    expect(router.state.location.pathname).toBe(`${PATH}/report`)
  })

  it('tabs navigate to real URLs', async () => {
    stubContractApi(finalizedOptions({ redline: fixtureRedlineExecution() }))
    const router = renderAt(PATH)
    await userEvent.click(await screen.findByRole('tab', { name: 'Approved changes' }))
    expect(router.state.location.pathname).toBe(`${PATH}/changes`)
    await userEvent.click(screen.getByRole('tab', { name: 'Change report' }))
    expect(router.state.location.pathname).toBe(`${PATH}/report`)
  })
})

describe('the pinned version', () => {
  it('reads the version the Review pins, never the contract’s latest', async () => {
    const api = stubContractApi({
      reviewSummary: { ...fixtureCompletedReviewSummary, contractVersionNumber: 2 },
      findings: fixtureFindingList,
      humanReview: fixtureHumanReview({ status: 'NOT_STARTED' }),
    })
    renderAt(PATH)
    await screen.findByRole('heading', { name: '2 Findings are ready for human review' })
    await waitFor(() => expect(calls(api, '/versions/2/semantics').length).toBeGreaterThan(0))
    expect(calls(api, '/versions/1/')).toHaveLength(0)
    expect(screen.getByText('v2, immutable')).toBeInTheDocument()
  })

  it('names the document the Review itself reports, never the contract’s latest', async () => {
    stubContractApi({
      reviewSummary: {
        ...fixtureQueuedReviewSummary,
        contractVersionNumber: 2,
        document: {
          contractFilename: 'pinned-at-v2.docx',
          uploadedAt: '2026-09-25T08:55:00Z',
          sizeBytes: 4096,
        },
      },
    })
    renderAt(PATH)
    await screen.findByRole('heading', { name: 'Waiting for analysis to start' })
    const rail = screen.getByRole('complementary', { name: 'Details' })
    expect(within(rail).getByText('pinned-at-v2.docx')).toBeInTheDocument()
    expect(within(rail).getByText('4.0 KB')).toBeInTheDocument()
    // The stubbed contract's latest version (v1) is msa.docx; the Review reports its own document.
    expect(screen.queryByText('msa.docx')).not.toBeInTheDocument()
  })

  it('uses the frozen review context, not the contract’s current profile', async () => {
    stubContractApi({ reviewSummary: fixtureQueuedReviewSummary })
    renderAt(PATH)
    await screen.findByRole('heading', { name: 'Waiting for analysis to start' })
    const rail = screen.getByRole('complementary', { name: 'Details' })
    expect(within(rail).getByText('Counterparty paper')).toBeInTheDocument()
    expect(within(rail).getByText('United Kingdom')).toBeInTheDocument()
    expect(within(rail).getByText('Publication 6')).toBeInTheDocument()
  })
})

describe('identity and errors', () => {
  it('a review that does not exist says so, with a way back', async () => {
    stubContractApi({ reviewSummaryStatus: 404 })
    renderAt(PATH)
    expect(await screen.findByText(/could not be found/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to reviews' })).toHaveAttribute('href', '/reviews')
  })

  it('a Review opened under the wrong contract is not shown', async () => {
    stubContractApi({ reviewSummary: { ...fixtureQueuedReviewSummary, contractId: 'someone-else' } })
    renderAt(PATH)
    expect(await screen.findByText(/could not be found/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Waiting for analysis to start' })).not.toBeInTheDocument()
  })

  it('a refused read says so', async () => {
    stubContractApi({ reviewSummaryStatus: 403 })
    renderAt(PATH)
    expect(await screen.findByText(/do not have permission/)).toBeInTheDocument()
  })

  it('breadcrumb and top bar action lead back to Reviews', async () => {
    stubContractApi({ reviewSummary: fixtureQueuedReviewSummary })
    renderAt(PATH)
    await screen.findByRole('heading', { name: 'Waiting for analysis to start' })
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Reviews' })).toHaveAttribute('href', '/reviews')
  })
})
