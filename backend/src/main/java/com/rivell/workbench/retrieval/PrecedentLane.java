package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeQueryService;

/**
 * Precedent evidence lane (AD #8.2): metadata narrowing through the structured
 * knowledge query, then lexical + vector discovery within the narrowed set,
 * deterministic RRF fusion, and bounded Jev reranking with policy compatibility.
 * Produces evidence — never a legal decision, never a promotion into policy.
 */
public class PrecedentLane {

    private final StructuredPolicyResolver resolver;
    private final com.rivell.workbench.knowledge.KnowledgeQueryService knowledgeQueries;
    private final RetrievalRepository repository;
    private final com.rivell.workbench.intelligence.EmbeddingService embeddingService;
    private final com.rivell.workbench.intelligence.SemanticDecisionService decisionService;
    private final RetrievalProperties properties;

    public PrecedentLane(
            StructuredPolicyResolver resolver,
            com.rivell.workbench.knowledge.KnowledgeQueryService knowledgeQueries,
            RetrievalRepository repository,
            com.rivell.workbench.intelligence.EmbeddingService embeddingService,
            com.rivell.workbench.intelligence.SemanticDecisionService decisionService,
            RetrievalProperties properties) {
        this.resolver = resolver;
        this.knowledgeQueries = knowledgeQueries;
        this.repository = repository;
        this.embeddingService = embeddingService;
        this.decisionService = decisionService;
        this.properties = properties;
    }

