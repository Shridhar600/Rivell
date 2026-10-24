import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import { useQueries, useQueryClient } from '@tanstack/react-query'
import {
  fetchCandidate,
  isKnowledgeForbiddenError,
  knowledgeKeys,
  knowledgeRetry,
  useBulkApproveCandidates,
  useCandidatesQuery,
  useExtractionUnitsQuery,
  useKnowledgeStructureQuery,
  useMakePosition,
  type ArtifactDetail,
  type Candidate,
} from '../../../api/knowledge'
import { useAllPublicationsQuery } from '../../../api/knowledgeRead'
import type { PublishedCompilation as PublishedResult } from '../../../api/knowledgeCompile'
import { groupCandidates, isPublishedCompilation, nextTarget, publishedDestination, readiness } from '../../../lib/compilation'
import { artifactName, buildPublicationIndex } from '../../../lib/knowledgeCatalog'
import { candidateLocations, highlightMap, indexNodes, passagesIn, sourceBlocks } from '../../../lib/knowledgeSource'
import { unitMark } from '../../../lib/knowledgeMarks'
import { publishedState } from '../../../lib/publishedNotice'
import { EmptyState, ErrorState, LoadingState } from '../../ui/State'
import { LinkButton } from '../../ui/Button'
import { CandidateRail } from './CandidateRail'
import { CandidateReview, type ReviewActions } from './CandidateReview'
import { CompileLayout, CompileTopBar } from './CompileLayout'
import { GroupedReview } from './GroupedReview'
import { PublishControl } from './PublishControl'
import { PublishDialog } from './PublishDialog'
import { PublishedCompilation } from './PublishedCompilation'
import { SourceSheet } from './SourceSheet'
import type { SourceState } from './Transform'

function isTyping(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && target.closest('input, textarea, select, [contenteditable="true"], dialog') !== null
}

/**
 * The compilation workspace for one extracted source: the candidate list, one candidate at a time
 * or the safe group, the source beside the interpretation, and the publication boundary. Every
 * status shown is what the server last returned, and every change is read back from it.
 */
