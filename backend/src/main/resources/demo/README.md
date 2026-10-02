# Rivell local demo data

Fictional demonstration data so **Home** has something truthful to show on a local machine.
This is not production data and not test mock data: every row is produced by the real
services (real DOCX parsing, real enrichment, a real analysis run), so Home renders genuine
backend state rather than a fixture.

## Turn it on

Start the backend with the seed enabled:

```bash
cd backend
RIVELL_DEMO_SEED_ENABLED=true ./mvnw spring-boot:run
```

Or, equivalently, as a JVM property:

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Drivell.demo.seed-enabled=true"
```

Then start the frontend as usual and open Home. You should see three contracts, a completed
review, two queued reviews, one ingested playbook and a publication.

## Turn it off

Do nothing. The default is `rivell.demo.seed-enabled=false`, so an ordinary startup seeds
nothing and never loads the scripted providers.

## Reset

The seed is idempotent: it checks for the demo playbook first and seeds nothing if it is
already present, so restarting cannot duplicate rows. To start over, recreate the local
database — for example:

```bash
docker compose down -v && docker compose up -d    # or: drop and recreate your local schema
```

and restart with the seed enabled. A bespoke cascading delete across contracts, snapshots,
runs, findings and publications was deliberately not written: it would be a much larger risk
than a local demo warrants.

## What is seeded

| Area | What |
|---|---|
| Contracts | `Atlas Mutual NDA.docx`, `Northstar Vendor MSA.docx`, `Beacon Data Processing Addendum.docx` |
| Reviews | 1 completed (analysis really runs), 2 queued (created, deliberately not run) |
| Knowledge | `Rivell Demo Playbook.docx` ingested, its candidates approved, one publication |

Not seeded, because the domain cannot reach them without corrupting an invariant:

- a **failed** review. A review only reaches `FAILED` when the analysis pipeline throws
  outside check handling. The honest outage path (an unavailable provider) *degrades* a
  check and still completes the run, so producing a failed review would mean writing a
  failure reason by hand.
- an **in-flight/ANALYZING** review. Analysis runs synchronously here, so a mid-flight state
  could only be faked. Queued reviews already give Home real pending work.

## Regenerating the documents

```bash
python3 src/main/resources/demo/generate_demo_documents.py
```

All content is invented. No real customer, counterparty or legal text is present.
