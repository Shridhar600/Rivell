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
 * row, and heal it in bounded batches. Idempotent by construction (upsert
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
     * One bounded pass over published indexable knowledge. Never throws: failures
     * are counted and left for the next scheduled run (retries are tolerated, not
     * looped).
     */
    public ReconciliationResult reconcile(int maxCandidates) {        var examined = 0;
        var indexed = 0;
        var embedded = 0;
        var failed = 0;
        var orgs = knowledgeQueries.organizationsWithPublishedKnowledge();
        for (var organizationId : orgs) {
            if (examined >= maxCandidates) {
                break;
            }
            try {
                var asOf = knowledgeQueries.maxPublicationNumberForOrganization(organizationId);
                if (asOf < 1) {
                    continue;
                }
                var remaining = maxCandidates - examined;
                var candidates = knowledgeQueries.listPublishedCandidatesForOrganization(
                        PublishedKnowledgeQuery.unpublishedNever(
                                organizationId, asOf, EvidenceIndexService.INDEXABLE_TYPES,
                                null, null, null, null, null, null, null));
                if (candidates.size() > remaining) {
                    candidates = candidates.subList(0, remaining);
                }
                var missing = new ArrayList<com.rivell.workbench.knowledge.PublishedCandidate>();
                var indexedIds = repository.findIndexedIds(organizationId,
                        candidates.stream().map(match -> match.candidate().id()).toList(),
                        properties.indexVersion());
                for (var match : candidates) {
                    if (!indexedIds.contains(match.candidate().id())) {
                        missing.add(match);
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
                if (!texts.isEmpty()) {
                    indexService.embedBatch(organizationId, texts);
                    embedded += texts.size();
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

    /** Scheduled entry point: one bounded pass at the configured batch size. */
    public void reconcileFromSchedule() {
        reconcile(properties.reconciliationBatchSize());
    }
}
