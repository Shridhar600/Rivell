package com.rivell.workbench.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.RelationshipType;
import com.rivell.workbench.contracts.SemanticsNotFoundException;

/**
 * Current-contract evidence lane (AD #8): the reviewed clause region, its typed
 * facts, and the clauses semantically related to it — resolved through the M5
 * canonical semantics, never through retrieval of the current document.
 */
public class CurrentContractLane {

    private final ContractEnrichmentService enrichment;

    public CurrentContractLane(ContractEnrichmentService enrichmentService) {
        this.enrichment = enrichmentService;
    }

    public record Outcome(List<EvidenceItem> items, String degradation) {

        public static Outcome empty() {
            return new Outcome(List.of(), null);
        }
    }

    public Outcome gather(EvidenceRequest request) {
        ContractSemantics semantics;
        try {
            semantics = enrichment.getSemantics(request.contractId(), request.versionNumber());
        } catch (com.rivell.workbench.contracts.ContractNotFoundException
                | com.rivell.workbench.contracts.ContractVersionNotFoundException
                | SemanticsNotFoundException e) {
            return new Outcome(List.of(), "CONTRACT_CONTEXT_INCOMPLETE");
        }
        if (semantics.enrichmentStatus() != EnrichmentStatus.READY) {
            return new Outcome(List.of(), "CONTRACT_CONTEXT_INCOMPLETE");
        }
        var subject = semantics.clauses().stream()
                .filter(clause -> clause.id().equals(request.clauseId()))
                .findFirst()
                .orElse(null);
        if (subject == null) {
            return new Outcome(List.of(), "CONTRACT_CONTEXT_INCOMPLETE");
        }
        var items = new ArrayList<EvidenceItem>();
        items.add(subjectItem(semantics, subject));
        for (var related : relatedClauses(semantics, subject)) {
            items.add(relatedItem(semantics, subject, related.clause(), related.type()));
        }
        return new Outcome(List.copyOf(items), null);
    }

    private EvidenceItem subjectItem(ContractSemantics semantics, ClauseSemantics subject) {
        return new EvidenceItem(
                UUID.randomUUID(),
                AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_CLAUSE",
                null,
                null,
                semantics.contractVersionId(),
                subject.id(),
                subject.primaryConcept().orElse(null),
                null,
                subject.contentText(),
                null,
                subject.effectiveFacts(),
                "CANONICAL_STRUCTURE",
                LaneInfo.NONE,
                null,
                null,
                subject.textNodeIds().stream().map(UUID::toString).toList(),
                null,
                null);
    }

    private EvidenceItem relatedItem(ContractSemantics semantics, ClauseSemantics subject,
            ClauseSemantics related, RelationshipType type) {
        return new EvidenceItem(
                UUID.randomUUID(),
                AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_CLAUSE",
                null,
                null,
                semantics.contractVersionId(),
                related.id(),
                related.primaryConcept().orElse(null),
                null,
                related.contentText(),
                null,
                related.effectiveFacts(),
                "SEMANTIC_RELATIONSHIP:" + type.name(),
                LaneInfo.NONE,
                null,
                null,
                related.textNodeIds().stream().map(UUID::toString).toList(),
                null,
                null);
    }

    /**
     * Semantic relationships reference canonical region (heading) node ids
     * (AD #5.13), while clause ids are fresh per interpretation — resolve through
     * the heading node id.
     */
    private List<RelatedClause> relatedClauses(ContractSemantics semantics, ClauseSemantics subject) {
        var related = new ArrayList<RelatedClause>();
        for (var relationship : semantics.relationships()) {
            var type = relationship.type();
            if (relationship.sourceRegionNodeId().equals(subject.headingNodeId())) {
                clauseByHeading(semantics, relationship.targetRegionNodeId())
                        .ifPresent(clause -> related.add(new RelatedClause(clause, type)));
            }
            if (relationship.targetRegionNodeId().equals(subject.headingNodeId())) {
                clauseByHeading(semantics, relationship.sourceRegionNodeId())
                        .ifPresent(clause -> related.add(new RelatedClause(clause, type)));
            }
        }
        return related;
    }

    private java.util.Optional<ClauseSemantics> clauseByHeading(
            ContractSemantics semantics, UUID headingNodeId) {
        return semantics.clauses().stream()
                .filter(clause -> clause.headingNodeId().equals(headingNodeId))
                .findFirst();
    }

    private record RelatedClause(ClauseSemantics clause, RelationshipType type) {
    }
}
