import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import type { components } from '../api/schema'
import type { StructureNode } from '../lib/documentTree'

type SourceSpan = components['schemas']['SourceSpanView']

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function ok(body: unknown): Response {
  return jsonResponse({ data: body, status: 200, timestamp: '2026-09-23T05:00:00Z' })
}

function span(bodyIndex: number, partial: Partial<SourceSpan> = {}): SourceSpan {
  return {
    partPath: 'word/document.xml',
    bodyIndex,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    cellIndex: null,
    runStart: null,
    runEnd: null,
    ...partial,
  }
}

function node(
  partial: Partial<StructureNode> & { id: string },
): StructureNode {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    rawStyleId: null,
    sourceSpan: span(0),
    ...partial,
  }
}

const summary = {
  id: 'c1',
  title: 'Master Services Agreement',
  latestVersion: {
    versionNumber: 1,
    parseStatus: 'OK',
    parseWarnings: [],
    parserVersion: 'rivell-docx-parser-2',
    sha256: 'abc123def456',
    filename: 'msa.docx',
    nodeCount: 5,
  },
}

const nodes: StructureNode[] = [
  node({ id: 'body', nodeType: 'BODY' }),
  node({
    id: 't',
    parentId: 'body',
    nodeType: 'TITLE',
    text: 'Master Services Agreement',
    sourceSpan: span(0),
  }),
  node({
    id: 'h',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: 'Fees',
    sourceSpan: span(1, { paragraphIndex: 1 }),
  }),
  node({
    id: 'p',
    parentId: 'body',
    text: 'Customer shall pay within 30 days.',
    sourceSpan: span(2, { paragraphIndex: 2 }),
  }),
  node({
    id: 'tbl',
    parentId: 'body',
    nodeType: 'TABLE',
    sourceSpan: span(3, { tableIndex: 0 }),
  }),
  node({
    id: 'row',
    parentId: 'tbl',
    nodeType: 'TABLE_ROW',
    sourceSpan: span(3, { tableIndex: 0, rowIndex: 0 }),
  }),
  node({
    id: 'cell',
    parentId: 'row',
    nodeType: 'TABLE_CELL',
    text: '30 days',
    sourceSpan: span(3, { tableIndex: 0, rowIndex: 0, cellIndex: 0 }),
  }),
]

const structureOk = {
  contractId: 'c1',
  versionNumber: 1,
  parserVersion: 'rivell-docx-parser-2',
  parseStatus: 'OK',
  parseWarnings: [],
  nodeCount: nodes.length,
  nodes,
}

