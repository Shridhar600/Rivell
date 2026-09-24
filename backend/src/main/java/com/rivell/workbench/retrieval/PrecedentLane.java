package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeQueryService;

/**
 * Precedent evidence lane (AD #8.2), step 1: scope-applicable published precedents
 * resolved through structured metadata only. Hybrid lexical/vector discovery,
 * deterministic fusion, and bounded Jev reranking join this lane in the following
 * batches; compatibility stays honestly NOT_ASSESSED until policy resolution feeds it.
 */
public class PrecedentLane {

    private final StructuredPolicyResolver resolver;
    private final KnowledgeQueryService knowledgeQueries;
    private final RetrievalProperties properties;

    public PrecedentLane(
            StructuredPolicyResolver resolver,
            KnowledgeQueryService knowledgeQueries,
            RetrievalProperties properties) {
        this.resolver = resolver;
        this.knowledgeQueries = knowledgeQueries;
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
        var items = new ArrayList<EvidenceItem>();
        for (var candidate : resolution.candidates()) {
            items.add(item(candidate, request));
        }
        var stage = new RetrievalTrace.StageSummary(
                "structured", resolution.candidates().size(),
                resolution.candidates().stream().map(c -> c.id().toString()).toList(),
                List.of());
        return new Outcome(List.copyOf(items), List.of(stage), List.of(), List.of(),
                List.of("index:" + properties.indexVersion()));
    }

    private EvidenceItem item(KnowledgeCandidate candidate, EvidenceRequest request) {
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
                "STRUCTURED_METADATA",
                LaneInfo.NONE,
                null,
                PolicyCompatibility.NOT_ASSESSED,
                provenance,
                null,
                null);
    }

    private LegalConcept conceptOf(KnowledgeCandidate candidate) {
        if (candidate.legalConceptKey() == null) {
            return null;
        }
        return LegalConcept.valueOf(candidate.legalConceptKey());
    }

    private String effectiveText(KnowledgeCandidate candidate) {
        return candidate.editedContentText() != null
                ? candidate.editedContentText()
                : candidate.contentText();
    }
}
