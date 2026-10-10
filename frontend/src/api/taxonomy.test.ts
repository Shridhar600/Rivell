import { describe, expect, it } from 'vitest'
import {
  conceptLabel,
  parseTaxonomy,
  taxonomyGroups,
  taxonomyLabels,
  type Taxonomy,
} from './taxonomy'

const payload = {
  version: 1,
  parents: [
    { key: 'CONFIDENTIALITY', label: 'Confidentiality', parentKey: null, definition: null },
    { key: 'TERM_AND_TERMINATION', label: 'Term and termination', parentKey: null, definition: null },
  ],
  leaves: [
    {
      key: 'CONFIDENTIALITY_OBLIGATIONS',
      label: 'Confidentiality obligations',
      parentKey: 'CONFIDENTIALITY',
      definition: 'The receiving party must protect the information.',
    },
    {
      key: 'ONWARD_DISCLOSURE',
      label: 'Disclosure to representatives',
      parentKey: 'CONFIDENTIALITY',
      definition: null,
    },
    { key: 'TERM', label: 'Term', parentKey: 'TERM_AND_TERMINATION', definition: null },
  ],
}

describe('parseTaxonomy', () => {
  it('reads the parents and leaves the endpoint returns', () => {
    const taxonomy = parseTaxonomy(payload)
    expect(taxonomy.version).toBe(1)
    expect(taxonomy.parents.map((parent) => parent.key)).toEqual([
      'CONFIDENTIALITY',
      'TERM_AND_TERMINATION',
    ])
    expect(taxonomy.leaves[1]).toEqual({
      key: 'ONWARD_DISCLOSURE',
      label: 'Disclosure to representatives',
      parentKey: 'CONFIDENTIALITY',
      definition: null,
    })
  })

  it('refuses a concept without a key rather than showing it unlabelled', () => {
    expect(() =>
      parseTaxonomy({ ...payload, leaves: [{ label: 'Missing key' }] }),
    ).toThrow(/taxonomy/)
  })
})

describe('taxonomy labels', () => {
  const taxonomy = parseTaxonomy(payload) as Taxonomy

  it('labels keys from the API and humanizes keys the taxonomy does not know', () => {
    expect(conceptLabel(taxonomy, 'ONWARD_DISCLOSURE')).toBe('Disclosure to representatives')
    expect(conceptLabel(taxonomy, 'RETIRED_TOPIC')).toBe('Retired topic')
    expect(conceptLabel(undefined, 'RETIRED_TOPIC')).toBe('Retired topic')
  })

  it('maps every parent and leaf key to its label', () => {
    const labels = taxonomyLabels(taxonomy)
    expect(labels.get('TERM')).toBe('Term')
    expect(labels.get('CONFIDENTIALITY')).toBe('Confidentiality')
  })
})

describe('taxonomy groups', () => {
  it('groups leaves under their parent label in the API order', () => {
    const groups = taxonomyGroups(parseTaxonomy(payload))
    expect(groups.map((group) => group.label)).toEqual([
      'Confidentiality',
      'Term and termination',
    ])
    expect(groups[0].leaves.map((leaf) => leaf.key)).toEqual([
      'CONFIDENTIALITY_OBLIGATIONS',
      'ONWARD_DISCLOSURE',
    ])
  })

  it('keeps a leaf with an unknown parent reachable', () => {
    const taxonomy = parseTaxonomy({
      ...payload,
      leaves: [...payload.leaves, { key: 'ORPHAN', label: 'Orphan', parentKey: 'GONE', definition: null }],
    })
    const groups = taxonomyGroups(taxonomy)
    expect(groups.at(-1)?.label).toBe('Other legal topics')
    expect(groups.at(-1)?.leaves.map((leaf) => leaf.key)).toEqual(['ORPHAN'])
  })
})
