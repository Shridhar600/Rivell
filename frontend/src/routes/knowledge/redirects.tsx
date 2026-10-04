import { Navigate, useLocation, useParams } from 'react-router'

/**
 * `/knowledge/new` was the old Add knowledge page. Add knowledge is now a dialog on the Knowledge
 * pages, so the old address opens it there, with the type kept when the link named one.
 */
export function AddKnowledgeRedirect() {
  const { search } = useLocation()
  const type = new URLSearchParams(search).get('type')
  const add = type === 'playbook' || type === 'clause-library' ? type : '1'
  return <Navigate replace to={`/knowledge?add=${add}`} />
}

/**
 * `/knowledge/artifacts/:id` was the old compilation page, and Home, the evidence links and the
 * publication records still point at it. It now lives at the compilation route, with the
 * `?candidate=` a link carried.
 */
export function ArtifactRedirect() {
  const { artifactId = '' } = useParams()
  const { search } = useLocation()
  return <Navigate replace to={`/knowledge/compilations/${encodeURIComponent(artifactId)}${search}`} />
}
