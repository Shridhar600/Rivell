package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.PublishedKnowledgeQuery;

/**
 * Index reconciliation (DW-012, retrieval-owned): discover published, indexable
 * evidence that should exist in the CURRENT index/model version but has no index
 * row or has one without a persisted vector, and heal it in bounded batches.
 * Idempotent by construction (upsert
 * on-conflict-do-nothing keyed candidate+hash+version); embeddings persist outside
 * transactions; per-candidate failures never abort the run. Snapshot/source truth
 * is preserved: only the derived index state is written.
 */
public class RetrievalIndexReconciliationService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(RetrievalIndexReconciliationService.class);

    private final KnowledgeQueryService knowledgeQueries;
    private final EvidenceIndexService indexService;
    private final RetrievalRepository repository;
    private final RetrievalProperties properties;

    public RetrievalIndexReconciliationService(
            KnowledgeQueryService knowledgeQueries,
            EvidenceIndexService indexService,
            RetrievalRepository repository,
            RetrievalProperties properties) {
        this.knowledgeQueries = knowledgeQueries;
        this.indexService = indexService;
        this.repository = repository;
        this.properties = properties;
    }

    public record ReconciliationResult(
            int organizationsScanned,
            int candidatesExamined,
            int rowsIndexed,
            int embeddingsPersisted,
            int failed) {

        public String summary() {
            return "orgs=" + organizationsScanned + " examined=" + candidatesExamined
                    + " indexed=" + rowsIndexed + " embedded=" + embeddingsPersisted
                    + " failed=" + failed;
        }
    }

    /**
     * One page-bounded pass over published indexable knowledge: every page of every
     * organization is examined, so a candidate older than the newest page is still healed.
     * Never throws: failures are counted and left for the next scheduled run (retries are
     * tolerated, not looped).
     */
    public ReconciliationResult reconcile(int pageSize) {
        // an index creation that failed on the publication pass is retried even when every row
        // already has its vector and no text needs embedding
        indexService.retryPendingVectorIndexes();
        var examined = 0;
        var indexed = 0;
        var embedded = 0;
        var failed = 0;
        var orgs = knowledgeQueries.organizationsWithPublishedKnowledge();
        for (var organizationId : orgs) {
            try {
                var asOf = knowledgeQueries.maxPublicationNumberForOrganization(organizationId);
                if (asOf < 1) {
                    continue;
                }
                var firstPage = PublishedKnowledgeQuery.unpublishedNever(
                        organizationId, asOf, EvidenceIndexService.INDEXABLE_TYPES,
                        null, null, null, null, null, null, null).withLimit(pageSize);
                com.rivell.workbench.knowledge.PublishedCandidate cursor = null;
                while (true) {
                    var query = cursor == null
                            ? firstPage
                            : firstPage.after(cursor.candidate().createdAt(),
                                    cursor.candidate().id());
                    var candidates = knowledgeQueries.listPublishedCandidatesForOrganization(query);
                    var missing = new ArrayList<com.rivell.workbench.knowledge.PublishedCandidate>();
                    var withoutVectors =
                            new ArrayList<com.rivell.workbench.knowledge.PublishedCandidate>();
                    var candidateIds = candidates.stream()
                            .map(match -> match.candidate().id()).toList();
                    var indexedIds = repository.findIndexedIds(organizationId, candidateIds,
                            properties.indexVersion());
                    var embeddingsConfigured = indexService.embeddingsConfigured();
                    var vectorReadyIds = embeddingsConfigured
                            ? repository.findEmbeddingReadyIds(organizationId, candidateIds,
                                    properties.indexVersion())
                            : java.util.Set.<UUID>of();
                    for (var match : candidates) {
                        if (!indexedIds.contains(match.candidate().id())) {
                            missing.add(match);
                        } else if (embeddingsConfigured
                                && !vectorReadyIds.contains(match.candidate().id())) {
                            // DW-065: the row exists, but an earlier embedding failed. It is
                            // searchable by text only, and no pass ever filled the gap. Without a
                            // provider there is no vector lane to heal, so the row is left alone.
                            withoutVectors.add(match);
                        }
                    }
                    examined += candidates.size();
                    var texts = new LinkedHashMap<UUID, String>();
                    for (var match : missing) {
                        try {
                            var candidate = indexService.indexCandidate(organizationId,
                                    match.candidate().id(), match.publicationNumber());
                            if (candidate != null) {
                                texts.put(candidate.id(), candidate.effectiveContentText());
                                indexed++;
                            }
                        } catch (RuntimeException e) {
                            failed++;
                            log.warn("Reconciliation failed for candidate {} in org {}: {}",
                                    match.candidate().id(), organizationId, e.toString());
                        }
                    }
                    for (var match : withoutVectors) {
                        try {
                            // With an unchanged content hash the upsert adds no row; the read is
                            // what is needed. A changed hash is treated as a new index row.
                            var candidate = indexService.indexCandidate(organizationId,
                                    match.candidate().id(), match.publicationNumber());
                            if (candidate != null) {
                                texts.put(candidate.id(), candidate.effectiveContentText());
                            }
                        } catch (RuntimeException e) {
                            failed++;
                            log.warn("Re-embedding failed for candidate {} in org {}: {}",
                                    match.candidate().id(), organizationId, e.toString());
                        }
                    }
                    if (!texts.isEmpty()) {
                        embedded += indexService.embedBatch(organizationId, texts);
                    }
                    // the read returns one row past the page to say that the corpus continues
                    if (candidates.size() <= pageSize) {
                        break;
                    }
                    cursor = candidates.getLast();
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("Reconciliation failed for organization {}: {}",
                        organizationId, e.toString());
            }
        }
        var result = new ReconciliationResult(
                orgs.size(), examined, indexed, embedded, failed);
        log.info("Retrieval index reconciliation: {}", result.summary());
        return result;
    }

    /** Scheduled entry point: one pass at the configured page size. */
    public void reconcileFromSchedule() {
        reconcile(properties.reconciliationBatchSize());
    }
}
