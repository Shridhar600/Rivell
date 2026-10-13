import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppProviders } from '../../app/AppProviders'
import { fixtureSemantics, fixtureStructure, stubContractApi } from '../contract/testFixtures'
import { ClauseDetails } from './ClauseDetails'
import { ContractAnalysis } from './ContractAnalysis'

afterEach(() => vi.unstubAllGlobals())

// A refetch parses the same server state into new objects; an unsaved draft must survive it.
const fresh = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T

describe('unsaved corrections survive a background refetch', () => {
  it('keeps a typed fact value when the clause arrives again unchanged', async () => {
    stubContractApi()
    const semantics = fixtureSemantics()
    const clause = semantics.clauses.find((entry) => entry.headingNodeId === 'node-6')!
    const props = {
      nodes: fixtureStructure.nodes,
      contractId: 'c1',
      versionNumber: 1,
      editable: true,
      latestVersionNumber: 1,
      regionLabels: new Map<string, string>(),
      onNavigateToNode: () => {},
      onRefresh: () => {},
    }
    const view = render(
      <AppProviders>
        <ClauseDetails clause={clause} semantics={semantics} {...props} />
      </AppProviders>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Correct the values' }))
    const cap = screen.getByLabelText('Liability cap multiple')
    await userEvent.clear(cap)
    await userEvent.type(cap, '2')

    view.rerender(
      <AppProviders>
        <ClauseDetails clause={fresh(clause)} semantics={fresh(semantics)} {...props} />
      </AppProviders>,
    )
    expect(screen.getByLabelText('Liability cap multiple')).toHaveValue(2)
  })

  it('keeps unsaved party roles when the analysis arrives again unchanged', async () => {
    stubContractApi()
    const semantics = fixtureSemantics()
    const props = {
      contractId: 'c1',
      editable: true,
      latestVersionNumber: 1,
      regionLabels: new Map<string, string>(),
      onNavigateToNode: () => {},
      onRefresh: () => {},
    }
    const view = render(
      <AppProviders>
        <ContractAnalysis semantics={semantics} {...props} />
      </AppProviders>,
    )
    await userEvent.click(screen.getByRole('button', { name: /^Parties/ }))
    const party = screen.getByText('Pallas Cloud Services GmbH').closest('.rv-ws-ca-item') as HTMLElement
    await userEvent.click(within(party).getByRole('button', { name: 'Correct the roles' }))
    await userEvent.click(within(party).getByLabelText('Customer'))

    view.rerender(
      <AppProviders>
        <ContractAnalysis semantics={fresh(semantics)} {...props} />
      </AppProviders>,
    )
    const again = screen.getByText('Pallas Cloud Services GmbH').closest('.rv-ws-ca-item') as HTMLElement
    expect(within(again).getByLabelText('Customer')).toBeChecked()
  })
})
