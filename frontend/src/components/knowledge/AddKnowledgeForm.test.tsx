import { afterEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function ok(body: unknown): Response {
  return jsonResponse({
    data: body,
    status: 200,
    timestamp: '2026-09-23T05:00:00Z',
  })
}

const artifactDetail = {
  id: 'a1',
  artifactType: 'PLAYBOOK',
  filename: 'playbook.docx',
  contentType: 'application/octet-stream',
  sizeBytes: 2048,
  sha256: 'a'.repeat(64),
  storageKey: 'org/knowledge/a1.docx',
  declaredContractTypes: ['MSA'],
  contractPackKey: 'MSA',
  partyPerspective: null,
  paperSource: null,
  jurisdiction: null,
  language: null,
  effectiveFrom: null,
  effectiveTo: null,
  manifestNote: null,
  ingestStatus: 'EXTRACTED',
  parserVersion: 'rivell-docx-parser-2',
  parseWarnings: [],
  createdAt: '2026-09-23T05:17:29.604991Z',
}

function renderForm() {
  const router = createMemoryRouter(appRoutes, { initialEntries: ['/knowledge/new'] })
  const result = render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { router, ...result }
}

function addForm() {
  return screen.getByRole('form', { name: /add knowledge/i })
}

function docxFile(name = 'playbook.docx') {
  return new File(['docx-bytes'], name, {
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  })
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('AddKnowledgeForm', () => {
  it('requires a file and an artifact type', async () => {
    renderForm()

    await userEvent.click(within(addForm()).getByRole('button', { name: /add knowledge/i }))

    expect(await screen.findByText(/choose a \.docx or \.xlsx/i)).toBeInTheDocument()
    expect(screen.getByText(/select the source artifact type/i)).toBeInTheDocument()
  })

  it('rejects unsupported file types', async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(jsonResponse({ code: '500', title: 'Should not upload' }, 500)),
    )
    vi.stubGlobal('fetch', fetchMock)

    renderForm()

    await userEvent.upload(
      screen.getByLabelText(/source file/i),
      new File(['notes'], 'notes.txt', { type: 'text/plain' }),
      { applyAccept: false },
    )
    await userEvent.click(within(addForm()).getByRole('button', { name: /add knowledge/i }))

    expect(
      await screen.findByText(/only \.docx and \.xlsx files are supported/i),
    ).toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('rejects an inverted effective period', async () => {
    renderForm()

    fireEvent.change(screen.getByLabelText(/effective from/i), {
      target: { value: '2026-06-01' },
    })
    fireEvent.change(screen.getByLabelText(/effective to/i), {
      target: { value: '2026-01-01' },
    })
    await userEvent.upload(screen.getByLabelText(/source file/i), docxFile())
    await userEvent.selectOptions(
      screen.getByLabelText(/artifact type/i),
      'PLAYBOOK',
    )
    await userEvent.click(within(addForm()).getByRole('button', { name: /add knowledge/i }))

    expect(
      await screen.findByText(/effective from must not be after effective to/i),
    ).toBeInTheDocument()
  })

  it('uploads a source with the declared manifest and lands on the artifact route', async () => {
    const requests: Request[] = []
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const request = input as Request
        requests.push(request)
        return Promise.resolve(ok(artifactDetail))
      }),
    )

    const { router } = renderForm()

    await userEvent.upload(screen.getByLabelText(/source file/i), docxFile())
    await userEvent.selectOptions(
      screen.getByLabelText(/artifact type/i),
      'PLAYBOOK',
    )
    await userEvent.type(
      screen.getByLabelText(/declared contract types/i),
      'MSA, NDA',
    )
    await userEvent.type(screen.getByLabelText(/contract pack key/i), 'MSA')
    await userEvent.type(screen.getByLabelText(/jurisdiction/i), 'US')
    fireEvent.change(screen.getByLabelText(/effective from/i), {
      target: { value: '2026-01-01' },
    })
    await userEvent.click(within(addForm()).getByRole('button', { name: /add knowledge/i }))

    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/knowledge/artifacts/a1')
    })

    const uploadRequest = requests.find((request) => request.method === 'POST')
    const url = new URL(uploadRequest!.url)
    expect(url.pathname).toBe('/api/knowledge/artifacts')
    expect(url.searchParams.get('artifactType')).toBe('PLAYBOOK')
    expect(url.searchParams.get('declaredContractTypes')).toBe('MSA,NDA')
    expect(url.searchParams.get('contractPackKey')).toBe('MSA')
    expect(url.searchParams.get('jurisdiction')).toBe('US')
    expect(url.searchParams.get('effectiveFrom')).toBe('2026-01-01')
  })

  it('shows the upload error and retries with the same file', async () => {
    let attempts = 0
    vi.stubGlobal(
      'fetch',
      vi.fn(() => {
        attempts += 1
        if (attempts === 1) {
          return Promise.resolve(
            jsonResponse(
              {
                code: '400',
                title: 'Invalid request',
                detail: 'Only .docx, .xlsx files are supported',
              },
              400,
            ),
          )
        }
        return Promise.resolve(ok(artifactDetail))
      }),
    )

    const { router } = renderForm()

    await userEvent.upload(screen.getByLabelText(/source file/i), docxFile())
    await userEvent.selectOptions(
      screen.getByLabelText(/artifact type/i),
      'PLAYBOOK',
    )
    await userEvent.click(within(addForm()).getByRole('button', { name: /add knowledge/i }))

    const alert = await within(addForm()).findByRole('alert')
    expect(alert).toHaveTextContent(/only \.docx, \.xlsx files are supported/i)

    await userEvent.click(
      within(addForm()).getByRole('button', { name: /try again/i }),
    )

    await vi.waitFor(() => {
      expect(router.state.location.pathname).toBe('/knowledge/artifacts/a1')
    })
  })

  it('shows the artifact type description for the selection', async () => {
    renderForm()

    await userEvent.selectOptions(
      screen.getByLabelText(/artifact type/i),
      'EXECUTED_PRECEDENT',
    )

    expect(
      screen.getByText(/historically accepted language/i),
    ).toBeInTheDocument()
  })
})
