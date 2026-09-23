import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { fixtureSemantics, stubContractApi } from './testFixtures'

function renderRoute(initialEntry: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] })
  const result = render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { router, ...result }
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('semantic analysis lifecycle', () => {
  it('offers the profile form when a version has no analysis yet', async () => {
    const api = stubContractApi({ semantics: null })
    renderRoute('/reviews/c1?view=semantics')

    expect(
      await screen.findByRole('heading', { name: 'Semantic analysis' }),
    ).toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('button', { name: 'Run semantic analysis' }),
    )
    expect(
      await screen.findByRole('alert'),
    ).toHaveTextContent(/choose a contract type/i)

    await userEvent.selectOptions(
      screen.getByLabelText('Contract type'),
      'MSA',
    )
    await userEvent.click(
      screen.getByRole('button', { name: 'Run semantic analysis' }),
    )

    await waitFor(() => {
      expect(
        api.requests.some(
          (request) =>
            request.method === 'POST' &&
            request.url.includes('/versions/1/enrichment'),
        ),
      ).toBe(true)
    })
    const enrichmentRequest = api.requests.find(
      (request) => request.method === 'POST' && request.url.includes('/enrichment'),
    )
    expect(enrichmentRequest?.body).toEqual({ contractType: 'MSA' })

    const overview = await screen.findByRole('heading', { name: 'Overview' })
    expect(overview).toHaveFocus()
  })

  it('shows the server message when enrichment fails', async () => {
    stubContractApi({
      semantics: null,
      enrichmentResponse: () =>
        jsonResponse(
          {
            code: '500',
            title: 'Internal server error',
            detail: 'Contract version has no parsed structure',
          },
          500,
        ),
    })
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Semantic analysis' })
    await userEvent.selectOptions(screen.getByLabelText('Contract type'), 'NDA')
    await userEvent.click(
      screen.getByRole('button', { name: 'Run semantic analysis' }),
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /no parsed structure/i,
    )
  })

  it('offers a retry with the stored profile after a failure', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        enrichmentStatus: 'FAILED',
        metadata: [],
        parties: [],
        definedTerms: [],
        references: [],
        clauses: [],
        relationships: [],
        quality: ['PROVIDER_DEGRADATION'],
        observations: null,
      }),
    })
    renderRoute('/reviews/c1?view=semantics')

    expect(
      await screen.findByRole('heading', { name: 'Semantic analysis failed' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Contract type')).toHaveValue('MSA')
    expect(
      screen.getByRole('button', { name: 'Try semantic analysis again' }),
    ).toBeInTheDocument()
  })

  it('shows analysis in progress and can check again', async () => {
    const api = stubContractApi({
      semantics: fixtureSemantics({ enrichmentStatus: 'ENRICHING' }),
    })
    renderRoute('/reviews/c1?view=semantics')

    expect(
      await screen.findByRole('heading', { name: 'Analysis in progress' }),
    ).toBeInTheDocument()

    api.setSemantics(fixtureSemantics())
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }))

    expect(
      await screen.findByRole('heading', { name: 'Overview' }),
    ).toBeInTheDocument()
  })

  it('keeps structural inspection available when analysis is not ready', async () => {
    stubContractApi({ semantics: null })
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Semantic analysis' })
    await userEvent.click(screen.getByRole('link', { name: 'Document' }))

    expect(
      await screen.findByRole('heading', {
        level: 2,
        name: 'Master Services Agreement',
      }),
    ).toBeInTheDocument()
  })
})


  it('renders available interpretations when the analysis state is unknown', async () => {
    stubContractApi({
      semantics: fixtureSemantics({ enrichmentStatus: 'UNKNOWN' }),
    })
    renderRoute('/reviews/c1?view=semantics')

    expect(
      await screen.findByText(/analysis state of this contract version is not recognised/i),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Overview' })).toBeInTheDocument()
  })


  it('renders corrected metadata values and correction-only records', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        metadata: [
          {
            field: 'AGREEMENT_TITLE',
            value: 'Draft Agreement',
            correctedValue: 'Master Services Agreement',
            corrected: true,
            sourceNodeCount: 1,
          },
          {
            field: 'TERM',
            value: '12 months',
            correctedValue: null,
            corrected: true,
            sourceNodeCount: 1,
          },
        ],
      }),
    })
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Overview' })
    const metadata = document.querySelector('.metadata-list') as HTMLElement
    expect(
      within(metadata).getByText('Master Services Agreement'),
    ).toBeInTheDocument()
    expect(within(metadata).getByText('Corrected')).toBeInTheDocument()
    expect(within(metadata).getByText('Correction recorded')).toBeInTheDocument()
  })


  it('maps a conflict on correction to a refresh action', async () => {
    const api = stubContractApi({
      partyCorrectionResponse: () =>
        jsonResponse(
          {
            code: '409',
            title: 'Invalid state',
            detail: 'Enrichment for contract 9f2a1c44 is already running',
          },
          409,
        ),
    })
    renderRoute('/reviews/c1?view=semantics&section=parties')

    await screen.findByRole('heading', { name: 'Parties' })
    const card = screen
      .getByRole('heading', { name: 'Northwind Customer Group LLC' })
      .closest('li') as HTMLElement
    await userEvent.click(within(card).getByLabelText('Party A'))
    await userEvent.click(within(card).getByRole('button', { name: 'Save roles' }))

    expect(
      await within(card).findByText(/semantic analysis for this contract is not ready/i),
    ).toBeInTheDocument()
    expect(
      within(card).queryByText(/9f2a1c44/),
    ).not.toBeInTheDocument()

    const before = api.requests.filter(
      (request) => request.method === 'GET' && request.url.includes('/semantics'),
    ).length
    await userEvent.click(
      within(card).getByRole('button', { name: 'Refresh analysis state' }),
    )
    await waitFor(() => {
      const after = api.requests.filter(
        (request) => request.method === 'GET' && request.url.includes('/semantics'),
      ).length
      expect(after).toBeGreaterThan(before)
    })
  })

  it('labels an unknown relationship detector neutrally', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        relationships: [
          {
            id: 'rel9',
            sourceRegionNodeId: 'node-5',
            targetRegionNodeId: 'node-6',
            type: 'DEPENDS_ON',
            detector: 'FUTURE_METHOD',
            basis: 'x',
            probability: 0.5,
            verification: 'NOT_VERIFIED',
          },
        ],
      }),
    })
    renderRoute('/reviews/c1?view=semantics&section=relationships')

    await screen.findByRole('heading', { name: 'Clause relationships' })
    expect(
      screen.getByText('Detection method not recognised'),
    ).toBeInTheDocument()
  })

  it('orders clauses by the document structure, not the response order', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        clauses: [
          {
            id: 'cl6',
            headingNodeId: 'node-6',
            heading: '6. Limitation of Liability',
            primaryConcept: 'LIMITATION_OF_LIABILITY',
            secondaryConcepts: [],
            correctedPrimaryConcept: null,
            machineFacts: [],
            effectiveFacts: [],
            interpretations: [],
            corrected: false,
          },
          {
            id: 'cl1',
            headingNodeId: 'node-1',
            heading: '1. Definitions',
            primaryConcept: null,
            secondaryConcepts: [],
            correctedPrimaryConcept: null,
            machineFacts: [],
            effectiveFacts: [],
            interpretations: [],
            corrected: false,
          },
        ],
      }),
    })
    renderRoute('/reviews/c1?view=semantics&section=clauses')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const buttons = Array.from(
      document.querySelectorAll('.clause-list__button'),
    ).map((button) => button.textContent)
    expect(buttons[0]).toContain('1. Definitions')
    expect(buttons[1]).toContain('6. Limitation of Liability')
  })

  it('keeps the cached analysis when a refresh fails', async () => {
    const api = stubContractApi({
      semantics: fixtureSemantics({ enrichmentStatus: 'ENRICHING' }),
    })
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Analysis in progress' })
    api.setSemantics(fixtureSemantics({ enrichmentStatus: 'ENRICHING' }))
    api.setSemanticsStatus(500)
    await userEvent.click(screen.getByRole('button', { name: 'Check again' }))

    expect(
      await screen.findByText(
        /the latest refresh failed/i,
        {},
        { timeout: 5000 },
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { name: 'Analysis in progress' }),
    ).toBeInTheDocument()
  })

  it('shows an explicit stale-selection state for an unknown clause key', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=missing-node')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    expect(
      screen.getByText(/not available in the current analysis/i),
    ).toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('button', { name: 'Clear clause selection' }),
    )
    expect(
      screen.queryByText(/not available in the current analysis/i),
    ).not.toBeInTheDocument()
  })

