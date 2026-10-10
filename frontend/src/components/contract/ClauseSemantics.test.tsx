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

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('clause semantics', () => {
  it('lists clauses and opens the selected clause from the URL', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=clauses&clause=node-6',
    )

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const list = document.querySelector('.clause-list') as HTMLElement
    expect(within(list).getByText('1. Definitions')).toBeInTheDocument()
    expect(within(list).getByText('5. Termination')).toBeInTheDocument()
    expect(within(list).getByText(/Limitation of liability/)).toBeInTheDocument()

    const primary = document.querySelector('.clause-concept-primary') as HTMLElement
    expect(within(primary).getByText('Limitation of liability')).toBeInTheDocument()
    const secondary = document.querySelector(
      '.clause-concept-secondary',
    ) as HTMLElement
    expect(within(secondary).getByText('Indemnification')).toBeInTheDocument()

    await userEvent.click(within(list).getByText('5. Termination'))
    expect(router.state.location.search).toContain('clause=node-5')
  })

  it('moves focus to the clause detail when a clause is selected', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    await userEvent.click(screen.getByText('5. Termination'))

    const heading = await screen.findByRole('heading', { name: '5. Termination' })
    expect(heading).toHaveFocus()
  })

  it('renders typed facts with verification and provenance', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const factList = document.querySelector('.fact-list') as HTMLElement

    expect(within(factList).getByText('3×')).toBeInTheDocument()
    expect(within(factList).getByText('Preceding 12-month fees')).toBeInTheDocument()
    expect(within(factList).getByText('Excluded')).toBeInTheDocument()
    expect(within(factList).getByText('Present')).toBeInTheDocument()
    expect(within(factList).getAllByText('Supported by source').length).toBe(2)
    expect(within(factList).getByText('Verification uncertain')).toBeInTheDocument()
    expect(within(factList).getByText('Source support disputed')).toBeInTheDocument()
    expect(
      within(factList).getAllByText(/Taken directly from the contract text/).length,
    ).toBeGreaterThan(0)
    expect(
      within(factList).getAllByText(/Classified from the clause text/).length,
    ).toBe(2)
  })

  it('renders termination facts with domain wording', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-5')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const factList = document.querySelector('.fact-list') as HTMLElement

    expect(within(factList).getByText('Termination notice')).toBeInTheDocument()
    expect(within(factList).getByText('60 days')).toBeInTheDocument()
    expect(within(factList).getByText('Either party')).toBeInTheDocument()
  })

  it('corrects the primary concept and keeps the other classifications', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement
    const editor = within(detail)
      .getByText('Correct classification')
      .closest('details') as HTMLElement

    await within(editor).findByRole('option', { name: 'Indemnification' })
    await userEvent.selectOptions(
      within(editor).getByLabelText('Primary concept'),
      'INDEMNIFICATION',
    )
    await userEvent.click(
      within(editor).getByRole('button', { name: 'Save classification' }),
    )

    await waitFor(() => {
      expect(
        api.requests.some(
          (request) =>
            request.method === 'POST' &&
            request.url.includes('/semantics/clauses/cl6/concepts'),
        ),
      ).toBe(true)
    })
    const request = api.requests.find(
      (item) => item.method === 'POST' && item.url.endsWith('/concepts'),
    )
    expect(request?.body).toEqual({ primary: 'INDEMNIFICATION' })

    await waitFor(() => {
      expect(
        within(detail).getByText(/classification updated/i),
      ).toBeInTheDocument()
    })
    expect(within(detail).getByText('Corrected')).toBeInTheDocument()
    const primary = detail.querySelector('.clause-concept-primary') as HTMLElement
    expect(within(primary).getByText('Indemnification')).toBeInTheDocument()
    expect(
      within(detail).getByText(
        /Rivell originally classified this clause as Limitation of liability/,
      ),
    ).toBeInTheDocument()
  })

  it('lists the taxonomy leaves grouped by parent and sends the chosen leaf', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const editor = within(document.querySelector('.clause-detail') as HTMLElement)
      .getByText('Correct classification')
      .closest('details') as HTMLElement

    const group = await within(editor).findByRole('group', { name: 'Confidentiality' })
    // ONWARD_DISCLOSURE's label is not its humanized key, so finding it proves the API label is used.
    expect(
      within(group).getByRole('option', { name: 'Disclosure to representatives' }),
    ).toBeInTheDocument()
    await userEvent.selectOptions(
      within(editor).getByLabelText('Primary concept'),
      'ONWARD_DISCLOSURE',
    )
    await userEvent.click(
      within(editor).getByRole('button', { name: 'Save classification' }),
    )
    await waitFor(() => {
      const request = api.requests.find(
        (item) => item.method === 'POST' && item.url.endsWith('/concepts'),
      )
      expect(request?.body).toEqual({ primary: 'ONWARD_DISCLOSURE' })
    })
  })

  it('shows a human classification on an unclassified clause everywhere', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        clauses: [
          {
            id: 'cl1',
            headingNodeId: 'node-1',
            regionKind: 'CLAUSE',
            heading: '1. Definitions',
            primaryConcept: null,
            secondaryConcepts: [],
            correctedPrimaryConcept: 'CONFIDENTIALITY_OBLIGATIONS',
            machineFacts: [],
            effectiveFacts: [],
            interpretations: [],
            corrected: true,
          },
        ],
      }),
    })
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=clauses&clause=node-1',
    )

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const list = document.querySelector('.clause-list') as HTMLElement
    expect(within(list).getByText(/Confidentiality obligations/)).toBeInTheDocument()
    expect(within(list).queryByText('No primary concept')).not.toBeInTheDocument()
    const primary = document.querySelector(
      '.clause-concept-primary',
    ) as HTMLElement
    expect(within(primary).getByText('Confidentiality obligations')).toBeInTheDocument()

    await userEvent.click(
      screen.getByRole('button', { name: 'View clause in document' }),
    )
    expect(router.state.location.search).toContain('view=document')
    expect(router.state.location.search).toContain('node=node-1')
    await waitFor(() => {
      expect(document.querySelector('.clause-summary')).not.toBeNull()
    })
    const summaryConcept = document.querySelector(
      '.clause-summary__concept',
    ) as HTMLElement
    expect(within(summaryConcept).getByText('Confidentiality obligations')).toBeInTheDocument()
  })

  it('corrects fact values and keeps the machine interpretation visible', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-5')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement
    const editor = within(detail)
      .getByText('Correct fact values')
      .closest('details') as HTMLElement

    const noticeInput = within(editor).getByLabelText('Notice period (days)')
    await userEvent.clear(noticeInput)
    await userEvent.type(noticeInput, '30')
    await userEvent.click(
      within(editor).getByRole('button', { name: 'Save fact values' }),
    )

    await waitFor(() => {
      expect(
        api.requests.some(
          (request) =>
            request.method === 'POST' &&
            request.url.includes('/semantics/clauses/cl5/facts'),
        ),
      ).toBe(true)
    })
    const request = api.requests.find((item) => item.url.endsWith('/facts'))
    expect(request?.body).toEqual({ values: { noticePeriodDays: '30' } })

    await waitFor(() => {
      expect(within(detail).getByText('30 days')).toBeInTheDocument()
    })
    const machineLine = within(detail).getByText(
      /Rivell originally interpreted: 60 days/,
    )
    expect(machineLine).toBeInTheDocument()
    expect(within(machineLine).getByText('Supported by source')).toBeInTheDocument()
    const correctedRow = machineLine.closest('.data-list__row') as HTMLElement
    expect(within(correctedRow).getByText('Corrected')).toBeInTheDocument()
  })


  it('keeps concept and fact corrections together (merge semantics)', async () => {
    stubContractApi()
    const first = renderRoute(
      '/reviews/c1?view=semantics&section=clauses&clause=node-6',
    )

    await screen.findByRole('heading', { name: 'Clause semantics' })
    let detail = document.querySelector('.clause-detail') as HTMLElement

    const conceptEditor = within(detail)
      .getByText('Correct classification')
      .closest('details') as HTMLElement
    await within(conceptEditor).findByRole('option', { name: 'Indemnification' })
    await userEvent.selectOptions(
      within(conceptEditor).getByLabelText('Primary concept'),
      'INDEMNIFICATION',
    )
    await userEvent.click(
      within(conceptEditor).getByRole('button', { name: 'Save classification' }),
    )
    await waitFor(() => {
      expect(
        within(detail.querySelector('.clause-concept-primary') as HTMLElement).getByText(
          'Indemnification',
        ),
      ).toBeInTheDocument()
    })

    const factEditor = within(detail)
      .getByText('Correct fact values')
      .closest('details') as HTMLElement
    const capInput = within(factEditor).getByLabelText('Liability cap multiple')
    await userEvent.clear(capInput)
    await userEvent.type(capInput, '2')
    await userEvent.click(
      within(factEditor).getByRole('button', { name: 'Save fact values' }),
    )
    await waitFor(() => {
      expect(within(detail).getByText('2×')).toBeInTheDocument()
    })

    expect(
      within(detail.querySelector('.clause-concept-primary') as HTMLElement).getByText(
        'Indemnification',
      ),
    ).toBeInTheDocument()
    expect(
      within(detail).getByText(
        /Rivell originally classified this clause as Limitation of liability/,
      ),
    ).toBeInTheDocument()
    expect(
      within(detail).getByText(/Rivell originally interpreted: 3×/),
    ).toBeInTheDocument()

    first.unmount()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')
    await screen.findByRole('heading', { name: 'Clause semantics' })
    detail = document.querySelector('.clause-detail') as HTMLElement
    await waitFor(() => {
      expect(
        within(detail.querySelector('.clause-concept-primary') as HTMLElement).getByText(
          'Indemnification',
        ),
      ).toBeInTheDocument()
    })
    expect(within(detail).getByText('2×')).toBeInTheDocument()
    expect(
      within(detail).getByText(/Rivell originally interpreted: 3×/),
    ).toBeInTheDocument()
  })

  it('validates fact values before sending them', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const editor = within(document.querySelector('.clause-detail') as HTMLElement)
      .getByText('Correct fact values')
      .closest('details') as HTMLElement

    const capInput = within(editor).getByLabelText('Liability cap multiple')
    await userEvent.clear(capInput)
    await userEvent.type(capInput, '0')
    await userEvent.click(
      within(editor).getByRole('button', { name: 'Save fact values' }),
    )

    expect(await within(editor).findByRole('alert')).toHaveTextContent(
      /greater than zero/i,
    )
    expect(
      api.requests.some((request) => request.url.endsWith('/facts')),
    ).toBe(false)
  })



  it('navigates from a fact to its supporting source regions', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=clauses&clause=node-6',
    )

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const factList = document.querySelector('.fact-list') as HTMLElement
    const capRow = within(factList).getByText('Liability cap').closest(
      '.data-list__row',
    ) as HTMLElement

    await userEvent.click(
      within(capRow).getByRole('button', { name: '6. Limitation of Liability' }),
    )
    expect(router.state.location.search).toContain('view=document')
    expect(router.state.location.search).toContain('node=node-6')

    const carveoutRow = within(factList)
      .getByText('Consequential damages')
      .closest('.data-list__row') as HTMLElement
    expect(
      within(carveoutRow).getAllByRole('button').length,
    ).toBeGreaterThanOrEqual(2)
  })

  it('keeps signature blocks and schedules out of the clause list', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const list = document.querySelector('.clause-list') as HTMLElement
    const structuralHeading = within(list).getByRole('heading', {
      name: 'Schedules and other regions',
    })

    expect(within(list).getByRole('heading', { name: 'Clauses' })).toBeInTheDocument()
    expect(
      within(list).getByRole('button', { name: /1\. Definitions/ }),
    ).toBeInTheDocument()

    const schedule = within(list).getByRole('button', {
      name: /Schedule A — Services/,
    })
    const signature = within(list).getByRole('button', {
      name: /Northwind Customer Group LLC/,
    })
    expect(schedule).toHaveTextContent('Schedule')
    expect(signature).toHaveTextContent('Signature block')

    const position = structuralHeading.compareDocumentPosition(signature)
    expect(position & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows a schedule region with its own semantics', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-9')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement

    expect(
      within(detail).getByRole('heading', { name: 'Schedule A — Services' }),
    ).toBeInTheDocument()
    expect(within(detail).getByText('Schedule')).toBeInTheDocument()
    expect(
      within(detail).queryByRole('link', { name: 'Inspect evidence' }),
    ).not.toBeInTheDocument()
  })

  it('offers evidence inspection for clauses', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement

    expect(
      within(detail).getByRole('link', { name: 'Inspect evidence' }),
    ).toHaveAttribute('href', '/reviews/c1/evidence?clause=node-6')
  })

  it('rejects a blank numeric fact value before sending it', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-5')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const editor = within(document.querySelector('.clause-detail') as HTMLElement)
      .getByText('Correct fact values')
      .closest('details') as HTMLElement
    await userEvent.clear(within(editor).getByLabelText('Notice period (days)'))
    await userEvent.click(
      within(editor).getByRole('button', { name: 'Save fact values' }),
    )

    expect(await within(editor).findByRole('alert')).toHaveTextContent(
      /is required/i,
    )
    expect(api.requests.some((request) => request.url.endsWith('/facts'))).toBe(
      false,
    )
  })

  it('explains clauses with no classification and no facts', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-1')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement

    expect(
      within(detail).getByText('No primary concept was determined for this clause.'),
    ).toBeInTheDocument()
    expect(
      within(detail).getByText('No typed facts were extracted for this clause.'),
    ).toBeInTheDocument()
  })

  it('does not offer fact editing for unknown fact schemas', async () => {
    stubContractApi({
      semantics: fixtureSemantics({
        clauses: [
          {
            id: 'cl9',
            headingNodeId: 'node-9',
            regionKind: 'CLAUSE',
            heading: '9. Data Retention',
            primaryConcept: null,
            secondaryConcepts: [],
            correctedPrimaryConcept: null,
            machineFacts: [
              {
                factSchema: 'privacy-facts',
                schemaVersion: 1,
                sourceNodeIds: [],
                values: { retentionDays: '30' },
              },
            ],
            effectiveFacts: [
              {
                factSchema: 'privacy-facts',
                schemaVersion: 1,
                sourceNodeIds: [],
                values: { retentionDays: '30' },
              },
            ],
            interpretations: [],
            corrected: false,
          },
        ],
      }),
    })
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-9')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement

    expect(within(detail).getByText('Retention days')).toBeInTheDocument()
    expect(within(detail).getByText('30')).toBeInTheDocument()
    expect(within(detail).queryByText('Correct fact values')).not.toBeInTheDocument()
  })


  it('does not leak an unsaved concept draft across clause selection', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=node-6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const editor = within(document.querySelector('.clause-detail') as HTMLElement)
      .getByText('Correct classification')
      .closest('details') as HTMLElement
    await within(editor).findByRole('option', { name: 'Governing law' })
    await userEvent.selectOptions(
      within(editor).getByLabelText('Primary concept'),
      'GOVERNING_LAW',
    )

    await userEvent.click(screen.getByText('5. Termination'))
    await screen.findByRole('heading', { name: '5. Termination' })
    const nextEditor = within(document.querySelector('.clause-detail') as HTMLElement)
      .getByText('Correct classification')
      .closest('details') as HTMLElement
    expect(within(nextEditor).getByLabelText('Primary concept')).toHaveValue(
      'TERM',
    )
  })

  it('navigates from the clause detail to the document region', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=clauses&clause=node-6',
    )

    await screen.findByRole('heading', { name: 'Clause semantics' })
    await userEvent.click(
      screen.getByRole('button', { name: 'View clause in document' }),
    )

    expect(router.state.location.search).toContain('view=document')
    expect(router.state.location.search).toContain('node=node-6')
    const target = document.querySelector('[data-node-id="node-6"]') as HTMLElement
    expect(target).toHaveClass('doc-node--selected')
    await waitFor(() => {
      expect(target).toHaveFocus()
    })
  })
})

  it('shows a clause semantic summary in the document view for a clause heading', async () => {
    stubContractApi()
    const { router } = renderRoute('/reviews/c1?node=node-6')

    const summary = await screen.findByRole('heading', { name: 'Clause semantics' })
    const panel = summary.closest('section') as HTMLElement

    expect(within(panel).getByText('Limitation of liability')).toBeInTheDocument()
    expect(
      within(panel).getByText('1 typed fact was extracted for this clause.'),
    ).toBeInTheDocument()

    await userEvent.click(
      within(panel).getByRole('button', { name: 'Open clause semantics' }),
    )
    expect(router.state.location.search).toContain('view=semantics')
    expect(router.state.location.search).toContain('section=clauses')
    expect(router.state.location.search).toContain('clause=node-6')
  })

  it('does not show a clause summary for a non-clause region', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?node=node-63')

    await screen.findByRole('heading', { level: 1, name: 'Master Services Agreement' })
    await waitFor(() => {
      expect(document.querySelector('[data-node-id="node-63"]')).toHaveClass(
        'doc-node--selected',
      )
    })
    expect(
      screen.queryByRole('heading', { name: 'Clause semantics' }),
    ).not.toBeInTheDocument()
  })

  it('keeps the document view working when no analysis exists', async () => {
    stubContractApi({ semantics: null })
    renderRoute('/reviews/c1?node=node-6')

    await screen.findByRole('heading', {
      level: 1,
      name: 'Master Services Agreement',
    })
    await waitFor(() => {
      expect(document.querySelector('[data-node-id="node-6"]')).toHaveClass(
        'doc-node--selected',
      )
    })
    expect(
      screen.queryByRole('heading', { name: 'Clause semantics' }),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'View Original' })).toBeInTheDocument()
  })

