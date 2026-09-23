import { useState, type FormEvent } from 'react'
import type { PartyRole, SemanticParty } from '../../api/semantics'
import { useCorrectPartyRole } from '../../api/semantics'
import { PARTY_ROLES, roleLabel } from '../../lib/contractSemantics'

function RoleChips({ roles }: { roles: string[] }) {
  if (roles.length === 0) {
    return <span className="semantic-empty">No roles determined</span>
  }
  return (
    <ul className="chip-list">
      {roles.map((role) => (
        <li className="chip" key={role}>
          {roleLabel(role)}
        </li>
      ))}
    </ul>
  )
}

function PartyRoleEditor({
  party,
  contractId,
  versionNumber,
}: {
  party: SemanticParty
  contractId: string
  versionNumber: number
}) {
  const correct = useCorrectPartyRole(contractId, versionNumber)
  const [selected, setSelected] = useState<string[]>(party.effectiveRoles)
  const [error, setError] = useState<string | null>(null)

  const toggle = (role: PartyRole) => {
    setSelected((previous) =>
      previous.includes(role)
        ? previous.filter((candidate) => candidate !== role)
        : [...previous, role],
    )
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (selected.length === 0) {
      setError('Choose at least one role for this party.')
      return
    }
    setError(null)
    correct.mutate({ partyId: party.id, roles: selected as PartyRole[] })
  }

  return (
    <details className="correction">
      <summary>Correct roles</summary>
      <form onSubmit={handleSubmit} aria-busy={correct.isPending}>
        <fieldset>
          <legend>Roles this party holds in this agreement</legend>
          <div className="role-options">
            {PARTY_ROLES.map((role) => (
              <label className="role-option" key={role}>
                <input
                  type="checkbox"
                  checked={selected.includes(role)}
                  onChange={() => toggle(role)}
                  disabled={correct.isPending}
                />
                {roleLabel(role)}
              </label>
            ))}
          </div>
        </fieldset>
        {error ? (
          <p role="alert" className="correction__error">
            {error}
          </p>
        ) : null}
        <button type="submit" disabled={correct.isPending}>
          {correct.isPending ? 'Saving…' : 'Save roles'}
        </button>
      </form>
      {correct.isError ? (
        <p role="alert" className="correction__error">
          {correct.error.message}
        </p>
      ) : null}
      {correct.isSuccess && !correct.isPending ? (
        <p role="status" className="correction__status">
          Roles updated. Rivell&apos;s original interpretation is kept below.
        </p>
      ) : null}
    </details>
  )
}

export function PartiesPanel({
  parties,
  contractId,
  versionNumber,
}: {
  parties: SemanticParty[]
  contractId: string
  versionNumber: number
}) {
  return (
    <section aria-labelledby="parties-heading">
      <h2 id="parties-heading" tabIndex={-1} data-section-heading>
        Parties
      </h2>
      {parties.length === 0 ? (
        <p className="semantic-empty">
          No parties were identified in this contract version.
        </p>
      ) : (
        <ul className="party-list">
          {parties.map((party) => (
            <li className="panel party-card" key={party.id}>
              <div className="party-card__header">
                <h3>{party.name}</h3>
                {party.corrected ? (
                  <span className="badge badge--edited">Corrected</span>
                ) : null}
              </div>
              {party.aliases.length > 0 ? (
                <p className="party-card__aliases">
                  Also referred to as: {party.aliases.join(', ')}
                </p>
              ) : null}
              <div className="party-card__roles">
                <div>
                  <h4>Rivell detected</h4>
                  <RoleChips roles={party.machineRoles} />
                </div>
                <div>
                  <h4>Effective roles</h4>
                  <RoleChips roles={party.effectiveRoles} />
                </div>
              </div>
              <p className="provenance-note">
                {party.sourceNodeCount === 1
                  ? 'Supported by 1 source region'
                  : `Supported by ${party.sourceNodeCount} source regions`}
              </p>
              <PartyRoleEditor
                party={party}
                contractId={contractId}
                versionNumber={versionNumber}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
