package com.rivell.workbench.knowledge;

import java.time.Clock;

import org.springframework.transaction.annotation.Transactional;

/**
 * One-time re-derivation of {@code knowledge_artifact.document_title} from the stored source
 * nodes. A title is derived once at ingest, so a source uploaded before a title rule improved
 * keeps the old reading: a playbook can still be named by its "Owner:" line, and a workbook
 * that names itself has no title at all. The rule itself is {@link KnowledgeDocumentTitle}, the
 * same method the compiler calls; this service only supplies the stored nodes and writes the
 * result back.
 *
 * <p>The title is never cleared: a source whose nodes now name nothing keeps the title it had.
 */
public class KnowledgeTitleRederivationService {

    /** Stable key for this one-time migration; changing it would run the work again. */
    static final String MIGRATION_KEY = "knowledge-document-titles-v1";

    private final KnowledgeRepository repository;
    private final Clock clock;

    public KnowledgeTitleRederivationService(KnowledgeRepository repository, Clock clock) {
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
        var result = rederive();
        repository.recordDataMigration(MIGRATION_KEY,
                "examined=" + result.examined() + " updated=" + result.updated(),
                clock.instant());
        return result;
    }

    /**
     * Re-derives every stored artifact regardless of the marker. The startup path is one-time;
     * this method is the idempotent work that path and the verification test share. Calling it
     * twice changes nothing because an unchanged title is skipped.
     */
    Result rederive() {
        var examined = 0;
        var updated = 0;
        for (var artifact : repository.listArtifactsForMigration()) {
            examined++;
            var derived = KnowledgeDocumentTitle.of(
                    repository.loadStructure(artifact.id(), artifact.organizationId()));
            if (derived == null || derived.text().equals(artifact.documentTitle())) {
                continue;
            }
            repository.updateDocumentTitle(artifact.id(), derived.text());
            updated++;
        }
        return new Result(examined, updated);
    }
}
