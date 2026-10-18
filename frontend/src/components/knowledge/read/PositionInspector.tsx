import { Fragment } from 'react'
import { Link } from 'react-router'
import type { ArtifactSummary, Candidate } from '../../../api/knowledge'
import { useExtractionUnitsQuery, useKnowledgeStructureQuery } from '../../../api/knowledge'
import { useTaxonomy } from '../../../api/taxonomy'
import { longDate } from '../../../lib/dates'
import { resolveCandidateSource } from '../../../lib/candidates'
import { positionText, type PublicationRef } from '../../../lib/knowledgeCatalog'
import { conceptName, positionRoles, POSITION_ROLE_LABELS, scopeRows } from '../../../lib/knowledgePosition'
import { sourceLocation } from '../../../lib/knowledgeSource'
import { ArrowRightIcon } from '../../ui/Icon'

interface PositionInspectorProps {
  candidate: Candidate
  artifact: ArtifactSummary
  publication: PublicationRef
}

/**
 * Detail for one published position: the roles and scope the compilation recorded, the exact
 * authored wording, and where it came from. The legal concept is shown as classification only. No
 * relationship between a playbook position and a clause library entry exists in the API, so none
 * is implied here.
 */
export function PositionInspector({ candidate, artifact, publication }: PositionInspectorProps) {
  const { labelFor } = useTaxonomy()
  const units = useExtractionUnitsQuery(artifact.id)
  const structure = useKnowledgeStructureQuery(artifact.id)
  const located =
    units.data && structure.data ? resolveCandidateSource(candidate, units.data, structure.data) : null
  const location = located ? sourceLocation(located.nodes) : null
  const roles = positionRoles(candidate)
  const concept = conceptName(candidate, labelFor)
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
            <dt>Topic</dt>
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
          Knowledge version {publication.number}, {longDate(publication.createdAt)}
        </dd>
      </dl>

      <div className="rv-k-prov">
        <span className="rv-k-loc">Source{location ? `, ${location}` : ''}</span>
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

    </div>
  )
}

