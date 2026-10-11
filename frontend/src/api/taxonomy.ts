import { useCallback, useMemo } from 'react'
import { useQuery, type UseQueryResult } from '@tanstack/react-query'
import { createApiClient } from './client'
import {
  asRecord,
  readArray,
  readNumber,
  readOptionalNullableString,
  readString,
} from './envelope'
import { humanizeCode } from '../lib/domainLabels'

/**
 * The legal topics clauses and playbook positions are classified against
 * (`TaxonomyResponse` / `TaxonomyConceptView` in the generated schema), read and checked here.
 */
export interface TaxonomyConcept {
  key: string
  label: string
  parentKey: string | null
  definition: string | null
}

export interface Taxonomy {
  version: number
  parents: TaxonomyConcept[]
  leaves: TaxonomyConcept[]
}

/** Resolves a topic key to the words a reviewer reads. Never returns a bare key. */
export type ConceptLabel = (key: string) => string

export interface TaxonomyGroup {
  parentKey: string | null
  label: string
  leaves: TaxonomyConcept[]
}

function parseTaxonomyConcept(value: unknown, what = 'taxonomy'): TaxonomyConcept {
  const concept = asRecord(value, what)
  return {
    key: readString(concept, 'key', what),
    label: readString(concept, 'label', what),
    parentKey: readOptionalNullableString(concept, 'parentKey', what),
    definition: readOptionalNullableString(concept, 'definition', what),
  }
}

export function parseTaxonomy(value: unknown, what = 'taxonomy'): Taxonomy {
  const taxonomy = asRecord(value, what)
  return {
    version: readNumber(taxonomy, 'version', what),
    parents: readArray(taxonomy, 'parents', what, parseTaxonomyConcept),
    leaves: readArray(taxonomy, 'leaves', what, parseTaxonomyConcept),
  }
}

export const taxonomyKeys = {
  taxonomy: ['taxonomy', 'concepts'] as const,
}

export async function fetchTaxonomy(): Promise<Taxonomy> {
  const client = createApiClient()
  const { data, response } = await client.GET('/api/taxonomy/concepts')
  if (!data) {
    throw new Error(`The legal topic list could not be loaded (${response.status}).`)
  }
  return parseTaxonomy(data)
}

/** Every known key's label, parents included, so a decision recorded against either renders. */
export function taxonomyLabels(taxonomy: Taxonomy | undefined): Map<string, string> {
  const labels = new Map<string, string>()
  if (!taxonomy) return labels
  for (const concept of [...taxonomy.parents, ...taxonomy.leaves]) {
    labels.set(concept.key, concept.label)
  }
  return labels
}

export function taxonomyGroups(taxonomy: Taxonomy | undefined): TaxonomyGroup[] {
  if (!taxonomy) return []
  const groups: TaxonomyGroup[] = taxonomy.parents.map((parent) => ({
    parentKey: parent.key,
    label: parent.label,
    leaves: taxonomy.leaves.filter((leaf) => leaf.parentKey === parent.key),
  }))
  const knownParents = new Set(taxonomy.parents.map((parent) => parent.key))
  const ungrouped = taxonomy.leaves.filter(
    (leaf) => leaf.parentKey === null || !knownParents.has(leaf.parentKey),
  )
  if (ungrouped.length > 0) {
    groups.push({ parentKey: null, label: 'Other legal topics', leaves: ungrouped })
  }
  return groups
}

export interface TaxonomyView {
  version: number | null
  parents: TaxonomyConcept[]
  leaves: TaxonomyConcept[]
  groups: TaxonomyGroup[]
  labelFor: ConceptLabel
  isPending: boolean
  isError: boolean
}

/**
 * The one source of topic labels and the pickable leaf list. The taxonomy only changes on deploy,
 * so one fetch lasts the session. Components that render a topic key call this; any number of them
 * share the same request.
 */
export function useTaxonomy(): TaxonomyView {
  const query: UseQueryResult<Taxonomy, Error> = useQuery({
    queryKey: taxonomyKeys.taxonomy,
    queryFn: fetchTaxonomy,
    staleTime: Infinity,
    gcTime: Infinity,
  })
  const labels = useMemo(() => taxonomyLabels(query.data), [query.data])
  const groups = useMemo(() => taxonomyGroups(query.data), [query.data])
  const labelFor = useCallback(
    (key: string) => labels.get(key) ?? humanizeCode(key),
    [labels],
  )
  return {
    version: query.data?.version ?? null,
    parents: query.data?.parents ?? [],
    leaves: query.data?.leaves ?? [],
    groups,
    labelFor,
    isPending: query.isPending,
    isError: query.isError,
  }
}
