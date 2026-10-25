package com.rivell.workbench.knowledge;

import java.time.Clock;

import org.springframework.transaction.annotation.Transactional;

/**
 * One-time re-derivation of the record-level contract scope already stored on candidates.
 *
 * <p>{@code source_contract_types} is derived from text, so widening the contract-type
 * vocabulary changes what old rows mean: a source that said "Supply" was stored as OTHER and
 * silently stops applying to a SUPPLY contract. The derivation rule itself is
 * {@link SourceApplicabilityDerivation}, the same method the compiler calls. This service only
 * supplies the stored inputs and writes the result back.
 *
 * <p>The migration is deliberately narrow: it rewrites only
 * {@code knowledge_candidate.source_contract_types}. Review snapshots and their frozen
 * applicable-knowledge sets are never read or written, so a review still replays as it ran.
 */
public class SourceApplicabilityRederivationService {

    /** Stable key for this one-time migration; changing it would run the work again. */
    static final String MIGRATION_KEY = "source-contract-types-v1";

    private final KnowledgeRepository repository;
    private final Clock clock;

    public SourceApplicabilityRederivationService(KnowledgeRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public record Result(int examined, int updated) {
    }

    /**
     * The startup entry point: runs once per database, and is a no-op on every later call once
     * the completion marker exists.
     */
    @Transactional
    public Result rederiveOnce() {
        if (repository.dataMigrationCompleted(MIGRATION_KEY)) {
            return new Result(0, 0);
        }
        var result = rederiveCandidates();
        repository.recordDataMigration(MIGRATION_KEY,
                "examined=" + result.examined() + " updated=" + result.updated(),
                clock.instant());
        return result;
    }

    /**
     * Re-derives every stored candidate regardless of the marker. The startup path is one-time;
     * this method is the idempotent work that path and the verification test share. Calling it
     * twice changes nothing because unchanged rows are skipped.
     */
    Result rederive() {
        return rederiveCandidates();
    }

    private Result rederiveCandidates() {
        var examined = 0;
        var updated = 0;
        for (var row : repository.listCandidateUnitsForMigration()) {
            examined++;
            var candidate = row.candidate();
            var derived = SourceApplicabilityDerivation.derive(
                    candidate.payload(), row.unitKind(), row.unitContentText());
            if (derived.isEmpty() || derived.equals(candidate.scope().sourceContractTypes())) {
                continue;
            }
            if (repository.updateSourceContractTypes(
                    candidate.id(), candidate.organizationId(), derived)) {
                updated++;
            }
        }
        return new Result(examined, updated);
    }
}
