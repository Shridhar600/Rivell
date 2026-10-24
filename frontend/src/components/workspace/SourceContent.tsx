import type { EvidenceItem } from '../../api/retrieval'
import { useTaxonomy } from '../../api/taxonomy'
import { describeFact, factSchemaLabel } from '../../lib/contractSemantics'
import { approvalSourceLabel } from '../../lib/reviews'
import {
  applicabilityDimensions,
  applicabilityNote,
  authorityLabel,
  policyCompatibilityLabel,
  policyPositionLabels,
  retrievalReasonLabel,
  sourceTypeLabel,
} from '../../lib/evidence'

interface Props {
  item: EvidenceItem
  regionLabels: Map<string, string>
  clauseHeadings: Map<string, string>
  /** Reveals the node in the workspace document, which is the version the review pinned. */
  onNavigateToNode?: (nodeId: string) => void
  /** Opens the clause's semantics in place, in the clause details sheet. */
  onOpenClauseSemantics?: (headingNodeId: string) => void
}

const TEXT_LABELS: Partial<Record<EvidenceItem['authorityClass'], string>> = {
  POLICY: 'Position text',
  APPROVED_DRAFTING: 'Approved wording',
  PRECEDENT: 'Text in the past agreement',
  CURRENT_CONTRACT: 'In the contract',
}

const FALLBACK_TITLES: Partial<Record<EvidenceItem['authorityClass'], string>> = {
  POLICY: 'Policy rule',
  APPROVED_DRAFTING: 'Approved wording',
  PRECEDENT: 'Comparable agreement',
  CURRENT_CONTRACT: 'Contract evidence',
}

/** The full evidence item, exactly as the review's analysis recorded it, in the source sheet. */
export function SourceContent({ item, regionLabels, clauseHeadings, onNavigateToNode, onOpenClauseSemantics }: Props) {
  const { labelFor } = useTaxonomy()
  const headingId = `evidence-${item.evidenceId}`
  const primaryNodeId = item.provenanceNodeIds[0] ?? null
  const regionLabel =
    item.authorityClass === 'CURRENT_CONTRACT' && primaryNodeId ? (regionLabels.get(primaryNodeId) ?? null) : null
  const title =
    regionLabel ??
    (item.legalConcept ? labelFor(item.legalConcept) : (FALLBACK_TITLES[item.authorityClass] ?? sourceTypeLabel(item.sourceType)))
  const scope = item.applicabilityScope
  const dimensions = scope ? applicabilityDimensions(scope) : []
  const scopeNote = scope ? applicabilityNote(scope) : null
  const positions = item.policyFacts ? policyPositionLabels(item.policyFacts.positions) : []
  const compatibility = item.authorityClass === 'PRECEDENT' ? policyCompatibilityLabel(item.policyCompatibility) : null
  const clauseHeading = item.sourceClauseId ? (clauseHeadings.get(item.sourceClauseId) ?? null) : null
  const regions = item.provenanceNodeIds.filter((nodeId) => regionLabels.has(nodeId))

  return (
    <article className="rv-ws-srcd" aria-labelledby={headingId}>
      <div className="rv-ws-kicker">
        <span>{authorityLabel(item.authorityClass)}</span>
        {item.publicationNumber !== null ? <span className="rv-ws-chip">Knowledge version {item.publicationNumber}</span> : null}
      </div>
      <h2 id={headingId}>{title}</h2>

      <dl className="rv-ws-props">
        {dimensions.map((dimension) => (
          <div key={dimension.key}>
            <dt>{dimension.label}</dt>
            <dd>{dimension.value}</dd>
          </div>
        ))}
        {positions.length > 0 ? (
          <div>
            <dt>Positions</dt>
            <dd>{positions.join(', ')}</dd>
          </div>
        ) : null}
        {item.policyFacts && item.policyFacts.authorityRules.length > 0 ? (
          <div>
            <dt>Approval</dt>
            <dd>
              {item.policyFacts.authorityRules
                .map((rule) => {
                  const stance =
                    rule.stances.includes('PROHIBITED')
                      ? 'walk-away'
                      : rule.stances.includes('FALLBACK')
                        ? 'fallback'
                        : 'fallback and walk-away'
                  const source = approvalSourceLabel(rule.source)
                  return `${rule.approver} approves the ${stance}${source ? `, ${source}` : ''}`
                })
                .join('; ')}
            </dd>
          </div>
        ) : null}
        {item.policyFacts?.escalationTrigger ? (
          <div>
            <dt>Escalation</dt>
            <dd>{item.policyFacts.escalationTrigger}</dd>
          </div>
        ) : null}
        {compatibility ? (
          <div>
            <dt>Against your playbook</dt>
            <dd>{compatibility}</dd>
          </div>
        ) : null}
        <div>
          <dt>Source</dt>
          <dd>{sourceTypeLabel(item.sourceType)}</dd>
        </div>
        <div>
          <dt>Matched by</dt>
          <dd>{retrievalReasonLabel(item.retrievalReason)}</dd>
        </div>
      </dl>
      {scopeNote ? <p className="rv-ws-caption">{scopeNote}</p> : null}

      <div className="rv-ws-sec">
        <h3 className="rv-ws-sec-h">{TEXT_LABELS[item.authorityClass] ?? 'Text'}</h3>
        <div className={item.authorityClass === 'APPROVED_DRAFTING' ? 'rv-ws-prop' : 'rv-ws-incl'}>{item.text}</div>
        {item.authorityClass === 'APPROVED_DRAFTING' && item.publicationNumber !== null ? (
          <p className="rv-ws-caption">Approved organizational drafting · Knowledge version {item.publicationNumber}</p>
        ) : null}
      </div>

      {item.contractFacts.map((fact) => (
        <div className="rv-ws-sec" key={`${fact.factSchema}-${fact.schemaVersion}`}>
          <h3 className="rv-ws-sec-h">{factSchemaLabel(fact.factSchema)}</h3>
          <dl className="rv-ws-props">
            {describeFact({ ...fact, sourceNodeIds: [] }).map((row) => (
              <div key={row.field}>
                <dt>{row.label}</dt>
                <dd>{row.value}</dd>
              </div>
            ))}
          </dl>
        </div>
      ))}

      {onNavigateToNode && regions.length > 0 ? (
        <div className="rv-ws-sec">
          <h3 className="rv-ws-sec-h">View in document</h3>
          <div className="rv-ws-srcd-acts">
            {regions.map((nodeId) => (
              <button key={nodeId} type="button" className="rv-ws-link" onClick={() => onNavigateToNode(nodeId)}>
                {regionLabels.get(nodeId)}
              </button>
            ))}
          </div>
        </div>
      ) : null}
      {onOpenClauseSemantics && clauseHeading ? (
        <div className="rv-ws-srcd-acts">
          <button type="button" className="rv-ws-link" onClick={() => onOpenClauseSemantics(clauseHeading)}>
            Clause details
          </button>
        </div>
      ) : null}
    </article>
  )
}