function renderWorkspace(initialEntry = '/reviews/c1') {
  const router = createMemoryRouter(appRoutes, {
    initialEntries: [initialEntry],
  })
  const result = render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { router, ...result }
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

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('ContractWorkspace', () => {
  it('renders the document, outline and original links after loading', async () => {
    stubFetch([
      ['/versions/1/structure', () => ok(structureOk)],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    renderWorkspace()

    expect(await screen.findByRole('status')).toHaveTextContent(/loading/i)

    const pageHeading = await screen.findByRole('heading', {
      level: 1,
      name: 'Master Services Agreement',
    })
    expect(pageHeading).toHaveFocus()
    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Master Services Agreement',
      }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('heading', { level: 4, name: 'Fees' }),
    ).toBeInTheDocument()

    const outline = screen.getByRole('navigation', {
      name: /document outline/i,
    })
    expect(within(outline).getByRole('button', { name: 'Fees' })).toBeInTheDocument()

    expect(screen.getByText(/msa\.docx/)).toBeInTheDocument()

    const view = screen.getByRole('link', { name: /view original/i })
    expect(view.getAttribute('href')).toMatch(
      /\/api\/contracts\/c1\/versions\/1\/original$/,
    )
    expect(view).toHaveAttribute('target', '_blank')

    const download = screen.getByRole('link', { name: /download original/i })
    expect(download).toHaveAttribute('download', 'msa.docx')
  })

  it('selects a node from the outline and shows its source span', async () => {
    stubFetch([
      ['/versions/1/structure', () => ok(structureOk)],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    renderWorkspace()

    const feesButton = await screen.findByRole('button', { name: 'Fees' })
    await userEvent.click(feesButton)

    await waitFor(() => {
      expect(feesButton).toHaveAttribute('aria-current', 'true')
    })
    expect(screen.getByRole('status')).toHaveTextContent(/word\/document\.xml/)
    expect(document.querySelector('[data-node-id="h"]')).toHaveClass(
      'doc-node--selected',
    )
  })

  it('scrolls a deep-linked selection into view', async () => {
    stubFetch([
      ['/versions/1/structure', () => ok(structureOk)],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    renderWorkspace('/reviews/c1?node=p')

    const selected = await screen.findByText('Customer shall pay within 30 days.')
    await waitFor(() => {
      expect(selected).toHaveClass('doc-node--selected')
    })
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })

  it('reports a stale node id without crashing', async () => {
    stubFetch([
      ['/versions/1/structure', () => ok(structureOk)],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    renderWorkspace('/reviews/c1?node=deleted-node')

    expect(
      await screen.findByText(/not part of this document version/i),
    ).toBeInTheDocument()
  })

  it('follows browser back and forward for URL selection', async () => {
    stubFetch([
      ['/versions/1/structure', () => ok(structureOk)],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    const { router } = renderWorkspace()

    const feesButton = await screen.findByRole('button', { name: 'Fees' })
    await userEvent.click(feesButton)
    await waitFor(() => {
      expect(feesButton).toHaveAttribute('aria-current', 'true')
    })

    await act(async () => {
      await router.navigate(-1)
    })
    await waitFor(() => {
      expect(feesButton).not.toHaveAttribute('aria-current')
    })

    await act(async () => {
      await router.navigate(1)
    })
    await waitFor(() => {
      expect(feesButton).toHaveAttribute('aria-current', 'true')
    })
  })

  it('does not stack history entries when the same node is selected again', async () => {
    stubFetch([
      ['/versions/1/structure', () => ok(structureOk)],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    const { router } = renderWorkspace()

    const feesButton = await screen.findByRole('button', { name: 'Fees' })
    await userEvent.click(feesButton)
    await waitFor(() => {
      expect(feesButton).toHaveAttribute('aria-current', 'true')
    })

    await userEvent.click(feesButton)
    await act(async () => {
      await router.navigate(-1)
    })

    await waitFor(() => {
      expect(feesButton).not.toHaveAttribute('aria-current')
    })
  })

  it('shows the parse limitations panel for a parse with warnings', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () =>
          ok({
            ...structureOk,
            parseWarnings: ['HEADERS_OR_FOOTERS_NOT_REPRESENTED'],
          }),
      ],
      [
        '/api/contracts/c1',
        () =>
          ok({
            ...summary,
            latestVersion: {
              ...summary.latestVersion,
              parseWarnings: ['HEADERS_OR_FOOTERS_NOT_REPRESENTED'],
            },
          }),
      ],
    ])

    renderWorkspace()

    const notice = await screen.findByText(/parsed with limitations/i)
    expect(notice).toBeInTheDocument()
    expect(
      screen.getByText(/headers or footers contain content/i),
    ).toBeInTheDocument()
    expect(screen.getByText(/1 warning/i)).toBeInTheDocument()
  })

  it('shows parsing in progress without structure', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () =>
          ok({
            ...structureOk,
            parseStatus: 'PARSING',
            nodes: [],
            nodeCount: 0,
          }),
      ],
      [
        '/api/contracts/c1',
        () =>
          ok({
            ...summary,
            latestVersion: {
              ...summary.latestVersion,
              parseStatus: 'PARSING',
            },
          }),
      ],
    ])

    renderWorkspace()

    expect(
      await screen.findByText(/parsing is still in progress/i),
    ).toBeInTheDocument()
  })

  it('shows the failure panel with warnings but keeps original links', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () =>
          ok({
            ...structureOk,
            parseStatus: 'FAILED',
            parseWarnings: ['SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED'],
            nodes: [],
            nodeCount: 0,
          }),
      ],
      [
        '/api/contracts/c1',
        () =>
          ok({
            ...summary,
            latestVersion: {
              ...summary.latestVersion,
              parseStatus: 'FAILED',
              parseWarnings: ['SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED'],
            },
          }),
      ],
    ])

    renderWorkspace()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/parsing failed/i)
    expect(alert).toHaveTextContent(/some document content could not be represented/i)
    expect(
      screen.getByRole('link', { name: /view original/i }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /download original/i }),
    ).toBeInTheDocument()
  })

  it('shows the empty state when the document has no structure', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () => ok({ ...structureOk, nodes: [], nodeCount: 0 }),
      ],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    renderWorkspace()

    expect(
      await screen.findByText(/no document structure/i),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /download original/i }),
    ).toBeInTheDocument()
  })

  it('shows a contract error with retry', async () => {
    let failures = 0
    stubFetch([
      [
        '/api/contracts/c1',
        () => {
          failures += 1
          if (failures <= 2) {
            return jsonResponse(
              {
                code: 'NOT_FOUND',
                title: 'Not found',
                detail: 'Contract 8f0e missing at com.rivell.workbench.contracts.ContractService.require:88',
              },
              404,
            )
          }
          return ok(summary)
        },
      ],
      ['/versions/1/structure', () => ok(structureOk)],
    ])

    renderWorkspace()

    const alert = await screen.findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/This contract could not be found/i)
    expect(alert).not.toHaveTextContent(/com\.rivell|8f0e|require:88/)

    await userEvent.click(screen.getByRole('button', { name: /try again/i }))
    expect(
      await screen.findByRole('heading', {
        level: 4,
        name: 'Fees',
      }),
    ).toBeInTheDocument()
  })

  it('degrades to the error state when the structure payload is malformed', async () => {
    stubFetch([
      [
        '/versions/1/structure',
        () => ok({ ...structureOk, nodes: 'broken' }),
      ],
      ['/api/contracts/c1', () => ok(summary)],
    ])

    renderWorkspace()

    const alert = await screen.findByRole('alert', {}, { timeout: 5000 })
    expect(alert).toHaveTextContent(/malformed structure response/i)
    expect(
      screen.getByRole('link', { name: /download original/i }),
    ).toBeInTheDocument()
  })
})