export function CompilationWorkspace({ artifact }: { artifact: ArtifactDetail }) {
  const [params, setParams] = useSearchParams()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const railId = useId()
  const root = useRef<HTMLDivElement>(null)
  const actions = useRef<ReviewActions | null>(null)

  const candidatesQuery = useCandidatesQuery(artifact.id)
  const unitsQuery = useExtractionUnitsQuery(artifact.id)
  const structureQuery = useKnowledgeStructureQuery(artifact.id)
  const publicationsQuery = useAllPublicationsQuery()
  const bulk = useBulkApproveCandidates()

  const [railOpen, setRailOpen] = useState(true)
  const [solo, setSolo] = useState<ReadonlySet<string>>(new Set())
  const [inspected, setInspected] = useState<ReadonlySet<string>>(new Set())
  const [supersede, setSupersede] = useState<Record<string, string>>({})
  const [bulkResult, setBulkResult] = useState<{ approved: string[]; skipped: { label: string; reason: string }[] } | null>(null)
  const [bulkError, setBulkError] = useState<string | null>(null)
  const [publishing, setPublishing] = useState(false)

  const candidates = useMemo(() => candidatesQuery.data ?? [], [candidatesQuery.data])
  const units = unitsQuery.data
  // Titles for the published candidates this draft was compared with. One map is loaded in one
  // place for the whole workspace, so no row issues its own candidate read (DW-043).
  const peerIds = useMemo(() => {
    const ids = new Set<string>()
    for (const entry of candidates) {
      for (const signal of entry.conflictSignals) {
        if (signal.publishedCandidateId) ids.add(signal.publishedCandidateId)
      }
    }
    for (const id of Object.values(supersede)) ids.add(id)
    return [...ids]
  }, [candidates, supersede])
  const peerQueries = useQueries({
    queries: peerIds.map((id) => ({
      queryKey: knowledgeKeys.candidate(id),
      queryFn: () => fetchCandidate(id),
      retry: knowledgeRetry,
    })),
  })
  const candidateTitles = useMemo(() => {
    const titles = new Map(candidates.map((entry) => [entry.id, entry.title]))
    peerQueries.forEach((query, position) => {
      const id = peerIds[position]
      if (id && query.data) titles.set(id, query.data.title)
    })
    return titles
  }, [candidates, peerIds, peerQueries])
  // A position a person made is always reviewed on its own, never inside the straightforward group.
  const reviewAlone = useMemo(() => {
    const made = new Set((units ?? []).filter((entry) => entry.recordKindOverride !== null).map((entry) => entry.id))
    return new Set([...solo, ...candidates.filter((candidate) => made.has(candidate.extractionUnitId)).map((candidate) => candidate.id)])
  }, [solo, units, candidates])
  const groups = useMemo(() => groupCandidates(candidates, reviewAlone), [candidates, reviewAlone])
  const state = useMemo(() => readiness(candidates), [candidates])
  const nodes = structureQuery.data
  const structureReady = units !== undefined && nodes !== undefined

  const blocks = useMemo(() => (structureReady ? sourceBlocks(nodes, highlightMap(candidates, units)) : null), [structureReady, nodes, units, candidates])
  const byId = useMemo(() => (nodes ? indexNodes(nodes) : null), [nodes])
  const locations = useMemo(() => (structureReady ? candidateLocations(candidates, units, nodes) : new Map<string, string | null>()), [structureReady, candidates, units, nodes])
  const unitsById = useMemo(() => new Map((units ?? []).map((entry) => [entry.id, entry])), [units])
  // Parts the compiler kept as units, by what Rivell read them as. Only kind-assessed parts with text
  // are listed: without a record kind there was no kind gate to exclude them, so listing them as
  // "not compiled" would mislead.
  const { notPosition, missed } = useMemo(() => {
    const parts = units ?? []
    return {
      notPosition: parts.filter((entry) => unitMark(entry, candidates) === 'NOT_A_POSITION'),
      missed: parts.filter((entry) => unitMark(entry, candidates) === 'MISSED'),
    }
  }, [units, candidates])
  // Only playbooks and clause libraries sort their parts into positions, so only there can a
  // person overrule that reading.
  const makePositionMutation = useMakePosition(artifact.id)
  const sortsPositions = artifact.artifactType === 'PLAYBOOK' || artifact.artifactType === 'CLAUSE_LIBRARY'
  const index = useMemo(() => (publicationsQuery.data ? buildPublicationIndex(publicationsQuery.data) : null), [publicationsQuery.data])

  const update = useCallback(
    (change: (next: URLSearchParams) => void) =>
      setParams(
        (previous) => {
          const next = new URLSearchParams(previous)
          change(next)
          return next
        },
        { replace: true },
      ),
    [setParams],
  )
  const select = useCallback(
    (id: string) =>
      update((next) => {
        next.set('candidate', id)
        next.delete('view')
        next.delete('context')
      }),
    [update],
  )
  const openGroup = useCallback(() => {
    setBulkResult(null)
    update((next) => {
      next.set('view', 'group')
      next.delete('candidate')
      next.delete('context')
    })
  }, [update])
  const makePosition = useCallback(
    async (unitId: string) => {
      const made = await makePositionMutation.mutateAsync(unitId)
      if (made[0]) select(made[0].id)
    },
    [makePositionMutation, select],
  )
  const reread = useCallback(() => void queryClient.invalidateQueries({ queryKey: knowledgeKeys.candidates(artifact.id) }), [queryClient, artifact.id])

  // ------------------------------------------------------------ what is open
  const requestedId = params.get('candidate')
  const requested = candidates.find((candidate) => candidate.id === requestedId) ?? null
  const wantsGroup = params.get('view') === 'group' && groups.bulk.length > 0
  type Open = { kind: 'candidate'; candidate: Candidate } | { kind: 'group' } | { kind: 'published' }
  const open: Open | null = (() => {
    if (wantsGroup) return { kind: 'group' }
    if (requested) return { kind: 'candidate', candidate: requested }
    const first = groups.attention[0] ?? groups.toReview[0]
    if (first) return { kind: 'candidate', candidate: first }
    if (groups.bulk.length > 0) return { kind: 'group' }
    if (isPublishedCompilation(candidates)) return { kind: 'published' }
    const any = candidates[0]
    return any ? { kind: 'candidate', candidate: any } : null
  })()
  const selected = open?.kind === 'candidate' ? open.candidate : null

  // The default is chosen once and then kept in the address. Otherwise it would move on its own as
  // the server's answers change, and take the reviewer away from the candidate they are working on.
  const defaultId = open?.kind === 'candidate' ? open.candidate.id : null
  const defaultGroup = open?.kind === 'group'
  useEffect(() => {
    if (requestedId || params.get('view')) return
    if (defaultId) update((next) => next.set('candidate', defaultId))
    else if (defaultGroup) update((next) => next.set('view', 'group'))
  }, [requestedId, params, defaultId, defaultGroup, update])
  const orderIds = useMemo(() => [...groups.attention, ...groups.toReview, ...groups.reviewed, ...groups.published].map((candidate) => candidate.id), [groups])

  // ---------------------------------------------------------------- moving on
  function goNext(from: string) {
    const target = nextTarget(candidates, from, reviewAlone)
    if (target?.kind === 'candidate') select(target.id)
    else if (target?.kind === 'bulk') openGroup()
  }

  // ------------------------------------------------------------- shortcuts
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.metaKey || event.ctrlKey || event.altKey || isTyping(event.target)) return
      // The shortcuts are single keys, so they work only while focus is inside the workspace.
      if (!root.current?.contains(document.activeElement)) return
      const key = event.key.toLowerCase()
      if (key === '[') {
        event.preventDefault()
        setRailOpen((value) => !value)
        return
      }
      if (key === 'j' || key === 'k') {
        const position = selected ? orderIds.indexOf(selected.id) : -1
        const target = orderIds[Math.min(orderIds.length - 1, Math.max(0, position + (key === 'j' ? 1 : -1)))]
        if (target) {
          event.preventDefault()
          select(target)
        }
        return
      }
      if (!selected) return
      if (key === 'a') actions.current?.approve()
      else if (key === 'r') actions.current?.reject()
      else if (key === 'e') {
        event.preventDefault()
        actions.current?.edit()
      } else if (key === 'n') goNext(selected.id)
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  })

  // ------------------------------------------------------------------ groups
  function approveGroup() {
    setBulkError(null)
    const ids = groups.bulk.map((candidate) => candidate.id)
    const labels = new Map(candidates.map((candidate) => [candidate.id, candidate.title]))
    bulk.mutate(
      { candidateIds: ids, artifactId: artifact.id },
      {
        onSuccess: (result) => {
          setInspected(new Set())
          setBulkResult({
            approved: result.approvedIds,
            skipped: Object.entries(result.skipped).map(([id, reason]) => ({ label: labels.get(id) ?? id, reason })),
          })
        },
        onError: (failure) => {
          setBulkError(failure.message)
          reread()
        },
      },
    )
  }

  // ----------------------------------------------------------------- source
  function sourceFor(candidate: Candidate): SourceState {
    const unit = units?.find((entry) => entry.id === candidate.extractionUnitId) ?? null
    if (unitsQuery.isPending || structureQuery.isPending) return { passages: null, unitText: unit?.contentText ?? null }
    return { passages: blocks && byId ? passagesIn(blocks, byId, candidate.id) : [], unitText: unit?.contentText ?? null }
  }

  // ---------------------------------------------------------------- publish
  function published(result: PublishedResult) {
    navigate(publishedDestination(artifact), {
      state: publishedState({
        publicationNumber: result.publication.publicationNumber,
        type: artifact.artifactType,
        published: result.published,
        rejected: result.rejected,
        corrected: result.corrected,
      }),
    })
  }

  // --------------------------------------------------------------- rendering
  const top = (
    <CompileTopBar type={artifact.artifactType} name={artifactName(artifact)} rail={{ open: railOpen, onToggle: () => setRailOpen((value) => !value), id: railId }}>
      {candidates.length > 0 ? <PublishControl type={artifact.artifactType} candidates={candidates} onPublish={() => setPublishing(true)} /> : null}
    </CompileTopBar>
  )

  if (candidatesQuery.isPending) {
    return (
      <CompileLayout top={top}>
        <div className="rv-kc-center">
          <LoadingState label="Loading entries" focus />
        </div>
      </CompileLayout>
    )
  }
  if (candidatesQuery.isError) {
    const forbidden = isKnowledgeForbiddenError(candidatesQuery.error)
    return (
      <CompileLayout top={top}>
        <div className="rv-kc-center">
          <ErrorState
            focus
            tone={forbidden ? 'neutral' : 'danger'}
            title={forbidden ? 'Knowledge is not available to you' : 'The entries could not be loaded'}
            message={forbidden ? 'Your account does not have permission to review knowledge. Ask an administrator for access.' : candidatesQuery.error.message}
            onRetry={forbidden ? undefined : () => void candidatesQuery.refetch()}
          />
        </div>
      </CompileLayout>
    )
  }
  const rail = (
    <CandidateRail
      id={railId}
      artifact={artifact}
      groups={groups}
      readiness={state}
      locations={locations}
      unitsById={unitsById}
      notPosition={notPosition}
      missed={missed}
      onMakePosition={sortsPositions ? makePosition : null}
      selectedId={selected?.id ?? null}
      groupSelected={open?.kind === 'group'}
      onSelect={select}
      onOpenGroup={openGroup}
    />
  )
  if (candidates.length === 0) {
    // No candidate at all: the units the kind gate kept are still shown, so no record is dropped.
    return (
      <CompileLayout top={top} rail={notPosition.length + missed.length > 0 ? rail : undefined} railOpen={railOpen}>
        <div className="rv-kc-center">
          <EmptyState
            focus
            title="No entries were produced"
            message="Rivell read this file but found nothing to turn into knowledge, so there is nothing to review or publish. The original is stored unchanged."
            action={<LinkButton to={`/knowledge/sources/${artifact.id}`}>Open the source</LinkButton>}
          />
        </div>
      </CompileLayout>
    )
  }
  const selectedUnit = selected ? (units?.find((entry) => entry.id === selected.extractionUnitId) ?? null) : null
  const showSheet = params.get('context') === '1' && selected !== null

  return (
    <div ref={root} className="rv-kc-root">
      <CompileLayout top={top} rail={rail} railOpen={railOpen}>
        {bulkResult ? (
          <div className="rv-kc-result" role="status">
            <p>
              <b>{bulkResult.approved.length}</b> {bulkResult.approved.length === 1 ? 'entry' : 'entries'} approved.
              <button type="button" className="rv-lnk rv-kc-result__x" onClick={() => setBulkResult(null)}>
                Dismiss
              </button>
            </p>
            {bulkResult.skipped.length > 0 ? (
              <>
                <p>Rivell did not approve {bulkResult.skipped.length}:</p>
                <ul>
                  {bulkResult.skipped.map((entry) => (
                    <li key={entry.label}>
                      {entry.label}: {entry.reason}
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </div>
        ) : null}
        {requestedId && !requested && open?.kind !== 'group' ? (
          <p className="rv-kc-banner" role="status">
            That entry is not part of this source. Showing the next one to review.
          </p>
        ) : null}
        {open?.kind === 'candidate' ? (
          <CandidateReview
            key={open.candidate.id}
            artifact={artifact}
            candidate={open.candidate}
            index={candidates.findIndex((candidate) => candidate.id === open.candidate.id) + 1}
            total={candidates.length}
            source={sourceFor(open.candidate)}
            location={locations.get(open.candidate.id) ?? null}
            readiness={state}
            replaces={supersede[open.candidate.id] ?? null}
            onReplace={(peer) =>
              setSupersede((previous) => {
                const next = { ...previous }
                if (peer) next[open.candidate.id] = peer
                else delete next[open.candidate.id]
                return next
              })
            }
            publishedIn={index?.added.get(open.candidate.id) ?? null}
            candidateTitles={candidateTitles}
            actions={actions}
            onDecided={(id) => goNext(id)}
            onNext={() => goNext(open.candidate.id)}
            onContext={() => update((next) => next.set('context', '1'))}
            onFailed={reread}
          />
        ) : null}
        {open?.kind === 'group' ? (
          <GroupedReview
            artifact={artifact}
            candidates={groups.bulk}
            sourceFor={sourceFor}
            inspected={inspected}
            onToggle={(id) =>
              setInspected((previous) => {
                const next = new Set(previous)
                if (next.has(id)) next.delete(id)
                else next.add(id)
                return next
              })
            }
            onInspectAll={() => setInspected(new Set(groups.bulk.map((candidate) => candidate.id)))}
            onReviewAlone={(id) => {
              setSolo((previous) => new Set(previous).add(id))
              setInspected((previous) => {
                const next = new Set(previous)
                next.delete(id)
                return next
              })
              select(id)
            }}
            onApproveAll={approveGroup}
            pending={bulk.isPending}
            error={bulkError}
          />
        ) : null}
        {open?.kind === 'published' ? <PublishedCompilation artifact={artifact} candidates={candidates} publications={publicationsQuery.data ?? null} /> : null}
      </CompileLayout>

      {showSheet && selected ? (
        <SourceSheet
          artifact={artifact}
          candidate={selected}
          unit={selectedUnit}
          location={locations.get(selected.id) ?? null}
          blocks={blocks}
          onClose={() => update((next) => next.delete('context'))}
        />
      ) : null}

      {publishing ? (
        <PublishDialog
          artifact={artifact}
          candidates={candidates}
          supersede={supersede}
          candidateTitles={candidateTitles}
          onClose={() => setPublishing(false)}
          onPublished={published}
          onStale={reread}
        />
      ) : null}
    </div>
  )
}
