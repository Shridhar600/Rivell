import type { ArtifactDetail } from '../../api/knowledge'

function DeclaredValue({ value }: { value: string | null }) {
  if (value === null || value.trim().length === 0) {
    return <span className="meta">Not declared</span>
  }
  return <>{value}</>
}

export function ArtifactManifest({ artifact }: { artifact: ArtifactDetail }) {
  return (
    <section aria-labelledby="artifact-manifest-heading" className="panel">
      <h2 id="artifact-manifest-heading">Manifest</h2>
      <p className="meta">
        Human-declared during upload. The artifact type is authoritative; the
        manifest defines the scope this knowledge applies to.
      </p>
      <dl className="data-list">
        <div className="data-list__row">
          <dt>Declared contract types</dt>
          <dd>
            {artifact.declaredContractTypes.length > 0 ? (
              artifact.declaredContractTypes.join(', ')
            ) : (
              <span className="meta">Not declared</span>
            )}
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Contract pack</dt>
          <dd>
            <DeclaredValue value={artifact.contractPackKey} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Party perspective</dt>
          <dd>
            <DeclaredValue value={artifact.partyPerspective} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Paper source</dt>
          <dd>
            <DeclaredValue value={artifact.paperSource} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Jurisdiction</dt>
          <dd>
            <DeclaredValue value={artifact.jurisdiction} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Language</dt>
          <dd>
            <DeclaredValue value={artifact.language} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Effective from</dt>
          <dd>
            <DeclaredValue value={artifact.effectiveFrom} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Effective to</dt>
          <dd>
            <DeclaredValue value={artifact.effectiveTo} />
          </dd>
        </div>
        <div className="data-list__row">
          <dt>Note</dt>
          <dd>
            <DeclaredValue value={artifact.manifestNote} />
          </dd>
        </div>
      </dl>
    </section>
  )
}
