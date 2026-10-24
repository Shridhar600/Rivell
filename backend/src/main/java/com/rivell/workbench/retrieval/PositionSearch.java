package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.intelligence.EmbeddingService;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.PublishedCandidate;

/**
 * Engine v2 position search (spec §6, SEARCH route): the published playbook positions that
 * apply to the contract (same metadata narrowing as every policy lookup), ranked for one
 * clause's text by the full-text and vector lanes and fused by rank (RRF), as the precedent
 * lane does. Ranking only: the caller's comparison decides whether a position addresses the
 * clause. A dedicated vector store can replace the two lanes behind this class (DW-064).
 */
final class PositionSearch {

    /** Positions narrowed by metadata before the text lanes run; a playbook holds far fewer. */
    static final int NARROWING_CAP = 500;

    private final StructuredPolicyResolver resolver;
    private final RetrievalRepository repository;
    private final EmbeddingService embeddingService;
    private final RetrievalProperties properties;

    PositionSearch(StructuredPolicyResolver resolver, RetrievalRepository repository,
            EmbeddingService embeddingService, RetrievalProperties properties) {
        this.resolver = resolver;
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    /** What the search found: positions in fused order, their lane ranks, the trace stages. */
    record Found(StructuredPolicyResolver.Resolution resolution, Map<UUID, LaneInfo> lanes,
            List<RetrievalTrace.StageSummary> stages, List<String> degradation) {
    }

    Found search(EvidenceRequest request, long asOfPublication, Set<UUID> exclude,
            Set<String> excludedTopics, int limit) {
        var narrowed = resolver.resolveAll(request, Set.of(CandidateType.PLAYBOOK_RULE),
                asOfPublication, NARROWING_CAP);
        var byId = new LinkedHashMap<UUID, PublishedCandidate>();
        for (var match : narrowed.published()) {
            var candidate = match.candidate();
            if (exclude.contains(candidate.id())) {
                continue;
            }
            // DW-085: a topic the person removed from the clause is not a way back to its positions
            if (candidate.topicKeys().stream().anyMatch(excludedTopics::contains)) {
                continue;
            }
            byId.put(candidate.id(), match);
        }
        var ids = List.copyOf(byId.keySet());
        var degradation = new ArrayList<String>();
        if (narrowed.truncated()) {
            degradation.add("STRUCTURED_LANE_TRUNCATED");
        }
        // a position without an index row is invisible to both lanes: say so, never pass silently
        var unindexed = new ArrayList<>(ids);
        unindexed.removeAll(repository.findIndexedIds(
                request.organizationId(), ids, properties.indexVersion()));
        if (!unindexed.isEmpty()) {
            degradation.add("INDEX_INCOMPLETE");
        }
        // a row whose embedding failed is searchable by text only: the vector lane cannot see it
        if (embeddingService.isConfigured() && !ids.isEmpty()) {
            var withoutVectors = new ArrayList<>(ids);
            withoutVectors.removeAll(repository.findEmbeddingReadyIds(
                    request.organizationId(), ids, properties.indexVersion()));
            // A position with no index row is already reported as INDEX_INCOMPLETE; it has no
            // vector row to call incomplete.
            withoutVectors.removeAll(unindexed);
            if (!withoutVectors.isEmpty()) {
                degradation.add("VECTOR_INDEX_INCOMPLETE");
            }
        }
        var lexical = ids.isEmpty() ? List.<RetrievalRepository.CandidateHit>of()
                : repository.findLexicalCandidates(request.organizationId(), ids,
                        LexicalQueries.orTerms(request.clauseText()), properties.lexicalLimit(),
                        properties.indexVersion());
        var vector = ids.isEmpty() ? List.<RetrievalRepository.CandidateHit>of()
                : PrecedentLane.vectorHits(embeddingService, repository, properties, request, ids,
                        degradation);
        var lanes = new LinkedHashMap<String, List<RankFusion.LaneHit>>();
        lanes.put("lexical", PrecedentLane.lanesOf(lexical));
        lanes.put("vector", PrecedentLane.lanesOf(vector));
        var fused = RankFusion.fuse(lanes);
        fused = fused.size() <= limit ? fused : fused.subList(0, limit);

        var found = new ArrayList<PublishedCandidate>();
        var laneInfo = new LinkedHashMap<UUID, LaneInfo>();
        for (var hit : fused) {
            found.add(byId.get(hit.candidateId()));
            laneInfo.put(hit.candidateId(), new LaneInfo(
                    PrecedentLane.rankOf(lexical, hit.candidateId()),
                    PrecedentLane.rankOf(vector, hit.candidateId()),
                    hit.fusedRank(), hit.score()));
        }
        var stages = List.of(
                new RetrievalTrace.StageSummary("structured", ids.size(),
                        ids.stream().map(UUID::toString).toList(), List.of(), List.of()),
                PrecedentLane.hitStage("lexical", lexical),
                PrecedentLane.hitStage("vector", vector),
                PrecedentLane.fusionStage(fused));
        return new Found(new StructuredPolicyResolver.Resolution(List.copyOf(found),
                asOfPublication, false), Map.copyOf(laneInfo), stages, List.copyOf(degradation));
    }
}
