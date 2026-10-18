import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { ClauseMark, FindingDetail, FindingSummary } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import type { AddFindingInput, HumanDecision } from '../../api/workflow'
import type { StructureNode, TreeNode } from '../../lib/documentTree'
import { clauseMarkLabel, clauseMarksLegend, type ShownClauseMark } from '../../lib/reviews'
import {
  clauseBody,
  executableChange,
  isActionable,
  isResolved,
  nodeLabel,
  nodeText,
  proposedChangeType,
  splitOnTerm,
  topLevelNodes,
} from '../../lib/workspace'
import { Button } from '../ui/Button'
import { ErrorState, LoadingState } from '../ui/State'

export type DocMode = 'changes' | 'orig' | 'final'

const MODES: { mode: DocMode; label: (final: boolean) => string }[] = [
  { mode: 'changes', label: () => 'Changes' },
  { mode: 'orig', label: () => 'Original' },
  { mode: 'final', label: () => 'Preview' },
]

interface Props {
  title: string
  versionNumber: number | null
  nodes: StructureNode[] | undefined
  isPending: boolean
  isError: boolean
  onRetry: () => void
  mode: DocMode
  onMode: (mode: DocMode) => void
  term: string
  onClearTerm: () => void
  findings: FindingSummary[]
  decisions: ReadonlyMap<string, HumanDecision>
  selectedId: string | null
  /** The selected finding's detail, for previewing its proposed wording. */
  selected: FindingDetail | undefined
  onSelect: (findingId: string) => void
  isFinalized: boolean
  /** Notes above the page: a pinned older version, limited coverage, analysis warnings. */
  notes: ReactNode
  /** An evidence jump target. A new object re-reveals the same node, so repeated jumps work. */
  focusTarget?: { nodeId: string } | null
  /** What the review did with each planned clause; null until an analysis completed. */
  marks?: ReadonlyMap<string, ClauseMark> | null
  /** Adds a finding Rivell missed on a clause; null when the user cannot add one now. */
  onAddFinding?: ((input: AddFindingInput) => Promise<void>) | null
  /** Opens a clause's semantics in place; given with the headings Rivell analysed. */
  onClauseDetails?: ((headingNodeId: string) => void) | null
  analysedHeadings?: ReadonlySet<string>
  /** Opens the contract-level analysis (parties, defined terms, references) in place. */
  onContractAnalysis?: (() => void) | null
}

const NOTE_LIMIT = 2000

interface Ctx {
  /** Clause regions are cut only at the document's top level; nested content is drawn plainly. */
  regionsOpen: boolean
  mode: DocMode
  term: string
  regions: Map<string, FindingSummary[]>
  decisions: ReadonlyMap<string, HumanDecision>
  selectedId: string | null
  selected: FindingDetail | undefined
  onSelect: (findingId: string) => void
  focusNodeId: string | null
  marks: ReadonlyMap<string, ClauseMark> | null
  onAddFinding: ((input: AddFindingInput) => Promise<void>) | null
  onClauseDetails: ((headingNodeId: string) => void) | null
  analysedHeadings: ReadonlySet<string>
}

/** A heading the review did not plan (a title, the signature block) is outside the review. */
function markOf(ctx: Ctx, headingId: string): ShownClauseMark {
  return ctx.marks?.get(headingId) ?? 'NOT_PLANNED'
}

/** Marks and the add action belong to the working views, not to the preview of the result. */
function showsMarks(ctx: Ctx): boolean {
  return ctx.marks !== null && ctx.mode !== 'final'
}

/**
 * What Rivell did with this clause, and the way to add what it missed. Kept apart from the clause
 * text: a click here never selects one of the clause's findings.
 */
