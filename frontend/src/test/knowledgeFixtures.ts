import type {
  ArtifactSummary,
  Candidate,
  ExtractionUnit,
  KnowledgeNode,
  Publication,
} from '../api/knowledge'

/** Typed factories whose output is also valid wire JSON, so one object serves unit and route tests. */
export function artifact(over: Partial<ArtifactSummary> & { id: string }): ArtifactSummary {
  return {
    artifactType: 'PLAYBOOK',
    filename: `${over.id}.docx`,
    documentTitle: null,
    contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    sizeBytes: 2048,
    sha256: 'a'.repeat(64),
    ingestStatus: 'EXTRACTED',
    createdAt: '2026-10-01T09:00:00Z',
    ...over,
  }
}

export function candidate(over: Partial<Candidate> & { id: string; artifactId: string }): Candidate {
  const rawLabel = over.rawLabel ?? `Label ${over.id}`
  const authorityRules = over.authorityRules ?? []
  return {
    extractionUnitId: `unit-${over.id}`,
    candidateType: 'PLAYBOOK_RULE',
    reviewStatus: 'PUBLISHED',
    rawLabel,
    editedTitle: over.editedTitle ?? null,
    title: over.title ?? over.editedTitle ?? rawLabel,
    contentText: `Wording of ${over.id}`,
    payloadColumns: {},
    legalConceptKey: null,
    effectiveConceptKey: null,
    conceptCorrection: null,
    classifiedConcepts: {},
    positions: ['PREFERRED'],
    variantRole: null,
    escalationTrigger: null,
    requiresApproval: null,
    preferredText: null,
    fallbackTexts: [],
    prohibitedText: null,
    authorityRules,
    storedAuthorityRules: over.storedAuthorityRules ?? authorityRules,
    authorityConflicts: [],
    unmappedAuthority: [],
    needsAttention: false,
    correctedFields: [],
    interpretations: [],
    editedContentText: null,
    editedPayloadColumns: {},
    supersedingCandidateId: null,
    contractPackKey: null,
    declaredContractTypes: [],
    sourceContractTypes: [],
    partyPerspective: null,
    paperSource: null,
    jurisdiction: null,
    effectiveFrom: null,
    effectiveTo: null,
    conflictSignals: [],
    reviewCategory: null,
    reviewReasons: [],
    reviewNote: null,
    reviewedAt: null,
    createdAt: '2026-10-01T09:00:00Z',
    updatedAt: '2026-10-01T09:00:00Z',
    ...over,
  }
}

export function publication(
  number: number,
  items: { candidateId: string; supersedes?: string }[],
  over: Partial<Publication> = {},
): Publication {
  const entries: Publication['items'] = []
  for (const item of items) {
    entries.push({ id: `item-${number}-${item.candidateId}`, candidateId: item.candidateId, supersededCandidateId: null, action: 'ADD' })
    if (item.supersedes) {
      entries.push({
        id: `sup-${number}-${item.candidateId}`,
        candidateId: item.candidateId,
        supersededCandidateId: item.supersedes,
        action: 'SUPERSEDE',
      })
    }
  }
  return {
    id: `pub-${number}`,
    organizationId: 'org-1',
    publicationNumber: number,
    note: null,
    items: entries,
    createdAt: `2026-10-0${number}T10:00:00Z`,
    ...over,
  }
}

export function node(over: Partial<KnowledgeNode> & { id: string; nodeType: string }): KnowledgeNode {
  return {
    artifactId: 'a1',
    parentId: null,
    ordinal: 0,
    text: null,
    headingLevel: null,
    numberingLabel: null,
    partPath: 'word/document.xml',
    bodyIndex: 0,
    paragraphIndex: null,
    tableIndex: null,
    rowIndex: null,
    columnIndex: null,
    runStart: null,
    runEnd: null,
    sheetName: null,
    mergedRegion: null,
    rawStyleId: null,
    ...over,
  }
}

export function unit(over: Partial<ExtractionUnit> & { id: string; artifactId: string }): ExtractionUnit {
  return {
    ordinal: 0,
    kind: 'HEADING_SECTION',
    title: null,
    contentText: null,
    sourceNodeIds: [],
    recordKind: null,
    recordKindOverride: null,
    recordKindOverriddenAt: null,
    ...over,
  }
}
