import { useMemo } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import {
  useArtifactQuery,
  useExtractionUnitsQuery,
  useKnowledgeStructureQuery,
  type Candidate,
} from '../../api/knowledge'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { useTaxonomy } from '../../api/taxonomy'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { KnowledgeFrame, type Crumb } from '../../components/knowledge/read/KnowledgeFrame'
import { SourceStateBadge } from '../../components/knowledge/read/KnowledgeRows'
import { LinkButton } from '../../components/ui/Button'
import { ArrowRightIcon, LockIcon } from '../../components/ui/Icon'
import { Chip } from '../../components/ui/Status'
import { EmptyState } from '../../components/ui/State'
import { longDate } from '../../lib/dates'
import {
  artifactFormat,
  clauseGroups,
  clauseKey,
  countNoun,
  publicationsTitle,
  type Catalog,
  type SourceView,
} from '../../lib/knowledgeCatalog'
import { ARTIFACT_TYPE_LABELS, formatBytes, shortSha } from '../../lib/knowledgeLabels'
import { highlightMap, sourceBlocks } from '../../lib/knowledgeSource'
import { SourceContent } from '../../components/knowledge/read/SourceContent'

export function SourceDetailPage() {
  const { artifactId = '' } = useParams()
  const state = useKnowledgeCatalog()
  const crumbs: Crumb[] = [{ label: 'Knowledge', to: '/knowledge' }, { label: 'Sources', to: '/knowledge/sources' }, { label: 'Source' }]
  return (
    <CatalogGate state={state} crumbs={crumbs} title="Source">
      {(catalog) => {
        const source = catalog.sources.find((entry) => entry.artifact.id === artifactId)
        if (source) return <SourceDetail catalog={catalog} source={source} state={state} />
        return (
          <KnowledgeFrame crumbs={crumbs} title="Source not found">
            <CatalogNotices state={state} catalog={catalog} />
            <EmptyState
              title="This source could not be found"
              message="It may have been opened from an old link."
              action={<LinkButton to="/knowledge/sources">All sources</LinkButton>}
            />
          </KnowledgeFrame>
        )
      }}
    </CatalogGate>
  )
}

/** Where the source's published knowledge can be read, when it has a place in the Knowledge area. */
function publishedTarget(source: SourceView): string | null {
  if (source.artifact.artifactType === 'PLAYBOOK') return `/knowledge/playbooks/${source.artifact.id}`
  if (source.artifact.artifactType === 'CLAUSE_LIBRARY') return '/knowledge/clauses'
  return null
}

