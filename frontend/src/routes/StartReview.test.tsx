import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../app/AppProviders'
import { appRoutes } from '../app/router'
import { fixtureSemantics } from '../components/contract/testFixtures'
import { fixturePackCatalog, fixtureQueuedReviewSummary } from '../components/review/testFixtures'
import { reviewWire } from '../test/reviewListFixtures'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

const ok = (data: unknown) => json({ data, status: 200, timestamp: '2026-10-03T10:00:00Z' })

const ingest = {
  contractId: 'c9',
  contractVersionId: 'v9',
  versionNumber: 1,
  parseStatus: 'OK',
  parseWarnings: [],
  filename: 'Vendor NDA.docx',
  sha256: 'abc',
  nodeCount: 12,
}

interface Call {
  step: 'upload' | 'enrich' | 'create' | 'other'
  body: Record<string, unknown> | null
}

interface Behaviour {
  upload?: () => Response | Promise<Response>
  enrich?: () => Response | Promise<Response>
  create?: () => Response | Promise<Response>
}

/** Replies per endpoint and records the three Start Review requests, in the order they arrive. */
function stub(behaviour: Behaviour = {}) {
  const calls: Call[] = []
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(String(input), init)
      const path = new URL(request.url).pathname
      const method = request.method
      if (method === 'GET' && path === '/api/reviews/packs') return ok(fixturePackCatalog)
      if (method === 'POST' && path === '/api/contracts') {
        calls.push({ step: 'upload', body: null })
        return behaviour.upload ? behaviour.upload() : ok(ingest)
      }
      if (method === 'POST' && path.endsWith('/enrichment')) {
        calls.push({ step: 'enrich', body: await request.clone().json() })
        return behaviour.enrich ? behaviour.enrich() : ok(fixtureSemantics({ contractId: 'c9' }))
      }
      if (method === 'POST' && path.endsWith('/reviews')) {
        calls.push({ step: 'create', body: await request.clone().json() })
        return behaviour.create ? behaviour.create() : ok({ ...fixtureQueuedReviewSummary, id: 'r-new', contractId: 'c9' })
      }
      if (method === 'GET' && path === '/api/reviews') return ok({ items: [], truncated: false, nextCursor: null })
      return json({}, 404)
    }),
  )
  return calls
}

function renderPage(path = '/reviews/new') {
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] })
  render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return router
}

const docx = (name = 'Vendor NDA.docx') =>
  new File(['x'], name, { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })

const form = () => screen.getByRole('form', { name: 'Start a contract review' })
const startButton = () => within(form()).getByRole('button', { name: 'Start analysis' })

async function fill({ type = 'NDA', file = docx() }: { type?: string; file?: File } = {}) {
  await userEvent.upload(screen.getByLabelText('Contract file (Word)'), file)
  await userEvent.selectOptions(screen.getByLabelText('Contract type'), type)
}

afterEach(() => vi.unstubAllGlobals())

