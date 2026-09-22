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

function renderForm() {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: ['/reviews'],
  })
  return render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
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
    renderForm()

    await userEvent.click(
      screen.getByRole('button', { name: /start review/i }),
    )

    expect(await within(reviewForm()).findByRole('alert')).toHaveTextContent(
      /choose a/i,
    )
  })

  it('rejects non-docx files with a clear message', async () => {
    renderForm()

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
        Promise.resolve(jsonResponse(ingest)),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderForm()

    const file = new File(['docx-bytes'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(
      screen.getByRole('button', { name: /start review/i }),
    )

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

    renderForm()

    const file = new File(['bad'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(
      screen.getByRole('button', { name: /start review/i }),
    )

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/upload|failed/i)
    expect(screen.getByLabelText(/contract file/i)).toBeInTheDocument()
  })

  it('surfaces the backend upload error detail', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn((_input: RequestInfo | URL, _init?: RequestInit) =>
        Promise.resolve(
          jsonResponse(
            {
              code: 'INVALID_UPLOAD',
              title: 'Invalid upload',
              detail: 'Only .docx files are supported',
            },
            400,
          ),
        ),
      ),
    )

    renderForm()

    const file = new File(['bad'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(
      screen.getByRole('button', { name: /start review/i }),
    )

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/only .docx files are supported/i)
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
                  detail: 'Transient upload failure',
                },
                400,
              ),
            )
          }
          return Promise.resolve(jsonResponse(ingest))
        }

        if (url.includes('/structure')) {
          return Promise.resolve(
            jsonResponse({
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
            jsonResponse({
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

        return Promise.resolve(
          jsonResponse({ service: 'rivell-backend', status: 'ready' }),
        )
      }),
    )

    renderForm()

    const file = new File(['docx-bytes'], 'upload.docx', {
      type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    })
    await userEvent.upload(screen.getByLabelText(/contract file/i), file)
    await userEvent.click(
      screen.getByRole('button', { name: /start review/i }),
    )

    const alert = await within(reviewForm()).findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/transient upload failure/i)

    await userEvent.click(
      within(reviewForm()).getByRole('button', { name: /try again/i }),
    )

    expect(await screen.findByRole('heading', { level: 1 })).toHaveTextContent(
      /uploaded contract/i,
    )
  })
})
