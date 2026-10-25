import { describe, expect, it } from 'vitest'
import {
  artifactFormat,
  artifactName,
  buildCatalog,
  buildPublicationIndex,
  clauseGroupDetail,
  clauseGroups,
  countNoun,
  isLiveAt,
  playbookPositions,
  sourceNote,
  versionsOf,
} from './knowledgeCatalog'
import { artifact, candidate, publication } from '../test/knowledgeFixtures'
import { humanizeCode } from './domainLabels'

const pb = artifact({ id: 'pb', filename: 'Commercial MSA Playbook.docx' })
const cl = artifact({ id: 'cl', artifactType: 'CLAUSE_LIBRARY', filename: 'Clauses.xlsx' })

describe('publication index', () => {
  const index = buildPublicationIndex([
    publication(1, [{ candidateId: 'a' }, { candidateId: 'b' }]),
    publication(2, [{ candidateId: 'c', supersedes: 'a' }]),
  ])

  it('records which publication added a candidate', () => {
    expect(index.added.get('a')?.number).toBe(1)
    expect(index.added.get('c')?.number).toBe(2)
  })

  it('reads a snapshot as of a publication, without the later supersession', () => {
    expect(isLiveAt(index, 'a', 1)).toBe(true)
    expect(isLiveAt(index, 'a', 2)).toBe(false)
    expect(isLiveAt(index, 'a')).toBe(false)
    expect(isLiveAt(index, 'c', 1)).toBe(false)
    expect(isLiveAt(index, 'c', 2)).toBe(true)
  })

  it('treats a candidate that was never published as not live', () => {
    expect(isLiveAt(index, 'never')).toBe(false)
  })
})

describe('version history', () => {
  const set = [
    candidate({ id: 'a', artifactId: 'pb' }),
    candidate({ id: 'b', artifactId: 'pb' }),
    candidate({ id: 'c', artifactId: 'pb' }),
  ]
  const index = buildPublicationIndex([
    publication(1, [{ candidateId: 'a' }, { candidateId: 'b' }], { note: 'First' }),
    publication(3, [{ candidateId: 'c', supersedes: 'a' }]),
  ])

  it('lists only publications that changed the set, newest first, with real counts', () => {
    const versions = versionsOf(set, index)
    expect(versions.map((v) => v.publication.number)).toEqual([3, 1])
    expect(versions[1]).toMatchObject({ added: 2, superseded: 0, live: 2 })
    expect(versions[0]).toMatchObject({ added: 1, superseded: 1, live: 2 })
  })

  it('does not invent a version for a publication that did not touch the set', () => {
    const versions = versionsOf(set, buildPublicationIndex([publication(2, [{ candidateId: 'unrelated' }])]))
    expect(versions).toEqual([])
  })
})

describe('artifact names', () => {
  it('uses the document title a playbook gives itself', () => {
    expect(artifactName(artifact({ id: 'pb', filename: 'Commercial MSA Playbook.docx', documentTitle: 'Mutual NDA Negotiation Guide' }))).toBe(
      'Mutual NDA Negotiation Guide',
    )
  })

  it('falls back to the file name when the file names no title', () => {
    expect(artifactName(artifact({ id: 'cl', filename: 'Clauses.xlsx', documentTitle: null }))).toBe('Clauses')
  })
})

