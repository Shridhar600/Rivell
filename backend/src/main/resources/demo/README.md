# Rivell local demo data

Fictional demonstration data so the local product has truthful states to exercise. This is
not production data and not test mock data: every row is produced by the real services
(real DOCX parsing, real enrichment, real analysis runs, real decisions, real finalization,
real deterministic redline, real knowledge publication), so the UI renders genuine backend
state rather than a fixture.

The full state map is in [`SCENARIOS.md`](SCENARIOS.md). It is the canonical list of what
this seed produces, what each state is for, and what is deliberately not produced.

## Turn it on

```bash
cd backend
docker compose up -d                           # empty local database
RIVELL_DEMO_SEED_ENABLED=true ./mvnw spring-boot:run
```

Or, equivalently, as a JVM property:

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Drivell.demo.seed-enabled=true"
```

Then start the frontend as usual. You get 14 scenario contracts, 12 reviews spanning the
review lifecycle (queued, ready, in review, escalation-blocking, ready-to-finalize,
finalized with and without changes, verified redline, failed redline with a successful retry,
analysis failed, limited analysis), and a published playbook with two immutable versions plus
a clause library.

The background job worker is off by default. Keep it off for the demo: that is what keeps the
R12 queued review genuinely queued. With `RIVELL_JOB_WORKER_ENABLED=true`, queued reviews are
consumed and R12 will complete instead.

## Turn it off

Do nothing. The default is `rivell.demo.seed-enabled=false`, so an ordinary startup seeds
nothing and never loads the scripted providers or the demo-only failure seam.

## Reset

Recreate the local database:

```bash
docker compose down -v && docker compose up -d    # or: drop and recreate your local schema
```

and restart with the seed enabled. The seed itself is idempotent: it finds existing contracts,
reviews and knowledge by name (reviews by a deterministic creation key) and adds nothing on a
rerun, so restarting cannot duplicate rows.

## What is seeded

See [`SCENARIOS.md`](SCENARIOS.md) for the per-scenario table. In short:

| Area | What |
|---|---|
| Contracts | 14 scenario-named documents, including a real parse failure and a sparse structure |
| Reviews | R01–R12 in `SCENARIOS.md`, every state reached through the real services |
| Knowledge | Published playbook with two superseding publications, published clause library, an unpublished compilation and a genuinely failed artifact |

One state is deliberately not seeded, with the reason recorded in `SCENARIOS.md`: the
**ANALYZING** state (R13) is lease-fenced and only exists inside one synchronous analysis
call, so a persistent row could only be faked. The R10 failed review and the R11 limited
analysis cover the failure and degradation surfaces instead.

Model-backed judgments come from the demo's deterministic scripted providers, not a live
model and not the network. That is what makes the catalog reproducible; the domain flow
around those answers is the production one.

## Regenerating the documents

```bash
python3 src/main/resources/demo/generate_demo_documents.py
```

Generation is byte-deterministic: regenerating identical content produces identical files.
All content is invented. No real customer, counterparty or legal text is present.
