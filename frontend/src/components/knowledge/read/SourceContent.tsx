import { useEffect, useRef } from 'react'
import { Link } from 'react-router'
import type { ArtifactType, Candidate } from '../../../api/knowledge'
import type { SourceBlock } from '../../../lib/knowledgeSource'
import { ArrowRightIcon, ChevronIcon } from '../../ui/Icon'

interface SourceContentProps {
  type: ArtifactType
  blocks: SourceBlock[]
  /** Published candidates produced from this source. */
  produced: Candidate[]
  selectedId: string | null
  onSelect: (candidateId: string | null) => void
  /** Where the selected candidate can be opened as published knowledge, when it can. */
  openTo: (candidate: Candidate) => { to: string; label: string } | null
}

const PRODUCED_TITLE: Partial<Record<ArtifactType, string>> = {
  PLAYBOOK: 'Produced positions',
  CLAUSE_LIBRARY: 'Produced clauses',
}

function Passage({ block, selectedId, onSelect }: { block: SourceBlock; selectedId: string | null; onSelect: (id: string | null) => void }) {
  const body =
    block.candidateIds.length > 0 ? (
      <button
        type="button"
        className="rv-k-hl"
        data-candidate={block.candidateIds.join(' ')}
        aria-pressed={selectedId !== null && block.candidateIds.includes(selectedId)}
        onClick={() => onSelect(block.candidateIds.find((id) => id !== selectedId) ?? block.candidateIds[0] ?? null)}
      >
        {block.text}
      </button>
    ) : (
      block.text
    )
  return block.kind === 'title' || block.kind === 'heading' ? <h4>{body}</h4> : <p>{body}</p>
}

/**
 * The extracted original beside the knowledge produced from it. A highlighted passage is the
 * exact source node (paragraph or row) the extraction unit recorded for a published candidate.
 * It is node-level lineage: it does not claim to mark individual words.
 */
export function SourceContent({ type, blocks, produced, selectedId, onSelect, openTo }: SourceContentProps) {
  const root = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!selectedId) return
    const target = Array.from(root.current?.querySelectorAll<HTMLElement>('[data-candidate]') ?? []).find((element) =>
      element.dataset.candidate?.split(' ').includes(selectedId),
    )
    target?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
  }, [selectedId])

  const selected = produced.find((candidate) => candidate.id === selectedId) ?? null
  const open = selected ? openTo(selected) : null
  const title = PRODUCED_TITLE[type] ?? 'Produced items'

  return (
    <div className="rv-k-two">
      <div>
        <div className="rv-k-sech">
          <h2>Original content</h2>
          <span>Highlights mark the exact source nodes</span>
        </div>
        <div className="rv-k-srcdoc" ref={root}>
          {blocks.map((block) => (
            <Passage key={block.id} block={block} selectedId={selectedId} onSelect={onSelect} />
          ))}
        </div>
      </div>
      <div>
        <div className="rv-k-sech">
          <h2>{title}</h2>
          <span>{produced.length}</span>
        </div>
        {produced.length === 0 ? (
          <p className="rv-note">Nothing from this source is published, so nothing is highlighted.</p>
        ) : (
          <ul className="rv-k-list rv-k-pl rv-k-rows" aria-label={title}>
            {produced.map((candidate) => (
              <li key={candidate.id}>
                <button
                  type="button"
                  aria-pressed={candidate.id === selectedId}
                  onClick={() => onSelect(candidate.id === selectedId ? null : candidate.id)}
                >
                  <span>{candidate.rawLabel}</span>
                  <ChevronIcon className="rv-icon" />
                </button>
              </li>
            ))}
          </ul>
        )}
        {open ? (
          <p className="rv-k-lnk-row">
            <Link className="rv-k-lnk" to={open.to}>
              {open.label}
              <ArrowRightIcon className="rv-icon" />
            </Link>
          </p>
        ) : null}
      </div>
    </div>
  )
}
