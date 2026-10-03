import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function ok(body: unknown): Response {
  return jsonResponse({ data: body, status: 200, timestamp: '2026-09-23T05:00:00Z' })
}

const ingest = {
  contractId: 'c9',
  contractVersionId: 'v9',
  versionNumber: 1,
  parseStatus: 'OK',
  parseWarnings: [],
  filename: 'upload.docx',
  sha256: 'abc',
  nodeCount: 0,
}

async function renderForm() {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/reviews'],
  })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  // The upload form lives behind the top bar's primary action.
  await userEvent.click(screen.getAllByRole('button', { name: /^start review$/i })[0])
}

function reviewForm() {
  return screen.getByRole('form', { name: /new review/i })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('NewReviewForm', () => {
  it('shows validation error when submitting without a file', async () => {
    await renderForm()

    await userEvent.click(within(reviewForm()).getByRole('button', { name: /start review/i }))

    expect(await within(reviewForm()).findByRole('alert')).toHaveTextContent(
      /choose a/i,
    )
  })

  it('rejects non-docx files with a clear message', async () => {
    await renderForm()

    const input = screen.getByLabelText(/contract file/i)
    const file = new File(['hi'], 'notes.txt', { type: 'text/plain' })
    await userEvent.upload(input, file, { applyAccept: false })

    expect(await within(reviewForm()).findByRole('alert')).toHaveTextContent(
      /\.docx/i,
    )
  })

  it('uploads a docx and navigates to the contract workspace', async () => {
    const fetchMock = vi.fn(
      (_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(ok(ingest)),
    )
    vi.stubGlobal('fetch', fetchMock)

    await renderForm()

    const file = new File(['docx-bytes'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(within(reviewForm()).getByRole('button', { name: /start review/i }))

    expect(await screen.findByRole('status')).toHaveTextContent(
      /loading contract/i,
    )
    const contractCall = fetchMock.mock.calls.find(([input]) => {
      const url =
        input instanceof Request
          ? input.url
          : String(input as RequestInfo | URL)
      return url.includes('/api/contracts') && !url.includes('/system/')
    })
    expect(contractCall).toBeDefined()
    const request = contractCall![0] as Request
    expect(request.method).toBe('POST')
  })

  it('shows upload failure without navigating', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(new Response(null, { status: 400 })),
      ),
    )

    await renderForm()

    const file = new File(['bad'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(within(reviewForm()).getByRole('button', { name: /start review/i }))

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/upload|failed/i)
    expect(screen.getByLabelText(/contract file/i)).toBeInTheDocument()
  })

  it('reports a bounded message and never the backend error body', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(
          jsonResponse(
            {
              code: 'INVALID_UPLOAD',
              title: 'Invalid upload',
              detail: 'Rejected at com.rivell.workbench.contracts.ContractService.require:88',
            },
            400,
          ),
        ),
      ),
    )

    await renderForm()

    const file = new File(['bad'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(within(reviewForm()).getByRole('button', { name: /start review/i }))

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/upload was not accepted/i)
    expect(alert).not.toHaveTextContent(/com\.rivell|require:88/)
  })

  it('states the deployment upload ceiling without inventing a byte number', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(
          jsonResponse(
            { code: '413', title: 'Payload too large', detail: 'The uploaded file exceeds 10485760 bytes' },
            413,
          ),
        ),
      ),
    )

    await renderForm()

    const file = new File(['big'], 'big.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(within(reviewForm()).getByRole('button', { name: /start review/i }))

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/larger than this Rivell deployment accepts/i)
    expect(alert).not.toHaveTextContent(/10485760|10\s*MB/i)
  })

  it('navigates to the workspace when a failed upload is retried successfully', async () => {
    let attempts = 0
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const request = input as Request
        const url = String(input instanceof Request ? input.url : input)

        if (request.method === 'POST') {
          attempts += 1
          if (attempts === 1) {
            return Promise.resolve(
              jsonResponse(
                {
                  code: 'INVALID_UPLOAD',
                  title: 'Invalid upload',
                  detail: 'Transient failure at com.rivell.workbench.platform.storage.LocalDocumentStorage:212',
                },
                400,
              ),
            )
          }
          return Promise.resolve(ok(ingest))
        }

        if (url.includes('/structure')) {
          return Promise.resolve(
            ok({
              contractId: 'c9',
              versionNumber: 1,
              parserVersion: 'rivell-docx-parser-2',
              parseStatus: 'OK',
              parseWarnings: [],
              nodeCount: 0,
              nodes: [],
            }),
          )
        }

        if (url.includes('/api/contracts/c9')) {
          return Promise.resolve(
            ok({
              id: 'c9',
              title: 'Uploaded contract',
              latestVersion: {
                versionNumber: 1,
                parseStatus: 'OK',
                parseWarnings: [],
                parserVersion: 'rivell-docx-parser-2',
                sha256: 'abc',
                filename: 'upload.docx',
                nodeCount: 0,
              },
            }),
          )
        }

        return Promise.resolve(ok({ service: 'rivell-backend', status: 'ready' }))
      }),
    )

    await renderForm()

    const file = new File(['docx-bytes'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(within(reviewForm()).getByRole('button', { name: /start review/i }))

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/upload was not accepted/i)
    expect(alert).not.toHaveTextContent(/com\.rivell|LocalDocumentStorage/)

    await userEvent.click(
      within(reviewForm()).getByRole('button', { name: /start review/i }),
    )

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      /uploaded contract/i,
    )
  })
})
