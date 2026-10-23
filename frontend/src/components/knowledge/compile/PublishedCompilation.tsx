import type { ArtifactDetail, Candidate, Publication } from '../../../api/knowledge'
import { artifactName, buildPublicationIndex, publicationsText, type PublicationRef } from '../../../lib/knowledgeCatalog'
import { hasHumanCorrection } from '../../../lib/compilationFields'
import { kindLabel, publishedDestination } from '../../../lib/compilation'
import { LinkButton } from '../../ui/Button'

/**
 * A compilation that has been published, shown as the immutable record it now is. It is derived
 * from what the server holds, so it reads the same after a reload, and it offers no way back to
 * editing: published knowledge cannot be reopened.
 */
export function PublishedCompilation({ artifact, candidates, publications }: { artifact: ArtifactDetail; candidates: Candidate[]; publications: Publication[] | null }) {
  const published = candidates.filter((candidate) => candidate.reviewStatus === 'PUBLISHED')
  const rejected = candidates.filter((candidate) => candidate.reviewStatus === 'REJECTED').length
  const corrected = published.filter((candidate) => hasHumanCorrection(candidate, artifact)).length
  const index = publications ? buildPublicationIndex(publications) : null
  const refs = new Map<number, PublicationRef>()
  for (const candidate of published) {
    const ref = index?.added.get(candidate.id)
    if (ref) refs.set(ref.number, ref)
  }
  const text = refs.size > 0 ? publicationsText([...refs.values()]) : null
  const kind = kindLabel(artifact.artifactType).toLowerCase()

  return (
    <div className="rv-kc-scroll">
      <div className="rv-kc-pubcard">
        <span className="rv-kc-stb rv-kc-stb--ok">Published{text ? `, ${text}` : ''}</span>
        <h1 tabIndex={-1}>{artifactName(artifact)}</h1>
        <p>
          This reviewed version is published knowledge for future reviews. The source cannot be changed and earlier versions remain available.
        </p>
        <dl className="rv-kc-snapshot">
          <div>
            <dt>Published entries</dt>
            <dd>{published.length}</dd>
          </div>
          <div>
            <dt>Corrected by reviewer</dt>
            <dd>{corrected}</dd>
          </div>
          <div>
            <dt>Rejected entries</dt>
            <dd>{rejected}</dd>
          </div>
        </dl>
        <div className="rv-kc-pubactions">
          <LinkButton variant="primary" to={publishedDestination(artifact)}>
            Open published {artifact.artifactType === 'CLAUSE_LIBRARY' ? 'library' : kind}
          </LinkButton>
          <LinkButton to={`/knowledge/sources/${artifact.id}`}>View retained source</LinkButton>
        </div>
        <p className="rv-kc-note">Publishing created a new version. It does not change earlier versions or reviews that used them.</p>
      </div>
    </div>
  )
}
