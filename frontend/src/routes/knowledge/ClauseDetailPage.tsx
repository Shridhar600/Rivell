import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { useExtractionUnitsQuery, useKnowledgeStructureQuery } from '../../api/knowledge'
import { useKnowledgeCatalog } from '../../api/knowledgeRead'
import { useTaxonomy } from '../../api/taxonomy'
import { CatalogGate, CatalogNotices } from '../../components/knowledge/read/CatalogGate'
import { KnowledgeFrame, type Crumb } from '../../components/knowledge/read/KnowledgeFrame'
import { LinkButton } from '../../components/ui/Button'
import { ArrowRightIcon, CopyIcon, SourceIcon } from '../../components/ui/Icon'
import { Chip } from '../../components/ui/Status'
import { EmptyState } from '../../components/ui/State'
import { resolveCandidateSource } from '../../lib/candidates'
import { onTabKeys } from '../../lib/tabKeys'
import { longDate } from '../../lib/dates'
import {
  clauseGroups,
  positionText,
  type Catalog,
  type ClauseGroup,
  type ClauseVariant,
} from '../../lib/knowledgeCatalog'
import { contractTypesLabel, displayScopeRows, positionRoles, POSITION_ROLE_LABELS } from '../../lib/knowledgePosition'
import { sourceLocation } from '../../lib/knowledgeSource'

export function ClauseDetailPage() {
  const { clauseKey: rawKey = '' } = useParams()
  const key = decodeURIComponent(rawKey)
  const { labelFor } = useTaxonomy()
  const [params] = useSearchParams()
  const state = useKnowledgeCatalog()
  const crumbs: Crumb[] = [{ label: 'Knowledge', to: '/knowledge' }, { label: 'Clause library', to: '/knowledge/clauses' }, { label: 'Clause' }]
  return (
    <CatalogGate state={state} crumbs={crumbs} title="Clause">
      {(catalog) => {
        const requested = Number(params.get('publication'))
        const versions = catalog.clauseLibrary.versions
        const at = versions.some((entry) => entry.publication.number === requested) ? requested : undefined
        const group = clauseGroups(catalog.clauseLibrary, catalog.index, labelFor, at).find((entry) => entry.key === key)
        if (group) return <ClauseDetail catalog={catalog} group={group} state={state} />
        return (
          <KnowledgeFrame crumbs={crumbs} title="Clause not found">
            <CatalogNotices state={state} catalog={catalog} />
            <EmptyState
              title="This clause is not in the published clause library"
              message={at ? `It is not part of knowledge version ${at}.` : 'It may have been superseded, or opened from an old link.'}
              action={<LinkButton to="/knowledge/clauses">Clause library</LinkButton>}
            />
          </KnowledgeFrame>
        )
      }}
    </CatalogGate>
  )
}

function ClauseDetail({ catalog, group, state }: { catalog: Catalog; group: ClauseGroup; state: ReturnType<typeof useKnowledgeCatalog> }) {
  const [params, setParams] = useSearchParams()
  const variant = group.variants.find((entry) => entry.candidate.id === params.get('variant')) ?? group.variants[0]
  const crumbs: Crumb[] = [
    { label: 'Knowledge', to: '/knowledge' },
    { label: 'Clause library', to: '/knowledge/clauses' },
    { label: group.title },
  ]
  const scopeTypes =
    variant.candidate.sourceContractTypes.length > 0
      ? variant.candidate.sourceContractTypes
      : variant.candidate.declaredContractTypes
  const scope = contractTypesLabel(scopeTypes)

  return (
    <KnowledgeFrame
      crumbs={crumbs}
      title={group.title}
      meta={
        <>
          <span className="rv-stb rv-stb--ok">Published</span>
          <Chip>Knowledge version {variant.publication.number}</Chip>
          <Chip>{scope}</Chip>
        </>
      }
      actions={
        <LinkButton to={`/knowledge/sources/${variant.artifact.id}?position=${variant.candidate.id}`}>
          <SourceIcon className="rv-icon" />
          Source
        </LinkButton>
      }
    >
      <CatalogNotices state={state} catalog={catalog} />
      {group.variants.length > 1 ? (
        <div className="rv-k-segc" role="tablist" aria-label="Variant">
          {group.variants.map((entry) => (
            <button
              key={entry.candidate.id}
              type="button"
              role="tab"
              aria-selected={entry.candidate.id === variant.candidate.id}
              tabIndex={entry.candidate.id === variant.candidate.id ? 0 : -1}
              onKeyDown={onTabKeys}
              onClick={() =>
                setParams(
                  (previous) => {
                    const next = new URLSearchParams(previous)
                    next.set('variant', entry.candidate.id)
                    return next
                  },
                  { replace: true },
                )
              }
            >
              {entry.candidate.title}
            </button>
          ))}
        </div>
      ) : null}
      <VariantBody key={variant.candidate.id} variant={variant} />
    </KnowledgeFrame>
  )
}

function VariantBody({ variant }: { variant: ClauseVariant }) {
  const { candidate, artifact, publication } = variant
  const units = useExtractionUnitsQuery(artifact.id)
  const structure = useKnowledgeStructureQuery(artifact.id)
  const located = units.data && structure.data ? resolveCandidateSource(candidate, units.data, structure.data) : null
  const location = located ? sourceLocation(located.nodes) : null
  const text = positionText(candidate)
  const roles = positionRoles(candidate).filter((role) => role !== 'COMMENTARY')
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle')

  async function copy() {
    try {
      await navigator.clipboard.writeText(text ?? '')
      setCopied('done')
    } catch {
      setCopied('failed')
    }
  }

  return (
    <div className="rv-k-two">
      <div className="rv-k-wording">
        <div className="rv-k-wording__h">
          <span>Approved wording</span>
          {text ? (
            <button type="button" className="rv-k-lnk" onClick={() => void copy()}>
              <CopyIcon className="rv-icon" />
              Copy
            </button>
          ) : null}
        </div>
        {text ? <p>{text}</p> : <p className="rv-k-loc">This clause has no approved wording.</p>}
        <span role="status" className="rv-visually-hidden">
          {copied === 'done' ? 'Copied the approved wording' : copied === 'failed' ? 'The wording could not be copied' : ''}
        </span>
      </div>
      <div className="rv-k-side">
        <dl className="rv-k-fdl">
          {roles.length > 0 ? (
            <>
              <dt>Role</dt>
              <dd>{roles.map((role) => POSITION_ROLE_LABELS[role]).join(', ')}</dd>
            </>
          ) : null}
          {displayScopeRows(candidate).map((row) => (
            <ScopeRow key={row.label} label={row.label} value={row.value} />
          ))}
          <dt>Published</dt>
          <dd>
            Knowledge version {publication.number}, {longDate(publication.createdAt)}
          </dd>
        </dl>
        <div className="rv-k-prov">
          <span className="rv-k-loc">Source</span>
          <b>{artifact.filename}</b>
          {location ? <span className="rv-k-loc">{location}</span> : null}
          <Link className="rv-k-lnk" to={`/knowledge/sources/${artifact.id}?position=${candidate.id}`}>
            Open source
            <ArrowRightIcon className="rv-icon" />
          </Link>
        </div>
      </div>
    </div>
  )
}

function ScopeRow({ label, value }: { label: string; value: string }) {
  return (
    <>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </>
  )
}
