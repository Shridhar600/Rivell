import type { ArtifactSummary, ArtifactType, Candidate, Publication } from '../api/knowledge'
import type { ConceptLabel } from '../api/taxonomy'
import { effectiveContentText, effectivePayloadColumns } from './candidates'

/**
 * The read model behind the Knowledge surfaces.
 *
 * Rivell publishes organization-wide, atomic, numbered snapshots. There is no per-artifact
 * publication read, so this module projects one from three real reads: the source list, each
 * source's candidates, and the publication records. Every join is by id. Nothing is inferred
 * from titles or wording, and a publication number is never presented as a per-artifact version.
 */

export interface PublicationRef {
  id: string
  number: number
  createdAt: string
  note: string | null
}

export interface CatalogInput {
  artifacts: ArtifactSummary[]
  publications: Publication[]
  /** Candidates by source id. A source with no entry could not be read. */
  candidates: ReadonlyMap<string, Candidate[]>
}

export interface PublicationIndex {
  /** The publication that added a candidate. Each candidate is added at most once. */
  added: ReadonlyMap<string, PublicationRef>
  /** A candidate that a later publication explicitly superseded, and the one that replaced it. */
  superseded: ReadonlyMap<string, { by: string; publication: PublicationRef }>
}

export function buildPublicationIndex(publications: Publication[]): PublicationIndex {
  const added = new Map<string, PublicationRef>()
  const superseded = new Map<string, { by: string; publication: PublicationRef }>()
  // Read oldest first, whatever order the list arrives in. A candidate can only leave the published
  // set once, so if later publications name it again, the earliest one is the one that removed it.
  const ordered = [...publications].sort((a, b) => a.publicationNumber - b.publicationNumber)
  for (const publication of ordered) {
    const ref = publicationRef(publication)
    for (const item of publication.items) {
      if (item.action === 'ADD') {
        if (!added.has(item.candidateId)) added.set(item.candidateId, ref)
      } else if (item.supersededCandidateId && !superseded.has(item.supersededCandidateId)) {
        superseded.set(item.supersededCandidateId, { by: item.candidateId, publication: ref })
      }
    }
  }
  return { added, superseded }
}

export function publicationRef(publication: Publication): PublicationRef {
  return {
    id: publication.id,
    number: publication.publicationNumber,
    createdAt: publication.createdAt,
    note: publication.note,
  }
}

/** Part of the published set as of a publication number. With no number, as of now. */
export function isLiveAt(index: PublicationIndex, candidateId: string, at = Number.POSITIVE_INFINITY): boolean {
  const added = index.added.get(candidateId)
  if (!added || added.number > at) return false
  const replaced = index.superseded.get(candidateId)
  return !replaced || replaced.publication.number > at
}

export interface VersionEntry {
  publication: PublicationRef
  /** Items from this set that entered the published set in this publication. */
  added: number
  /** Items from this set that this publication explicitly superseded. */
  superseded: number
  /** How many items of this set are in the published set as of this publication. */
  live: number
}

/** Publications that changed this set of candidates, newest first. */
export function versionsOf(set: Candidate[], index: PublicationIndex): VersionEntry[] {
  const entries = new Map<number, { publication: PublicationRef; added: number; superseded: number }>()
  const entry = (publication: PublicationRef) => {
    let current = entries.get(publication.number)
    if (!current) {
      current = { publication, added: 0, superseded: 0 }
      entries.set(publication.number, current)
    }
    return current
  }
  for (const candidate of set) {
    const added = index.added.get(candidate.id)
    if (added) entry(added).added += 1
    const replaced = index.superseded.get(candidate.id)
    if (replaced) entry(replaced.publication).superseded += 1
  }
  return [...entries.values()]
    .sort((a, b) => b.publication.number - a.publication.number)
    .map((value) => ({
      ...value,
      live: set.filter((candidate) => isLiveAt(index, candidate.id, value.publication.number)).length,
    }))
}

// ---------------------------------------------------------------- sources

export type SourceState = 'published' | 'review' | 'compiling' | 'failed' | 'none' | 'unreadable'

export interface StatusCounts {
  total: number
  pending: number
  approved: number
  edited: number
  rejected: number
  published: number
}

export interface SourceView {
  artifact: ArtifactSummary
  state: SourceState
  /** Null when the candidates could not be read. */
  counts: StatusCounts | null
  /** Candidates still to be decided or published. */
  unresolved: number
  /** Distinct publications that added this source's candidates, newest first. */
  publications: PublicationRef[]
  /** Items from this source in the published set now. */
  live: number
  candidates: Candidate[]
}

