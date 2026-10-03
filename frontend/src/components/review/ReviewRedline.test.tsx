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
  fixtureClauseFindingDetail,
  fixtureCompletedReviewSummary,
  fixtureFindingList,
} from './testFixtures'
import { fixtureChangeSet, fixtureDecision, fixtureHumanReview } from './workflowFixtures'
import type { RedlineExecution } from '../../api/redline'
import {
  fixtureFailedRedlineExecution,
  fixtureMixedRedlineExecution,
  fixtureRedlineExecution,
} from './redlineFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-26T14:00:00Z' }
}

function errorResponse(status: number) {
  return jsonResponse(
    { code: String(status), title: 'Invalid state', detail: 'server detail text' },
    status,
  )
}

function renderRouter(api: ReturnType<typeof stubContractApi>) {
  void api
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/reviews/c1/analysis/r-1'],
  })
  render(
    <StrictMode>
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>
    </StrictMode>,
  )
  return { router }
}

/**
 * Redline lives in the "Redline" sheet, opened from the "Approved changes frozen" card in the
 * finding detail panel. That card exists only once the review is finalized.
 */
async function openRedlinePanel() {
  await userEvent.click(await screen.findByRole('button', { name: 'Redline and change report' }))
  const sheet = await screen.findByRole('dialog', { name: 'Redline' })
  return within(sheet).findByRole('region', { name: 'Redline execution' })
}

function finalizedHumanReview() {
  return fixtureHumanReview({
    status: 'COMPLETED',
    readyToFinalize: false,
    resolvedFindings: 1,
    remainingFindings: 0,
    accepted: 1,
    blockers: [],
    changeSetId: 'cs-1',
    finalizedBy: 'u-1',
    finalizedAt: '2026-09-26T11:00:00Z',
  })
}

function baseOptions(options: StubOptions = {}): StubOptions {
  return {
    reviewSummary: fixtureCompletedReviewSummary,
    findings: fixtureFindingList,
    findingDetail: fixtureClauseFindingDetail,
    decisions: { items: [fixtureDecision()], truncated: false },
    humanReview: finalizedHumanReview(),
    changeSet: fixtureChangeSet(),
    redline: null,
    ...options,
  }
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: vi.fn(() => 'blob:redline'),
  })
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: vi.fn(),
  })
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('redline availability', () => {
  it('does not offer generation before the review is finalized', async () => {
    const api = stubContractApi(
      baseOptions({ humanReview: fixtureHumanReview({ status: 'IN_PROGRESS' }) }),
    )
    renderRouter(api)
    expect(await screen.findByText('Rivell found')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Redline and change report' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('Approved changes frozen', { selector: 'b' })).not.toBeInTheDocument()
    expect(
      api.requests.filter((request) => request.url.includes('/redline')),
    ).toHaveLength(0)
  })

  it('offers generation after finalization when no execution exists', async () => {
    const api = stubContractApi(baseOptions())
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    ).toBeEnabled()
    expect(panel).toHaveTextContent('never modified')
    const gets = api.requests.filter(
      (request) => request.method === 'GET' && request.url.includes('/redline'),
    )
    expect(gets).toHaveLength(1)
  })
})

