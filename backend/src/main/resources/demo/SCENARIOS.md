# Rivell Demo Scenario Catalog

Local demonstration data only. Every state below is produced at application startup by the
real services (real DOCX parsing, real enrichment, real analysis runs, real human decisions,
real finalization, real deterministic redline, real knowledge publication). Nothing writes a
Finding, a decision, a finalization state, a redline result or a publication directly.

The catalog is code-owned in `DemoScenarioCatalog` and this file is checked against it by
`DemoScenarioCatalogIT`. Enable it only with `RIVELL_DEMO_SEED_ENABLED=true`; an ordinary
startup seeds nothing and creates none of the demo beans.

Model-backed judgments in the seeded analysis run through the demo's deterministic scripted
providers, not a live model and not the network. That is what makes the catalog reproducible;
the domain flow around those answers is the production one.

## Reset and run

```bash
cd backend
docker compose down -v          # recreate an empty local database
docker compose up -d
RIVELL_DEMO_SEED_ENABLED=true ./mvnw spring-boot:run
```

The job worker stays off by default (`RIVELL_JOB_WORKER_ENABLED` is unset), which is what
keeps R12 truthfully queued. With the worker on, queued reviews are consumed and R12 will
complete instead. Resetting is recreating the database; the seed itself is idempotent and a
rerun adds nothing.

After seeding, the application log prints one line per scenario and a summary with counts and
seed runtime. No document body is logged.

## Review scenarios

| Key | Contract (display name) | Intended state | Source fixture | Findings | Workspace | Finalize | Redline | Caveats |
|---|---|---|---|---|---|---|---|---|
| R01 | Vendor NDA - Ready for review | analysis COMPLETED, no decisions | NDA body, NDA pack | 2 confidentiality review-required findings | yes | yes (server readiness) | no | none |
| R02 | Acme MSA - Review in progress | COMPLETED, one finding accepted, others unresolved | MSA body, MSA pack | 3: 2 confidentiality, 1 liability fallback (accepted) | yes | no (unresolved review-required) | no | none |
| R03 | Hooli MSA - Escalation blocking | COMPLETED, one finding ESCALATEd with a rationale | MSA body, MSA pack | 3: escalation blocks via ACTIVE_ESCALATION | yes | no (ACTIVE_ESCALATION) | no | none |
| R04 | Procurement NDA - Ready to finalize | all required findings resolved, not finalized | NDA body, NDA pack | 2 rejected | yes | yes | no | none |
| R05 | Atlas MSA - Finalized | finalized with a frozen change set containing a REPLACE | MSA body, MSA pack | 2 rejected, 1 accepted fallback | read-only | done | not generated | redline deliberately not generated here |
| R06 | Employee NDA - Finalized, no changes | all rejected, finalized with an empty change set | NDA body, NDA pack | 2 rejected | read-only | done | not required | genuine empty change set |
| R07 | Procurement MSA - Verified redline | finalized with changes, redline COMPLETED and verified | MSA body, MSA pack | 2 rejected, 1 accepted fallback | read-only | done | COMPLETED, download yes | artifact is a real DOCX |
| R08 | Globex NDA - Redline failed | finalized with an INSERT, then a human semantics correction drifts the digest; execution FAILED | m5 NDA absence body, MSA pack | 1 blocking absence inserted for, 1 informational absence | read-only | done | FAILED APPROVED_SEMANTICS_DRIFT, no artifact | deterministic failure by design; not a retryable-transient promise (DW-048) |
| R09 | Initech NDA - Retry succeeded | R08 path, correction reverted, retry attempt 2 COMPLETED | m5 NDA absence body, MSA pack | as R08 | read-only | done | attempt 1 FAILED preserved, attempt 2 COMPLETED, download yes | the only recoverable redline failure; no generic retry promise |
| R10 | Umbrella MSA - Analysis failed | analysis genuinely FAILED at a demo-only injected service boundary | MSA body, MSA pack | 0 (failed) | unavailable | no | no | the seam exists only while demo seeding is enabled |
| R11 | Soylent MSA - Limited analysis | COMPLETED with every check DEGRADED after a semantics correction | MSA body, MSA pack | 0 (all checks degraded) | yes | no (ANALYSIS_DEGRADED) | no | partial degradation is not modelled; this fixture degrades every check |
| R12 | Vandelay MSA - Analysis queued | ANALYSIS_QUEUED, no analysis run | MSA body, MSA pack | none yet | no | no | no | only truthful while the worker is off |

