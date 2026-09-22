import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { components } from '../api/schema'
import type { OutlineItem, StructureNode } from '../lib/documentTree'
import { OutlineTree } from './OutlineTree'
import { SourceSpanPanel } from './SourceSpanPanel'
import { StructuralRenderer } from './StructuralRenderer'

type SourceSpan = components['schemas']['SourceSpanView']

function span(bodyIndex: number, partial: Partial<SourceSpan> = {}): SourceSpan {
  return {
    partPath: 'word/document.xml',
    bodyIndex,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    cellIndex: null,
    runStart: null,
    runEnd: null,
    ...partial,
  }
}

function node(
  partial: Partial<StructureNode> & { id: string },
): StructureNode {
  return {
    parentId: null,
    nodeType: 'PARAGRAPH',
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    rawStyleId: null,
    sourceSpan: span(0),
    ...partial,
  }
}

const realisticDocument: StructureNode[] = [
  node({ id: 'body', nodeType: 'BODY', sourceSpan: span(0) }),
  node({
    id: 't',
    parentId: 'body',
    nodeType: 'TITLE',
    text: 'Master Agreement',
    sourceSpan: span(0),
  }),
  node({
    id: 'h1',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 1,
    text: 'Definitions',
    sourceSpan: span(1),
  }),
  node({
    id: 'h2',
    parentId: 'body',
    nodeType: 'HEADING',
    headingLevel: 2,
    text: 'Terms',
    sourceSpan: span(2),
  }),
  node({
    id: 'p',
    parentId: 'body',
    text: 'The parties agree.',
    sourceSpan: span(3),
  }),
  node({
    id: 'tbl',
    parentId: 'body',
    nodeType: 'TABLE',
    sourceSpan: span(4, { tableIndex: 0 }),
  }),
  node({
    id: 'row',
    parentId: 'tbl',
    nodeType: 'TABLE_ROW',
    sourceSpan: span(4, { tableIndex: 0, rowIndex: 0 }),
  }),
  node({
    id: 'cell',
    parentId: 'row',
    nodeType: 'TABLE_CELL',
    text: 'Fee',
    sourceSpan: span(4, { tableIndex: 0, rowIndex: 0, cellIndex: 0 }),
  }),
]

