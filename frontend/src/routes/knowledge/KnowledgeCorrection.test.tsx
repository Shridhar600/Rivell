import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { candidate } from '../../test/knowledgeFixtures'
import { SRC, renderApp, requests, reviewWorld, startFakeServer, type Fake } from './compileWorld'

afterEach(() => vi.unstubAllGlobals())

async function openEdit(id: string, world: Fake = reviewWorld()) {
  startFakeServer(world)
  const view = renderApp(`/knowledge/compilations/${SRC}?candidate=${id}`)
  await screen.findByRole('heading', { level: 1 })
  await userEvent.click(within(decisionBar()).getByRole('button', { name: /Edit/ }))
  const form = await screen.findByRole('form', { name: 'Correct the structured candidate' })
  return { world, form, ...view }
}
const decisionBar = () => document.querySelector('.rv-kc-decide') as HTMLElement
const save = () => userEvent.click(within(decisionBar()).getByRole('button', { name: 'Save correction' }))
const editCalls = (world: Fake) => requests(world, 'POST', /\/edit$/)
const conceptCalls = (world: Fake) => requests(world, 'POST', /\/concept$/)

describe('candidate correction', () => {
  it('offers only the fields the backend lets a reviewer change', async () => {
    const { form } = await openEdit('uncertain')
    for (const label of ['Legal concept', 'Escalate', 'Counsel approval', 'Wording', 'Contract types', 'Jurisdiction', 'Effective from', 'Review note, optional']) {
      expect(within(form).getByLabelText(label)).toBeInTheDocument()
    }
    expect(within(form).getByRole('group', { name: 'Position' })).toBeInTheDocument()
    // There is no control for anything the backend would ignore.
    expect(within(form).queryByLabelText(/^(Title|Label|Concept key|Candidate type)$/i)).not.toBeInTheDocument()
  })

  it('sends wording to the edit endpoint and leaves the legal concept endpoint alone', async () => {
    const { world, form } = await openEdit('uncertain')
    const wording = within(form).getByLabelText('Wording')
    await userEvent.clear(wording)
    await userEvent.type(wording, 'Mutual, capped at the liability cap.')
    await save()
    await waitFor(() => expect(editCalls(world)).toHaveLength(1))
    expect(editCalls(world)[0]?.body).toEqual({ contentText: 'Mutual, capped at the liability cap.' })
    expect(conceptCalls(world)).toHaveLength(0)
  })

  it('shows the correction as the effective value, with the machine value kept in history', async () => {
    const { form } = await openEdit('uncertain')
    const wording = within(form).getByLabelText('Wording')
    await userEvent.clear(wording)
    await userEvent.type(wording, 'Mutual, capped.')
    await save()
    const card = await screen.findByRole('region', { name: 'Structured interpretation' })
    await within(card).findByText('Corrected by you')
    expect(within(card).getByText('Mutual, capped.')).toBeInTheDocument()
    expect(within(card).getByText('Rivell extracted: Mutual and capped.')).toBeInTheDocument()
    expect(within(card).getByText(/extracted values remain in history/)).toBeInTheDocument()
    // A correction is not an approval: the corrected value still has to be approved.
    expect(screen.getByText(/Corrected. Approve the corrected value to include it in the publication/)).toBeInTheDocument()
    expect(within(decisionBar()).getByRole('button', { name: 'Approve correction' })).toBeEnabled()
    expect(within(screen.getByRole('navigation', { name: 'Candidate list' })).getByRole('button', { name: /Indemnification/ })).toHaveTextContent('Corrected')
  })

  it('does not change the uploaded source when a candidate is corrected', async () => {
    const { world, form } = await openEdit('uncertain')
    await userEvent.type(within(form).getByLabelText('Wording'), ' Edited.')
    await save()
    await waitFor(() => expect(editCalls(world)).toHaveLength(1))
    // The only writes are to the candidate. The source structure is never written.
    expect(world.calls.filter((call) => call.method === 'POST').every((call) => /\/candidates\//.test(call.path))).toBe(true)
    const source = screen.getByRole('region', { name: 'Source passage' })
    expect(within(source).getByText('Mutual and capped.')).toBeInTheDocument()
  })

  it('corrects the applicability scope', async () => {
    const { world, form } = await openEdit('uncertain')
    await userEvent.type(within(form).getByLabelText('Contract types'), 'MSA, NDA')
    await userEvent.type(within(form).getByLabelText('Jurisdiction'), 'ENGLAND_WALES')
    await save()
    await waitFor(() => expect(editCalls(world)).toHaveLength(1))
    expect(editCalls(world)[0]?.body).toMatchObject({
      scope: { declaredContractTypes: ['MSA', 'NDA'], jurisdiction: 'ENGLAND_WALES' },
    })
    expect(await screen.findByText('MSA, NDA')).toBeInTheDocument()
  })

  it('corrects the position roles, the escalation trigger and counsel approval together', async () => {
    const { world, form } = await openEdit('uncertain')
    await userEvent.click(within(form).getByRole('checkbox', { name: 'Fallback' }))
    await userEvent.clear(within(form).getByLabelText('Escalate'))
    await userEvent.type(within(form).getByLabelText('Escalate'), 'Any uncapped or unlimited indemnity')
    await userEvent.selectOptions(within(form).getByLabelText('Counsel approval'), 'true')
    await save()
    await waitFor(() => expect(editCalls(world)).toHaveLength(1))
    expect(editCalls(world)[0]?.body).toEqual({
      semanticFields: { positions: ['PREFERRED', 'FALLBACK'], escalationTrigger: 'Any uncapped or unlimited indemnity', requiresApproval: true },
    })
  })

  it('corrects the legal concept through its own endpoint, and only that', async () => {
    const { world, form } = await openEdit('uncertain')
    const group = await within(form).findByRole('group', { name: 'Liability and indemnity' })
    expect(within(group).getByRole('option', { name: 'Indemnification' })).toBeInTheDocument()
    await userEvent.selectOptions(within(form).getByLabelText('Legal concept'), 'INDEMNIFICATION')
    await save()
    await waitFor(() => expect(conceptCalls(world)).toHaveLength(1))
    expect(conceptCalls(world)[0]?.body).toEqual({ primary: 'INDEMNIFICATION' })
    expect(editCalls(world)).toHaveLength(0)
    expect(await screen.findByText('Indemnification', { selector: 'dd' })).toBeInTheDocument()
  })

  it('clears the legal concept by sending an empty value, and keeps what Rivell classified', async () => {
    const world = reviewWorld()
    world.candidates[SRC] = (world.candidates[SRC] ?? []).map((entry) =>
      entry.id === 'uncertain' ? { ...entry, legalConceptKey: 'GOVERNING_LAW', effectiveConceptKey: 'GOVERNING_LAW' } : entry,
    )
    const { form } = await openEdit('uncertain', world)
    await userEvent.selectOptions(within(form).getByLabelText('Legal concept'), '')
    await save()
    await waitFor(() => expect(conceptCalls(world)).toHaveLength(1))
    expect(conceptCalls(world)[0]?.body).toEqual({ primary: '' })
    expect(await screen.findByText('Rivell classified: Governing law')).toBeInTheDocument()
  })

  it('makes two requests when both a value and the concept change, and says so if the second fails', async () => {
    const world = reviewWorld()
    world.fail['POST concept uncertain'] = 400
    const { form } = await openEdit('uncertain', world)
    await userEvent.type(within(form).getByLabelText('Wording'), ' Edited.')
    await userEvent.selectOptions(within(form).getByLabelText('Legal concept'), 'INDEMNIFICATION')
    await save()
    const alert = await within(form).findByRole('alert')
    expect(alert).toHaveTextContent(/Your other corrections were saved, but the legal concept was not/)
    expect(alert).toHaveTextContent(/not one Rivell knows/)
    expect(editCalls(world)).toHaveLength(1)
    expect(conceptCalls(world)).toHaveLength(1)
  })

  it('asks for a change instead of sending an empty correction', async () => {
    const { world, form } = await openEdit('uncertain')
    await save()
    expect(await within(form).findByRole('alert')).toHaveTextContent('Change at least one value before saving.')
    expect(editCalls(world)).toHaveLength(0)
    expect(conceptCalls(world)).toHaveLength(0)
  })

  it('cancels without sending anything', async () => {
    const { world, form } = await openEdit('uncertain')
    await userEvent.type(within(form).getByLabelText('Wording'), ' Edited.')
    await userEvent.click(within(decisionBar()).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('form', { name: 'Correct the structured candidate' })).not.toBeInTheDocument()
    expect(editCalls(world)).toHaveLength(0)
  })

  it('shows what the server refused and keeps the form', async () => {
    const world = reviewWorld()
    world.fail['POST edit uncertain'] = 409
    const { form } = await openEdit('uncertain', world)
    await userEvent.type(within(form).getByLabelText('Wording'), ' Edited.')
    await save()
    expect(await within(form).findByRole('alert')).toHaveTextContent(/does not apply in the current state/i)
    expect(screen.getByRole('form', { name: 'Correct the structured candidate' })).toBeInTheDocument()
  })

  it('brings a rejected candidate back for review only by correcting it', async () => {
    const world = reviewWorld()
    world.candidates[SRC] = (world.candidates[SRC] ?? []).map((entry) => (entry.id === 'uncertain' ? { ...entry, reviewStatus: 'REJECTED' as const } : entry))
    const { form } = await openEdit('uncertain', world)
    await userEvent.type(within(form).getByLabelText('Wording'), ' Reconsidered.')
    await save()
    await screen.findByText(/Corrected. Approve the corrected value/)
    expect(within(screen.getByRole('navigation', { name: 'Candidate list' })).getByRole('button', { name: /Indemnification/ })).toHaveTextContent('Corrected')
  })

  it('edits a clause variant by its role and its wording, and shows the spreadsheet columns', async () => {
    const world = reviewWorld()
    world.artifacts = [{ ...world.artifacts[0]!, artifactType: 'CLAUSE_LIBRARY', filename: 'Clauses.xlsx' }]
    world.candidates[SRC] = [
      candidate({
        id: 'v1',
        artifactId: SRC,
        extractionUnitId: 'u-uncertain',
        reviewStatus: 'PENDING',
        reviewCategory: 'ELEVATED',
        reviewReasons: ['SCOPE_NARROWING_PROPOSED'],
        candidateType: 'CLAUSE_VARIANT',
        rawLabel: 'Mutual, standard',
        contentText: 'Mutual, standard | Capped at 12 months',
        payloadColumns: { Variant: 'Mutual, standard', Wording: 'Capped at 12 months' },
        positions: [],
        variantRole: 'PREFERRED',
      }),
    ]
    const { form } = await openEdit('v1', world)
    expect(within(form).getByLabelText('Role')).toHaveValue('PREFERRED')
    expect(within(form).getByLabelText('Variant')).toHaveValue('Mutual, standard')
    expect(within(form).getByText(/are published as the corrected wording/)).toBeInTheDocument()
    await userEvent.selectOptions(within(form).getByLabelText('Role'), 'FALLBACK')
    await save()
    await waitFor(() => expect(editCalls(world)).toHaveLength(1))
    expect(editCalls(world)[0]?.body).toEqual({ semanticFields: { variantRole: 'FALLBACK' } })
  })
})