    public record Outcome(
            List<EvidenceItem> items,
            List<RetrievalTrace.StageSummary> stages,
            List<RetrievalTrace.RejectedItem> rejected,
            List<String> degradation,
            List<String> modelVersions) {

        public static Outcome empty() {
            return new Outcome(List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    public Outcome gather(EvidenceRequest request, long asOfPublicationNumber,
            PolicyContext policyContext) {
        var resolution = resolver.resolve(request, java.util.Set.of(CandidateType.PRECEDENT),
                asOfPublicationNumber);
        if (resolution.candidates().isEmpty()) {
            return Outcome.empty();
        }
        var byId = new LinkedHashMap<UUID, KnowledgeCandidate>();
        for (var candidate : resolution.candidates()) {
            byId.put(candidate.id(), candidate);
        }
        var ids = List.copyOf(byId.keySet());
        var degradation = new ArrayList<String>();

        var lexicalHits = repository.findLexicalCandidates(
                request.organizationId(), ids,
                LexicalQueries.orTerms(request.clauseText()), properties.lexicalLimit());
        var vectorHits = vectorHits(request, ids, degradation);
        var fused = bounded(
                RankFusion.fuse(Map.of(
                        "lexical", lanesOf(lexicalHits),
                        "vector", lanesOf(vectorHits))),
                properties.fusionLimit());

        var stages = new ArrayList<RetrievalTrace.StageSummary>();
        stages.add(stage("structured", byId.size(), idStrings(ids), null));
        stages.add(hitStage("lexical", lexicalHits));
        stages.add(hitStage("vector", vectorHits));
        stages.add(fusionStage(fused));

        var rejected = new ArrayList<RetrievalTrace.RejectedItem>();
        var modelVersions = new ArrayList<String>();
        modelVersions.add("index:" + properties.indexVersion());

        var relevanceExecutions = new LinkedHashMap<UUID,
                com.rivell.workbench.intelligence.DecisionExecution>();
        var reranked = rerank(request, fused, byId, rejected, degradation, modelVersions,
                relevanceExecutions);
        stages.add(stage("rerank", reranked.size(), idStrings(reranked), null));

        var compatibilities = assessCompatibility(request, reranked, byId, policyContext,
                degradation, modelVersions, stages);

        var items = new ArrayList<EvidenceItem>();
        for (var candidateId : reranked) {
            items.add(item(byId.get(candidateId), request, rankOf(lexicalHits, candidateId),
                    rankOf(vectorHits, candidateId), fusedHit(fused, candidateId),
                    compatibilities.getOrDefault(candidateId, PolicyCompatibility.NOT_ASSESSED),
                    relevanceExecutions.get(candidateId)));
        }
        return new Outcome(List.copyOf(items), List.copyOf(stages), List.copyOf(rejected),
                List.copyOf(degradation), List.copyOf(modelVersions));
    }

    private Map<UUID, PolicyCompatibility> assessCompatibility(
            EvidenceRequest request,
            List<UUID> reranked,
            Map<UUID, KnowledgeCandidate> byId,
            PolicyContext policyContext,
            List<String> degradation,
            List<String> modelVersions,
            List<RetrievalTrace.StageSummary> stages) {
        var assessed = new LinkedHashMap<UUID, PolicyCompatibility>();
        if (!policyContext.policyResolved()) {
            for (var candidateId : reranked) {
                assessed.put(candidateId, PolicyCompatibility.NOT_ASSESSED);
            }
            return assessed;
        }
        var assessedIds = new ArrayList<UUID>();
        for (var candidateId : reranked) {
            try {
                var execution = decisionService.evaluate(
                        M6SemanticSpecs.precedentPolicyCompatibility(),
                        new M6SemanticStates.PolicyCompatibilityState(
                                effectiveText(byId.get(candidateId)), policyContext.policyTexts()));
                var answer = execution.answers().get(M6SemanticSpecs.COMPATIBLE_WITH_POLICY);
                assessed.put(candidateId,
                        answer instanceof com.rivell.workbench.intelligence.YesNoAnswer yesNo
                                && yesNo.probability() >= 0.5
                                ? PolicyCompatibility.COMPATIBLE
                                : PolicyCompatibility.INCOMPATIBLE);
                assessedIds.add(candidateId);
            } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
                assessed.put(candidateId, PolicyCompatibility.NOT_ASSESSED);
                degradation.add("COMPATIBILITY_UNAVAILABLE");
            }
        }
        stages.add(stage("compatibility", assessedIds.size(), idStrings(assessedIds), null));
        modelVersions.add("spec:" + M6SemanticSpecs.PRECEDENT_POLICY_COMPATIBILITY_SPEC);
        return assessed;
    }

    private List<UUID> rerank(
            EvidenceRequest request,
            List<RankFusion.FusedHit> fused,
            Map<UUID, KnowledgeCandidate> byId,
            List<RetrievalTrace.RejectedItem> rejected,
            List<String> degradation,
            List<String> modelVersions,
            Map<UUID, com.rivell.workbench.intelligence.DecisionExecution> executions) {
        var kept = new ArrayList<UUID>();
        for (var hit : fused) {
            if (kept.size() >= properties.rerankBudget()) {
                rejected.add(new RetrievalTrace.RejectedItem(
                        hit.candidateId(), "rerank", "candidate budget exhausted"));
                continue;
            }
            var candidate = byId.get(hit.candidateId());
            var state = new M6SemanticStates.PrecedentRelevanceState(
                    request.clauseText(),
                    request.legalConcept().name(),
                    effectiveText(candidate),
                    candidate.legalConceptKey());
            try {
                var execution = decisionService.evaluate(M6SemanticSpecs.precedentRelevance(),
                        state);
                var sameIssue = execution.answers().get(M6SemanticSpecs.SAME_LEGAL_ISSUE);
                if (sameIssue instanceof com.rivell.workbench.intelligence.YesNoAnswer yesNo
                        && yesNo.probability() < 0.5) {
                    rejected.add(new RetrievalTrace.RejectedItem(
                            hit.candidateId(), "rerank", "different legal issue"));
                    continue;
                }
                executions.put(hit.candidateId(), execution);
                kept.add(hit.candidateId());
            } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
                degradation.add("RERANK_UNAVAILABLE");
                kept.add(hit.candidateId());
            }
        }
        modelVersions.add("spec:" + M6SemanticSpecs.PRECEDENT_RELEVANCE_SPEC);
        return kept;
    }

    private List<RetrievalRepository.CandidateHit> vectorHits(
            EvidenceRequest request, List<UUID> ids, List<String> degradation) {
        if (!embeddingService.isConfigured()) {
            return List.of();
        }
        try {
            var query = embeddingService.embed(List.of(request.clauseText()));
            var vector = query.vectors().get(0);
            return repository.findVectorCandidates(
                    request.organizationId(), ids, embeddingService.modelIdentity(),
                    vector.length, VectorLiterals.of(vector), properties.vectorLimit());
        } catch (com.rivell.workbench.intelligence.SemanticDecisionException e) {
            degradation.add("VECTOR_LANE_UNAVAILABLE");
            return List.of();
        }
    }

    private EvidenceItem item(
            KnowledgeCandidate candidate,
            EvidenceRequest request,
            Integer lexicalRank,
            Integer vectorRank,
            RankFusion.FusedHit fused,
            PolicyCompatibility compatibility,
            com.rivell.workbench.intelligence.DecisionExecution relevanceExecution) {
        var unit = knowledgeQueries.findUnit(candidate.extractionUnitId()).orElse(null);
        var provenance = unit == null ? List.<String>of()
                : unit.sourceNodeIds().stream().map(UUID::toString).toList();
        return new EvidenceItem(
                UUID.randomUUID(),
                AuthorityClass.PRECEDENT,
                "PUBLISHED_PRECEDENT",
                candidate.artifactId(),
                candidate.id(),
                null,
                null,
                conceptOf(candidate),
                candidate.scope(),
                effectiveText(candidate),
                null,
                List.of(),
                "HYBRID_FUSION",
                new LaneInfo(lexicalRank, vectorRank,
                        fused == null ? null : fused.fusedRank(),
                        fused == null ? null : fused.score()),
                semanticAssessment(relevanceExecution, judgments(relevanceExecution)),
                compatibility,
                provenance,
                null,
                null);
    }

    private Map<String, Boolean> judgments(
            com.rivell.workbench.intelligence.DecisionExecution execution) {
        var judgments = new LinkedHashMap<String, Boolean>();
        for (var name : List.of(M6SemanticSpecs.SAME_LEGAL_ISSUE,
                M6SemanticSpecs.SAME_CLAUSE_FUNCTION, M6SemanticSpecs.COMPARABLE_CONTEXT)) {
            var answer = execution.answers().get(name);
            if (answer instanceof com.rivell.workbench.intelligence.YesNoAnswer yesNo) {
                judgments.put(name, yesNo.probability() >= 0.5);
            }
        }
        return java.util.Collections.unmodifiableMap(judgments);
    }

    private EvidenceItem.SemanticAssessment semanticAssessment(
            com.rivell.workbench.intelligence.DecisionExecution execution,
            Map<String, Boolean> judgments) {
        var metadata = execution.metadata();
        return new EvidenceItem.SemanticAssessment(
                judgments, metadata.specId(), metadata.specVersion(), metadata.executionId());
    }

    private LegalConcept conceptOf(KnowledgeCandidate candidate) {
        if (candidate.legalConceptKey() == null) {
            return null;
        }
        return LegalConcept.valueOf(candidate.legalConceptKey());
    }

    private String effectiveText(KnowledgeCandidate candidate) {
        return candidate.effectiveContentText();
    }

    private List<RankFusion.LaneHit> lanesOf(List<RetrievalRepository.CandidateHit> hits) {
        var lanes = new ArrayList<RankFusion.LaneHit>();
        for (var hit : hits) {
            lanes.add(new RankFusion.LaneHit(hit.candidateId(), hit.rank()));
        }
        return List.copyOf(lanes);
    }

    private List<RankFusion.FusedHit> bounded(List<RankFusion.FusedHit> hits, int limit) {
        return hits.size() <= limit ? hits : hits.subList(0, limit);
    }

    private Integer rankOf(List<RetrievalRepository.CandidateHit> hits, UUID candidateId) {
        for (var hit : hits) {
            if (hit.candidateId().equals(candidateId)) {
                return hit.rank();
            }
        }
        return null;
    }

    private RankFusion.FusedHit fusedHit(List<RankFusion.FusedHit> fused, UUID candidateId) {
        for (var hit : fused) {
            if (hit.candidateId().equals(candidateId)) {
                return hit;
            }
        }
        return null;
    }

    private List<String> idStrings(List<UUID> ids) {
        var values = new ArrayList<String>();
        for (var id : ids) {
            values.add(id.toString());
        }
        return List.copyOf(values);
    }

    private RetrievalTrace.StageSummary stage(
            String name, int count, List<String> ids, List<Integer> ranks) {
        return new RetrievalTrace.StageSummary(name, count, ids, ranks == null ? List.of() : ranks);
    }

    private RetrievalTrace.StageSummary hitStage(
            String name, List<RetrievalRepository.CandidateHit> hits) {
        var ids = new ArrayList<String>();
        var ranks = new ArrayList<Integer>();
        for (var hit : hits) {
            ids.add(hit.candidateId().toString());
            ranks.add(hit.rank());
        }
        return new RetrievalTrace.StageSummary(
                name, hits.size(), List.copyOf(ids), List.copyOf(ranks));
    }

    private RetrievalTrace.StageSummary fusionStage(List<RankFusion.FusedHit> fused) {
        var ids = new ArrayList<String>();
        var ranks = new ArrayList<Integer>();
        for (var hit : fused) {
            ids.add(hit.candidateId().toString());
            ranks.add(hit.fusedRank());
        }
        return new RetrievalTrace.StageSummary(
                "fusion", fused.size(), List.copyOf(ids), List.copyOf(ranks));
    }
}