function ClauseTools({ heading, ctx }: { heading: TreeNode; ctx: Ctx }) {
  const [open, setOpen] = useState(false)
  const [severity, setSeverity] = useState<AddFindingInput['severity']>('MEDIUM')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [pending, setPending] = useState(false)
  const mark = markOf(ctx, heading.id)
  // a finding belongs to a clause the review planned; the server refuses any other heading
  const add = mark === 'NOT_PLANNED' ? null : ctx.onAddFinding

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (!add || pending) return
    if (!note.trim()) {
      setError('Say what Rivell missed.')
      return
    }
    setError(null)
    setPending(true)
    try {
      await add({ headingNodeId: heading.id, severity, note: note.trim() })
      setOpen(false)
      setNote('')
      setSeverity('MEDIUM')
    } catch {
      setError('The finding could not be added. Try again.')
    } finally {
      setPending(false)
    }
  }

  return (
    <div className="rv-ws-ctools" data-clause-tools="1">
      <span className="rv-ws-mark" data-mark={mark}>
        {clauseMarkLabel(mark)}
      </span>
      {add && !open ? (
        <button type="button" className="rv-ws-link" onClick={() => setOpen(true)}>
          Add a finding
        </button>
      ) : null}
      {ctx.onClauseDetails && ctx.analysedHeadings.has(heading.id) && !open ? (
        <button type="button" className="rv-ws-link" onClick={() => ctx.onClauseDetails?.(heading.id)}>
          Clause details
        </button>
      ) : null}
      {add && open ? (
        <form className="rv-ws-addf" onSubmit={(event) => void submit(event)} noValidate>
          {ctx.regions.has(heading.id) ? (
            <p className="rv-ws-addf-warn">
              This clause already has a finding. Two changes on one clause cannot both be approved.
            </p>
          ) : null}
          <label>
            Severity
            <select value={severity} onChange={(event) => setSeverity(event.target.value as AddFindingInput['severity'])}>
              <option value="HIGH">High</option>
              <option value="MEDIUM">Medium</option>
              <option value="LOW">Low</option>
            </select>
          </label>
          <label>
            What did Rivell miss?
            <textarea
              value={note}
              maxLength={NOTE_LIMIT}
              rows={3}
              onChange={(event) => setNote(event.target.value)}
            />
          </label>
          {error ? (
            <p className="rv-ws-addf-err" role="alert">
              {error}
            </p>
          ) : null}
          <div className="rv-ws-addf-act">
            <Button
              compact
              onClick={() => {
                setOpen(false)
                setError(null)
              }}
            >
              Cancel
            </Button>
            <Button variant="primary" compact type="submit" disabled={pending}>
              Add finding
            </Button>
          </div>
        </form>
      ) : null}
    </div>
  )
}

/** A clause with no finding: drawn as received, with its mark. */
function MarkedClause({ heading, body, ctx }: { heading: TreeNode; body: TreeNode[]; ctx: Ctx }) {
  const focusNodeId = ctx.focusNodeId
  const focused =
    focusNodeId !== null &&
    (containsNode(heading, focusNodeId) || body.some((node) => containsNode(node, focusNodeId)))
  return (
    <div
      className={`rv-ws-cl rv-ws-cl--plain${focused ? ' rv-ws-cl--focus' : ''}`}
      data-mark={markOf(ctx, heading.id)}
      data-focus={focused ? '1' : undefined}
      tabIndex={focused ? -1 : undefined}
    >
      <ClauseTools heading={heading} ctx={ctx} />
      <Sequence nodes={[heading, ...body]} ctx={{ ...ctx, regionsOpen: false }} />
    </div>
  )
}

/** Whether the node itself, or anything beneath it, is the evidence jump's target. */
function containsNode(node: TreeNode, nodeId: string): boolean {
  return node.id === nodeId || node.children.some((child) => containsNode(child, nodeId))
}

function Text({ text, term }: { text: string; term: string }) {
  return (
    <>
      {splitOnTerm(text, term).map((part, index) =>
        part.match ? <mark key={index}>{part.text}</mark> : part.text,
      )}
    </>
  )
}

