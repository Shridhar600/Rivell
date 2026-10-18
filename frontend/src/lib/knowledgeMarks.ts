import type { Candidate, ExtractionUnit } from '../api/knowledge'

/**
 * What became of one part of an uploaded file (design 2026-10-12 §9): taken as a candidate, read
 * by Rivell as something other than a position, or read as a position or wording yet holding
 * nothing. A part with no text, or with no kind decision, gets no mark: there was no gate to miss.
 */
export type UnitMark = 'ABSORBED' | 'NOT_A_POSITION' | 'MISSED'

export function unitMark(unit: ExtractionUnit, candidates: readonly Candidate[]): UnitMark | null {
  if (candidates.some((candidate) => candidate.extractionUnitId === unit.id)) return 'ABSORBED'
  if (!unit.contentText?.trim() || unit.recordKind === null) return null
  return unit.recordKind === 'POSITION' || unit.recordKind === 'WORDING' ? 'MISSED' : 'NOT_A_POSITION'
}

export const UNIT_MARK_LABELS: Record<Exclude<UnitMark, 'ABSORBED'>, string> = {
  NOT_A_POSITION: 'Not treated as a position',
  MISSED: 'Missed',
}

/**
 * The mark of each source node that belongs to a part Rivell did not take, so the whole file can
 * show what became of every part. Taken parts are left out: their highlight already says so.
 */
export function partMarksByNode(
  units: readonly ExtractionUnit[],
  candidates: readonly Candidate[],
): Map<string, Exclude<UnitMark, 'ABSORBED'>> {
  const marks = new Map<string, Exclude<UnitMark, 'ABSORBED'>>()
  for (const unit of units) {
    const mark = unitMark(unit, candidates)
    if (mark === null || mark === 'ABSORBED') continue
    for (const nodeId of unit.sourceNodeIds) marks.set(nodeId, mark)
  }
  return marks
}