describe('semantic overview', () => {
  it('shows the profile, metadata, coverage and quality notes', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Overview' })
    const profile = document.querySelector('.profile-summary') as HTMLElement

    expect(within(profile).getByText('MSA')).toBeInTheDocument()
    expect(within(profile).getByText('Customer')).toBeInTheDocument()
    expect(within(profile).getByText('Counterparty paper')).toBeInTheDocument()
    expect(within(profile).getByText('United Kingdom')).toBeInTheDocument()

    const metadata = document.querySelector('.metadata-list') as HTMLElement
    expect(within(metadata).getByText('Master Services Agreement')).toBeInTheDocument()
    expect(within(metadata).getAllByText('Source: 1 region').length).toBeGreaterThan(0)

    const coverage = document.querySelector('.coverage-list') as HTMLElement
    expect(within(coverage).getByText('Parties').nextElementSibling).toHaveTextContent('2')
    expect(
      within(coverage).getByText('Defined terms').nextElementSibling,
    ).toHaveTextContent('2')

    expect(
      screen.getByText('Some analysis could not be completed automatically'),
    ).toBeInTheDocument()
    expect(screen.getByText('Some clauses could not be classified')).toBeInTheDocument()
  })

  it('never renders probabilities, provider plumbing or telemetry counters', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Overview' })
    const text = document.body.textContent ?? ''

    expect(text).not.toMatch(/probability/i)
    expect(text).not.toMatch(/provider|openai|anthropic|typesafe|jev/i)
    expect(text).not.toMatch(/0\.91/)
    expect(text).not.toMatch(/questionCount|decisionExecutionCount|inputTokens/)
  })
})