describe('Start Review: the canonical page', () => {
  it('is the two-step canonical form, with the frozen-context rail', async () => {
    stub()
    renderPage()
    expect(await screen.findByRole('heading', { name: 'Start a contract review' })).toBeInTheDocument()
    expect(screen.getByText('Upload a contract, then confirm the details for this review.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '1. Contract' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: '2. Review context' })).toBeInTheDocument()
    expect(screen.getByText('Choose a Word file')).toBeInTheDocument()
    expect(screen.getByText('Word files only. The original is never changed.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Review details' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'What happens next' })).toBeInTheDocument()
    const crumbs = screen.getByRole('navigation', { name: 'Breadcrumb' })
    expect(within(crumbs).getByRole('link', { name: 'Reviews' })).toHaveAttribute('href', '/reviews')
    expect(screen.getByRole('link', { name: 'Cancel' })).toHaveAttribute('href', '/reviews')
  })

  it('declares nothing on the reviewer’s behalf, and cannot start without a file and a contract type', async () => {
    const calls = stub()
    renderPage()
    expect(await screen.findByLabelText('Contract type')).toHaveValue('')
    expect(screen.getByLabelText('Jurisdiction')).toHaveValue('UNSPECIFIED')
    expect(screen.queryAllByRole('button', { pressed: true })).toHaveLength(0)
    expect(startButton()).toBeDisabled()
    await userEvent.upload(screen.getByLabelText('Contract file (Word)'), docx())
    expect(startButton()).toBeDisabled()
    await userEvent.selectOptions(screen.getByLabelText('Contract type'), 'MSA')
    expect(startButton()).toBeEnabled()
    expect(calls).toHaveLength(0)
  })

  it('shows the chosen file, and Replace puts the picker back', async () => {
    stub()
    renderPage()
    await userEvent.upload(await screen.findByLabelText('Contract file (Word)'), docx('Acme MSA.docx'))
    expect(screen.getByText('Acme MSA.docx')).toBeInTheDocument()
    expect(screen.getByText(/Word file, .*Kept unchanged/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Replace' }))
    expect(screen.getByText('Choose a Word file')).toBeInTheDocument()
  })

  it('refuses anything that is not a .docx', async () => {
    const calls = stub()
    renderPage()
    const user = userEvent.setup({ applyAccept: false })
    await user.upload(await screen.findByLabelText('Contract file (Word)'), new File(['x'], 'scan.pdf', { type: 'application/pdf' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Choose a Word document.')
    expect(screen.getByText('Choose a Word file')).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })

  it('offers the real pack catalog, and a pack-less review', async () => {
    stub()
    renderPage()
    const pack = await screen.findByLabelText(/Review pack/)
    await waitFor(() => expect(within(pack).getByRole('option', { name: 'NDA pack' })).toBeInTheDocument())
    expect(within(pack).getByRole('option', { name: 'No explicit pack' })).toBeInTheDocument()
    await userEvent.selectOptions(pack, 'NDA_PACK')
    expect(screen.getByText(/Confidentiality and term checks for non-disclosure agreements/)).toBeInTheDocument()
  })

  it('lets optional choices be cleared by choosing them again', async () => {
    stub()
    renderPage()
    const side = within(await screen.findByRole('group', { name: /Our side/ }))
    await userEvent.click(side.getByRole('button', { name: 'Customer' }))
    expect(side.getByRole('button', { name: 'Customer' })).toHaveAttribute('aria-pressed', 'true')
    await userEvent.click(side.getByRole('button', { name: 'Customer' }))
    expect(side.getByRole('button', { name: 'Customer' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('summarises what has been declared, and warns, without blocking, when the filename suggests another type', async () => {
    stub()
    renderPage()
    await fill({ type: 'MSA', file: docx('Vendor NDA.docx') })
    await userEvent.click(screen.getByRole('button', { name: 'Supplier' }))
    await userEvent.click(screen.getByRole('button', { name: 'Counterparty paper' }))
    expect(screen.getByText('MSA, Supplier side, counterparty paper')).toBeInTheDocument()
    expect(screen.getByRole('note')).toHaveTextContent('The file name suggests a different contract type.')
    expect(startButton()).toBeEnabled()
    await userEvent.selectOptions(screen.getByLabelText('Contract type'), 'NDA')
    expect(screen.queryByRole('note')).not.toBeInTheDocument()
  })
})

describe('Start Review: the three requests', () => {
  it('uploads, confirms the context and pack, then creates the review, in that order, and opens it', async () => {
    const calls = stub()
    const router = renderPage()
    await fill()
    await userEvent.click(screen.getByRole('button', { name: 'Customer' }))
    await userEvent.click(screen.getByRole('button', { name: 'Counterparty paper' }))
    await userEvent.selectOptions(screen.getByLabelText('Jurisdiction'), 'UK')
    const pack = screen.getByLabelText(/Review pack/)
    await waitFor(() => expect(within(pack).getByRole('option', { name: 'NDA pack' })).toBeInTheDocument())
    await userEvent.selectOptions(pack, 'NDA_PACK')
    await userEvent.click(startButton())

    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c9/overview/r-new'))
    expect(calls.map((call) => call.step)).toEqual(['upload', 'enrich', 'create'])
    expect(calls[1].body).toEqual({
      contractType: 'NDA',
      ourSideRole: 'CUSTOMER',
      paperSource: 'COUNTERPARTY_PAPER',
      jurisdiction: 'UK',
      contractPackKey: 'NDA_PACK',
    })
    expect(calls[2].body).toMatchObject({ contractPackKey: 'NDA_PACK' })
    expect(calls[2].body?.clientRequestId).toMatch(UUID)
  })

  it('sends only what was declared, and no pack for a pack-less review', async () => {
    const calls = stub()
    const router = renderPage()
    await fill({ type: 'OTHER' })
    await userEvent.click(startButton())
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c9/overview/r-new'))
    expect(calls[1].body).toEqual({ contractType: 'OTHER', jurisdiction: 'UNSPECIFIED' })
    expect(Object.keys(calls[2].body ?? {})).toEqual(['clientRequestId'])
  })

  it.each([
    ['ENRICHING', 'analysis is not ready yet'],
    ['FAILED', 'analysis is not ready yet'],
    ['NOT_ENRICHED', 'analysis is not ready yet'],
    ['SOMETHING_ELSE', 'analysis is not ready yet'],
  ])('never creates a review when the semantic analysis comes back %s', async (status, wording) => {
    const calls = stub({ enrich: () => ok({ ...fixtureSemantics({ contractId: 'c9' }), enrichmentStatus: status }) })
    renderPage()
    await fill()
    await userEvent.click(startButton())
    expect(await screen.findByRole('alert')).toHaveTextContent(wording)
    expect(screen.getByRole('alert')).toHaveTextContent('no review was started')
    expect(calls.map((call) => call.step)).toEqual(['upload', 'enrich'])
  })

  it('does not go on to the review when the file cannot be read as a contract', async () => {
    const calls = stub({ upload: () => ok({ ...ingest, parseStatus: 'FAILED' }) })
    renderPage()
    await fill()
    await userEvent.click(startButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('could not read this file as a contract')
    expect(calls.map((call) => call.step)).toEqual(['upload'])
  })

  it('stops at a failed upload and says nothing was stored', async () => {
    const calls = stub({ upload: () => json({}, 413) })
    renderPage()
    await fill()
    await userEvent.click(startButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('larger than this Rivell deployment accepts')
    expect(screen.queryByRole('link', { name: 'Open the stored contract' })).not.toBeInTheDocument()
    expect(calls.map((call) => call.step)).toEqual(['upload'])
  })

  it('resumes after a failed confirmation without uploading the contract twice', async () => {
    let enrichments = 0
    const calls = stub({
      enrich: () => (++enrichments === 1 ? json({}, 503) : ok(fixtureSemantics({ contractId: 'c9' }))),
    })
    const router = renderPage()
    await fill()
    await userEvent.click(startButton())
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The analysis service is unavailable right now.')
    expect(alert).toHaveTextContent('uploaded and stored unchanged')
    // the retry below resumes on this page; there is no separate contract page to open
    expect(within(alert).queryByRole('link', { name: 'Open the stored contract' })).not.toBeInTheDocument()

    await userEvent.click(startButton())
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c9/overview/r-new'))
    expect(calls.map((call) => call.step)).toEqual(['upload', 'enrich', 'enrich', 'create'])
  })

  it('reuses one request id when the outcome of the create is unconfirmed, so it cannot start twice', async () => {
    let creates = 0
    const calls = stub({
      create: () => (++creates === 1 ? json({}, 500) : ok({ ...fixtureQueuedReviewSummary, id: 'r-new', contractId: 'c9' })),
    })
    const router = renderPage()
    await fill()
    await userEvent.click(startButton())
    expect(await screen.findByRole('alert')).toHaveTextContent('cannot create a second review')

    await userEvent.click(startButton())
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c9/overview/r-new'))
    // Confirmed once, not again: the context did not change.
    expect(calls.map((call) => call.step)).toEqual(['upload', 'enrich', 'create', 'create'])
    expect(calls[2].body?.clientRequestId).toMatch(UUID)
    expect(calls[3].body?.clientRequestId).toBe(calls[2].body?.clientRequestId)
  })

  it('treats a changed context as a new operation: it is confirmed again and gets its own request id', async () => {
    let creates = 0
    const calls = stub({
      create: () => (++creates === 1 ? json({}, 500) : ok({ ...fixtureQueuedReviewSummary, id: 'r-new', contractId: 'c9' })),
    })
    const router = renderPage()
    await fill({ type: 'NDA' })
    await userEvent.click(startButton())
    await screen.findByRole('alert')

    await userEvent.selectOptions(screen.getByLabelText('Contract type'), 'MSA')
    await userEvent.click(startButton())
    await waitFor(() => expect(router.state.location.pathname).toBe('/reviews/c9/overview/r-new'))
    expect(calls.map((call) => call.step)).toEqual(['upload', 'enrich', 'create', 'enrich', 'create'])
    expect(calls[3].body).toMatchObject({ contractType: 'MSA' })
    expect(calls[4].body?.clientRequestId).not.toBe(calls[2].body?.clientRequestId)
  })

  it('uploads again only for a different file', async () => {
    let enrichments = 0
    const calls = stub({ enrich: () => (++enrichments === 1 ? json({}, 503) : ok(fixtureSemantics({ contractId: 'c9' }))) })
    renderPage()
    await fill()
    await userEvent.click(startButton())
    await screen.findByRole('alert')
    await userEvent.click(screen.getByRole('button', { name: 'Replace' }))
    await userEvent.upload(screen.getByLabelText('Contract file (Word)'), docx('Other NDA.docx'))
    await userEvent.click(startButton())
    await waitFor(() => expect(calls.filter((call) => call.step === 'upload')).toHaveLength(2))
  })

  it('shows which step is running and locks the form while it runs', async () => {
    let release: (response: Response) => void = () => {}
    stub({ enrich: () => new Promise<Response>((resolve) => (release = resolve)) })
    renderPage()
    await fill()
    await userEvent.click(startButton())
    expect(await screen.findByRole('status')).toHaveTextContent('Saving the review details…')
    expect(startButton()).toBeDisabled()
    expect(screen.getByLabelText('Contract type')).toBeDisabled()
    release(ok(fixtureSemantics({ contractId: 'c9' })))
    await waitFor(() => expect(screen.queryByText('Saving the review details…')).not.toBeInTheDocument())
  })
})

describe('Start Review: leaving while it saves', () => {
  it('still lists the new review when the reader went back to Reviews before it finished', async () => {
    let finishEnrichment: () => void = () => {}
    const enrichment = new Promise<Response>((resolve) => {
      finishEnrichment = () => resolve(ok(fixtureSemantics({ contractId: 'c9' })))
    })
    const calls = stub({ enrich: () => enrichment })
    const original = globalThis.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const request = input instanceof Request ? input : new Request(String(input), init)
        const path = new URL(request.url).pathname
        if (request.method === 'GET' && path === '/api/reviews') {
          const created = calls.some((call) => call.step === 'create')
          return ok({ items: created ? [reviewWire('r-new', 'ANALYSIS_QUEUED', 'Vendor NDA.docx', { contractId: 'c9' })] : [], truncated: false, nextCursor: null })
        }
        return original(input, init)
      }),
    )
    const router = renderPage('/reviews')
    expect(await screen.findByRole('heading', { name: 'Reviews' })).toBeInTheDocument()
    await router.navigate('/reviews/new')
    await screen.findByLabelText('Contract file (Word)')
    await fill()
    await userEvent.click(startButton())
    await waitFor(() => expect(calls.some((call) => call.step === 'enrich')).toBe(true))

    await act(() => router.navigate(-1))
    expect(router.state.location.pathname).toBe('/reviews')
    finishEnrichment()

    await waitFor(() => expect(calls.some((call) => call.step === 'create')).toBe(true))
    await waitFor(() => expect(screen.getByRole("heading", { name: "Reviews" })).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText(/Vendor NDA/)).toBeInTheDocument())
  })

  it('asks the browser to confirm before the page is closed or reloaded while the review is starting', async () => {
    const enrichment = new Promise<Response>(() => {})
    stub({ enrich: () => enrichment })
    renderPage()
    await screen.findByLabelText('Contract file (Word)')
    const idle = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(idle)
    expect(idle.defaultPrevented).toBe(false)

    await fill()
    await userEvent.click(startButton())
    await screen.findByText(/Saving the review details/)

    const leaving = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(leaving)
    expect(leaving.defaultPrevented).toBe(true)
  })
})
