import { useState } from 'react'
import { Link } from 'react-router'
import type { FindingSummary } from '../../api/reviews'
import { useTaxonomy } from '../../api/taxonomy'
import type { ApprovedChange, ApprovedChangeSet, HumanDecision, HumanReview } from '../../api/workflow'
import { changeLocation, changeOutcome, changeTitle, pluralise } from '../../lib/lifecycleView'
import { workspacePath } from '../../lib/reviewLifecycle'
import { changeLanguageProvenanceLabel } from '../../lib/workflow'
import { ArrowIcon, PadlockIcon } from './icons'
import { ChevronIcon } from '../ui/Icon'

interface Props {
  contractId: string
  reviewId: string
  changeSet: ApprovedChangeSet
  humanReview: HumanReview
  findingsById: Map<string, FindingSummary>
  decisionsByFindingId: Map<string, HumanDecision>
  regionLabels: Map<string, string>
  /** The current text of a clause, or null when the contract structure cannot supply it. */
  clauseText: (change: ApprovedChange) => string | null
  /** Whether the pinned contract structure, which the "before" text comes from, is available. */
  structure: 'loading' | 'ready' | 'error'
}

/**
 * Read-only inspection of the frozen ApprovedChangeSet, ported from the canonical `vChanges()`.
 * Nothing here can edit a change. The "before" text is the pinned clause as the contract holds it,
 * and the "after" text is exactly the language a person approved: Rivell computes no word-level
 * diff, so none is implied.
 */
export function ChangesPanel({
  contractId,
  reviewId,
  changeSet,
  humanReview,
  findingsById,
  decisionsByFindingId,
  regionLabels,
  clauseText,
  structure,
}: Props) {
  const { labelFor } = useTaxonomy()
  const [open, setOpen] = useState<Set<string>>(() => new Set(changeSet.changes.slice(0, 1).map((change) => change.id)))
  const toggle = (id: string) =>
    setOpen((previous) => {
      const next = new Set(previous)
      if (!next.delete(id)) next.add(id)
      return next
    })

  if (changeSet.changes.length === 0) {
    return (
      <div className="rv-lc-frozen">
        <PadlockIcon className="rv-icon" />
        <span>
          <b>Approved changes are final:</b> 0 changes.
        </span>
      </div>
    )
  }

  return (
    <>
      <div className="rv-lc-frozen">
        <PadlockIcon className="rv-icon" />
        <span>
          <b>Approved changes are final:</b> {pluralise(changeSet.changes.length, 'change')}. This cannot be
          edited.
        </span>
      </div>
      <ol className="rv-lc-klist" aria-label="Approved changes">
        {changeSet.changes.map((change, position) => {
          const finding = findingsById.get(change.findingId)
          const decision = decisionsByFindingId.get(change.findingId)
          const isOpen = open.has(change.id)
          const before = change.actionType === 'INSERT' ? null : clauseText(change)
          const bodyId = `chg-${change.id}`
          return (
            <li key={change.id} className={`rv-lc-chg${isOpen ? ' rv-lc-chg--open' : ''}`}>
              <button type="button" aria-expanded={isOpen} aria-controls={bodyId} onClick={() => toggle(change.id)}>
                <span className="rv-lc-chg__n">{position + 1}</span>
                <span>
                  <TypeChip type={change.actionType} />
                </span>
                <span>
                  <b>{changeTitle(change, finding, regionLabels, labelFor)}</b>
                  <div className="rv-lc-chg__l">{changeLocation(change, regionLabels, labelFor)}</div>
                </span>
                <span className="rv-stb">{changeOutcome(decision)}</span>
                <ChevronIcon className="rv-icon" />
              </button>
              <div className="rv-lc-chg__bd" id={bodyId} hidden={!isOpen}>
                <div className="rv-lc-dif">
                  {before ? (
                    <div>
                      <del>{before}</del>
                    </div>
                  ) : null}
                  {change.approvedLanguage ? (
                    <div className={before ? 'rv-lc-dif__after' : undefined}>
                      <ins>{change.approvedLanguage}</ins>
                    </div>
                  ) : null}
                  {change.actionType !== 'INSERT' && !before ? (
                    <div className="rv-lc-quiet">
                      {structure === 'loading'
                        ? 'Loading the current clause text…'
                        : 'The current clause text is not available.'}
                    </div>
                  ) : null}
                  {!before && !change.approvedLanguage && change.actionType === 'INSERT' ? (
                    <div className="rv-lc-quiet">No text is recorded for this change.</div>
                  ) : null}
                </div>
                {change.actionType === 'INSERT' ? (
                  <p className="rv-lc-quiet">Rivell will place this provision when it builds the redline.</p>
                ) : null}
                <p className="rv-lc-prv">
                  {decision ? (
                    <span>
                      Decision revision <b>{decision.revision}</b>
                    </span>
                  ) : null}
                  <span>
                    Outcome <b>{changeOutcome(decision)}</b>
                  </span>
                  <span>
                    Language <b>{changeLanguageProvenanceLabel(change.languageSource)}</b>
                  </span>
                  <Link
                    className="rv-lc-lnk"
                    to={`${workspacePath(contractId, reviewId)}?finding=${encodeURIComponent(change.findingId)}`}
                  >
                    Open Review Workspace <ArrowIcon className="rv-icon" />
                  </Link>
                </p>
              </div>
            </li>
          )
        })}
      </ol>
      {humanReview.rejected > 0 ? (
        <p className="rv-lc-quiet">
          Also reviewed: {pluralise(humanReview.rejected, 'Finding')} rejected; no approved change was created for{' '}
          {humanReview.rejected === 1 ? 'it' : 'them'}.
        </p>
      ) : null}
    </>
  )
}

export function TypeChip({ type }: { type: string }) {
  return <span className={`rv-lc-typ rv-lc-typ--${type}`}>{type}</span>
}
