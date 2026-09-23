import { useState, type FormEvent } from 'react'
import type {
  SemanticClause,
  SemanticFact,
  SemanticInterpretation,
} from '../../api/semantics'
import { useCorrectClauseFacts } from '../../api/semantics'
import {
  describeFact,
  factDivergence,
  factEditorFields,
  humanizeCode,
  interpretationKindDescription,
} from '../../lib/contractSemantics'
import { VerificationBadge } from './VerificationBadge'

function FactCard({
  fact,
  machineFact,
  interpretations,
  clauseId,
  contractId,
  versionNumber,
}: {
  fact: SemanticFact
  machineFact: SemanticFact | null
  interpretations: SemanticInterpretation[]
  clauseId: string
  contractId: string
  versionNumber: number
}) {
  const rows = describeFact(fact)
  const machineRows = new Map(
    (machineFact ? describeFact(machineFact) : []).map((row) => [row.field, row.value]),
  )
  const divergences = factDivergence(machineFact ? [machineFact] : [], [fact])
  const divergenceByField = new Map(
    divergences.map((divergence) => [divergence.field, divergence]),
  )
  const interpretationsByField = new Map(
    interpretations
      .filter((interpretation) => interpretation.field in fact.values)
      .map((interpretation) => [interpretation.field, interpretation]),
  )

  return (
    <div className="fact-card">
      <h5>{humanizeCode(fact.factSchema)}</h5>
      <dl className="data-list fact-list">
        {rows.map((row) => {
          const divergence = divergenceByField.get(row.field)
          const interpretation = interpretationsByField.get(row.field)
          return (
            <div className="data-list__row" key={row.field}>
              <dt>{row.label}</dt>
              <dd>
                <span className="fact-list__value">{row.value}</span>
                {interpretation ? (
                  <VerificationBadge verification={interpretation.verification} />
                ) : null}
                {divergence ? (
                  <span className="fact-list__machine">
                    Rivell originally interpreted:{' '}
                    {machineRows.get(row.field) ?? divergence.machineValue}
                  </span>
                ) : null}
                {interpretation ? (
                  <span className="fact-list__provenance">
                    {interpretationKindDescription(interpretation.kind)} ·{' '}
                    {interpretation.sourceNodeCount === 1
                      ? '1 source region'
                      : `${interpretation.sourceNodeCount} source regions`}
                  </span>
                ) : null}
              </dd>
            </div>
          )
        })}
      </dl>
      <FactEditor
        fact={fact}
        clauseId={clauseId}
        contractId={contractId}
        versionNumber={versionNumber}
      />
    </div>
  )
}