export function countStatuses(candidates: Candidate[]): StatusCounts {
  const counts: StatusCounts = { total: candidates.length, pending: 0, approved: 0, edited: 0, rejected: 0, published: 0 }
  for (const candidate of candidates) {
    if (candidate.reviewStatus === 'PENDING') counts.pending += 1
    else if (candidate.reviewStatus === 'APPROVED') counts.approved += 1
    else if (candidate.reviewStatus === 'EDITED') counts.edited += 1
    else if (candidate.reviewStatus === 'REJECTED') counts.rejected += 1
    else counts.published += 1
  }
  return counts
}

function sourceView(artifact: ArtifactSummary, candidates: Candidate[] | undefined, index: PublicationIndex): SourceView {
  if (artifact.ingestStatus === 'FAILED') {
    return { artifact, state: 'failed', counts: null, unresolved: 0, publications: [], live: 0, candidates: [] }
  }
  if (artifact.ingestStatus === 'RECEIVED' || artifact.ingestStatus === 'EXTRACTING') {
    return { artifact, state: 'compiling', counts: null, unresolved: 0, publications: [], live: 0, candidates: [] }
  }
  if (!candidates) {
    return { artifact, state: 'unreadable', counts: null, unresolved: 0, publications: [], live: 0, candidates: [] }
  }
  const counts = countStatuses(candidates)
  const unresolved = counts.pending + counts.approved + counts.edited
  const publications = versionsOf(candidates, index)
    .filter((version) => version.added > 0)
    .map((version) => version.publication)
  const live = candidates.filter((candidate) => isLiveAt(index, candidate.id)).length
  const state: SourceState = unresolved > 0 ? 'review' : counts.published > 0 ? 'published' : 'none'
  return { artifact, state, counts, unresolved, publications, live, candidates }
}

// -------------------------------------------------------------- playbooks

export interface PlaybookView {
  artifact: ArtifactSummary
  /** Every candidate of this source that has ever been published. */
  published: Candidate[]
  /** The newest publication that changed this playbook, by adding or superseding positions. */
  latest: PublicationRef
  /** Positions in the published set now. */
  live: number
  versions: VersionEntry[]
}

/** The positions of one playbook source as of a publication number. With no number, as of now. */
export function playbookPositions(view: PlaybookView, index: PublicationIndex, at?: number): Candidate[] {
  return view.published.filter((candidate) => isLiveAt(index, candidate.id, at))
}

// ----------------------------------------------------------- clause library

export interface ClauseVariant {
  candidate: Candidate
  artifact: ArtifactSummary
  publication: PublicationRef
}

export interface ClauseGroup {
  /** The source clause id when it is unique to one library; a shared one scoped by source; else the concept, else the candidate id. Safe to use as a route id. */
  key: string
  title: string
  conceptKey: string | null
  variants: ClauseVariant[]
}

function payloadValue(candidate: Candidate, role: string): string | null {
  for (const [label, value] of Object.entries(effectivePayloadColumns(candidate))) {
    if (candidate.payloadFieldRoles[label] === role && value.trim()) return value.trim()
  }
  return null
}

/** The source's own clause id ("Clause ID", "Ref"), when it states one. */
function clauseReference(candidate: Candidate): string | null {
  return payloadValue(candidate, 'REFERENCE')
}

/** The source's own topic for the clause ("Topic", "Clause topic"), when it states one. */
function clauseTopic(candidate: Candidate): string | null {
  return payloadValue(candidate, 'TOPIC')
}

/** The source's own variant name ("Preferred", "Fallback 1"), when it states one. */
function clauseVariant(candidate: Candidate): string | null {
  return payloadValue(candidate, 'VARIANT')
}

export function clauseKey(candidate: Candidate, sharedReferences: ReadonlySet<string> = new Set()): string {
  const reference = clauseReference(candidate)
  if (reference) {
    return sharedReferences.has(reference) ? `${candidate.artifactId}:${reference}` : reference
  }
  return candidate.effectiveConceptKey ?? `candidate-${candidate.id}`
}

/**
 * Source clause references that more than one clause library states. A reference identifies a
 * clause only inside its own source, so a shared one is scoped by the source; the reference
 * itself stays on the variant as its source's display text.
 */
export function sharedClauseReferences(view: ClauseLibraryView): ReadonlySet<string> {
  const sourcesByReference = new Map<string, Set<string>>()
  for (const { candidate, artifact } of view.published) {
    const reference = clauseReference(candidate)
    if (!reference) continue
    const sources = sourcesByReference.get(reference) ?? new Set<string>()
    sources.add(artifact.id)
    sourcesByReference.set(reference, sources)
  }
  return new Set(
    [...sourcesByReference].filter(([, sources]) => sources.size > 1).map(([reference]) => reference),
  )
}

