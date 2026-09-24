import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { stubContractApi } from '../contract/testFixtures'
import { fixtureBundle, fixtureTrace } from './testFixtures'

function renderRoute(initialEntry: string) {
  const router = createMemoryRouter(appRoutes, { initialEntries: [initialEntry] })
  const result = render(
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { router, ...result }
}

function evidenceRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter((request) => request.url.includes('/api/retrieval/evidence'))
}

function traceRequests(api: ReturnType<typeof stubContractApi>) {
  return api.requests.filter((request) => request.url.includes('/api/retrieval/traces/'))
}

async function buildEvidence() {
  await userEvent.click(
    await screen.findByRole('button', { name: 'Build evidence' }),
  )
}

beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('evidence inspector lifecycle', () => {
  it('does not build evidence until the user asks, then renders the bundle', async () => {
    const api = stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    expect(
      await screen.findByRole('heading', { name: 'Master Services Agreement' }),
    ).toBeInTheDocument()
    expect(await screen.findByText('Clause review')).toBeInTheDocument()
    expect(await screen.findByText('Limitation of liability')).toBeInTheDocument()
    expect(evidenceRequests(api)).toHaveLength(0)
    expect(screen.queryByRole('region', { name: 'Policy' })).not.toBeInTheDocument()

    await buildEvidence()

    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(1))
    const post = evidenceRequests(api)[0]
    expect(post.method).toBe('POST')
    expect(post.body).toEqual({ contractId: 'c1', versionNumber: 1, clauseId: 'cl6' })
    expect(await screen.findByRole('region', { name: 'Policy' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rebuild evidence' })).toBeInTheDocument()
  })

  it('rebuilds only on an explicit request', async () => {
    const api = stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(1))

    await userEvent.click(screen.getByRole('button', { name: 'Rebuild evidence' }))
    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(2))
  })

  it('disables building for an unclassified clause with an honest reason', async () => {
    const api = stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-1')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    expect(await screen.findByText(/has no primary legal concept/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Build evidence' })).toBeDisabled()
    expect(evidenceRequests(api)).toHaveLength(0)
  })

  it('shows a conflict state without server detail and links to semantic analysis', async () => {
    const api = stubContractApi({ evidenceStatus: 409 })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/semantic analysis is not ready/i)
    expect(screen.queryByText('server detail text')).not.toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: /semantic analysis/i }),
    ).toHaveAttribute('href', '/reviews/c1?view=semantics&section=clauses&clause=node-6')
    expect(evidenceRequests(api)).toHaveLength(1)
  })

  it('requires semantic analysis before evidence can be built', async () => {
    stubContractApi({ semantics: null })
    renderRoute('/reviews/c1/evidence')

    expect(
      await screen.findByRole('link', { name: 'Open semantic analysis' }),
    ).toHaveAttribute('href', '/reviews/c1?view=semantics')
  })
})

describe('clause selection and cache identity', () => {
  it('does not rebuild when the clause changes and keeps each bundle cached', async () => {
    const api = stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(1))

    await userEvent.selectOptions(await screen.findByLabelText('Clause'), 'node-5')
    expect(
      await screen.findByRole('button', { name: 'Build evidence' }),
    ).toBeInTheDocument()
    expect(evidenceRequests(api)).toHaveLength(1)

    await userEvent.selectOptions(screen.getByLabelText('Clause'), 'node-6')
    expect(await screen.findByRole('region', { name: 'Policy' })).toBeInTheDocument()
    expect(evidenceRequests(api)).toHaveLength(1)
  })
})

describe('retrieval details', () => {
  it('fetches the trace only when expanded and never shows provider internals', async () => {
    const api = stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(1))

    expect(traceRequests(api)).toHaveLength(0)

    await userEvent.click(screen.getByText('Retrieval details'))

    await waitFor(() => expect(traceRequests(api)).toHaveLength(1))
    expect(await screen.findByText('Rank fusion')).toBeInTheDocument()
    expect(screen.getByText(/different legal issue/)).toBeInTheDocument()
    expect(
      screen.getByText('Some precedent index entries are not currently searchable.'),
    ).toBeInTheDocument()
    expect(screen.getByText('300 ms')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Request' })).toBeInTheDocument()
    expect(screen.getAllByText('Clause review').length).toBeGreaterThan(0)

    const text = document.body.textContent ?? ''
    expect(text).not.toMatch(/api[_ -]?key|authorization|prompt/i)
  })
})

describe('pinned and stale context', () => {
  it('shows the pinned knowledge state and does not rebuild on render', async () => {
    const pinned = fixtureBundle({
      request: { ...fixtureBundle().request, knowledgePinPublicationNumber: 1 },
    })
    const api = stubContractApi({ evidenceBundle: pinned, trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(1))

    expect(await screen.findByText('Knowledge state: publication v1')).toBeInTheDocument()
    expect(evidenceRequests(api)).toHaveLength(1)
  })

  it('notes when the contract classification changed after the build', async () => {
    const stale = fixtureBundle({
      request: { ...fixtureBundle().request, legalConcept: 'CONFIDENTIALITY' },
    })
    stubContractApi({ evidenceBundle: stale, trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()

    expect(
      await screen.findByText(/built with the classification Confidentiality/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/current classification is Limitation of liability/i),
    ).toBeInTheDocument()
  })
})
