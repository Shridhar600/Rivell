import type { ArtifactDetail, Candidate, Interpretation, VerificationState } from '../api/knowledge'
import type { ConceptLabel } from '../api/taxonomy'
import { effectiveContentText, effectivePayloadColumns, isWordingHeader } from './candidates'
import { contractTypesText, scopeRows } from './knowledgePosition'
import {
  approvalDiverge,
  escalationDiverge,
  formatBoolean,
  formatPositions,
  interpretationFor,
  positionsDiverge,
  proposedApproval,
  proposedEscalationTrigger,
  proposedPositions,
  proposedVariantRole,
  scopeDifferences,
  variantRoleDiverge,
  POSITION_LABELS,
} from './semantics'

/**
 * The structured interpretation of a candidate, as rows the reviewer reads and corrects. Only what
 * the backend actually holds appears: the passage wording, the roles, the escalation trigger,
 * the approval flag, the legal concept and the applicability scope. The machine's proposal stays
 * beside any human correction, and nothing here is invented from the wording.
 */

export interface FieldRow {
  key: string
  label: string
  value: string
  /** What Rivell extracted, when a person has since changed it. */
  machine: string | null
  /** A note about how well the source supports this value. */
  support: string | null
  /** The value is a person's correction. */
  corrected: boolean
}

const SUPPORT_NOTES: Partial<Record<VerificationState, string>> = {
  UNCERTAIN: 'Uncertain, check the source',
  DISPUTED: 'The source does not clearly support this',
}

function supportNote(interpretation: Interpretation | null): string | null {
  if (!interpretation) return null
  const note = SUPPORT_NOTES[interpretation.verification]
  if (note) return note
  return interpretation.verification === 'NOT_VERIFIED' && interpretation.kind === 'GENERATIVE_EXTRACTION'
    ? 'Not checked against a second source'
    : null
}