function SourceDetail({ catalog, source, state }: { catalog: Catalog; source: SourceView; state: ReturnType<typeof useKnowledgeCatalog> }) {
  const { labelFor } = useTaxonomy()
  const { artifact } = source
  const [params, setParams] = useSearchParams()
  const detail = useArtifactQuery(artifact.id)
  const hasCandidates = source.candidates.length > 0
  const units = useExtractionUnitsQuery(artifact.id)
  const structure = useKnowledgeStructureQuery(artifact.id)

  const crumbs: Crumb[] = [
    { label: 'Knowledge', to: '/knowledge' },
    { label: 'Sources', to: '/knowledge/sources' },
    { label: artifact.filename },
  ]

  // Published candidates are the produced knowledge; a candidate still in review is not.
  const produced = useMemo(
    () => source.candidates.filter((candidate) => catalog.index.added.has(candidate.id)),
    [source.candidates, catalog.index],
  )
  const highlights = useMemo(() => highlightMap(produced, units.data ?? []), [produced, units.data])
  const blocks = useMemo(() => sourceBlocks(structure.data ?? [], highlights), [structure.data, highlights])
  const selectedId = produced.some((candidate) => candidate.id === params.get('position')) ? params.get('position') : null
  const select = (id: string | null) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (id) next.set('position', id)
        else next.delete('position')
        return next
      },
      { replace: true },
    )

  const target = publishedTarget(source)
  const clauseCount = clauseGroups(catalog.clauseLibrary, catalog.index, labelFor).length

  return (
    <KnowledgeFrame
      crumbs={crumbs}
      title={artifact.filename}
      meta={
        <>
          <SourceStateBadge state={source.state} />
          <Chip>{ARTIFACT_TYPE_LABELS[artifact.artifactType]}</Chip>
          <Chip>{artifactFormat(artifact)}</Chip>
          <Chip>
            <LockIcon className="rv-icon" />
            Immutable original
          </Chip>
        </>
      }
    >
      <CatalogNotices state={state} catalog={catalog} />

      <div className="rv-k-lin" aria-label="Lineage: source, compilation, published knowledge">
        <div className="rv-k-lc">
          <span className="rv-k-lc__k">Source</span>
          <b>{artifact.filename}</b>
          <small>
            {formatBytes(artifact.sizeBytes)}, uploaded {longDate(artifact.createdAt)}
          </small>
          <small>sha256 {shortSha(artifact.sha256)}</small>
        </div>
        <ArrowRightIcon className="rv-icon" />
        <div className="rv-k-lc">
          <span className="rv-k-lc__k">Compilation</span>
          <b>{compilationTitle(source)}</b>
          <small>{compilationDetail(source)}</small>
        </div>
        <ArrowRightIcon className="rv-icon" />
        {source.state === 'review' ? (
          <Link to={`/knowledge/compilations/${artifact.id}`} className="rv-k-lc rv-k-lc--attention">
            <span className="rv-k-lc__k">Published knowledge</span>
            <b>{source.publications.length > 0 ? publicationsTitle(source.publications) : 'None yet'}</b>
            <small>Continue review →</small>
          </Link>
        ) : source.publications.length > 0 && target ? (
          <Link to={target} className="rv-k-lc">
            <span className="rv-k-lc__k">Published knowledge</span>
            <b>{publicationsTitle(source.publications)}</b>
            <small>
              {artifact.artifactType === 'CLAUSE_LIBRARY'
                ? `${clauseCount} ${clauseCount === 1 ? 'clause' : 'clauses'} in the library`
                : `${countNoun(artifact.artifactType, source.live)}${source.live < (source.counts?.published ?? 0) ? ' in use' : ''}`}
            </small>
          </Link>
        ) : source.publications.length > 0 ? (
          <div className="rv-k-lc">
            <span className="rv-k-lc__k">Published knowledge</span>
            <b>{publicationsTitle(source.publications)}</b>
            <small>{countNoun(artifact.artifactType, source.live)}</small>
          </div>
        ) : (
          <div className="rv-k-lc">
            <span className="rv-k-lc__k">Published knowledge</span>
            <b>None yet</b>
            <small>{source.state === 'compiling' ? 'Waiting for compilation' : source.state === 'failed' ? 'Extraction failed' : 'Nothing was published'}</small>
          </div>
        )}
      </div>

      {detail.data && detail.data.parseWarnings.length > 0 ? (
        <p className="rv-note">
          The parser reported {detail.data.parseWarnings.length} {detail.data.parseWarnings.length === 1 ? 'warning' : 'warnings'} for this file.
        </p>
      ) : null}

      {hasCandidates && blocks.length > 0 ? (
        <SourceContent
          type={artifact.artifactType}
          blocks={blocks}
          produced={produced}
          selectedId={selectedId}
          onSelect={select}
          openTo={(candidate) => openTarget(source, candidate)}
        />
      ) : (
        <div className="rv-k-list">
          <p className="rv-k-plain">{plainNote(source, structure.isPending || units.isPending, produced.length)}</p>
        </div>
      )}
    </KnowledgeFrame>
  )
}

function openTarget(source: SourceView, candidate: Candidate): { to: string; label: string } | null {
  if (source.artifact.artifactType === 'PLAYBOOK') {
    return { to: `/knowledge/playbooks/${source.artifact.id}?position=${candidate.id}`, label: 'Open position' }
  }
  if (source.artifact.artifactType === 'CLAUSE_LIBRARY') {
    return { to: `/knowledge/clauses/${encodeURIComponent(clauseKey(candidate))}?variant=${candidate.id}`, label: 'Open clause' }
  }
  return null
}

function compilationTitle(source: SourceView): string {
  const counts = source.counts
  if (source.state === 'compiling') return 'Compiling'
  if (source.state === 'failed') return 'Extraction failed'
  if (source.state === 'unreadable' || !counts) return 'Status unavailable'
  if (source.state === 'review') return `${counts.total} candidates, ${counts.total - counts.pending} reviewed`
  return counts.total === 0 ? 'No candidates produced' : 'Compiled and reviewed'
}

function compilationDetail(source: SourceView): string {
  const counts = source.counts
  if (!counts) return source.state === 'compiling' ? 'Candidates appear when it finishes' : 'No candidates are available'
  if (source.state === 'review') return `${source.unresolved} still to decide or publish`
  return `${counts.published} published, ${counts.rejected} rejected`
}

function plainNote(source: SourceView, loading: boolean, produced: number): string {
  if (loading && source.candidates.length > 0) return 'Loading the original content…'
  if (source.state === 'failed') return 'The original is stored and cannot be changed. Its content could not be extracted.'
  if (source.state === 'compiling') return 'The original is stored and cannot be changed. Candidates appear here once compilation finishes.'
  if (source.candidates.length === 0) return 'The original is stored and cannot be changed. No candidates were produced from it.'
  if (produced === 0) return 'Nothing from this source is published yet, so no passage is linked to published knowledge.'
  return 'The extracted content could not be shown, but the original is stored unchanged.'
}

