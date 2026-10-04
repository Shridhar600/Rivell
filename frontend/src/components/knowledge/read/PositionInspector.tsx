import { Fragment } from 'react'
import { Link } from 'react-router'
import type { ArtifactSummary, Candidate } from '../../../api/knowledge'
import { useExtractionUnitsQuery, useKnowledgeStructureQuery } from '../../../api/knowledge'
import { longDate } from '../../../lib/dates'
import { resolveCandidateSource } from '../../../lib/candidates'
import { positionText, type ClauseGroup, type PublicationRef } from '../../../lib/knowledgeCatalog'
import { conceptName, positionRoles, POSITION_ROLE_LABELS, scopeRows } from '../../../lib/knowledgePosition'
import { sourceLocation } from '../../../lib/knowledgeSource'
import { ArrowRightIcon } from '../../ui/Icon'

interface PositionInspectorProps {
  candidate: Candidate
  artifact: ArtifactSummary
  publication: PublicationRef
  /** The published clause on the same legal concept, when there is one. */
  clause: ClauseGroup | null
}

/**
 * Detail for one published position: the roles and scope the compilation recorded, the exact
 * authored wording, where it came from, and the clause on the same legal concept when one exists.
 */
export function PositionInspector({ candidate, artifact, publication, clause }: PositionInspectorProps) {
  const units = useExtractionUnitsQuery(artifact.id)
  const structure = useKnowledgeStructureQuery(artifact.id)
  const located =
    units.data && structure.data ? resolveCandidateSource(candidate, units.data, structure.data) : null
  const location = located ? sourceLocation(located.nodes) : null
  const roles = positionRoles(candidate)
  const concept = conceptName(candidate)
  const text = positionText(candidate)
  const escalation = candidate.escalationTrigger?.trim()

  return (
    <div className="rv-k-ins">
      <p className="rv-k-kicker">Position</p>
      <h2>{candidate.rawLabel}</h2>
      <dl className="rv-k-fdl">
        {roles.length > 0 ? (
          <>
            <dt>Roles</dt>
            <dd>{roles.map((role) => POSITION_ROLE_LABELS[role]).join(', ')}</dd>
          </>
        ) : null}
        {escalation ? (
          <>
            <dt>Escalate</dt>
            <dd>{escalation}</dd>
          </>
        ) : null}
        {concept ? (
          <>
            <dt>Legal concept</dt>
            <dd>{concept}</dd>
          </>
        ) : null}
        {scopeRows(candidate).map((row) => (
          <Fragment key={row.label}>
            <dt>{row.label}</dt>
            <dd>{row.value}</dd>
          </Fragment>
        ))}
        <dt>Published</dt>
        <dd>
          Publication {publication.number}, {longDate(publication.createdAt)}
        </dd>
      </dl>

      <div className="rv-k-prov">
        <span className="rv-k-loc">Provenance{location ? `, ${location}` : ''}</span>
        {text ? (
          <q>
            <span className="rv-k-hl">{text}</span>
          </q>
        ) : (
          <span className="rv-k-loc">This position has no authored wording.</span>
        )}
        <Link className="rv-k-lnk" to={`/knowledge/sources/${artifact.id}?position=${candidate.id}`}>
          Open in original source
          <ArrowRightIcon className="rv-icon" />
        </Link>
      </div>

      {clause ? (
        <div className="rv-k-prov">
          <span className="rv-k-loc">Clause library, same legal concept</span>
          <b>{clause.title}</b>
          <Link className="rv-k-lnk" to={`/knowledge/clauses/${encodeURIComponent(clause.key)}`}>
            Open clause
            <ArrowRightIcon className="rv-icon" />
          </Link>
        </div>
      ) : null}
    </div>
  )
}

