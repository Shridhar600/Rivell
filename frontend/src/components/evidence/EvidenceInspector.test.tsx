import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter } from 'react-router'
import { RouterProvider } from 'react-router/dom'
import { AppProviders } from '../../app/AppProviders'
import { appRoutes } from '../../app/router'
import { stubContractApi } from '../contract/testFixtures'
import { fixtureBundle, fixtureTrace } from './testFixtures'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

function envelope(data: unknown) {
  return { data, status: 200, timestamp: '2026-09-24T05:00:00Z' }
}

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
    expect(screen.getByText('Effective on')).toBeInTheDocument()
    expect(screen.getByText('2026-09-24')).toBeInTheDocument()
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

  it('clears a build error when the clause changes and restores it when returning', async () => {
    const api = stubContractApi({ evidenceStatus: 409 })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    expect(await screen.findByRole('alert')).toBeInTheDocument()

    await userEvent.selectOptions(await screen.findByLabelText('Clause'), 'node-5')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(evidenceRequests(api)).toHaveLength(1)

    await userEvent.selectOptions(screen.getByLabelText('Clause'), 'node-6')
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('replaces the displayed bundle on rebuild and announces the new state', async () => {
    let builds = 0
    const api = stubContractApi({
      evidenceResponse: () => {
        builds += 1
        return jsonResponse(
          envelope(
            builds === 1
              ? fixtureBundle()
              : fixtureBundle({
                  sufficiency: 'PARTIAL',
                  degradationWarnings: ['INDEX_INCOMPLETE'],
                }),
          ),
        )
      },
      trace: fixtureTrace,
    })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    expect(await screen.findByText('Evidence complete')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Rebuild evidence' }))
    expect(await screen.findByText('Evidence partially complete')).toBeInTheDocument()
    expect(
      screen.getByText('Some precedent index entries are not currently searchable.'),
    ).toBeInTheDocument()
    expect(evidenceRequests(api)).toHaveLength(2)
  })

  it('exposes named controls and the original document', async () => {
    stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    expect(await screen.findByRole('combobox', { name: 'Clause' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Build evidence' })).toBeEnabled()
    expect(screen.getByRole('link', { name: 'Back to contract' })).toBeInTheDocument()
    const original = screen.getByRole('link', { name: 'View Original' })
    expect(original.getAttribute('href')).toContain('/versions/1/original')
  })

  it('explains an unmatched clause link instead of silently selecting another clause', async () => {
    const api = stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=unknown-node')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    expect(
      await screen.findByText(/not part of the current semantic analysis/i),
    ).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Unknown clause' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Build evidence' })).toBeDisabled()
    expect(evidenceRequests(api)).toHaveLength(0)
  })

  it('shows a pending build state and announces the result', async () => {
    stubContractApi({
      evidenceResponse: async () => {
        await new Promise((resolve) => setTimeout(resolve, 150))
        return jsonResponse(envelope(fixtureBundle()))
      },
      trace: fixtureTrace,
    })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await userEvent.click(
      await screen.findByRole('button', { name: 'Build evidence' }),
    )
    const pending = await screen.findByRole('button', { name: 'Building evidence…' })
    expect(pending).toBeDisabled()

    expect(await screen.findByText('Evidence complete')).toBeInTheDocument()
    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(
        'Evidence built. Evidence complete',
      )
    })
    expect(screen.getByRole('button', { name: 'Rebuild evidence' })).toBeInTheDocument()
  })

  it('maps a missing clause to a not-found state without server detail', async () => {
    stubContractApi({ evidenceStatus: 404 })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()

    expect(await screen.findByRole('alert')).toHaveTextContent(/could not be found/i)
    expect(screen.queryByText('server detail text')).not.toBeInTheDocument()
  })

  it('recovers when the trace request fails', async () => {
    let attempts = 0
    stubContractApi({
      evidenceBundle: fixtureBundle(),
      traceResponse: () => {
        attempts += 1
        return attempts === 1
          ? jsonResponse({ code: '500', title: 'Error', detail: 'server detail text' }, 500)
          : jsonResponse(envelope(fixtureTrace))
      },
    })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await waitFor(() => expect(evidenceRequests.length).toBeGreaterThan(0))

    await userEvent.click(screen.getByText('Retrieval details'))
    expect(
      await screen.findByText('Retrieval details could not be loaded.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('server detail text')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Rank fusion')).toBeInTheDocument()
  })

  it('gives each exact-text disclosure a distinct accessible name', async () => {
    stubContractApi({ evidenceBundle: fixtureBundle(), trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await screen.findByRole('region', { name: 'Precedents' })

    const names = screen
      .getAllByText('Show exact text')
      .map((summary) => summary.closest('summary')?.getAttribute('aria-label'))
    expect(names.length).toBeGreaterThan(1)
    expect(names.every((name) => name?.startsWith('Show exact text:'))).toBe(true)
    expect(new Set(names).size).toBe(names.length)
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
      items: fixtureBundle().items.map((item) => ({
        ...item,
        publicationId: item.publicationId === null ? null : 'pub-1',
        publicationNumber: item.publicationNumber === null ? null : 1,
      })),
    })
    const api = stubContractApi({ evidenceBundle: pinned, trace: fixtureTrace })
    renderRoute('/reviews/c1/evidence?clause=node-6')

    await screen.findByRole('heading', { name: 'Master Services Agreement' })
    await buildEvidence()
    await waitFor(() => expect(evidenceRequests(api)).toHaveLength(1))

    expect(await screen.findByText('Knowledge state: publication v1')).toBeInTheDocument()
    expect(screen.getAllByText('Published in v1').length).toBeGreaterThan(0)
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
