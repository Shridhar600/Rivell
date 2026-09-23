import type { ArtifactDetail, ArtifactType } from '../../api/knowledge'
import { ARTIFACT_TYPE_LABELS } from '../../lib/knowledgeLabels'
import { ASSESSMENT_STATE_LABELS, humanizeCode } from '../../lib/semantics'
import { warningLabel } from '../../lib/warnings'

function assessedTypeLabel(assessedType: string): string {
  return (
    ARTIFACT_TYPE_LABELS[assessedType as ArtifactType] ?? humanizeCode(assessedType)
  )
}

export function ArtifactAssessmentPanel({
  artifact,
}: {
  artifact: ArtifactDetail
}) {
  const assessment = artifact.artifactAssessment
  if (!assessment) return null

  const declaredLabel = ARTIFACT_TYPE_LABELS[artifact.artifactType]
  const assessedLabel = assessedTypeLabel(assessment.assessedType)

  if (assessment.state === 'MATCH') {
    return (
      <p className="meta">
        Semantic assessment: Rivell's classification agrees with the declared
        artifact type ({declaredLabel}).
      </p>
    )
  }

  const isMismatch = assessment.state === 'POSSIBLE_MISMATCH'

  return (
    <section
      className="state state--warning parse-notice"
      role="status"
      aria-labelledby="artifact-assessment-heading"
    >
      <h2 id="artifact-assessment-heading">
        {isMismatch
          ? 'Source may not match the declared artifact type'
          : 'Artifact type assessment is ambiguous'}
      </h2>
      <p>
        Declared: <strong>{declaredLabel}</strong> · Rivell's assessment:{' '}
        <strong>{assessedLabel}</strong> (
        {ASSESSMENT_STATE_LABELS[assessment.state].toLowerCase()})
      </p>
      <p>
        The declared type remains authoritative; parsing and compilation use it.
        Changing the artifact type is not available in this milestone.
      </p>
      {assessment.warnings.length > 0 ? (
        <ul className="parse-notice__warnings">
          {assessment.warnings.map((warning) => (
            <li key={warning}>{warningLabel(warning)}</li>
          ))}
        </ul>
      ) : null}
    </section>
  )
}
