import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
  return jsonResponse({
    data: body,
    status: 200,
    timestamp: '2026-09-23T05:00:00Z',
  })
}

const artifact = {
  id: 'a1',
  artifactType: 'PLAYBOOK',
  filename: 'msa-playbook.docx',
  contentType: 'application/octet-stream',
  sizeBytes: 2048,
  sha256: 'b'.repeat(64),
  ingestStatus: 'EXTRACTED',
  createdAt: '2026-09-23T05:17:29.604991Z',
}

const publication = {
  id: 'p1',
  organizationId: 'o1',
  publicationNumber: 3,
  note: 'Q3 refresh',
  items: [
    { id: 'i1', candidateId: 'c1', supersededCandidateId: null, action: 'ADD' },
  ],
  createdAt: '2026-09-23T06:00:00Z',
}

function renderRoute(path = '/knowledge') {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  return render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
}

function stubKnowledge(overrides: {
  artifacts?: Response
  publications?: Response
} = {}) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input)
    if (url.includes('/api/knowledge/publications')) {
      return Promise.resolve(overrides.publications ?? ok([publication]))
    }
    if (url.includes('/api/knowledge/artifacts')) {
      return Promise.resolve(overrides.artifacts ?? ok([artifact]))
    }
    return Promise.resolve(jsonResponse({ code: '404', title: 'Not found' }, 404))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('Knowledge overview', () => {
  it('lists sources with type, status and provenance, and recent publications', async () => {
    stubKnowledge()

    renderRoute()

    const heading = screen.getByRole('heading', { level: 1, name: 'Knowledge' })
    expect(heading).toHaveFocus()

    const source = await screen.findByRole('link', { name: 'msa-playbook.docx' })
    expect(source).toHaveAttribute('href', '/knowledge/artifacts/a1')
    expect(screen.getByText(/Contract playbook/)).toBeInTheDocument()
    expect(screen.getByText('Extracted')).toBeInTheDocument()
    expect(screen.getByText(/sha256 bbbbbbbbbbbb/)).toBeInTheDocument()

    const publicationLink = await screen.findByRole('link', {
      name: 'Publication v3',
    })
    expect(publicationLink).toHaveAttribute('href', '/knowledge/publications/p1')
    expect(screen.getByText(/1 item/)).toBeInTheDocument()
  })

  it('shows empty states when there is no knowledge yet', async () => {
    stubKnowledge({ artifacts: ok([]), publications: ok([]) })

    renderRoute()

    expect(
      await screen.findByText(/no knowledge sources yet/i),
    ).toBeInTheDocument()
    expect(screen.getByText(/no published versions yet/i)).toBeInTheDocument()
  })

  it('recovers from a sources error with retry', async () => {
    let failures = 0
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const url = String(input instanceof Request ? input.url : input)
      if (url.includes('/api/knowledge/artifacts')) {
        failures += 1
        if (failures <= 2) {
          return Promise.resolve(
            jsonResponse(
              {
                code: '500',
                title: 'Server error',
                detail: 'Knowledge sources are unavailable',
              },
              500,
            ),
          )
        }
        return Promise.resolve(ok([artifact]))
      }
      return Promise.resolve(ok([]))
    })
    vi.stubGlobal('fetch', fetchMock)

    renderRoute()

    const alert = await screen.findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/knowledge request could not be completed/i)

    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(
      await screen.findByRole('link', { name: 'msa-playbook.docx' }),
    ).toBeInTheDocument()
  })

  it('links to the add knowledge flow', () => {
    stubKnowledge()

    const router = createMemoryRouter(appRoutes, { initialEntries: ['/knowledge'] })
    render(
      <AppProviders>
        <RouterProvider router={router} />
      </AppProviders>,
    )

    expect(screen.getByRole('link', { name: /add knowledge/i })).toHaveAttribute(
      'href',
      '/knowledge/new',
    )
  })

  it('renders the add knowledge page', () => {
    stubKnowledge()

    renderRoute('/knowledge/new')

    expect(
      screen.getByRole('heading', { level: 1, name: 'Add knowledge' }),
    ).toHaveFocus()
    expect(screen.getByRole('form', { name: /add knowledge/i })).toBeInTheDocument()
  })

  it('waits for pending fetches with a loading state', async () => {
    let resolveArtifacts!: (value: Response) => void
    let resolvePublications!: (value: Response) => void
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL) => {
        const url = String(input instanceof Request ? input.url : input)
        return new Promise<Response>((resolve) => {
          if (url.includes('/api/knowledge/publications')) resolvePublications = resolve
          else resolveArtifacts = resolve
        })
      }),
    )

    renderRoute()

    expect(
      screen.getAllByRole('status').some((node) => /loading/i.test(node.textContent ?? '')),
    ).toBe(true)

    resolveArtifacts(ok([artifact]))
    resolvePublications(ok([publication]))

    await waitFor(() =>
      expect(screen.getByRole('link', { name: 'msa-playbook.docx' })).toBeInTheDocument(),
    )
  })
})
