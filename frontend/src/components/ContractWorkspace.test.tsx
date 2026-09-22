import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
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

const summary = {
  id: 'c1',
  title: 'Master Services Agreement',
  latestVersion: {
    versionNumber: 1,
    parseStatus: 'OK',
    sha256: 'abc123',
    nodeCount: 3,
  },
}

const structureOk = {
  contractId: 'c1',
  versionNumber: 1,
  parserVersion: 'redstar-docx-parser-1',
  parseStatus: 'OK',
  nodeCount: 3,
  nodes: [
    {
      id: 't',
      parentId: null,
      nodeType: 'TITLE',
      ordinal: 0,
      text: 'Master Services Agreement',
      headingLevel: null,
      rawStyleId: null,
      sourceSpan: { partPath: 'word/document.xml', bodyIndex: 0 },
    },
    {
      id: 'h',
      parentId: null,
      nodeType: 'HEADING',
      ordinal: 1,
      text: 'Fees',
      headingLevel: 2,
      rawStyleId: null,
      sourceSpan: { partPath: 'word/document.xml', bodyIndex: 1 },
    },
    {
      id: 'p',
      parentId: null,
      nodeType: 'PARAGRAPH',
      ordinal: 2,
      text: 'Customer shall pay within 30 days.',
      headingLevel: null,
      rawStyleId: null,
      sourceSpan: {
        partPath: 'word/document.xml',
        bodyIndex: 2,
        paragraphIndex: 5,
      },
    },
  ],
}

function renderWorkspace(initialEntry = '/reviews/c1') {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [initialEntry],
  })
  return render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
}

function stubFetch(handlers: Array<[string, () => Response]>) {
  const fetchMock = vi.fn((input: RequestInfo | URL) => {
    const url = String(input instanceof Request ? input.url : input)
    const matches = handlers
      .filter(([fragment]) => url.includes(fragment))
      .sort((a, b) => b[0].length - a[0].length)
    const chosen = matches[0]
    if (chosen) return Promise.resolve(chosen[1]())
    return Promise.resolve(jsonResponse({ message: 'not found' }, 404))
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ContractWorkspace', () => {
  it('shows loading then renders structure, outline, original links', async () => {
    stubFetch([
      ['/versions/1/structure', () => jsonResponse(structureOk)],
      ['/api/contracts/c1', () => jsonResponse(summary)],
    ])

    renderWorkspace()

    expect(await screen.findByRole('status')).toHaveTextContent(/loading/i)

    const outline = await screen.findByRole('navigation', {
      name: /document outline/i,
    })
    expect(
      within(outline).getByRole('button', { name: 'Fees' }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { name: 'Master Services Agreement' }).length).toBeGreaterThan(0)
    expect(
      screen.getByRole('heading', { level: 2, name: 'Fees' }),
    ).toBeInTheDocument()

    const view = screen.getByRole('link', { name: /view original/i })
    expect(view.getAttribute('href')).toMatch(
      /\/api\/contracts\/c1\/versions\/1\/original$/,
    )
    expect(view).toHaveAttribute('target', '_blank')

    const download = screen.getByRole('link', { name: /download original/i })
    expect(download).toHaveAttribute('download', 'original.docx')
  })

  it('selects a node from the outline and shows its source span', async () => {
    stubFetch([
      ['/versions/1/structure', () => jsonResponse(structureOk)],
      ['/api/contracts/c1', () => jsonResponse(summary)],
    ])

    renderWorkspace()

    const feesButton = await screen.findByRole('button', { name: 'Fees' })
    await userEvent.click(feesButton)

    await waitFor(() => {
      expect(feesButton).toHaveAttribute('aria-current', 'true')
    })
    expect(
      screen.getByText(/word\/document\.xml/),
    ).toBeInTheDocument()
    const selected = document.querySelector('[data-node-id="h"]')
    expect(selected).toHaveClass('doc-node--selected')
  })

  it('honors ?node= deep links on load', async () => {
    stubFetch([
      ['/versions/1/structure', () => jsonResponse(structureOk)],
      ['/api/contracts/c1', () => jsonResponse(summary)],
    ])

    renderWorkspace('/reviews/c1?node=p')

    const selected = await screen.findByText(
      'Customer shall pay within 30 days.',
    )
    await waitFor(() => {
      expect(selected).toHaveClass('doc-node--selected')
    })
    expect(
      screen.getByRole('button', { name: 'Fees' }),
    ).not.toHaveAttribute('aria-current')
  })

  it('shows contract error with retry', async () => {
    let failures = 0
    stubFetch([
      [
        '/api/contracts/c1',
        () => {
          failures += 1
          if (failures <= 2) {
            return new Response(null, { status: 500 })
          }
          return jsonResponse(summary)
        },
      ],
      ['/versions/1/structure', () => jsonResponse(structureOk)],
    ])

    renderWorkspace()

    const alert = await screen.findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/unavailable/i)

    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(
      await screen.findByRole('heading', { level: 2, name: 'Fees' }),
    ).toBeInTheDocument()
  })

  it('shows parse failure panel but keeps original links', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () =>
          jsonResponse({
            ...structureOk,
            parseStatus: 'FAILED',
            nodes: [],
          }),
      ],
      [
        '/api/contracts/c1',
        () =>
          jsonResponse({
            ...summary,
            latestVersion: {
              ...summary.latestVersion,
              parseStatus: 'FAILED',
            },
          }),
      ],
    ])

    renderWorkspace()

    expect(await screen.findByRole('alert')).toHaveTextContent(/parsing/i)
    expect(
      screen.getByRole('link', { name: /view original/i }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /download original/i }),
    ).toBeInTheDocument()
  })

  it('shows empty state when structure has no nodes and parse succeeded', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () =>
          jsonResponse({
            ...structureOk,
            nodes: [],
          }),
      ],
      ['/api/contracts/c1', () => jsonResponse(summary)],
    ])

    renderWorkspace()

    expect(
      await screen.findByText(/no document structure/i),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /download original/i }),
    ).toBeInTheDocument()
  })
})
