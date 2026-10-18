import { useId, useState, type FormEvent, type ReactNode } from 'react'
import type { PartyRole, SemanticContract, SemanticParty, SemanticReference } from '../../api/semantics'
import { isConflictError, useCorrectPartyRole } from '../../api/semantics'
import {
  PARTY_ROLES,
  qualityGroups,
  qualityNoteLabel,
  referenceStatusLabel,
  relationshipTypeVerb,
  roleLabel,
  verificationLabel,
} from '../../lib/contractSemantics'

interface Props {
  semantics: SemanticContract
  contractId: string
  /** False when the review pinned an older version: corrections then go to a version no review uses. */
  editable: boolean
  latestVersionNumber: number | null
  regionLabels: Map<string, string>
  onNavigateToNode: (nodeId: string) => void
  onRefresh: () => void
}

function detectorLabel(detector: string): string {
  if (detector === 'DETERMINISTIC') return 'Stated in the clause text'
  if (detector === 'SEMANTIC') return 'Inferred from the clause texts'
  return 'Detection method not recognised'
}

const REFERENCE_ORDER: Record<string, number> = { BROKEN: 0, AMBIGUOUS: 1, EXTERNAL: 2, RESOLVED: 3 }

function Group({ title, count, children }: { title: string; count: number; children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const regionId = useId()
  return (
    <section className="rv-ws-ca-group" aria-label={title}>
      <button type="button" className="rv-ws-ca-head" aria-expanded={open} aria-controls={regionId} onClick={() => setOpen((value) => !value)}>
        <span>{title}</span>
        <span className="rv-ws-chip">{count}</span>
      </button>
      <div id={regionId} hidden={!open} className="rv-ws-ca-body">
        {open ? children : null}
      </div>
    </section>
  )
}

function Regions({ nodeIds, regionLabels, onNavigateToNode }: { nodeIds: string[]; regionLabels: Map<string, string>; onNavigateToNode: (nodeId: string) => void }) {
  const known = nodeIds.filter((nodeId) => regionLabels.has(nodeId))
  if (known.length === 0) return <span className="rv-ws-caption">No passage in this version</span>
  return (
    <span className="rv-ws-srcd-acts">
      {known.map((nodeId) => (
        <button key={nodeId} type="button" className="rv-ws-link" onClick={() => onNavigateToNode(nodeId)}>
          {regionLabels.get(nodeId)}
        </button>
      ))}
    </span>
  )
}

function RoleEditor({ party, contractId, versionNumber, onRefresh }: { party: SemanticParty; contractId: string; versionNumber: number; onRefresh: () => void }) {
  const correct = useCorrectPartyRole(contractId, versionNumber)
  const [open, setOpen] = useState(false)
  // Reset the draft when the saved roles change, not when a refetch re-creates the same roles.
  const savedKey = party.effectiveRoles.join('|')
  const [serverKey, setServerKey] = useState(savedKey)
  const [selected, setSelected] = useState<string[]>(party.effectiveRoles)
  const [error, setError] = useState<string | null>(null)
  if (serverKey !== savedKey) {
    setServerKey(savedKey)
    setSelected(party.effectiveRoles)
  }
  const toggle = (role: PartyRole) =>
    setSelected((previous) => (previous.includes(role) ? previous.filter((entry) => entry !== role) : [...previous, role]))

  function submit(event: FormEvent) {
    event.preventDefault()
    if (selected.length === 0) {
      setError('Choose at least one role for this party.')
      return
    }
    setError(null)
    correct.mutate({ partyId: party.id, roles: selected as PartyRole[] })
  }

  return (
    <div className="rv-ws-cd-disc">
      <button type="button" className="rv-ws-link" aria-expanded={open} onClick={() => setOpen((value) => !value)}>
        Correct the roles
      </button>
      {open ? (
        <form className="rv-ws-addf" onSubmit={submit} aria-busy={correct.isPending} noValidate>
          <fieldset className="rv-ws-ca-roles">
            <legend>Roles this party holds in this agreement</legend>
            {PARTY_ROLES.map((role) => (
              <label key={role}>
                <input type="checkbox" checked={selected.includes(role)} onChange={() => toggle(role)} disabled={correct.isPending} />
                {roleLabel(role)}
              </label>
            ))}
          </fieldset>
          {error ? (
            <p role="alert" className="rv-ws-addf-err">
              {error}
            </p>
          ) : null}
          <div className="rv-ws-addf-act">
            <button type="submit" className="rv-btn rv-btn--compact" disabled={correct.isPending}>
              {correct.isPending ? 'Saving…' : 'Save roles'}
            </button>
          </div>
        </form>
      ) : null}
      {correct.isError ? (
        isConflictError(correct.error) ? (
          <div role="alert" className="rv-ws-life-alert">
            <p>The analysis of this contract version is not ready. Refresh it and try again.</p>
            <button type="button" className="rv-btn rv-btn--compact" onClick={onRefresh}>
              Refresh analysis state
            </button>
          </div>
        ) : (
          <p role="alert" className="rv-ws-error">
            {correct.error.message}
          </p>
        )
      ) : null}
      {correct.isSuccess && !correct.isPending ? (
        <p role="status" className="rv-ws-caption">
          Roles updated. Rivell&apos;s own reading is kept.
        </p>
      ) : null}
    </div>
  )
}

function ReferenceTarget({ reference, regionLabels, onNavigateToNode }: { reference: SemanticReference; regionLabels: Map<string, string>; onNavigateToNode: (nodeId: string) => void }) {
  if (reference.status === 'RESOLVED' && reference.targetNodeId) {
    return <Regions nodeIds={[reference.targetNodeId]} regionLabels={regionLabels} onNavigateToNode={onNavigateToNode} />
  }
  if (reference.status === 'AMBIGUOUS' && reference.candidateNodeIds.length > 0) {
    return (
      <span>
        <span className="rv-ws-caption">Possible targets: </span>
        <Regions nodeIds={reference.candidateNodeIds} regionLabels={regionLabels} onNavigateToNode={onNavigateToNode} />
      </span>
    )
  }
  return null
}

/**
 * The contract-level semantics of the version this review pinned: parties (with role corrections),
 * defined terms, cross-references, related clauses and the analysis's own quality notes. Everything
 * links to its place in the document beside the sheet.
 */
export function ContractAnalysis({ semantics, contractId, editable, latestVersionNumber, regionLabels, onNavigateToNode, onRefresh }: Props) {
  const quality = qualityGroups(semantics.quality)
  const degraded = semantics.observations?.providerDegraded === true || semantics.observations?.budgetExhausted === true
  const qualityNotes = [...quality.analysis, ...quality.unresolved]
  const references = [...semantics.references].sort(
    (left, right) => (REFERENCE_ORDER[left.status] ?? 4) - (REFERENCE_ORDER[right.status] ?? 4),
  )

  return (
    <div className="rv-ws-srcd">
      <div className="rv-ws-kicker">
        <span>Contract analysis</span>
        <span className="rv-ws-chip">Version {semantics.versionNumber}</span>
      </div>
      <p className="rv-ws-caption">
        What Rivell read in this contract version. These are not legal conclusions.
      </p>

      <Group title="Parties" count={semantics.parties.length}>
        {semantics.parties.length === 0 ? <p className="rv-ws-caption">No parties were identified in this contract version.</p> : null}
        {semantics.parties.map((party) => (
          <div key={party.id} className="rv-ws-ca-item">
            <div className="rv-ws-ca-name">
              {party.name}
              {party.corrected ? <span className="rv-ws-chip">Corrected by you</span> : null}
            </div>
            {party.aliases.length > 0 ? <p className="rv-ws-caption">Also referred to as {party.aliases.join(', ')}</p> : null}
            <dl className="rv-ws-props">
              <div>
                <dt>Roles</dt>
                <dd>{party.effectiveRoles.length > 0 ? party.effectiveRoles.map(roleLabel).join(', ') : 'No roles determined'}</dd>
              </div>
              {party.corrected ? (
                <div>
                  <dt>Rivell read</dt>
                  <dd>{party.machineRoles.length > 0 ? party.machineRoles.map(roleLabel).join(', ') : 'No roles determined'}</dd>
                </div>
              ) : null}
              <div>
                <dt>Found in</dt>
                <dd>
                  <Regions nodeIds={party.sourceNodeIds} regionLabels={regionLabels} onNavigateToNode={onNavigateToNode} />
                </dd>
              </div>
            </dl>
            {editable ? <RoleEditor party={party} contractId={contractId} versionNumber={semantics.versionNumber} onRefresh={onRefresh} /> : null}
          </div>
        ))}
      </Group>

      <Group title="Defined terms" count={semantics.definedTerms.length}>
        {semantics.definedTerms.length === 0 ? <p className="rv-ws-caption">No defined terms were identified.</p> : null}
        <dl className="rv-ws-cd-terms">
          {semantics.definedTerms.map((term) => (
            <div key={term.id}>
              <dt>
                <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(term.definingNodeId)}>
                  {term.term}
                </button>{' '}
                <span className="rv-ws-caption">
                  Used in {term.referenceNodeIds.length} {term.referenceNodeIds.length === 1 ? 'place' : 'places'}
                </span>{' '}
                {term.duplicate ? <span className="rv-ws-chip" data-tone="amber">Defined more than once</span> : null}
              </dt>
              <dd>{term.definition}</dd>
            </div>
          ))}
        </dl>
      </Group>

      <Group title="Cross-references" count={references.length}>
        {references.length === 0 ? <p className="rv-ws-caption">No cross-references were identified.</p> : null}
        <ul className="rv-ws-cov">
          {references.map((reference) => (
            <li key={reference.id}>
              <span className="rv-ws-cov-c">{reference.referenceText}</span>
              <span className="rv-ws-chip" data-tone={reference.status === 'BROKEN' || reference.status === 'AMBIGUOUS' ? 'amber' : undefined}>
                {referenceStatusLabel(reference.status)}
              </span>
              <ReferenceTarget reference={reference} regionLabels={regionLabels} onNavigateToNode={onNavigateToNode} />
            </li>
          ))}
        </ul>
      </Group>

      <Group title="Related clauses" count={semantics.relationships.length}>
        {semantics.relationships.length === 0 ? <p className="rv-ws-caption">No relationships between clauses were identified.</p> : null}
        <ul className="rv-ws-cov">
          {semantics.relationships.map((relationship) => (
            <li key={relationship.id}>
              <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(relationship.sourceRegionNodeId)}>
                {regionLabels.get(relationship.sourceRegionNodeId) ?? 'A clause'}
              </button>
              <span className="rv-ws-cov-e">{relationshipTypeVerb(relationship.type)}</span>
              <button type="button" className="rv-ws-link" onClick={() => onNavigateToNode(relationship.targetRegionNodeId)}>
                {regionLabels.get(relationship.targetRegionNodeId) ?? 'another clause'}
              </button>
              <span className="rv-ws-caption">{detectorLabel(relationship.detector)}</span>
              <span className="rv-ws-chip">{verificationLabel(relationship.verification)}</span>
            </li>
          ))}
        </ul>
      </Group>

      {qualityNotes.length > 0 || degraded ? (
        <div className="rv-ws-sec">
          <h3 className="rv-ws-sec-h">Analysis quality</h3>
          {degraded ? (
            <p className="rv-ws-warn">Some details could not be read. Only what was found is shown.</p>
          ) : null}
          <ul className="rv-ws-cov">
            {qualityNotes.map((note) => (
              <li key={note}>{qualityNoteLabel(note)}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <p className="rv-ws-caption">
        {editable
          ? 'A correction is used by the next review of this contract. It does not change this review, which stays as it was when it was created.'
          : `Corrections apply to the latest contract version${latestVersionNumber ? ` (version ${latestVersionNumber})` : ''}. This review uses version ${semantics.versionNumber}, so this view is read-only.`}
      </p>
    </div>
  )
}
