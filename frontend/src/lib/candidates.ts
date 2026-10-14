import type { Candidate, ExtractionUnit, KnowledgeNode } from '../api/knowledge'

/**
 * The single authoritative wording of a candidate, mirroring the backend's effectiveContentText():
 * explicit human wording, then the wording rendered from the human's corrected columns, then the
 * machine wording captured at extraction. A layer that is absent or blank is skipped rather than
 * accepted, so a blank correction can never hide valid machine wording.
 */
export function effectiveContentText(candidate: Candidate): string | null {
  return firstReadable(
    candidate.editedContentText,
    renderCorrectedColumns(candidate),
    candidate.contentText,
  )
}

export function effectivePayloadColumns(
  candidate: Candidate,
): Record<string, string> {
  return Object.keys(candidate.editedPayloadColumns).length > 0
    ? candidate.editedPayloadColumns
    : candidate.payloadColumns
}

/**
 * The corrected columns rendered the way extraction renders machine wording: the non-blank values
 * in source order, joined by " | ". A correction replaces the values it names and never reorders
 * the row, so the order recovered from the machine wording is applied to the corrected values.
 */
function renderCorrectedColumns(candidate: Candidate): string | null {
  const corrected = candidate.editedPayloadColumns
  const received = Object.keys(corrected)
  if (received.length === 0) return null

  const machineOrder = recoverSourceOrder(candidate.contentText, candidate.payloadColumns)
  const ordered = machineOrder
    ? [
        ...machineOrder.filter((name) => name in corrected),
        ...received.filter((name) => !machineOrder.includes(name)),
      ]
    : received

  const rendered = ordered
    .map((name) => corrected[name])
    .filter((value) => value !== null && value !== undefined && value.trim() !== '')
  return rendered.length > 0 ? rendered.join(' | ') : null
}

/**
 * The machine wording is the reliable record of source order, because extraction rendered it left
 * to right. It is replayed against the machine columns to recover which column came first. The
 * recovery is applied only when the wording is exactly the rendering of those values; otherwise no
 * source order is claimed and the columns are read in the order they arrived.
 */
function recoverSourceOrder(
  machineWording: string | null,
  machineColumns: Record<string, string>,
): string[] | null {
  if (machineWording === null || machineWording.trim() === '') return null
  const readable = machineWording
    .split('|')
    .map((value) => value.trim())
    .filter((value) => value !== '')
  const remaining = new Map(
    Object.entries(machineColumns).filter(([, value]) => value !== null && value.trim() !== ''),
  )
  if (remaining.size !== readable.length) return null

  const order: string[] = []
  for (const value of readable) {
    const match = [...remaining].find(([, columnValue]) => columnValue === value)
    if (!match) return null
    remaining.delete(match[0])
    order.push(match[0])
  }
  return order
}

function firstReadable(...layers: (string | null)[]): string | null {
  for (const layer of layers) {
    if (layer !== null && layer.trim() !== '') return layer
  }
  return null
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