describe('semantic sections', () => {
  it('navigates between sections and focuses the section heading', async () => {
    stubContractApi()
    const { router } = renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Overview' })
    await userEvent.click(screen.getByRole('link', { name: 'Parties' }))

    expect(router.state.location.search).toContain('section=parties')
    const heading = await screen.findByRole('heading', { name: 'Parties' })
    expect(heading).toHaveFocus()
    expect(screen.getAllByText('Rivell detected').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Customer').length).toBeGreaterThan(0)
    expect(screen.getAllByText('Party A').length).toBeGreaterThan(0)
    expect(screen.getByText('Corrected')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('link', { name: 'Overview' }))
    expect(await screen.findByRole('heading', { name: 'Overview' })).toHaveFocus()
  })

  it('shows defined terms with usage counts and duplicates', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=definitions')

    await screen.findByRole('heading', { name: 'Defined terms' })
    expect(screen.getByText('Services')).toBeInTheDocument()
    expect(screen.getByText('Used 4 times in the contract text')).toBeInTheDocument()
    expect(screen.getByText('Defined more than once')).toBeInTheDocument()
  })

  it('navigates from a resolved cross-reference to its target', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=references',
    )

    await screen.findByRole('heading', { name: 'Cross-references' })
    expect(screen.getByText('Section 99')).toBeInTheDocument()
    expect(
      screen.getByText('No matching section was found in this document'),
    ).toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('button', {
        name: 'Go to 6.3 The exclusions in Section 6.2 do not apply to breaches…',
      }),
    )

    await waitFor(() => {
      expect(router.state.location.search).toContain('view=document')
    })
    expect(router.state.location.search).toContain('node=node-63')
    const target = document.querySelector('[data-node-id="node-63"]') as HTMLElement
    expect(target).toHaveClass('doc-node--selected')
    await waitFor(() => {
      expect(target).toHaveFocus()
    })
  })

  it('shows clause relationships with verbs and navigation', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=relationships',
    )

    await screen.findByRole('heading', { name: 'Clause relationships' })
    expect(screen.getByText('depends on')).toBeInTheDocument()
    expect(screen.getByText('Inferred from the clause texts')).toBeInTheDocument()
    expect(screen.getByText('carves out')).toBeInTheDocument()
    expect(screen.getByText('Stated in the clause text')).toBeInTheDocument()
    expect(screen.getByText('Supported by source')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: '5. Termination' }))
    expect(router.state.location.search).toContain('view=document')
    expect(router.state.location.search).toContain('node=node-5')
  })

  it('corrects a party role while keeping the machine interpretation', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=parties')

    await screen.findByRole('heading', { name: 'Parties' })
    const card = screen
      .getByRole('heading', { name: 'Northwind Customer Group LLC' })
      .closest('li') as HTMLElement

    await userEvent.click(within(card).getByLabelText('Party A'))
    await userEvent.click(within(card).getByRole('button', { name: 'Save roles' }))

    await waitFor(() => {
      expect(
        api.requests.some(
          (request) =>
            request.method === 'POST' &&
            request.url.includes('/semantics/parties/p1/role'),
        ),
      ).toBe(true)
    })
    const request = api.requests.find(
      (item) => item.method === 'POST' && item.url.includes('/parties/p1/role'),
    )
    expect(request?.body).toEqual({ roles: ['CUSTOMER'] })

    await waitFor(() => {
      expect(
        within(card).getByText(/roles updated/i),
      ).toBeInTheDocument()
    })
    expect(within(card).getByText('Rivell detected')).toBeInTheDocument()
  })
})
