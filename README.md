# Rivell

Rivell is a contract review workbench. You give it your playbook (the positions your team accepts, the fallbacks and the walk-away points) and a contract in Word format. Rivell checks each clause against your positions, tells you what to fix and how serious it is, and writes the fixes back into your original Word file as tracked changes.

It is built for precision. A lawyer checks every finding, so every false alarm costs time.

## How it works

1. **Knowledge.** Upload your playbooks (`.docx`, `.xlsx`). Rivell finds each position, tags its topic and extracts its fields. You approve them before they are used. Rivell also flags positions in different playbooks that contradict each other.
2. **Review.** For each clause and each position that applies, a small decision model answers one fixed question: does the clause meet the position, fall within the fallback, go beyond it, or hit a walk-away point? The answer comes with a confidence.
3. **Second opinion.** When the decision model is unsure, an LLM gives a second opinion. When the two disagree, the item goes to a "needs your look" group and you decide.
4. **Search when the topic is unclear.** If no topic matches a clause, hybrid search (full text plus vectors, merged by rank) finds the closest positions, and the decision model judges them.
5. **Redline.** Rivell writes the changes as tracked changes into your original file. The text comes from your approved drafting first, then from generated text, then from your own edits. Four checks verify the file before you download it.

Every finding shows the clause, the position it was checked against, who decided it (the model, the second opinion or you) and why.

## Results

Measured on 16 contracts that were sealed until the run (80 known issues, 42 clean clauses). Each setup used the same playbooks.

| Setup | Issues found | Serious false alarms on clean clauses | Findings per contract |
|---|---|---|---|
| Rivell | 88% | 5% | about 11 |
| Hybrid search + LLM | 90% | 48% | about 28 |
| Whole contract + LLM | 88 to 90% | 26% | 15 to 23 |

Rivell finds about as many issues with far less noise, at about $0.015 per contract. Its known weakness: it rates some serious issues lower than the LLM setups do (65% rated medium or high, against about 90%). This is the next area of work.

## Run it locally

Rivell has no sign-in yet. It runs with one fixed local user. **Do not expose it to the internet.**

You need Java 21, Node 22 or later, and Docker.

```bash
# 1. Database (PostgreSQL with pgvector)
cd backend
docker compose up -d

# 2. Backend on http://localhost:8080
export TYPESAFE_API_KEY=...        # decision model (Jev, typesafe.ai)
export OPENAI_API_KEY=...          # second opinion and drafting; any OpenAI-compatible API
export OPENAI_MODEL=...            # the model to use
export RIVELL_JOB_WORKER_ENABLED=true
./mvnw spring-boot:run

# 3. Frontend on http://localhost:5173
cd ../frontend
cp .env.example .env
npm ci
npm run dev
```

Optional:

- **Hybrid search vectors.** Set `RIVELL_EMBEDDINGS_PROVIDER=openai-compatible`, `RIVELL_EMBEDDINGS_API_KEY` and `RIVELL_EMBEDDINGS_MODEL`. A local Ollama works too; see `backend/src/main/resources/application.properties`. Without it, search uses full text only.
- **Demo data.** Set `RIVELL_DEMO_SEED_ENABLED=true` to load fictional contracts and playbooks.

All settings and their defaults are in `backend/src/main/resources/application.properties`.

## Tests

```bash
cd backend && ./mvnw test        # needs Docker (Testcontainers)
cd frontend && npm run lint && npm run typecheck && npm test
```

## Tech

Java 21, Spring Boot 3.5, PostgreSQL with pgvector, Flyway, docx4j, JobRunr. React 19, TypeScript, TanStack Query, Vite, Vitest.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Report security problems as described in [SECURITY.md](SECURITY.md).

## License

Copyright (C) 2026 Shridhar600.

Rivell is licensed under the [GNU Affero General Public License v3.0](LICENSE). You can use, study and change it. If you distribute it, or run a changed version as a service for others, you must publish your complete source code under the same license.

For a commercial license without these terms, contact the maintainer through GitHub.