function FactEditor({
  fact,
  clauseId,
  contractId,
  versionNumber,
}: {
  fact: SemanticFact
  clauseId: string
  contractId: string
  versionNumber: number
}) {
  const fields = factEditorFields(fact.factSchema)
  const correct = useCorrectClauseFacts(contractId, versionNumber)
  const [serverValues, setServerValues] = useState(fact.values)
  const [draft, setDraft] = useState<Record<string, string>>(() => ({
    ...fact.values,
  }))
  const [error, setError] = useState<string | null>(null)

  if (serverValues !== fact.values) {
    setServerValues(fact.values)
    setDraft({ ...fact.values })
  }

  if (!fields) return null

  const changed = Object.fromEntries(
    Object.entries(draft).filter(([field, value]) => fact.values[field] !== value),
  )
  const hasChanges = Object.keys(changed).length > 0

  const validate = (): string | null => {
    for (const field of fields) {
      const value = draft[field.field]
      if (value === undefined) continue
      if (field.kind === 'decimal') {
        const number = Number(value)
        if (!Number.isFinite(number) || number <= 0) {
          return `${field.label} must be a number greater than zero.`
        }
      }
      if (field.kind === 'integer') {
        const number = Number(value)
        if (!Number.isInteger(number) || number < 0) {
          return `${field.label} must be a whole number of zero or more.`
        }
      }
    }
    return null
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const validationError = validate()
    if (validationError) {
      setError(validationError)
      return
    }
    if (!hasChanges) {
      setError('Change at least one value before saving.')
      return
    }
    setError(null)
    correct.mutate({ clauseId, values: changed })
  }

  return (
    <details className="correction">
      <summary>Correct fact values</summary>
      <form onSubmit={handleSubmit} aria-busy={correct.isPending}>
        <div className="fact-editor">
          {fields.map((field) => {
            const inputId = `fact-${clauseId}-${fact.factSchema}-${field.field}`
            return (
              <div className="profile-form__field" key={field.field}>
                <label htmlFor={inputId}>{field.label}</label>
                {field.kind === 'boolean' ? (
                  <select
                    id={inputId}
                    value={draft[field.field] ?? 'false'}
                    onChange={(event) =>
                      setDraft((previous) => ({
                        ...previous,
                        [field.field]: event.target.value,
                      }))
                    }
                    disabled={correct.isPending}
                  >
                    <option value="true">
                      {field.booleanLabels?.whenTrue ?? 'Yes'}
                    </option>
                    <option value="false">
                      {field.booleanLabels?.whenFalse ?? 'No'}
                    </option>
                  </select>
                ) : field.kind === 'enum' ? (
                  <select
                    id={inputId}
                    value={draft[field.field] ?? ''}
                    onChange={(event) =>
                      setDraft((previous) => ({
                        ...previous,
                        [field.field]: event.target.value,
                      }))
                    }
                    disabled={correct.isPending}
                  >
                    {(field.options ?? []).map((option) => (
                      <option key={option} value={option}>
                        {field.optionLabels?.[option] ?? humanizeCode(option)}
                      </option>
                    ))}
                  </select>
                ) : (
                  <input
                    id={inputId}
                    type="number"
                    step={field.kind === 'decimal' ? '0.1' : '1'}
                    value={draft[field.field] ?? ''}
                    onChange={(event) =>
                      setDraft((previous) => ({
                        ...previous,
                        [field.field]: event.target.value,
                      }))
                    }
                    disabled={correct.isPending}
                  />
                )}
              </div>
            )
          })}
        </div>
        {error ? (
          <p role="alert" className="correction__error">
            {error}
          </p>
        ) : null}
        <button type="submit" disabled={correct.isPending || !hasChanges}>
          {correct.isPending ? 'Saving…' : 'Save fact values'}
        </button>
      </form>
      {correct.isError ? (
        <p role="alert" className="correction__error">
          {correct.error.message}
        </p>
      ) : null}
      {correct.isSuccess && !correct.isPending ? (
        <p role="status" className="correction__status">
          Fact values updated. Rivell&apos;s original interpretation is kept above.
        </p>
      ) : null}
    </details>
  )
}

export function ClauseFacts({
  clause,
  contractId,
  versionNumber,
}: {
  clause: SemanticClause
  contractId: string
  versionNumber: number
}) {
  const machineBySchema = new Map(
    clause.machineFacts.map((fact) => [fact.factSchema, fact]),
  )
  const coveredFields = new Set(
    clause.effectiveFacts.flatMap((fact) => Object.keys(fact.values)),
  )
  const leftoverInterpretations = clause.interpretations.filter(
    (interpretation) => !coveredFields.has(interpretation.field),
  )

  return (
    <div className="clause-facts">
      <h4>Extracted facts</h4>
      {clause.effectiveFacts.length === 0 ? (
        <p className="semantic-empty">
          No typed facts were extracted for this clause.
        </p>
      ) : (
        clause.effectiveFacts.map((fact) => (
          <FactCard
            key={fact.factSchema}
            fact={fact}
            machineFact={machineBySchema.get(fact.factSchema) ?? null}
            interpretations={clause.interpretations}
            clauseId={clause.id}
            contractId={contractId}
            versionNumber={versionNumber}
          />
        ))
      )}
      {leftoverInterpretations.length > 0 ? (
        <div className="interpretation-list">
          <h4>Other interpreted fields</h4>
          <ul>
            {leftoverInterpretations.map((interpretation) => (
              <li key={`${interpretation.field}-${interpretation.proposedValue}`}>
                <span>{humanizeCode(interpretation.field)}</span>
                <span className="interpretation-list__value">
                  {interpretation.proposedValue}
                </span>
                <VerificationBadge verification={interpretation.verification} />
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  )
}