export interface ClauseLibraryView {
  /** Clause variants that have ever been published, with their source. */
  published: { candidate: Candidate; artifact: ArtifactSummary }[]
  versions: VersionEntry[]
  latest: PublicationRef | null
  /** How many distinct sources the library is built from. */
  sourceCount: number
}

/**
 * Clauses grouped by the source clause id, so the variants of one source clause stay together;
 * a reference that more than one source states is scoped by the source, a reference-less variant
 * falls back to the legal concept or candidate id, and a variant with no concept is its own
 * clause. As of a publication number; with no number, as of now.
 */
export function clauseGroups(
  view: ClauseLibraryView,
  index: PublicationIndex,
  label: ConceptLabel,
  at?: number,
): ClauseGroup[] {
  const groups = new Map<string, ClauseGroup>()
  const sharedReferences = sharedClauseReferences(view)
  for (const { candidate, artifact } of view.published) {
    if (!isLiveAt(index, candidate.id, at)) continue
    const publication = index.added.get(candidate.id)
    if (!publication) continue
    const key = clauseKey(candidate, sharedReferences)
    let group = groups.get(key)
    if (!group) {
      group = {
        key,
        title: candidate.effectiveConceptKey ? label(candidate.effectiveConceptKey) : candidate.title,
        conceptKey: candidate.effectiveConceptKey,
        variants: [],
      }
      groups.set(key, group)
    }
    group.variants.push({ candidate, artifact, publication })
  }
  for (const group of groups.values()) {
    group.title = familyTitle(group, label)
  }
  return [...groups.values()]
}

/**
 * A source clause's variants can be classified under different topics: the preferred wording
 * sets the obligation, the fallback the period. The source's own topic then names the family;
 * a family on one topic keeps that topic's label.
 */
function familyTitle(group: ClauseGroup, label: ConceptLabel): string {
  const topics = group.variants
    .map((variant) => variant.candidate.effectiveConceptKey)
    .filter((key): key is string => key !== null)
    .map(label)
  if (new Set(topics).size < 2) return group.title
  const first = group.variants[0]
  return (first ? clauseTopic(first.candidate) : null) ?? group.title
}

/** The topic a variant was classified under, in words, else the source's own topic. */
function variantTopic(candidate: Candidate, label: ConceptLabel): string | null {
  return candidate.effectiveConceptKey ? label(candidate.effectiveConceptKey) : clauseTopic(candidate)
}

/**
 * One line for a group. When its variants sit on different topics, each variant is shown with
 * its own, so the topics that used to split the family read as facets of one clause.
 */
export function clauseGroupDetail(group: ClauseGroup, label: ConceptLabel): string {
  const topics = group.variants.map((variant) => variantTopic(variant.candidate, label))
  const distinct = new Set(topics.filter((topic): topic is string => topic !== null))
  if (distinct.size < 2) {
    return group.variants.map((variant) => variant.candidate.title).join(', ')
  }
  return group.variants
    .map((variant, at) => {
      const name = clauseVariant(variant.candidate) ?? variant.candidate.title
      const topic = topics[at]
      return topic && topic !== name ? `${name} · ${topic}` : name
    })
    .join(', ')
}

// ---------------------------------------------------------------- catalog

export interface RecentPublication {
  key: string
  artifact: ArtifactSummary
  type: 'PLAYBOOK' | 'CLAUSE_LIBRARY'
  publication: PublicationRef
  /** Items this source added in that publication. */
  count: number
}

export interface Catalog {
  index: PublicationIndex
  sources: SourceView[]
  playbooks: PlaybookView[]
  clauseLibrary: ClauseLibraryView
  /** Sources whose candidates could not be read, so their published state is unknown. */
  unreadable: ArtifactSummary[]
  /** Sources being compiled or reviewed. Nothing here is used in a review. */
  inCompilation: SourceView[]
  recent: RecentPublication[]
}

