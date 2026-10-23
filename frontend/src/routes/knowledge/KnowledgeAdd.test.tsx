import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { SRC, renderApp, reviewWorld, startFakeServer, type Fake } from './compileWorld'

afterEach(() => vi.unstubAllGlobals())

const docx = (name = 'Commercial MSA Playbook v4.docx') => new File(['x'], name, { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
const xlsx = (name = 'Clause Library 2026.xlsx') => new File(['x'], name, { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })

async function openDialog(path = '/knowledge/sources?add=1', world: Fake = reviewWorld()) {
  startFakeServer(world)
  const view = renderApp(path)
  const dialog = await screen.findByRole('dialog', { name: 'Add knowledge' })
  return { world, dialog, ...view }
}
const choose = (dialog: HTMLElement, file: File) => userEvent.upload(within(dialog).getByLabelText('Source file') as HTMLInputElement, file, { applyAccept: false })

describe('Add knowledge', () => {
  it('asks the reviewer to choose what the document is, and chooses nothing for them', async () => {
    const { dialog } = await openDialog()
    const group = within(dialog).getByRole('radiogroup', { name: 'What is this document?' })
    const playbook = within(group).getByRole('radio', { name: /Playbook/ })
    const clauses = within(group).getByRole('radio', { name: /Clause library/ })
    expect(playbook).not.toBeChecked()
    expect(clauses).not.toBeChecked()
    expect(within(dialog).getByText(/Rivell uses the type you choose\./)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Continue to draft' })).toBeDisabled()
  })

  it('needs a type, a file and a contract-type choice before it can start', async () => {
    const { dialog } = await openDialog()
    await choose(dialog, docx('Supply Agreement.docx'))
    expect(within(dialog).getByRole('button', { name: 'Continue to draft' })).toBeDisabled()
    await userEvent.click(within(dialog).getByRole('radio', { name: /Playbook/ }))
    expect(within(dialog).getByRole('button', { name: 'Continue to draft' })).toBeDisabled()
    expect(within(dialog).getByText('Choose which contracts this applies to.')).toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'MSA' }))
    expect(within(dialog).queryByText('Choose which contracts this applies to.')).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Continue to draft' })).toBeEnabled()
  })

  it('pre-ticks the contract types the file name suggests, and only as a suggestion', async () => {
    const { dialog } = await openDialog()
    await choose(dialog, docx('Mutual NDA.docx'))
    expect(within(dialog).getByRole('checkbox', { name: 'NDA' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'Mutual NDA' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'One-way NDA' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'MSA' })).not.toBeChecked()

    // Until the reviewer touches the choices, a new file replaces the suggestion.
    await choose(dialog, docx('Master Services Agreement.docx'))
    expect(within(dialog).getByRole('checkbox', { name: 'MSA' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'NDA' })).not.toBeChecked()

    // A choice the reviewer made is not overwritten by a later file name.
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'DPA' }))
    await choose(dialog, docx('Mutual NDA.docx'))
    expect(within(dialog).getByRole('checkbox', { name: 'MSA' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'DPA' })).toBeChecked()
    expect(within(dialog).getByRole('checkbox', { name: 'NDA' })).not.toBeChecked()
  })

  it('treats "All contract types" and a named type as one choice', async () => {
    const { dialog } = await openDialog()
    await choose(dialog, docx('Supply Agreement.docx'))
    const all = within(dialog).getByRole('checkbox', { name: 'All contract types' })
    const msa = within(dialog).getByRole('checkbox', { name: 'MSA' })
    await userEvent.click(all)
    expect(all).toBeChecked()
    expect(msa).not.toBeChecked()
    await userEvent.click(msa)
    expect(all).not.toBeChecked()
    expect(msa).toBeChecked()
  })

  it('suggests nothing when the file name names no contract type', async () => {
    const { dialog } = await openDialog()
    await choose(dialog, docx('Supply Agreement.docx'))
    expect(within(dialog).getAllByRole('checkbox').every((box) => !(box as HTMLInputElement).checked)).toBe(true)
  })

  it('offers the type the page is about, and still lets the reviewer change it', async () => {
    const { dialog } = await openDialog('/knowledge/playbooks?add=playbook')
    expect(within(dialog).getByRole('radio', { name: /Playbook/ })).toBeChecked()
    await userEvent.click(within(dialog).getByRole('radio', { name: /Clause library/ }))
    expect(within(dialog).getByRole('radio', { name: /Clause library/ })).toBeChecked()
    expect(within(dialog).getByRole('radio', { name: /Playbook/ })).not.toBeChecked()
  })

  it('presets the clause library from the clause library page', async () => {
    const { dialog } = await openDialog('/knowledge/clauses?add=clause-library')
    expect(within(dialog).getByRole('radio', { name: /Clause library/ })).toBeChecked()
  })

  it('accepts DOCX and XLSX only, and says PDF is not supported', async () => {
    const { dialog, world } = await openDialog()
    const input = within(dialog).getByLabelText('Source file') as HTMLInputElement
    expect(input.accept).toBe('.docx,.xlsx')
    expect(within(dialog).getByText('File (Word or Excel)')).toBeInTheDocument()
    expect(dialog.textContent).not.toMatch(/DOCX or PDF|PDF or DOCX/i)

    await choose(dialog, new File(['x'], 'Playbook.pdf', { type: 'application/pdf' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Choose a Word or Excel file. PDF is not supported.')
    await userEvent.click(within(dialog).getByRole('radio', { name: /Playbook/ }))
    expect(within(dialog).getByRole('button', { name: 'Continue to draft' })).toBeDisabled()
    expect(world.uploads).toHaveLength(0)

    await choose(dialog, xlsx())
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument()
    expect(within(dialog).getByText('Clause Library 2026.xlsx')).toBeInTheDocument()
  })

  it('has no sample-file control, because nothing real stands behind one', async () => {
    const { dialog } = await openDialog()
    expect(within(dialog).queryByText(/sample file/i)).not.toBeInTheDocument()
  })

  it('never switches the authority class by looking at the file', async () => {
    const { dialog, world } = await openDialog()
    await userEvent.click(within(dialog).getByRole('radio', { name: /Playbook/ }))
    await choose(dialog, xlsx('Clause Library 2026.xlsx'))
    // The file name says clause library. The choice stays Playbook, and no warning second-guesses it.
    expect(within(dialog).getByRole('radio', { name: /Playbook/ })).toBeChecked()
    expect(within(dialog).queryByText(/looks like/i)).not.toBeInTheDocument()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'MSA' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    await waitFor(() => expect(world.uploads).toHaveLength(1))
    expect(world.uploads[0]?.artifactType).toBe('PLAYBOOK')
  })

  it('uploads the file with the chosen type and goes to the compilation of the source it created', async () => {
    const world = reviewWorld()
    world.uploadResult = { id: 'created-7', status: 'EXTRACTED' }
    world.artifacts = [...world.artifacts, { ...world.artifacts[0]!, id: 'created-7', filename: 'Commercial MSA Playbook v4.docx' }]
    world.candidates['created-7'] = []
    const { dialog, router } = await openDialog('/knowledge/sources?add=1', world)
    await userEvent.click(within(dialog).getByRole('radio', { name: /Playbook/ }))
    await choose(dialog, docx())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/knowledge/compilations/created-7'))
    expect(world.uploads).toHaveLength(1)
    expect(world.uploads[0]?.artifactType).toBe('PLAYBOOK')
  })

  it('sends the chosen contract types with the upload', async () => {
    const { dialog, world } = await openDialog('/knowledge/playbooks?add=playbook')
    await choose(dialog, docx('Master Services Agreement.docx'))
    expect(within(dialog).getByRole('checkbox', { name: 'MSA' })).toBeChecked()
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'DPA' }))
    await userEvent.click(within(dialog).getByText('Where this applies (optional)'))
    await userEvent.type(within(dialog).getByLabelText('Jurisdiction'), 'ENGLAND_WALES')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    await waitFor(() => expect(world.uploads).toHaveLength(1))
    expect(world.uploads[0]?.query).toMatchObject({
      artifactType: 'PLAYBOOK',
      declaredContractTypes: 'MSA,DPA',
      jurisdiction: 'ENGLAND_WALES',
    })
  })

  it('sends no declared types when All contract types is chosen', async () => {
    const { dialog, world } = await openDialog('/knowledge/playbooks?add=playbook')
    await choose(dialog, docx('Supply Agreement.docx'))
    await userEvent.click(within(dialog).getByRole('checkbox', { name: 'All contract types' }))
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    await waitFor(() => expect(world.uploads).toHaveLength(1))
    expect(world.uploads[0]?.query.declaredContractTypes).toBeUndefined()
  })

  it('refuses an effective period that ends before it starts', async () => {
    const { dialog, world } = await openDialog('/knowledge/playbooks?add=playbook')
    await userEvent.click(within(dialog).getByText('Where this applies (optional)'))
    await userEvent.type(within(dialog).getByLabelText('Effective from'), '2026-12-01')
    await userEvent.type(within(dialog).getByLabelText('Effective to'), '2026-01-01')
    await choose(dialog, docx())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Effective from must not be after effective to.')
    expect(world.uploads).toHaveLength(0)
  })

  it('shows that the server is working, with no percentage, stage or estimate, and cannot be dismissed meanwhile', async () => {
    let release: () => void = () => undefined
    const world = reviewWorld()
    world.holdUpload = new Promise<void>((resolve) => (release = resolve))
    world.uploadResult = { id: 'created-8', status: 'EXTRACTED' }
    world.artifacts = [...world.artifacts, { ...world.artifacts[0]!, id: 'created-8' }]
    world.candidates['created-8'] = []
    const { dialog } = await openDialog('/knowledge/playbooks?add=playbook', world)
    await choose(dialog, docx())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    expect(await within(dialog).findByRole('status')).toHaveTextContent('Saving and reading the file. This can take a moment.')
    expect(dialog.textContent).not.toMatch(/\d+\s?%|ETA|remaining|step \d/i)
    expect(within(dialog).getByRole('button', { name: 'Reading…' })).toBeDisabled()
    expect(within(dialog).getByRole('button', { name: 'Cancel' })).toBeDisabled()
    await userEvent.keyboard('{Escape}')
    expect(screen.getByRole('dialog', { name: 'Add knowledge' })).toBeInTheDocument()
    release()
    await waitFor(() => expect(screen.queryByRole('dialog', { name: 'Add knowledge' })).not.toBeInTheDocument())
  })

  it('says an upload failure plainly, without claiming nothing was stored, and keeps the file for another try', async () => {
    const world = reviewWorld()
    world.uploadResult = { status: 500 }
    const { dialog, router } = await openDialog('/knowledge/playbooks?add=playbook', world)
    await choose(dialog, docx())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent(/Rivell could not process this file/)
    expect(alert).toHaveTextContent(/appears in Sources as a failed source/)
    expect(alert.textContent).not.toMatch(/Nothing was changed/)
    expect(alert.textContent).not.toMatch(/Exception|stack|at \w+\./)
    expect(router.state.location.pathname).toBe('/knowledge/playbooks')
    expect(within(dialog).getByText('Commercial MSA Playbook v4.docx')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Try again' })).toBeEnabled()
  })

  it('goes to the compilation even when extraction failed, which then says so and publishes nothing', async () => {
    const world = reviewWorld()
    world.uploadResult = { id: 'bad-1', status: 'FAILED' }
    const { dialog, router } = await openDialog('/knowledge/playbooks?add=playbook', world)
    await choose(dialog, docx())
    await userEvent.click(within(dialog).getByRole('button', { name: 'Continue to draft' }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/knowledge/compilations/bad-1'))
    expect(await screen.findByRole('heading', { name: 'Rivell could not read this file' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Publish/ })).not.toBeInTheDocument()
  })

  it('closes with Cancel or Escape and leaves the page where it was', async () => {
    const { dialog, router } = await openDialog('/knowledge/playbooks?add=playbook')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(router.state.location.pathname).toBe('/knowledge/playbooks')
    expect(router.state.location.search).toBe('')
  })

  it('shows no identity, no search and no organization', async () => {
    const { dialog } = await openDialog()
    expect(dialog.textContent).not.toMatch(/uploaded by|Northgate|Priya/i)
    expect(SRC).toBe('src1')
  })
})