function Plain({ node, ctx }: { node: TreeNode; ctx: Ctx }) {
  const content = nodeLabel(node)
  const children = <Sequence nodes={node.children} ctx={{ ...ctx, regionsOpen: false }} />
  switch (node.nodeType) {
    case 'TITLE':
      return <h1>{content}</h1>
    case 'HEADING':
      return (
        <>
          <h4>
            <Text text={content} term={ctx.term} />
          </h4>
          {children}
        </>
      )
    case 'PARAGRAPH':
      return (
        <>
          <p>
            <Text text={content} term={ctx.term} />
          </p>
          {children}
        </>
      )
    case 'TABLE':
      return (
        <table>
          <tbody>{children}</tbody>
        </table>
      )
    case 'TABLE_ROW':
      return <tr>{children}</tr>
    case 'TABLE_CELL':
      return (
        <td>
          {content ? <Text text={content} term={ctx.term} /> : null}
          {children}
        </td>
      )
    default:
      return (
        <>
          {content ? (
            <p>
              <Text text={content} term={ctx.term} />
            </p>
          ) : null}
          {children}
        </>
      )
  }
}

/**
 * What the reviewer's decision does to this clause. REPLACE keeps the heading and swaps the body;
 * DELETE removes both. The open finding that is selected previews Rivell's proposal instead.
 */
function changeFor(findings: FindingSummary[], ctx: Ctx) {
  for (const finding of findings) {
    const change = executableChange(ctx.decisions.get(finding.id))
    if (change) return { change, proposal: false }
  }
  const selected = ctx.selected
  if (
    selected &&
    findings.some((finding) => finding.id === selected.id) &&
    !ctx.decisions.get(selected.id) &&
    selected.proposedLanguage &&
    proposedChangeType(selected) === 'REPLACE'
  ) {
    return { change: { type: 'REPLACE' as const, language: selected.proposedLanguage }, proposal: true }
  }
  return null
}

function Region({ heading, body, findings, ctx }: { heading: TreeNode; body: TreeNode[]; findings: FindingSummary[]; ctx: Ctx }) {
  const applied = ctx.mode === 'orig' ? null : changeFor(findings, ctx)
  const change = applied && (ctx.mode === 'changes' || !applied.proposal) ? applied.change : null
  if (ctx.mode === 'final' && change?.type === 'DELETE') return null

  const settled = findings.every((finding) => !isActionable(finding) || isResolved(ctx.decisions.get(finding.id)))
  const selected = findings.some((finding) => finding.id === ctx.selectedId)
  // The evidence jump's target may be the heading or any passage inside this clause.
  const focusNodeId = ctx.focusNodeId
  const focused =
    focusNodeId !== null &&
    (containsNode(heading, focusNodeId) || body.some((node) => containsNode(node, focusNodeId)))
  const bodyOriginal = body.map(nodeText).filter(Boolean).join('\n')

  let content: ReactNode
  if (change?.type === 'REPLACE') {
    content = (
      <>
        <h4>{nodeLabel(heading)}</h4>
        {ctx.mode === 'final' ? (
          <p>{change.language}</p>
        ) : (
          <p>
            {bodyOriginal ? <del>{bodyOriginal}</del> : null} {change.language ? <ins>{change.language}</ins> : null}
          </p>
        )}
      </>
    )
  } else if (change?.type === 'DELETE') {
    content = (
      <p>
        <del>{[nodeLabel(heading), bodyOriginal].filter(Boolean).join('\n')}</del>
      </p>
    )
  } else {
    content = <Sequence nodes={[heading, ...body]} ctx={{ ...ctx, regionsOpen: false }} />
  }

  return (
    <div
      className={`rv-ws-cl rv-ws-cl--${ctx.mode === 'orig' || settled ? 'dec' : 'has'}${focused ? ' rv-ws-cl--focus' : ''}`}
      data-sel={selected ? '1' : undefined}
      data-focus={focused ? '1' : undefined}
      tabIndex={focused ? -1 : undefined}
      data-findings={findings.map((finding) => finding.id).join(' ')}
      data-mark={showsMarks(ctx) ? markOf(ctx, heading.id) : undefined}
    >
      {showsMarks(ctx) ? <ClauseTools heading={heading} ctx={ctx} /> : null}
      {content}
    </div>
  )
}

