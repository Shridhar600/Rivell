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
      '/reviews/c1?view=semantics&section=clauses&clause=cl6',
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
    expect(router.state.location.search).toContain('clause=cl5')
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
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl6')

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
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl5')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const factList = document.querySelector('.fact-list') as HTMLElement

    expect(within(factList).getByText('Termination notice')).toBeInTheDocument()
    expect(within(factList).getByText('60 days')).toBeInTheDocument()
    expect(within(factList).getByText('Either party')).toBeInTheDocument()
  })

  it('corrects the primary concept and keeps the other classifications', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement
    const editor = within(detail)
      .getByText('Correct classification')
      .closest('details') as HTMLElement

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
    const request = api.requests.find((item) => item.url.endsWith('/concepts'))
    expect(request?.body).toEqual({ primary: 'INDEMNIFICATION' })

    await waitFor(() => {
      expect(
        within(detail).getByText(/classification updated/i),
      ).toBeInTheDocument()
    })
    expect(within(detail).getByText('Corrected')).toBeInTheDocument()
  })

  it('corrects fact values and keeps the machine interpretation visible', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl5')

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
    expect(
      within(detail).getByText(/Rivell originally interpreted: 60 days/),
    ).toBeInTheDocument()
  })

  it('validates fact values before sending them', async () => {
    const api = stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl6')

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

  it('explains clauses with no classification and no facts', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl1')

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
            heading: '9. Data Retention',
            contentText: '9. Data Retention\nRecords are kept for 30 days.',
            primaryConcept: null,
            secondaryConcepts: [],
            correctedPrimaryConcept: null,
            machineFacts: [
              {
                factSchema: 'privacy-facts',
                schemaVersion: 1,
                values: { retentionDays: '30' },
              },
            ],
            effectiveFacts: [
              {
                factSchema: 'privacy-facts',
                schemaVersion: 1,
                values: { retentionDays: '30' },
              },
            ],
            interpretations: [],
            corrected: false,
          },
        ],
      }),
    })
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl9')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const detail = document.querySelector('.clause-detail') as HTMLElement

    expect(within(detail).getByText('Retention days')).toBeInTheDocument()
    expect(within(detail).getByText('30')).toBeInTheDocument()
    expect(within(detail).queryByText('Correct fact values')).not.toBeInTheDocument()
  })


  it('does not leak an unsaved concept draft across clause selection', async () => {
    stubContractApi()
    renderRoute('/reviews/c1?view=semantics&section=clauses&clause=cl6')

    await screen.findByRole('heading', { name: 'Clause semantics' })
    const editor = within(document.querySelector('.clause-detail') as HTMLElement)
      .getByText('Correct classification')
      .closest('details') as HTMLElement
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
      'TERM_TERMINATION',
    )
  })

  it('navigates from the clause detail to the document region', async () => {
    stubContractApi()
    const { router } = renderRoute(
      '/reviews/c1?view=semantics&section=clauses&clause=cl6',
    )

    await screen.findByRole('heading', { name: 'Clause semantics' })
    await userEvent.click(
      screen.getByRole('button', { name: 'View clause in document' }),
    )

    expect(router.state.location.search).toContain('view=document')
    expect(router.state.location.search).toContain('node=node-6')
    expect(document.querySelector('[data-node-id="node-6"]')).toHaveClass(
      'doc-node--selected',
    )
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
    expect(router.state.location.search).toContain('clause=cl6')
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