### R13 - Analyzing (omitted)

`ANALYZING` is a lease-fenced, in-flight state written only between `markAnalyzing` and the
terminal write inside one synchronous analysis call. There is no supported mechanism to hold
a review in it persistently without pausing a worker thread, which would be orchestration
theatre rather than product state. It is documented and left to UI/unit tests instead of
being seeded as a fabricated row.

## Contract and ingestion scenarios

| Key | Contract | Intended state | Source fixture | Caveats |
|---|---|---|---|---|
| C01 | Clean upload - Vendor NDA - Ready for review | parsed immutable version v1 | NDA body | shared with R01 |
| C02 | Ingestion parse failure - Corrupt Upload - Parse failed | `contract_version.parse_status = FAILED` through the real parser | invalid bytes | no exception, no fake status |
| C03 | Minimal sparse structure - Minimal Sparse - Empty structure | structurally valid sparse document with an empty section and a table-only region | sparse body | ingested as a contract; the null-text knowledge compilation path is covered by NullTextUnitCompilationIT, not by this fixture |

## Knowledge scenarios

| Key | Artifact | Intended state | Source fixture | Caveats |
|---|---|---|---|---|
| K01 | Published playbook - Rivell Demo Playbook | ingested, approved, publication #1 | playbook v1 | carries positions and a verified escalation trigger |
| K02 | Playbook version history - Rivell Demo Playbook v2 | publication #2 with explicit SUPERSEDE items | playbook v2 | publication #1 stays immutable; published after all reviews so frozen evidence is unaffected |
| K03 | Published clause library - Rivell Demo Clause Library | approved and published with variants and provenance | clause library | supplies approved drafting for the fallback finding |
| K04 | Source lineage | source nodes to extraction unit to candidate to publication item all resolve | playbook v1 | asserted by the catalog test |
| K05 | Compilation in review - Rivell Demo Draft Playbook | ingested with candidates PENDING, never published | draft playbook | illustrates compilation in review, and carries a genuine `CONTRADICTS_EXISTING` candidate: its uncapped-liability draft openly contradicts the published cap |
| K06 | Knowledge ingestion failure - Unreadable Playbook | `ingest_status = FAILED` with no candidates | invalid bytes | real persisted failure state; there is no compilation-failure entity |
| K07 | Routine grouped review - Rivell Demo Routine Playbook | candidates left `PENDING` and `ROUTINE` with an empty reason set | routine playbook | eligibility is produced by the real categorizer, never inserted; two or more bulk-approvable candidates so `Review all` then `Approve all` can be exercised live; bulk approval re-derives eligibility against current published knowledge, and once the group has been approved a reseed reports the scenario as already exercised instead of rebuilding it |

## Expected totals

From a clean database: 14 contracts, 12 reviews, 6 knowledge artifacts, 2 publications, and
the findings listed above. Seed runtime is reported by the summary log; the scenario catalog
test also reports it. A few dozen entities, not thousands.

## Known limitations

- **Retry Redline** (DW-048): every reachable persisted failure is deterministic. R08/R09
  demonstrate the one genuinely recoverable condition, a human semantics correction that is
  later reverted. No surface may promise that retrying resolves an unresolved cause.
- **Escalation trigger** is extracted through the demo generative provider for prose playbooks
  (quoted from the source and independently verified) and through the deterministic column
  path for table-shaped playbooks. The recorded deterministic-extraction gap for prose
  remains a Knowledge milestone item (DW-049).
- **Document generator** is byte-deterministic; regenerating fixtures produces identical files.