export function buildCatalog({ artifacts, publications, candidates }: CatalogInput): Catalog {
  const index = buildPublicationIndex(publications)
  const sources = artifacts.map((artifact) => sourceView(artifact, candidates.get(artifact.id), index))

  const playbooks: PlaybookView[] = []
  const clausePublished: ClauseLibraryView['published'] = []
  const clauseCandidates: Candidate[] = []
  const clauseSources = new Set<string>()
  for (const source of sources) {
    const published = source.candidates.filter((candidate) => index.added.has(candidate.id))
    if (published.length === 0) continue
    if (source.artifact.artifactType === 'PLAYBOOK') {
      const versions = versionsOf(published, index)
      const latest = versions[0]
      if (!latest) continue
      playbooks.push({
        artifact: source.artifact,
        published,
        latest: latest.publication,
        live: published.filter((candidate) => isLiveAt(index, candidate.id)).length,
        versions,
      })
    } else if (source.artifact.artifactType === 'CLAUSE_LIBRARY') {
      clauseSources.add(source.artifact.id)
      for (const candidate of published) {
        if (candidate.candidateType !== 'CLAUSE_VARIANT') continue
        clausePublished.push({ candidate, artifact: source.artifact })
        clauseCandidates.push(candidate)
      }
    }
  }
  playbooks.sort((a, b) => b.latest.number - a.latest.number || b.artifact.createdAt.localeCompare(a.artifact.createdAt))

  const clauseVersions = versionsOf(clauseCandidates, index)
  const clauseLibrary: ClauseLibraryView = {
    published: clausePublished,
    versions: clauseVersions,
    latest: clauseVersions.find((version) => version.added > 0)?.publication ?? null,
    sourceCount: clauseSources.size,
  }

  const recent: RecentPublication[] = []
  for (const source of sources) {
    const type = source.artifact.artifactType
    if (type !== 'PLAYBOOK' && type !== 'CLAUSE_LIBRARY') continue
    for (const version of versionsOf(source.candidates, index)) {
      if (version.added === 0) continue
      recent.push({
        key: `${source.artifact.id}:${version.publication.number}`,
        artifact: source.artifact,
        type,
        publication: version.publication,
        count: version.added,
      })
    }
  }
  recent.sort((a, b) => b.publication.number - a.publication.number || b.publication.createdAt.localeCompare(a.publication.createdAt))

  return {
    index,
    sources,
    playbooks,
    clauseLibrary,
    unreadable: sources.filter((source) => source.state === 'unreadable').map((source) => source.artifact),
    inCompilation: sources.filter(
      (source) =>
        (source.artifact.artifactType === 'PLAYBOOK' || source.artifact.artifactType === 'CLAUSE_LIBRARY') &&
        (source.state === 'compiling' || source.state === 'review'),
    ),
    recent,
  }
}

// ------------------------------------------------------------- presentation

/** The title the file gives itself, else the filename without its extension. */
export function artifactName(artifact: ArtifactSummary): string {
  return artifact.documentTitle ?? artifact.filename.replace(/\.(docx|xlsx)$/i, '')
}

export function artifactFormat(artifact: ArtifactSummary): string {
  const extension = /\.([a-z0-9]+)$/i.exec(artifact.filename)?.[1]?.toLowerCase()
  if (extension === 'docx') return 'Word file'
  if (extension === 'xlsx') return 'Excel file'
  return 'File'
}

const TYPE_NOUN: Partial<Record<ArtifactType, [string, string]>> = {
  PLAYBOOK: ['position', 'positions'],
  CLAUSE_LIBRARY: ['clause variant', 'clause variants'],
}

/** "1 position", "5 positions", "3 clause variants". Other source types hold generic items. */
export function countNoun(type: ArtifactType, count: number): string {
  const [one, many] = TYPE_NOUN[type] ?? ['entry', 'entries']
  return `${count} ${count === 1 ? one : many}`
}

export function positionText(candidate: Candidate): string | null {
  const text = effectiveContentText(candidate)?.trim()
  return text ? text : null
}

export function publicationsText(publications: PublicationRef[]): string {
  const numbers = [...publications].map((publication) => publication.number).sort((a, b) => a - b)
  return numbers.length === 1 ? `knowledge version ${numbers[0]}` : `knowledge versions ${numbers.join(', ')}`
}

/** The same, capitalized for a heading. */
export function publicationsTitle(publications: PublicationRef[]): string {
  const text = publicationsText(publications)
  return text.charAt(0).toUpperCase() + text.slice(1)
}

/** One factual line about a source: what happened to it, from real counts only. */
export function sourceNote(source: SourceView): string {
  const type = source.artifact.artifactType
  const counts = source.counts
  switch (source.state) {
    case 'failed':
      return 'Could not read file'
    case 'compiling':
      return 'Reading entries'
    case 'unreadable':
      return 'Its entries could not be read'
    case 'review':
      if (counts && counts.published > 0) {
        return `${countNoun(type, counts.published)} published, ${source.unresolved} awaiting review`
      }
      return counts ? `Draft, ${counts.total - counts.pending} of ${counts.total} entries reviewed` : 'Draft'
    case 'published':
      return `${countNoun(type, counts?.published ?? 0)} published in ${publicationsText(source.publications)}`
    case 'none':
      return counts && counts.total > 0 ? 'No entry was published' : 'No entries were produced'
  }
}

/** "4 of 12 candidates reviewed": reviewed means a human has decided it, published or not. */
export function reviewedText(source: SourceView): string | null {
  if (!source.counts) return null
  const { total, pending } = source.counts
  return `${total - pending} of ${total} entries reviewed`
}