describe('StructuralRenderer', () => {
  it('renders headings, paragraphs and tables with a document-relative hierarchy', () => {
    render(
      <StructuralRenderer
        nodes={realisticDocument}
        selectedId={null}
        onSelect={() => {}}
      />,
    )

    expect(
      screen.getByRole('heading', { level: 2, name: 'Master Agreement' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 3, name: 'Definitions' }),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 4, name: 'Terms' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    expect(screen.getByText('The parties agree.')).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'Fee' })).toBeInTheDocument()
  })

  it('renders numbering labels from the source document', () => {
    render(
      <StructuralRenderer
        nodes={[
          node({
            id: 'h',
            nodeType: 'HEADING',
            headingLevel: 1,
            numberingLabel: '1.2',
            text: 'Confidentiality',
          }),
        ]}
        selectedId={null}
        onSelect={() => {}}
      />,
    )

    expect(
      screen.getByRole('heading', { level: 3, name: '1.2 Confidentiality' }),
    ).toBeInTheDocument()
  })

  it('selects exactly the clicked paragraph inside the body, not the body', async () => {
    const onSelect = vi.fn()
    render(
      <StructuralRenderer
        nodes={realisticDocument}
        selectedId={null}
        onSelect={onSelect}
      />,
    )

    await userEvent.click(screen.getByText('The parties agree.'))

    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenCalledWith('p')
  })

  it('selects the innermost table cell exactly once', async () => {
    const onSelect = vi.fn()
    render(
      <StructuralRenderer
        nodes={realisticDocument}
        selectedId={null}
        onSelect={onSelect}
      />,
    )

    await userEvent.click(screen.getByRole('cell', { name: 'Fee' }))

    expect(onSelect).toHaveBeenCalledTimes(1)
    expect(onSelect).toHaveBeenCalledWith('cell')
  })

  it('preserves text and children of unknown node types', () => {
    render(
      <StructuralRenderer
        nodes={[
          node({
            id: 'future',
            nodeType: 'FUTURE_BLOCK',
            text: 'future block text',
          }),
          node({
            id: 'child',
            parentId: 'future',
            text: 'child paragraph',
            sourceSpan: span(0),
          }),
        ]}
        selectedId={null}
        onSelect={() => {}}
      />,
    )

    expect(screen.getByText(/future block text/)).toBeInTheDocument()
    expect(screen.getByText('child paragraph')).toBeInTheDocument()
  })

  it('marks the selected node', () => {
    render(
      <StructuralRenderer
        nodes={realisticDocument}
        selectedId="h2"
        onSelect={() => {}}
      />,
    )

    expect(document.querySelector('[data-node-id="h2"]')).toHaveClass(
      'doc-node--selected',
    )
    expect(document.querySelector('[data-node-id="h1"]')).not.toHaveClass(
      'doc-node--selected',
    )
  })

  it('keeps the document to one tab stop and moves focus with arrow keys', async () => {
    const onSelect = vi.fn()
    render(
      <StructuralRenderer
        nodes={[
          node({ id: 'p1', text: 'one', sourceSpan: span(1) }),
          node({ id: 'p2', text: 'two', sourceSpan: span(2) }),
        ]}
        selectedId={null}
        onSelect={onSelect}
      />,
    )

    const first = document.querySelector<HTMLElement>('[data-node-id="p1"]')
    const second = document.querySelector<HTMLElement>('[data-node-id="p2"]')
    expect(first).toHaveAttribute('tabindex', '0')
    expect(second).toHaveAttribute('tabindex', '-1')

    await userEvent.tab()
    expect(first).toHaveFocus()

    await userEvent.keyboard('{ArrowDown}')
    expect(second).toHaveFocus()
    expect(onSelect).not.toHaveBeenCalled()

    await userEvent.keyboard('{Enter}')
    expect(onSelect).toHaveBeenCalledWith('p2')
  })

  it('moves focus to the last and first selectable node with End and Home', async () => {
    render(
      <StructuralRenderer
        nodes={[
          node({ id: 'p1', text: 'one', sourceSpan: span(1) }),
          node({ id: 'p2', text: 'two', sourceSpan: span(2) }),
        ]}
        selectedId={null}
        onSelect={() => {}}
      />,
    )

    await userEvent.tab()
    await userEvent.keyboard('{End}')
    expect(document.querySelector('[data-node-id="p2"]')).toHaveFocus()

    await userEvent.keyboard('{Home}')
    expect(document.querySelector('[data-node-id="p1"]')).toHaveFocus()
  })
})

describe('OutlineTree', () => {
  const items: OutlineItem[] = [
    {
      id: 't',
      label: 'Agreement',
      level: 0,
      children: [
        {
          id: 'h',
          label: 'Definitions',
          level: 1,
          children: [{ id: 's', label: 'Terms', level: 2, children: [] }],
        },
      ],
    },
  ]

  it('renders nested semantic lists that convey hierarchy', () => {
    render(<OutlineTree items={items} selectedId={null} onSelect={() => {}} />)

    const nav = screen.getByRole('navigation', { name: /document outline/i })
    expect(within(nav).getAllByRole('list')).toHaveLength(3)
    expect(within(nav).getAllByRole('listitem')).toHaveLength(3)

    const definitionsItem = within(nav)
      .getByRole('button', { name: 'Definitions' })
      .closest('li')
    expect(definitionsItem).not.toBeNull()
    expect(
      within(definitionsItem as HTMLElement).getByRole('button', {
        name: 'Terms',
      }),
    ).toBeInTheDocument()
  })

  it('reports selection and marks the current item', async () => {
    const onSelect = vi.fn()
    render(
      <OutlineTree items={items} selectedId="h" onSelect={onSelect} />,
    )

    const current = screen.getByRole('button', { name: 'Definitions' })
    expect(current).toHaveAttribute('aria-current', 'true')

    await userEvent.click(screen.getByRole('button', { name: 'Terms' }))
    expect(onSelect).toHaveBeenCalledWith('s')
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

    const panel = screen.getByRole('status')
    expect(panel).toHaveTextContent('word/document.xml')
    expect(panel).toHaveTextContent(/body\[\s*3\s*\]/)
    expect(panel).toHaveTextContent(/paragraph\[\s*7\s*\]/)
    expect(panel).not.toHaveTextContent(/table\[/)
  })

  it('renders nothing without a span', () => {
    const { container } = render(
      <SourceSpanPanel label="Selected region" span={null} />,
    )
    expect(container).toBeEmptyDOMElement()
  })
})