describe('catalog', () => {
  const candidates = new Map([
    [
      'pb',
      [
        candidate({ id: 'p1', artifactId: 'pb', rawLabel: 'Liability' }),
        candidate({ id: 'p2', artifactId: 'pb', rawLabel: 'Payment', reviewStatus: 'PENDING' }),
      ],
    ],
    [
      'cl',
      [
        candidate({ id: 'c1', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Mutual', effectiveConceptKey: 'LIMITATION_OF_LIABILITY' }),
        candidate({ id: 'c2', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Supplier', effectiveConceptKey: 'LIMITATION_OF_LIABILITY' }),
        candidate({ id: 'c3', artifactId: 'cl', candidateType: 'CLAUSE_VARIANT', rawLabel: 'Audit | MSA | 2026', title: 'Audit', effectiveConceptKey: null }),
      ],
    ],
  ])
  const publications = [
    publication(1, [{ candidateId: 'p1' }], { note: 'Playbook' }),
    publication(2, [{ candidateId: 'c1' }, { candidateId: 'c3' }]),
    publication(3, [{ candidateId: 'c2' }]),
  ]
  const catalog = buildCatalog({ artifacts: [pb, cl], publications, candidates })

  it('lists a playbook only when something from it is published', () => {
    expect(catalog.playbooks.map((p) => p.artifact.id)).toEqual(['pb'])
    expect(catalog.playbooks[0]?.live).toBe(1)
    const unpublished = buildCatalog({ artifacts: [pb], publications: [], candidates })
    expect(unpublished.playbooks).toEqual([])
  })

  it('reports a source that still has candidates to decide as in compilation', () => {
    expect(catalog.sources.find((s) => s.artifact.id === 'pb')).toMatchObject({ state: 'review', unresolved: 1 })
    expect(catalog.inCompilation.map((s) => s.artifact.id)).toEqual(['pb'])
  })

  it('groups clause variants by their typed legal concept and never by title', () => {
    const groups = clauseGroups(catalog.clauseLibrary, catalog.index, humanizeCode)
    const liability = groups.find((g) => g.conceptKey === 'LIMITATION_OF_LIABILITY')
    expect(liability?.variants.map((v) => v.candidate.id)).toEqual(['c1', 'c2'])
    // A variant with no concept is its own clause, however similar its label is to another.
    const audit = groups.find((g) => g.conceptKey === null)
    expect(audit?.variants.map((v) => v.candidate.id)).toEqual(['c3'])
    expect(audit?.key).toBe('candidate-c3')
    expect(audit?.title).toBe('Audit')
  })

  it('keeps the variants of one source clause together and shows each variant topic', () => {
    // The two rows of G-CONF-01 in the clause library fixture: one preferred wording, one
    // fallback, classified under different topics.
    const rows = [
      candidate({
        id: 'conf-preferred',
        artifactId: 'cl',
        candidateType: 'CLAUSE_VARIANT',
        title: 'Confidentiality · Preferred',
        effectiveConceptKey: 'CONFIDENTIALITY_OBLIGATIONS',
        payloadColumns: { 'Clause ID': 'G-CONF-01', Topic: 'Confidentiality', Variant: 'Preferred' },
        payloadFieldRoles: { 'Clause ID': 'REFERENCE', Topic: 'TOPIC', Variant: 'VARIANT' },
      }),
      candidate({
        id: 'conf-fallback',
        artifactId: 'cl',
        candidateType: 'CLAUSE_VARIANT',
        title: 'Confidentiality · Fallback 1',
        effectiveConceptKey: 'CONFIDENTIALITY_PERIOD',
        payloadColumns: { 'Clause ID': 'G-CONF-01', Topic: 'Confidentiality', Variant: 'Fallback 1' },
        payloadFieldRoles: { 'Clause ID': 'REFERENCE', Topic: 'TOPIC', Variant: 'VARIANT' },
      }),
    ]
    const world = buildCatalog({
      artifacts: [cl],
      publications: [publication(1, [{ candidateId: 'conf-preferred' }, { candidateId: 'conf-fallback' }])],
      candidates: new Map([['cl', rows]]),
    })

    const groups = clauseGroups(world.clauseLibrary, world.index, humanizeCode)

    expect(groups).toHaveLength(1)
    expect(groups[0]).toMatchObject({ key: 'G-CONF-01', title: 'Confidentiality' })
    expect(clauseGroupDetail(groups[0]!, humanizeCode)).toBe(
      'Preferred · Confidentiality obligations, Fallback 1 · Confidentiality period',
    )
  })

  it('reads the clause library as of an older publication', () => {
    const at2 = clauseGroups(catalog.clauseLibrary, catalog.index, humanizeCode, 2)
    expect(at2.find((g) => g.conceptKey === 'LIMITATION_OF_LIABILITY')?.variants).toHaveLength(1)
    expect(catalog.clauseLibrary.versions.map((v) => v.publication.number)).toEqual([3, 2])
    expect(catalog.clauseLibrary.latest?.number).toBe(3)
  })

  it('orders recent publications newest first and counts what each added', () => {
    expect(catalog.recent.map((r) => [r.artifact.id, r.publication.number, r.count])).toEqual([
      ['cl', 3, 1],
      ['cl', 2, 2],
      ['pb', 1, 1],
    ])
  })

  it('flags a source whose candidates could not be read instead of guessing', () => {
    const partial = buildCatalog({ artifacts: [pb, cl], publications, candidates: new Map([['cl', candidates.get('cl') ?? []]]) })
    expect(partial.unreadable.map((a) => a.id)).toEqual(['pb'])
    expect(partial.playbooks).toEqual([])
  })

  it('reads a snapshot of a playbook as of an older publication', () => {
    const index = buildPublicationIndex([
      publication(1, [{ candidateId: 'x' }, { candidateId: 'y' }]),
      publication(2, [{ candidateId: 'z', supersedes: 'x' }]),
    ])
    const set = [
      candidate({ id: 'x', artifactId: 'pb' }),
      candidate({ id: 'y', artifactId: 'pb' }),
      candidate({ id: 'z', artifactId: 'pb' }),
    ]
    const view = buildCatalog({ artifacts: [pb], publications: [publication(1, [{ candidateId: 'x' }, { candidateId: 'y' }]), publication(2, [{ candidateId: 'z', supersedes: 'x' }])], candidates: new Map([['pb', set]]) }).playbooks[0]
    expect(view).toBeDefined()
    if (!view) return
    expect(playbookPositions(view, index, 1).map((c) => c.id)).toEqual(['x', 'y'])
    expect(playbookPositions(view, index).map((c) => c.id)).toEqual(['y', 'z'])
  })

  it('classifies a source that is extracting or failed without reading candidates', () => {
    const view = buildCatalog({
      artifacts: [artifact({ id: 'e', ingestStatus: 'EXTRACTING' }), artifact({ id: 'f', ingestStatus: 'FAILED' })],
      publications: [],
      candidates: new Map(),
    })
    expect(view.sources.map((s) => s.state)).toEqual(['compiling', 'failed'])
    expect(view.unreadable).toEqual([])
  })
})

describe('publication history isolation', () => {
  const a = artifact({ id: 'a', filename: 'A.docx' })
  const b = artifact({ id: 'b', filename: 'B.docx' })
  const set = (id: string, ids: string[]) => ids.map((entry) => candidate({ id: entry, artifactId: id }))
  const publications = [
    publication(1, [{ candidateId: 'a1' }, { candidateId: 'a2' }]),
    publication(2, [{ candidateId: 'b1' }, { candidateId: 'b2' }]),
    publication(3, [{ candidateId: 'a3', supersedes: 'a1' }]),
    publication(4, [{ candidateId: 'a4' }]),
  ]
  const candidates = new Map([
    ['a', set('a', ['a1', 'a2', 'a3', 'a4'])],
    ['b', set('b', ['b1', 'b2'])],
  ])
  const catalog = buildCatalog({ artifacts: [a, b], publications, candidates })
  const playbook = (id: string) => {
    const view = catalog.playbooks.find((entry) => entry.artifact.id === id)
    if (!view) throw new Error(`no playbook ${id}`)
    return view
  }
  const ids = (id: string, at?: number) => playbookPositions(playbook(id), catalog.index, at).map((c) => c.id)

  it('never shows publication N an item that was first published in N+1 or later', () => {
    expect(ids('a', 1)).toEqual(['a1', 'a2'])
    expect(ids('a', 2)).toEqual(['a1', 'a2'])
    expect(ids('a', 3)).toEqual(['a2', 'a3'])
    expect(ids('a', 4)).toEqual(['a2', 'a3', 'a4'])
  })

  it('removes a superseded item at its superseding publication and not before', () => {
    expect(isLiveAt(catalog.index, 'a1', 2)).toBe(true)
    expect(isLiveAt(catalog.index, 'a1', 3)).toBe(false)
    expect(isLiveAt(catalog.index, 'a1', 4)).toBe(false)
    // The replacement is live exactly from that publication.
    expect(isLiveAt(catalog.index, 'a3', 2)).toBe(false)
    expect(isLiveAt(catalog.index, 'a3', 3)).toBe(true)
  })

  it('reproduces an older snapshot identically after later publications exist', () => {
    const before = buildCatalog({ artifacts: [a], publications: publications.slice(0, 1), candidates })
    const snapshot = (c: typeof catalog) => playbookPositions(c.playbooks[0]!, c.index, 1).map((entry) => entry.id)
    expect(snapshot(catalog)).toEqual(snapshot(before))
  })

  it('does not let another source\'s publications into this source\'s history', () => {
    expect(playbook('a').versions.map((v) => v.publication.number)).toEqual([4, 3, 1])
    expect(playbook('b').versions.map((v) => v.publication.number)).toEqual([2])
    // Publication 2 touched only B, so A has no version for it and no snapshot differs at 2.
    expect(playbook('a').versions.some((v) => v.publication.number === 2)).toBe(false)
    expect(playbook('b').versions[0]).toMatchObject({ added: 2, superseded: 0, live: 2 })
  })

  it('does not depend on the order the publication list arrives in', () => {
    const reversed = buildPublicationIndex([...publications].reverse())
    expect(isLiveAt(reversed, 'a1', 2)).toBe(true)
    expect(isLiveAt(reversed, 'a1', 3)).toBe(false)
  })

  it('removes a twice-superseded item at the earliest supersession, in either list order', () => {
    const twice = [
      publication(1, [{ candidateId: 'x' }]),
      publication(2, [{ candidateId: 'y', supersedes: 'x' }]),
      publication(3, [{ candidateId: 'z', supersedes: 'x' }]),
    ]
    for (const list of [twice, [...twice].reverse()]) {
      const index = buildPublicationIndex(list)
      expect(isLiveAt(index, 'x', 1)).toBe(true)
      expect(isLiveAt(index, 'x', 2)).toBe(false)
    }
  })

  it('counts a version by what changed this source only', () => {
    const entry = playbook('a').versions.find((v) => v.publication.number === 3)
    expect(entry).toMatchObject({ added: 1, superseded: 1, live: 2 })
  })
})

describe('presentation helpers', () => {
  it('names a source by its filename without the extension and nothing else', () => {
    expect(artifactName(pb)).toBe('Commercial MSA Playbook')
    expect(artifactFormat(cl)).toBe('Excel file')
  })

  it('counts with the right noun and number', () => {
    expect(countNoun('PLAYBOOK', 1)).toBe('1 position')
    expect(countNoun('PLAYBOOK', 5)).toBe('5 positions')
    expect(countNoun('CLAUSE_LIBRARY', 3)).toBe('3 clause variants')
    expect(countNoun('TEMPLATE', 2)).toBe('2 entries')
  })

  it('describes a source from real counts only', () => {
    const view = buildCatalog({
      artifacts: [pb],
      publications: [publication(1, [{ candidateId: 'p1' }])],
      candidates: new Map([['pb', [candidate({ id: 'p1', artifactId: 'pb' })]]]),
    })
    expect(sourceNote(view.sources[0]!)).toBe('1 position published in knowledge version 1')
  })
})
