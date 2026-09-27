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

  it('shows a bounded message when enrichment fails', async () => {
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

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('The semantic analysis could not be started.')
    expect(alert).not.toHaveTextContent(/no parsed structure/i)
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

  it('states a service failure without claiming it is transient or that a retry will work', async () => {
    const api = stubContractApi({
      semantics: fixtureSemantics({
        enrichmentStatus: 'FAILED',
        failureReason: 'SERVICE_UNAVAILABLE',
      }),
    })
    renderRoute('/reviews/c1?view=semantics')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Rivell could not complete the semantic analysis')
    expect(alert).not.toHaveTextContent('SERVICE_UNAVAILABLE')
    // The tenant copy must not carry the operator's vocabulary, whatever the reason code was.
    expect(alert.textContent ?? '').not.toMatch(/provider|credential|quota|configured/i)
    // DW-041 corrective: this class merges transient provider failures with two that can never
    // succeed on a retry, so the rendered surface may not assert a timeframe or an outcome.
    expect(alert.textContent ?? '').not.toMatch(/in a moment|just now|shortly|will (?:succeed|work)/i)
    expect(
      screen.getByRole('button', { name: 'Try semantic analysis again' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/The analysis can be run again with the profile below/)).toBeInTheDocument()
    expect(api.requests.filter((r) => r.url.includes('/enrichment'))).toHaveLength(0)
  })

  it('never tells the user a retry is pointless while offering one', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        enrichmentStatus: 'FAILED',
        failureReason: 'UNSUPPORTED_SOURCE',
      }),
    })
    renderRoute('/reviews/c1?view=semantics')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('This document cannot be analysed for semantics as it is supplied.')
    expect(screen.queryByText(/unlikely to change the result/)).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Try semantic analysis again' }),
    ).toBeInTheDocument()
  })

  it('admits a missing reason instead of hiding it', async () => {
    stubContractApi({ semantics: fixtureSemantics({ enrichmentStatus: 'FAILED', failureReason: null }) })
    renderRoute('/reviews/c1?view=semantics')

    expect(await screen.findByRole('alert')).toHaveTextContent('did not record a reason')
  })

