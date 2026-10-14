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
            semantics = enrichment.getSemanticsForOrganization(request.organizationId(),
                    request.contractId(), request.versionNumber());
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
        var seenClauses = new java.util.LinkedHashSet<UUID>();
        items.add(subjectItem(semantics, subject));
        seenClauses.add(subject.id());
        for (var related : relatedClauses(semantics, subject)) {
            if (seenClauses.add(related.clause().id())) {
                items.add(relatedItem(semantics, subject, related.clause(),
                        "SEMANTIC_RELATIONSHIP:" + related.type().name()));
            }
        }
        for (var reference : resolvedReferencesFrom(semantics, subject)) {
            if (seenClauses.add(reference.clause().id())) {
                items.add(relatedItem(semantics, subject, reference.clause(),
                        "RESOLVED_REFERENCE"));
            }
        }
        for (var sibling : siblingClauses(semantics, subject)) {
            if (seenClauses.add(sibling.id())) {
                items.add(relatedItem(semantics, subject, sibling, "SIBLING_CONCEPT"));
            }
        }
        for (var term : definedTermsReferencedBy(semantics, subject)) {
            items.add(definedTermItem(semantics, term));
        }
        return new Outcome(List.copyOf(items), null);
    }

    private List<RelatedClause> resolvedReferencesFrom(
            ContractSemantics semantics, ClauseSemantics subject) {
        var resolved = new ArrayList<RelatedClause>();
        var subjectTextNodes = new java.util.HashSet<>(subject.textNodeIds());
        for (var reference : semantics.references()) {
            if (reference.status() != com.rivell.workbench.contracts.ReferenceStatus.RESOLVED
                    || reference.targetNodeId() == null) {
                continue;
            }
            if (!subjectTextNodes.contains(reference.sourceNodeId())) {
                continue;
            }
            clauseByHeading(semantics, reference.targetNodeId())
                    .ifPresent(clause -> resolved.add(new RelatedClause(clause, null)));
        }
        return resolved;
    }

    private List<ClauseSemantics> siblingClauses(
            ContractSemantics semantics, ClauseSemantics subject) {
        var concept = subject.effectivePrimaryConcept();
        if (concept.isEmpty()) {
            return List.of();
        }
        return semantics.clauses().stream()
                .filter(clause -> !clause.id().equals(subject.id()))
                .filter(clause -> clause.effectivePrimaryConcept().equals(concept))
                .toList();
    }

    /**
     * Defined terms the subject uses. The subject's node set includes its heading: a run-in
     * heading can hold the clause's own text and a term reference (Task 1). A term the clause
     * itself defines is skipped — its definition is already in the clause text.
     */
    private List<com.rivell.workbench.contracts.DefinedTerm> definedTermsReferencedBy(
            ContractSemantics semantics, ClauseSemantics subject) {
        var subjectNodes = new java.util.HashSet<>(subject.textNodeIds());
        subjectNodes.add(subject.headingNodeId());
        return semantics.definedTerms().stream()
                .filter(term -> !subjectNodes.contains(term.definingNodeId()))
                .filter(term -> term.referenceNodeIds().stream()
                        .anyMatch(subjectNodes::contains))
                .toList();
    }

    private EvidenceItem definedTermItem(
            ContractSemantics semantics, com.rivell.workbench.contracts.DefinedTerm term) {
        return new EvidenceItem(
                UUID.randomUUID(),
                AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_DEFINED_TERM",
                null,
                null,
                semantics.contractVersionId(),
                null,
                null,
                null,
                term.term() + " means " + term.definition(),
                null,
                null,
                "DEFINED_TERM",
                LaneInfo.NONE,
                null,
                null,
                term.sourceNodeIds().stream().map(UUID::toString).toList(),
                null,
                null);
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
                subject.effectivePrimaryConcept().orElse(null),
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
            ClauseSemantics related, String reason) {
        return new EvidenceItem(
                UUID.randomUUID(),
                AuthorityClass.CURRENT_CONTRACT,
                "CURRENT_CONTRACT_CLAUSE",
                null,
                null,
                semantics.contractVersionId(),
                related.id(),
                related.effectivePrimaryConcept().orElse(null),
                null,
                related.contentText(),
                null,
                related.effectiveFacts(),
                reason,
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
