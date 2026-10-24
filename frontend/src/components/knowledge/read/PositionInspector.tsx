import { Fragment } from 'react'
import { Link } from 'react-router'
import type { ArtifactSummary, Candidate } from '../../../api/knowledge'
import { useExtractionUnitsQuery, useKnowledgeStructureQuery } from '../../../api/knowledge'
import { useTaxonomy } from '../../../api/taxonomy'
import { longDate } from '../../../lib/dates'
import { resolveCandidateSource } from '../../../lib/candidates'
import { positionText, type PublicationRef } from '../../../lib/knowledgeCatalog'
import { conceptName, displayScopeRows, positionRoles, POSITION_ROLE_LABELS } from '../../../lib/knowledgePosition'
import { headingPaths, sourceLocation } from '../../../lib/knowledgeSource'
import { AUTHORITY_SOURCE_LABELS } from '../../../lib/compilationFields'
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
  const location = located
    ? sourceLocation(located.nodes, structure.data ? headingPaths(structure.data) : undefined)
    : null
  const roles = positionRoles(candidate)
  const concept = conceptName(candidate, labelFor)
  const text = positionText(candidate)
  const escalation = candidate.escalationTrigger?.trim()

  return (
    <div className="rv-k-ins">
      <p className="rv-k-kicker">Position</p>
      <h2>{candidate.title}</h2>
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
        {candidate.preferredText ? (
          <>
            <dt>Preferred</dt>
            <dd>{candidate.preferredText}</dd>
          </>
        ) : null}
        {candidate.fallbackTexts.length > 0 ? (
          <>
            <dt>{candidate.fallbackTexts.length > 1 ? 'Fallbacks' : 'Fallback'}</dt>
            <dd>{candidate.fallbackTexts.join('; ')}</dd>
          </>
        ) : null}
        {candidate.prohibitedText ? (
          <>
            <dt>Walk-away</dt>
            <dd>{candidate.prohibitedText}</dd>
          </>
        ) : null}
        {candidate.authorityRules.map((rule, index) => (
          <Fragment key={`approval-${index}`}>
            <dt>Approval</dt>
            <dd>
              {rule.approver} for{' '}
              {rule.stances.length > 0
                ? rule.stances.map((role) => POSITION_ROLE_LABELS[role].toLowerCase()).join(', ')
                : 'fallback and walk-away'}{' '}
              ({AUTHORITY_SOURCE_LABELS[rule.source]})
              {rule.condition ? `, if ${rule.condition}` : ''}
            </dd>
          </Fragment>
        ))}
        {candidate.authorityConflicts.length > 0 ? (
          <>
            <dt>Other level</dt>
            <dd>
              {candidate.authorityConflicts
                .map(
                  (conflict) =>
                    `${conflict.approver} (${AUTHORITY_SOURCE_LABELS[conflict.source]}, ` +
                    `${POSITION_ROLE_LABELS[conflict.stance].toLowerCase()})`,
                )
                .join('; ')}
            </dd>
          </>
        ) : null}
        {candidate.unmappedAuthority.length > 0 ? (
          <>
            <dt>Needs attention</dt>
            <dd>
              The source states a walk-away or an approver Rivell could not map:{' '}
              {candidate.unmappedAuthority.join('; ')}
            </dd>
          </>
        ) : null}
        {concept ? (
          <>
            <dt>Topic</dt>
            <dd>{concept}</dd>
          </>
        ) : null}
        {displayScopeRows(candidate).map((row) => (
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