/**
   * The retry paragraph itself, which no component test covered.
   *
   * <p>It previously varied by reason and one of its two branches told the tenant that "Rivell did
   * not record whether running it again would help" — untrue for every reason it applied to, because
   * the backend records a reason for every FAILED enrichment, and INTERNAL is the most common
   * recorded reason of all. The branch also claimed to withhold a re-run control while the control
   * was in fact rendered unconditionally. Both claims are unrecoverable from the copy alone, so the
   * rendered sentence is asserted directly.
   */
  it('never tells the tenant that no recoverability was recorded, for any reason', async () => {
    for (const failureReason of ['SERVICE_UNAVAILABLE', 'INTERNAL', 'UNSUPPORTED_SOURCE'] as const) {
      const api = stubContractApi({
        semantics: fixtureSemantics({ enrichmentStatus: 'FAILED', failureReason }),
      })
      const { unmount } = renderRoute('/reviews/c1?view=semantics')

      await screen.findByRole('alert')
      const body = document.body.textContent ?? ''
      expect(body).not.toMatch(/did not record whether/i)
      // A re-run is offered for every reason, and the copy may not imply the profile is the remedy.
      expect(
        screen.getByRole('button', { name: 'Try semantic analysis again' }),
      ).toBeInTheDocument()
      expect(body).not.toMatch(/try again with the profile/i)
      expect(body).toMatch(/can be run again with the profile below/i)
      unmount()
      api.requests.length = 0
    }
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



  it('navigates from a definition to its defining region', async () => {
    stubContractApi()
    const { router } = renderRoute('/reviews/c1?view=semantics&section=definitions')

    await screen.findByRole('heading', { name: 'Defined terms' })
    const services = screen.getByText('Services').closest('li') as HTMLElement

    await userEvent.click(
      within(services).getByRole('button', { name: '1. Definitions' }),
    )
    expect(router.state.location.search).toContain('view=document')
    expect(router.state.location.search).toContain('node=node-1')
    await waitFor(() => {
      expect(document.querySelector('[data-node-id="node-1"]')).toHaveFocus()
    })
  })

  it('navigates from a definition to a usage region', async () => {
    stubContractApi()
    const { router } = renderRoute('/reviews/c1?view=semantics&section=definitions')

    await screen.findByRole('heading', { name: 'Defined terms' })
    const services = screen.getByText('Services').closest('li') as HTMLElement

    await userEvent.click(
      within(services).getByRole('button', { name: '5. Termination' }),
    )
    expect(router.state.location.search).toContain('node=node-5')
  })

  it('shows a reference occurrence and its resolved target, both navigable', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=references',
    )

    await screen.findByRole('heading', { name: 'Cross-references' })
    const row = screen.getByText('Section 6.3').closest('li') as HTMLElement

    await userEvent.click(
      within(row).getByRole('button', { name: '5. Termination' }),
    )
    expect(router.state.location.search).toContain('node=node-5')
  })

  it('shows ambiguous candidates without implying a resolved target', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=references')

    await screen.findByRole('heading', { name: 'Cross-references' })
    const row = screen.getByText('Section 5').closest('li') as HTMLElement

    expect(within(row).getByText('Possible targets:')).toBeInTheDocument()
    expect(
      within(row).queryByText(/Resolved target/),
    ).not.toBeInTheDocument()
    expect(
      within(row).getByRole('button', { name: '5. Termination' }),
    ).toBeInTheDocument()
  })

  it('navigates from metadata provenance to the source region', async () => {
    stubContractApi()
    const { router } = renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Overview' })
    const metadata = document.querySelector('.metadata-list') as HTMLElement
    await userEvent.click(
      within(metadata).getByRole('button', { name: '6. Limitation of Liability' }),
    )
    expect(router.state.location.search).toContain('node=node-6')
  })

  it('navigates from party provenance to the source region', async () => {
    stubContractApi()
    const { router } = renderRoute('/reviews/c1?view=semantics&section=parties')

    await screen.findByRole('heading', { name: 'Parties' })
    const party = (await screen.findByText('Northwind Customer Group LLC')).closest(
      'li',
    ) as HTMLElement
    await userEvent.click(
      within(party).getByRole('button', { name: '1. Definitions' }),
    )
    expect(router.state.location.search).toContain('node=node-1')
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
            sourceNodeIds: ['title'],
          },
          {
            field: 'TERM',
            value: '12 months',
            correctedValue: null,
            corrected: true,
            sourceNodeIds: [],
          },
        ],
      }),
    })
    renderRoute('/reviews/c1?view=semantics')

    await screen.findByRole('heading', { name: 'Overview' })
    const metadata = document.querySelector('.metadata-list') as HTMLElement
    expect(
      within(metadata).getByText('Master Services Agreement', {
        selector: '.metadata-list__value',
      }),
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
            regionKind: 'CLAUSE',
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
            regionKind: 'CLAUSE',
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
    expect(
      within(metadata).getByText('Master Services Agreement', {
        selector: '.metadata-list__value',
      }),
    ).toBeInTheDocument()
    expect(
      within(metadata).getByRole('button', { name: '6. Limitation of Liability' }),
    ).toBeInTheDocument()

    const coverage = document.querySelector('.coverage-list') as HTMLElement
    expect(within(coverage).getByText('Parties').nextElementSibling).toHaveTextContent('2')
    expect(
      within(coverage).getByText('Defined terms').nextElementSibling,
    ).toHaveTextContent('2')

    expect(
      screen.getByText('Some analysis could not be completed automatically'),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Some contract regions could not be classified'),
    ).toBeInTheDocument()
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
    const services = screen.getByText('Services').closest('li') as HTMLElement
    expect(within(services).getByText(/Defined in:/)).toBeInTheDocument()
    expect(
      within(services).getByRole('button', { name: '1. Definitions' }),
    ).toBeInTheDocument()
    expect(within(services).getByText(/Used in 2 regions:/)).toBeInTheDocument()
    expect(
      within(services).getByRole('button', { name: '5. Termination' }),
    ).toBeInTheDocument()
    expect(
      within(services).getByRole('button', { name: '6. Limitation of Liability' }),
    ).toBeInTheDocument()
    expect(
      screen.getByText('No references found in the contract text'),
    ).toBeInTheDocument()
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
    expect(screen.getAllByText(/Occurrence:/).length).toBeGreaterThan(0)

    const resolvedRow = screen.getByText('Section 6.3').closest('li') as HTMLElement
    await userEvent.click(
      within(resolvedRow).getByRole('button', {
        name: '6.3 The exclusions in Section 6.2 do not apply to breaches…',
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
