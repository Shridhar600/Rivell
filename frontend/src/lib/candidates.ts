import type { Candidate, ExtractionUnit, KnowledgeNode } from '../api/knowledge'

export function effectiveContentText(candidate: Candidate): string | null {
  return candidate.editedContentText ?? candidate.contentText
}

export function effectivePayloadColumns(
  candidate: Candidate,
): Record<string, string> {
  return Object.keys(candidate.editedPayloadColumns).length > 0
    ? candidate.editedPayloadColumns
    : candidate.payloadColumns
}

export function resolveCandidateSource(
  candidate: Candidate,
  units: ExtractionUnit[],
  nodes: KnowledgeNode[],
): { unit: ExtractionUnit | null; nodes: KnowledgeNode[] } {
  const unit =
    units.find((candidateUnit) => candidateUnit.id === candidate.extractionUnitId) ??
    null
  if (!unit) return { unit: null, nodes: [] }

  const nodeById = new Map(nodes.map((node) => [node.id, node]))
  const sourceNodes = unit.sourceNodeIds
    .map((nodeId) => nodeById.get(nodeId))
    .filter((node): node is KnowledgeNode => node !== undefined)

  return { unit, nodes: sourceNodes }
}