function textOrNull(value: string | null | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

/** The legal concept Rivell classified, before any human correction. */
export function machineConcept(candidate: Candidate): string | null {
  return candidate.legalConceptKey
}

export function conceptCorrected(candidate: Candidate): boolean {
  return candidate.conceptCorrection !== null
}

/** Whether a person has changed anything about this candidate's interpretation. */
export function hasHumanCorrection(candidate: Candidate, artifact?: ArtifactDetail): boolean {
  if (candidate.editedTitle !== null) return true
  if (candidate.editedContentText !== null && candidate.editedContentText !== candidate.contentText) return true
  if (Object.keys(candidate.editedPayloadColumns).length > 0) return true
  if (conceptCorrected(candidate)) return true
  if (positionsDiverge(candidate) || variantRoleDiverge(candidate) || escalationDiverge(candidate) || approvalDiverge(candidate)) {
    return true
  }
  return artifact ? scopeDifferences(candidate, artifact).length > 0 : false
}

/** A payload column the source did not name is not a label a reviewer should read. */
function payloadColumnLabel(name: string, conceptRow: boolean): string {
  if (/^(Column|Field) \d+$/.test(name)) return 'Value'
  if (conceptRow && name.trim().toLowerCase() === 'topic') return 'Topic in the file'
  return name
}

function sameContractTypes(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false
  const set = new Set(b)
  return a.every((type) => set.has(type))
}

export function interpretationRows(
  candidate: Candidate,
  label: ConceptLabel,
  file?: ArtifactDetail,
): FieldRow[] {
  const rows: FieldRow[] = []
  const isPlaybook = candidate.candidateType === 'PLAYBOOK_RULE'
  const isClause = candidate.candidateType === 'CLAUSE_VARIANT'

  // The reviewer's own correction, when there is one, takes the place of the machine concept.
  const concept = candidate.effectiveConceptKey ? label(candidate.effectiveConceptKey) : null
  const machine = machineConcept(candidate)
  const classified = machine ? label(machine) : null
  rows.push({
    key: 'concept',
    label: 'Topic',
    value: concept ?? 'Not classified',
    machine: conceptCorrected(candidate) && classified !== concept ? `Rivell classified: ${classified ?? 'not classified'}` : null,
    support: concept ? null : 'Rivell could not place this under a topic',
    corrected: conceptCorrected(candidate),
  })

  if (isClause) {
    const proposed = proposedVariantRole(candidate)
    rows.push({
      key: 'variantRole',
      label: 'Role',
      value: POSITION_LABELS[candidate.variantRole ?? 'UNSPECIFIED'],
      machine: variantRoleDiverge(candidate) && proposed ? `Rivell proposed: ${POSITION_LABELS[proposed]}` : null,
      support: supportNote(interpretationFor(candidate, 'variantRole')),
      corrected: variantRoleDiverge(candidate),
    })
  }

  if (isPlaybook) {
    const proposed = proposedPositions(candidate)
    rows.push({
      key: 'positions',
      label: 'Position',
      value: candidate.positions.length > 0 ? formatPositions(candidate.positions) : 'Not specified',
      machine: positionsDiverge(candidate) && proposed ? `Rivell proposed: ${formatPositions(proposed)}` : null,
      support: supportNote(interpretationFor(candidate, 'positions')),
      corrected: positionsDiverge(candidate),
    })
    rows.push({
      key: 'escalation',
      label: 'Escalate',
      value: textOrNull(candidate.escalationTrigger) ?? 'Not stated',
      machine: escalationDiverge(candidate) ? `Rivell proposed: ${proposedEscalationTrigger(candidate) ?? 'none'}` : null,
      support: supportNote(interpretationFor(candidate, 'escalationTrigger')),
      corrected: escalationDiverge(candidate),
    })
    if (candidate.requiresApproval !== null || interpretationFor(candidate, 'requiresApproval')) {
      const machine = proposedApproval(candidate)
      rows.push({
        key: 'approval',
        label: 'Counsel approval',
        value: formatBoolean(candidate.requiresApproval),
        machine: approvalDiverge(candidate) && machine !== null ? `Rivell proposed: ${formatBoolean(machine)}` : null,
        support: supportNote(interpretationFor(candidate, 'requiresApproval')),
        corrected: approvalDiverge(candidate),
      })
    }
  }

  // The wording Rivell uses in reviews, in retrieval and in a publication is one value, resolved
  // from explicit human wording, then the human's corrected columns, then the machine wording. A
  // column-only correction therefore changes this text even though it changed no wording field, so
  // the text is shown first and the columns, where there are any, follow as the source's own cells.
  const text = effectiveContentText(candidate)
  const shownWording = textOrNull(text)
  const columnsDiverge = Object.keys(candidate.editedPayloadColumns).some(
    (name) => candidate.editedPayloadColumns[name] !== candidate.payloadColumns[name],
  )
  const textChanged =
    (candidate.editedContentText !== null && candidate.editedContentText !== candidate.contentText) ||
    columnsDiverge
  rows.push({
    key: 'wording',
    label: isClause ? 'Approved wording' : 'Wording',
    value: shownWording ?? 'No wording was read',
    machine: textChanged ? `Rivell read: ${candidate.contentText ?? ''}` : null,
    support: null,
    corrected: textChanged,
  })
  const columns = effectivePayloadColumns(candidate)
  for (const name of Object.keys(columns)) {
    const value = columns[name] ?? ''
    // The wording column is already the "Approved wording" row, so it is not shown twice. A
    // value that differs from the wording stays visible: it is then not a duplicate.
    if (isWordingHeader(name) && shownWording !== null && value.trim() === shownWording) continue
    const edited = candidate.editedPayloadColumns[name]
    const changed = edited !== undefined && edited !== candidate.payloadColumns[name]
    rows.push({
      key: `column:${name}`,
      label: payloadColumnLabel(name, true),
      value,
      machine: changed ? `Rivell read: ${candidate.payloadColumns[name] ?? ''}` : null,
      support: null,
      corrected: changed,
    })
  }

  const recordTypes = candidate.sourceContractTypes
  const ownTypes = recordTypes.length > 0 ? recordTypes : candidate.declaredContractTypes
  const scope = scopeRows(candidate, ownTypes)
  const fileTypes = file?.declaredContractTypes ?? []
  const scopeSupport = supportNote(interpretationFor(candidate, 'scope'))
  const hasAppliesTo = scope.some((row) => row.label === 'Applies to')
  if (scope.length === 0 && fileTypes.length === 0) {
    rows.push({ key: 'scope', label: 'Applies to', value: 'Not declared', machine: null, support: scopeSupport, corrected: false })
  } else {
    // The declared types row is the record's own when the record states one. Otherwise it is
    // inherited from the file's manifest and says so, rather than repeating "Applies to".
    const inheritedFromFile =
      recordTypes.length === 0 &&
      file !== undefined &&
      sameContractTypes(ownTypes, fileTypes)
    scope.forEach((row, position) =>
      rows.push({
        key: `scope:${row.label}`,
        label: inheritedFromFile && row.label === 'Applies to' ? 'File scope' : row.label,
        value: row.value,
        machine: null,
        support: position === 0 ? scopeSupport : null,
        corrected: false,
      }),
    )
    const ownScopeDiffers =
      recordTypes.length > 0 &&
      fileTypes.length > 0 &&
      !sameContractTypes(recordTypes, fileTypes)
    if (ownScopeDiffers || (!hasAppliesTo && fileTypes.length > 0)) {
      rows.push({
        key: 'scope:File scope',
        label: 'File scope',
        value: contractTypesText(fileTypes) ?? '',
        machine: null,
        support: hasAppliesTo ? null : scopeSupport,
        corrected: false,
      })
    }
  }
  return rows
}