/** Top-level passages in order, with each clause that has findings drawn as one selectable region. */
function Sequence({ nodes, ctx }: { nodes: readonly TreeNode[]; ctx: Ctx }) {
  const out: ReactNode[] = []
  for (let i = 0; i < nodes.length; i += 1) {
    const node = nodes[i]
    const findings = node.nodeType === 'HEADING' ? ctx.regions.get(node.id) : undefined
    if (findings && ctx.regionsOpen) {
      const body = clauseBody(nodes, i)
      out.push(<Region key={node.id} heading={node} body={body} findings={findings} ctx={ctx} />)
      i += body.length
    } else if (node.nodeType === 'HEADING' && ctx.regionsOpen && showsMarks(ctx)) {
      const body = clauseBody(nodes, i)
      out.push(<MarkedClause key={node.id} heading={node} body={body} ctx={ctx} />)
      i += body.length
    } else {
      // A jump target outside every clause region is highlighted on its own.
      const focused = ctx.regionsOpen && ctx.focusNodeId !== null && containsNode(node, ctx.focusNodeId)
      out.push(
        focused ? (
          <div key={node.id} className="rv-ws-cl rv-ws-cl--focus" data-focus="1" tabIndex={-1}>
            <Plain node={node} ctx={ctx} />
          </div>
        ) : (
          <Plain key={node.id} node={node} ctx={ctx} />
        ),
      )
    }
  }
  return <>{out}</>
}

/** A finding about a provision the contract lacks has no location, so it is a card, not a clause. */
function AbsenceCards({ ctx, findings }: { ctx: Ctx; findings: FindingSummary[] }) {
  const { labelFor } = useTaxonomy()
  const cards = findings.filter((finding) => finding.subjectKind === 'ABSENCE')
  return (
    <>
      {cards.map((finding) => {
        const change = executableChange(ctx.decisions.get(finding.id))
        if (ctx.mode === 'final' && !change) return null
        const name = finding.absenceConcept ? labelFor(finding.absenceConcept) : 'Provision'
        return (
          <div
            key={finding.id}
            className={`rv-ws-absent${change ? ' rv-ws-absent--approved' : ''}`}
            data-sel={finding.id === ctx.selectedId ? '1' : undefined}
            data-findings={finding.id}
          >
            <b>
              {ctx.mode === 'final'
                ? `Approved insertion: ${name}`
                : change
                  ? `Approved insertion decision: ${name}`
                  : `Expected provision not found: ${name}`}
            </b>
            <span>
              {ctx.mode === 'final'
                ? `${change?.language ?? ''}\nPlacement will be resolved by deterministic redline execution.`
                : change
                  ? 'Rivell will place this provision when it builds the redline.'
                  : 'This clause does not exist in the contract, so there is no source location. The proposed insertion is shown in the Finding panel.'}
            </span>
          </div>
        )
      })}
    </>
  )
}

