import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RedlineExecution } from '../../api/redline'
import { RedlineDownloadAction } from './RedlineDownloadAction'

/**
 * M10A.1 / DW-036(1): the saved filename must come from the execution record, not from the
 * Content-Disposition header.
 *
 * Every existing fixture used the same value in both places, so the precedence was never actually
 * exercised: swapping the two would have left every test green. These cases make them differ.
 */
function executionWith(artifactFilename: string | null, artifactSizeBytes: number): RedlineExecution {
  return {
    id: 'exec-1',
    reviewId: 'review-1',
    changeSetId: 'set-1',
    contractId: 'contract-1',
    contractVersionNumber: 1,
    changeSetVersion: 1,
    status: 'COMPLETED',
    outcome: 'REDLINE_GENERATED',
    failureCode: null,
    failureDetail: null,
    rendererVersion: 'rivell-docx-renderer-1',
    placementStrategyVersion: 'frozen-semantics-1',
    sourceSha256: null,
    changeCount: 1,
    requestedBy: 'user-1',
    startedAt: '2026-09-27T10:00:00Z',
    completedAt: '2026-09-27T10:00:01Z',
    artifactFilename,
    artifactSizeBytes,
    artifactSha256: 'abc',
    downloadAvailable: true,
    changes: [
      {
        approvedChangeId: 'change-1',
        ordinal: 0,
        actionType: 'REPLACE',
        targetKind: 'CLAUSE',
        status: 'APPLIED',
        verification: 'VERIFIED',
        failureCode: null,
        placement: null,
        resolvedTarget: '8. Confidentiality',
        paragraphsDeleted: 0,
        paragraphsInserted: 2,
      },
    ],
  }
}

const BODY = new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8])

function stubDownload(headerFilename: string) {
  const fetchMock = vi.fn(async () => new Response(BODY, {
    status: 200,
    headers: {
      'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'Content-Disposition': `attachment; filename="${headerFilename}"`,
    },
  }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/**
 * The filename the component asked the browser to save. It has to be read at click time: the
 * component removes the anchor immediately after clicking it, so the DOM is already clean by the
 * time the download resolves.
 */
let savedFilename: string | null = null

function installAnchorClickSpy() {
  savedFilename = null
  Object.defineProperty(HTMLAnchorElement.prototype, 'click', {
    configurable: true,
    value: function (this: HTMLAnchorElement) {
      savedFilename = this.download
    },
  })
}

async function captureSavedFilename(): Promise<string> {
  await waitFor(() => expect(savedFilename).not.toBeNull())
  return savedFilename as unknown as string
}

describe('redline download filename precedence', () => {
  beforeEach(() => {
    vi.stubGlobal('URL', {
      ...URL,
      createObjectURL: () => 'blob:rivell-test',
      revokeObjectURL: () => undefined,
    })
    installAnchorClickSpy()
  })

  afterEach(() => {
    // The prototype patch is not undone by restoreAllMocks, so it is undone explicitly. Vitest
    // currently isolates each file in its own jsdom environment, but a shared environment would
    // otherwise inherit a disabled anchor click from this file.
    delete (HTMLAnchorElement.prototype as unknown as Record<string, unknown>).click
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('prefers the execution record over the Content-Disposition header', async () => {
    stubDownload('from-header.docx')
    render(
      <RedlineDownloadAction
        reviewId="review-1"
        execution={executionWith('from-execution.docx', BODY.byteLength)}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /download redlined docx/i }))

    expect(await captureSavedFilename()).toBe('from-execution.docx')
  })

  it('falls back to the header when the execution record carries no filename', async () => {
    stubDownload('from-header.docx')
    render(
      <RedlineDownloadAction
        reviewId="review-1"
        execution={executionWith(null, BODY.byteLength)}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /download redlined docx/i }))

    expect(await captureSavedFilename()).toBe('from-header.docx')
  })

  it('does not offer a download when the artifact is not available', () => {
    const execution = { ...executionWith('from-execution.docx', BODY.byteLength), downloadAvailable: false }
    render(<RedlineDownloadAction reviewId="review-1" execution={execution} />)

    expect(screen.queryByRole('button', { name: /download redlined docx/i })).toBeNull()
  })
})
