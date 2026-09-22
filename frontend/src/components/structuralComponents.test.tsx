import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { components } from '../api/schema'
import { OutlineTree } from './OutlineTree'
import { SourceSpanPanel } from './SourceSpanPanel'
import { StructuralRenderer } from './StructuralRenderer'

type DocumentNode = components['schemas']['NodeView']

function node(partial: Partial<DocumentNode> & { id: string }): DocumentNode {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    rawStyleId: null,
    sourceSpan: { partPath: 'word/document.xml', bodyIndex: 0 },
    ...partial,
  }
}

describe('StructuralRenderer', () => {
  it('renders headings, paragraphs, and tables with semantic markup', () => {
    const nodes = [
      node({ id: 't', nodeType: 'TITLE', text: 'Master Agreement' }),
      node({ id: 'h', nodeType: 'HEADING', headingLevel: 2, text: 'Terms' }),
      node({ id: 'p', text: 'The parties agree.' }),
      node({ id: 'tbl', nodeType: 'TABLE', ordinal: 1 }),
      node({
        id: 'row',
        parentId: 'tbl',
        nodeType: 'TABLE_ROW',
      }),
      node({
        id: 'cell',
        parentId: 'row',
        nodeType: 'TABLE_CELL',
        text: 'Fee',
      }),
    ]

    render(<StructuralRenderer nodes={nodes} selectedId={null} onSelect={() => {}} />)

    expect(
      screen.getByRole('heading', { level: 1, name: 'Master Agreement' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 2, name: 'Terms' }),
    ).toBeInTheDocument()
    expect(screen.getByText('The parties agree.')).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Fee' })).toBeInTheDocument()
  })

  it('marks the selected node and reports clicks', async () => {
    const onSelect = vi.fn()
    const nodes = [
      node({ id: 'p1', text: 'one' }),
      node({ id: 'p2', ordinal: 1, text: 'two' }),
    ]

    render(
      <StructuralRenderer nodes={nodes} selectedId="p2" onSelect={onSelect} />,
    )

    const selected = document.querySelector('[data-node-id="p2"]')
    expect(selected).toHaveClass('doc-node--selected')
    const unselected = document.querySelector('[data-node-id="p1"]')
    expect(unselected).not.toHaveClass('doc-node--selected')

    await userEvent.click(screen.getByText('one'))
    expect(onSelect).toHaveBeenCalledWith('p1')
  })
})

describe('OutlineTree', () => {
  const items = [
    { id: 't', label: 'Agreement', depth: 0 },
    { id: 'h', label: 'Definitions', depth: 1 },
  ]

  it('renders outline buttons and reports selection', async () => {
    const onSelect = vi.fn()
    render(<OutlineTree items={items} selectedId={null} onSelect={onSelect} />)

    await userEvent.click(
      screen.getByRole('button', { name: 'Definitions' }),
    )
    expect(onSelect).toHaveBeenCalledWith('h')
  })

  it('marks the current outline item with aria-current', () => {
    render(
      <OutlineTree items={items} selectedId="t" onSelect={() => {}} />,
    )
    expect(screen.getByRole('button', { name: 'Agreement' })).toHaveAttribute(
      'aria-current',
      'true',
    )
    expect(
      screen.getByRole('button', { name: 'Definitions' }),
    ).not.toHaveAttribute('aria-current')
  })

  it('renders an empty message when there is no outline', () => {
    render(<OutlineTree items={[]} selectedId={null} onSelect={() => {}} />)
    expect(screen.getByText(/no outline/i)).toBeInTheDocument()
  })
})

describe('SourceSpanPanel', () => {
  it('shows part path and applicable indices for the selected node', () => {
    render(
      <SourceSpanPanel
        label="Selected region"
        span={{
          partPath: 'word/document.xml',
          bodyIndex: 3,
          paragraphIndex: 7,
          tableIndex: null,
          rowIndex: null,
          cellIndex: null,
          runStart: null,
          runEnd: null,
        }}
      />,
    )

    const code = screen.getByText(/word\/document\.xml/)
    expect(code).toHaveTextContent('word/document.xml')
    expect(code).toHaveTextContent(/body\[\s*3\s*\]/)
    expect(code).toHaveTextContent(/paragraph\[\s*7\s*\]/)
    expect(code).not.toHaveTextContent(/table\[/)
  })

  it('renders nothing without a span', () => {
    const { container } = render(
      <SourceSpanPanel label="Selected region" span={null} />,
    )
    expect(container).toBeEmptyDOMElement()
  })
})