export function ContractPane(props: Props) {
  const { nodes, findings, mode, term, isFinalized, selectedId, onSelect } = props
  const page = useRef<HTMLElement>(null)
  const focusNodeId = props.focusTarget?.nodeId ?? null

  // An evidence jump brings its clause into view and lands focus there, so the reviewer arrives at
  // the place the source points to without losing their keyboard position.
  useEffect(() => {
    if (focusNodeId === null) return
    const target = page.current?.querySelector<HTMLElement>('[data-focus="1"]')
    if (!target) return
    target.scrollIntoView({ block: 'center', behavior: 'smooth' })
    target.focus({ preventScroll: true })
  }, [props.focusTarget, focusNodeId, nodes])

  // A click on a clause or an absent-provision card selects its finding. This is a pointer
  // convenience only: the Findings list is the keyboard path to every finding.
  useEffect(() => {
    const element = page.current
    if (!element) return
    const select = (event: MouseEvent) => {
      if ((event.target as Element | null)?.closest('[data-clause-tools]')) return
      const target = (event.target as Element | null)?.closest<HTMLElement>('[data-findings]')
      const ids = target?.dataset.findings?.split(' ')
      if (!ids) return
      onSelect(ids[(ids.indexOf(selectedId ?? '') + 1) % ids.length])
    }
    element.addEventListener('click', select)
    return () => element.removeEventListener('click', select)
  }, [nodes, selectedId, onSelect])
  const ctx: Ctx = {
    regionsOpen: true,
    mode,
    term,
    decisions: props.decisions,
    selectedId,
    selected: props.selected,
    onSelect,
    regions: new Map(),
    focusNodeId,
    marks: props.marks ?? null,
    onAddFinding: props.onAddFinding ?? null,
    onClauseDetails: props.onClauseDetails ?? null,
    analysedHeadings: props.analysedHeadings ?? new Set(),
  }
  for (const finding of findings) {
    if (finding.subjectKind !== 'CLAUSE' || !finding.subjectHeadingNodeId) continue
    ctx.regions.set(finding.subjectHeadingNodeId, [...(ctx.regions.get(finding.subjectHeadingNodeId) ?? []), finding])
  }
  const top = nodes ? topLevelNodes(nodes) : []
  const hasTitle = top.some((node) => node.nodeType === 'TITLE')
  const executable = findings.filter((finding) => executableChange(props.decisions.get(finding.id))).length
  const legend = showsMarks(ctx)
    ? clauseMarksLegend(top.filter((node) => node.nodeType === 'HEADING').map((node) => markOf(ctx, node.id)))
    : ''

  // What each view shows is a tooltip on its tab, so the toolbar holds only the tabs.
  const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`
  const modeHelp: Record<DocMode, string> = {
    changes: 'The contract with Rivell’s suggestion for the selected finding and the changes you decided',
    orig: 'The contract as received, shown as plain text',
    final: isFinalized
      ? `The contract with the ${plural(executable, 'approved change')} that go into the redline`
      : `The contract with your ${plural(executable, 'decided change')}. Open findings keep the original text.`,
  }
  const note = term ? (
    <>
      Matches for “{term}”{' '}
      <button type="button" className="rv-ws-link" onClick={props.onClearTerm}>
        Clear
      </button>
    </>
  ) : null

  return (
    <main className="rv-ws-pane rv-ws-doc" aria-label="Contract">
      <div className="rv-ws-doctools">
        <div className="rv-ws-segc" role="tablist" aria-label="Document view">
          {MODES.map((entry) => (
            <button
              key={entry.mode}
              type="button"
              role="tab"
              aria-selected={mode === entry.mode}
              title={modeHelp[entry.mode]}
              onClick={() => props.onMode(entry.mode)}
            >
              {entry.label(isFinalized)}
            </button>
          ))}
        </div>
        {note ? <span className="rv-ws-dnote">{note}</span> : null}
      </div>
      {legend || props.onContractAnalysis ? (
        <div className="rv-ws-legend">
          {legend ? <span aria-label="What Rivell did with each clause">Clauses: {legend}</span> : null}
          {props.onContractAnalysis ? (
            <button type="button" className="rv-ws-link" onClick={props.onContractAnalysis}>
              Contract analysis
            </button>
          ) : null}
        </div>
      ) : null}
      {props.notes}
      {props.isPending ? <LoadingState label="Loading contract…" /> : null}
      {props.isError ? <ErrorState message="The contract could not be loaded." onRetry={props.onRetry} /> : null}
      {nodes ? (
        <article className="rv-ws-page" ref={page}>
          {hasTitle ? null : <h1>{props.title}</h1>}
          <Sequence nodes={top} ctx={ctx} />
          {mode !== 'orig' ? <AbsenceCards ctx={ctx} findings={findings} /> : null}
        </article>
      ) : null}
    </main>
  )
}