describe('redline generation', () => {
  it('generates, verifies and offers the verified download', async () => {
    const api = stubContractApi(
      baseOptions({ redlineTriggerResponse: () => jsonResponse(envelope(fixtureRedlineExecution())) }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    )
    await waitFor(() =>
      expect(within(panel).getByText('Redline generated')).toBeInTheDocument(),
    )
    expect(
      api.requests.filter(
        (request) => request.method === 'POST' && request.url.includes('/redline'),
      ),
    ).toHaveLength(1)
    expect(panel).toHaveTextContent('Verification passed')
    expect(panel).toHaveTextContent('regenerated and reopened the DOCX')
    const report = within(panel).getByRole('list', { name: 'Redline change report' })
    expect(report).toHaveTextContent('Replace clause')
    expect(report).toHaveTextContent('8. Limitation of Liability')
    expect(report).toHaveTextContent('Applied')
    expect(report).toHaveTextContent('Verified')
    expect(panel).toHaveTextContent('acme-msa-v1-redline.docx')
    expect(
      within(panel).getByRole('button', { name: 'Download redlined DOCX' }),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Redline execution' })).toHaveFocus(),
    )
  })

  it('does not allow a duplicate trigger while generation is in flight', async () => {
    let resolveTrigger: (response: Response) => void = () => {}
    const api = stubContractApi(
      baseOptions({
        redlineTriggerResponse: () =>
          new Promise<Response>((resolve) => {
            resolveTrigger = resolve
          }),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    const trigger = await within(panel).findByRole('button', { name: 'Generate redline' })
    await userEvent.click(trigger)
    await waitFor(() => expect(trigger).toBeDisabled())
    expect(panel).toHaveTextContent('Generating and verifying the redlined document…')
    await userEvent.click(trigger)
    expect(
      api.requests.filter(
        (request) => request.method === 'POST' && request.url.includes('/redline'),
      ),
    ).toHaveLength(1)
    resolveTrigger(jsonResponse(envelope(fixtureRedlineExecution())))
    await waitFor(() =>
      expect(within(panel).getByText('Redline generated')).toBeInTheDocument(),
    )
  })

  it('renders INSERT with backend placement and DELETE without replacement text', async () => {
    const changeSet = fixtureChangeSet()
    const api = stubContractApi(
      baseOptions({
        changeSet: {
          ...changeSet,
          changes: [
            changeSet.changes[0],
            {
              id: 'ch-2',
              findingId: 'f-2',
              decisionId: 'd-9',
              ordinal: 2,
              actionType: 'INSERT',
              targetKind: 'ABSENCE',
              targetHeadingNodeId: null,
              absenceConcept: 'CONFIDENTIALITY',
              approvedLanguage: 'Confidential information shall be protected.',
              languageSource: 'HUMAN_EDIT',
              approvedDraftingEvidenceId: null,
            },
          ],
        },
        redlineTriggerResponse: () =>
          jsonResponse(envelope(fixtureMixedRedlineExecution())),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    )
    const report = await within(panel).findByRole('list', {
      name: 'Redline change report',
    })
    expect(report).toHaveTextContent('Insert provision')
    expect(report).toHaveTextContent('Missing provision: Confidentiality')
    expect(report).toHaveTextContent('After clause: Confidentiality')
    expect(report).toHaveTextContent('Delete clause')
    expect(report).toHaveTextContent('5. Term and Renewal')
    expect(report).toHaveTextContent('3 paragraphs deleted')
    expect(report).not.toHaveTextContent('Confidential information shall be protected.')
  })

  it('shows no-changes truthfully without a download', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: fixtureRedlineExecution({
          outcome: 'NO_CHANGES',
          changeCount: 0,
          changes: [],
          downloadAvailable: false,
          artifactFilename: null,
          artifactSizeBytes: null,
          artifactSha256: null,
        }),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(await within(panel).findByText('No document changes')).toBeInTheDocument()
    expect(
      within(panel).getByText('No approved document changes were produced for this review.'),
    ).toBeInTheDocument()
    expect(
      within(panel).queryByRole('button', { name: 'Download redlined DOCX' }),
    ).not.toBeInTheDocument()
    expect(
      api.requests.filter(
        (request) => request.method === 'POST' && request.url.includes('/redline'),
      ),
    ).toHaveLength(0)
  })

  it('does not render a change report for a no-changes outcome', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: fixtureRedlineExecution({
          outcome: 'NO_CHANGES',
          changeCount: 0,
          changes: [fixtureRedlineExecution().changes[0]],
          downloadAvailable: false,
        }),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(await within(panel).findByText('No document changes')).toBeInTheDocument()
    expect(
      within(panel).queryByRole('list', { name: 'Redline change report' }),
    ).not.toBeInTheDocument()
  })

  it('reloads an existing execution without regenerating', async () => {
    const api = stubContractApi(
      baseOptions({ redline: fixtureMixedRedlineExecution() }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(await within(panel).findByText('Redline generated')).toBeInTheDocument()
    expect(
      within(panel).queryByRole('button', { name: 'Generate redline' }),
    ).not.toBeInTheDocument()
    expect(
      api.requests.filter(
        (request) => request.method === 'POST' && request.url.includes('/redline'),
      ),
    ).toHaveLength(0)
    expect(
      api.requests.filter((request) => request.url.includes('/api/retrieval/')),
    ).toHaveLength(0)
  })

  it('fails closed when a completed execution has no recorded outcome', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: {
          ...fixtureRedlineExecution(),
          outcome: null,
          downloadAvailable: true,
        } as unknown as RedlineExecution,
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(
      await within(panel).findByText('Redline execution completed'),
    ).toBeInTheDocument()
    expect(panel).toHaveTextContent('The outcome was not recorded for this execution.')
    expect(
      within(panel).queryByRole('button', { name: 'Download redlined DOCX' }),
    ).not.toBeInTheDocument()
  })

  it('fails closed when verification is incomplete despite a reported artifact', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: fixtureRedlineExecution({
          changes: [],
          changeCount: 1,
          downloadAvailable: true,
        }),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(await within(panel).findByText('Verification incomplete')).toBeInTheDocument()
    expect(
      within(panel).queryByRole('button', { name: 'Download redlined DOCX' }),
    ).not.toBeInTheDocument()
  })

  it('fails closed when the server reports no verified artifact', async () => {
    const api = stubContractApi(
      baseOptions({ redline: fixtureRedlineExecution({ downloadAvailable: false }) }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(await within(panel).findByText('Redline generated')).toBeInTheDocument()
    expect(await within(panel).findByText('Verification passed')).toBeInTheDocument()
    expect(
      within(panel).queryByRole('button', { name: 'Download redlined DOCX' }),
    ).not.toBeInTheDocument()
  })

  it('keeps unknown execution states safe', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: {
          ...fixtureRedlineExecution(),
          status: 'PROCESSING_DOCUMENT',
        } as unknown as RedlineExecution,
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(await within(panel).findByText('Execution state unknown')).toBeInTheDocument()
    expect(
      within(panel).queryByRole('button', { name: 'Download redlined DOCX' }),
    ).not.toBeInTheDocument()
  })
})

describe('redline failure truth', () => {
  it('moves focus to the failure notice after a failed trigger', async () => {
    let api: ReturnType<typeof stubContractApi>
    api = stubContractApi(
      baseOptions({
        redlineTriggerResponse: () => {
          api.setRedline(fixtureFailedRedlineExecution())
          return errorResponse(409)
        },
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    )
    const failureTitle = await within(panel).findByRole('heading', {
      name: 'Redline could not be generated',
    })
    await waitFor(() => expect(failureTitle).toHaveFocus())
  })

  it('shows a safe failure and keeps the approved change set visible', async () => {
    const api = stubContractApi(
      baseOptions({ redline: fixtureFailedRedlineExecution() }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    expect(
      await within(panel).findByText('Redline could not be generated'),
    ).toBeInTheDocument()
    expect(panel).toHaveTextContent(
      'This document already contains tracked changes and cannot be safely redlined automatically.',
    )
    expect(panel).toHaveTextContent('The document contains existing tracked revisions.')
    expect(panel).toHaveTextContent('The legal review is complete and the approved change set is unchanged.')
    expect(
      within(panel).queryByRole('button', { name: 'Download redlined DOCX' }),
    ).not.toBeInTheDocument()
    const detail = screen.getByRole('complementary', { name: 'Finding detail' })
    expect(within(detail).getByText('Approved changes frozen')).toBeInTheDocument()
    expect(detail).toHaveTextContent('1 executable change in the immutable approved change set.')
  })

  it('explains unsupported structure without blaming the legal review', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: fixtureFailedRedlineExecution('UNSUPPORTED_SOURCE_STRUCTURE'),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await waitFor(() =>
      expect(panel).toHaveTextContent(
        'An approved change targets document structure that cannot be safely redlined automatically.',
      ),
    )
    expect(panel).toHaveTextContent('The legal review is complete')
  })

  it('classifies a trigger conflict into safe copy without offering a retry', async () => {
    const api = stubContractApi(
      baseOptions({ redlineTriggerResponse: () => errorResponse(409) }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    )
    await waitFor(() =>
      expect(panel).toHaveTextContent(
        'Rivell could not generate the redline from this review’s frozen source.',
      ),
    )
    expect(panel).not.toHaveTextContent('server detail text')
  })
})

describe('redline trigger recovery', () => {
  it('shows an unconfirmed notice when the post-conflict re-read fails', async () => {
    let api: ReturnType<typeof stubContractApi>
    api = stubContractApi(
      baseOptions({
        redlineTriggerResponse: () => {
          api.setRedlineStatus(500)
          return errorResponse(409)
        },
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    )
    await waitFor(
      () => expect(panel).toHaveTextContent('The redline request could not be confirmed.'),
      { timeout: 5000 },
    )
    expect(panel).toHaveTextContent('The redline execution could not be loaded.')
  })
})

describe('redline artifact storage failure', () => {
  it('states that no document was produced, keeps a retry, and offers no download', async () => {
    const api = stubContractApi(
      baseOptions({ redlineTriggerResponse: () => errorResponse(503) }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()

    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Generate redline' }),
    )

    const alert = await within(panel).findByRole('alert')
    expect(alert).toHaveTextContent('The generated redline could not be stored')
    expect(alert).toHaveTextContent('approved change set are unchanged')
    expect(alert).not.toHaveTextContent('redline engine is unavailable')
    expect(alert.textContent).not.toMatch(/Exception|com\.rivell/)
    expect(within(panel).queryByRole('button', { name: /download/i })).not.toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Generate redline' })).toBeEnabled()
  })
})

describe('redline download', () => {
  it('downloads the verified DOCX as a file and revokes the object URL after the click', async () => {
    const clicked: { href: string; download: string }[] = []
    let revokedDuringClickTask = false
    const revoke = vi.fn()
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: revoke,
    })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push({ href: this.href, download: this.download })
      queueMicrotask(() => {
        revokedDuringClickTask = revoke.mock.calls.length > 0
      })
    })
    const api = stubContractApi(
      baseOptions({ redline: fixtureRedlineExecution() }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Download redlined DOCX' }),
    )
    await waitFor(() => expect(clicked).toHaveLength(1))
    expect(clicked[0]).toMatchObject({
      href: 'blob:redline',
      download: 'acme-msa-v1-redline.docx',
    })
    expect(revokedDuringClickTask).toBe(false)
    await waitFor(() => expect(revoke).toHaveBeenCalledWith('blob:redline'))
    const downloads = api.requests.filter((request) =>
      request.url.includes('/redline/download'),
    )
    expect(downloads).toHaveLength(1)
  })

  it('rejects a download body that does not match the recorded artifact size', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: fixtureRedlineExecution({ artifactSizeBytes: 999_999 }),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Download redlined DOCX' }),
    )
    await waitFor(() =>
      expect(panel).toHaveTextContent(
        'The redline was generated, but the file could not be downloaded. Try again.',
      ),
    )
    expect(within(panel).getByText('Redline generated')).toBeInTheDocument()
  })

  it('keeps execution state when the download fails', async () => {
    const api = stubContractApi(
      baseOptions({
        redline: fixtureRedlineExecution(),
        redlineDownloadResponse: () => errorResponse(409),
      }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    await userEvent.click(
      await within(panel).findByRole('button', { name: 'Download redlined DOCX' }),
    )
    await waitFor(() =>
      expect(panel).toHaveTextContent(
        'The redline was generated, but the file could not be downloaded. Try again.',
      ),
    )
    expect(within(panel).getByText('Redline generated')).toBeInTheDocument()
    expect(
      within(panel).getByRole('button', { name: 'Download redlined DOCX' }),
    ).toBeInTheDocument()
  })
})

describe('redline permissions', () => {
  it('hides generation and explains a read-only permission failure', async () => {
    const api = stubContractApi(baseOptions({ redlineStatus: 403 }))
    renderRouter(api)
    const panel = await openRedlinePanel()
    await waitFor(() =>
      expect(panel).toHaveTextContent(
        'You do not have permission to generate or download redlines for this review.',
      ),
    )
    expect(
      within(panel).queryByRole('button', { name: 'Generate redline' }),
    ).not.toBeInTheDocument()
  })

  it('disables the trigger after a permission failure and shows no raw detail', async () => {
    const api = stubContractApi(
      baseOptions({ redlineTriggerResponse: () => errorResponse(403) }),
    )
    renderRouter(api)
    const panel = await openRedlinePanel()
    const trigger = await within(panel).findByRole('button', { name: 'Generate redline' })
    await userEvent.click(trigger)
    await waitFor(() =>
      expect(panel).toHaveTextContent(
        'You do not have permission to generate redlines for this review.',
      ),
    )
    expect(trigger).toBeDisabled()
    expect(panel).not.toHaveTextContent('server detail text')
  })
})
