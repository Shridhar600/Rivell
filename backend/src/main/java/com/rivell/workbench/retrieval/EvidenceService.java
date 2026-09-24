package com.rivell.workbench.retrieval;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.contracts.LegalConcept;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;

/**
 * Orchestrates authority-aware evidence construction (AD #8): resolves the pinned
 * knowledge state, runs the structured policy/approved-drafting lanes, the precedent
 * discovery lane, and the current-contract lane, then derives a deterministic
 * sufficiency state and persists the retrieval trace. Returns evidence — never a
 * legal decision, never a Finding, never a recommendation.
 */
public class EvidenceService {

    private final StructuredPolicyResolver resolver;
    private final PrecedentLane precedentLane;
    private final CurrentContractLane currentContractLane;
    private final RetrievalRepository repository;
    private final com.rivell.workbench.knowledge.KnowledgeQueryService knowledgeQueries;
    private final Clock clock;

    public EvidenceService(
            StructuredPolicyResolver resolver,
            PrecedentLane precedentLane,
            CurrentContractLane currentContractLane,
            RetrievalRepository repository,
            com.rivell.workbench.knowledge.KnowledgeQueryService knowledgeQueries,
            Clock clock) {
        this.resolver = resolver;
        this.precedentLane = precedentLane;
        this.currentContractLane = currentContractLane;
        this.repository = repository;
        this.knowledgeQueries = knowledgeQueries;
        this.clock = clock;
    }

    public EvidenceBundle build(EvidenceRequest request) {
        var startedAt = clock.instant();
        var asOfPublication = resolveAsOf(request);
        var policyResolution = resolver.resolve(request, Set.of(CandidateType.PLAYBOOK_RULE),
                asOfPublication);
        var draftingResolution = resolver.resolve(request, Set.of(CandidateType.CLAUSE_VARIANT),
                asOfPublication);
        var policyContext = new PolicyContext(
                policyResolution.candidates().size() == 1,
                policyResolution.candidates().stream()
                        .map(KnowledgeCandidate::effectiveContentText)
                        .toList());
        var precedentOutcome = precedentLane.gather(request, asOfPublication, policyContext);
        var currentOutcome = currentContractLane.gather(request);
        var sufficiency = sufficiency(policyResolution, draftingResolution,
                precedentOutcome, currentOutcome, asOfPublication);
        return new EvidenceBundle(UUID.randomUUID(), request,
                items(policyResolution, AuthorityClass.POLICY, "PUBLISHED_PLAYBOOK_RULE"),
                items(draftingResolution, AuthorityClass.APPROVED_DRAFTING,
                        "PUBLISHED_CLAUSE_VARIANT"),
                precedentOutcome.items(), currentOutcome.items(), List.of(),
                sufficiency, null);
    }

    private long resolveAsOf(EvidenceRequest request) {
        if (request.knowledgePin().pinned()) {
            return request.knowledgePin().publicationNumber();
        }
        return 0L;
    }

    private EvidenceSufficiency sufficiency(
            StructuredPolicyResolver.Resolution policy,
            StructuredPolicyResolver.Resolution drafting,
            PrecedentLane.Outcome precedent,
            CurrentContractLane.Outcome current,
            long asOfPublication) {
        if (current.degradation() != null) {
            return EvidenceSufficiency.CONTRACT_CONTEXT_INCOMPLETE;
        }
        if (asOfPublication == 0L || policy.missing()) {
            return EvidenceSufficiency.POLICY_MISSING;
        }
        if (policy.ambiguous()) {
            return EvidenceSufficiency.AMBIGUOUS_POLICY;
        }
        var conflicts = policy.candidates().stream()
                .anyMatch(candidate -> !candidate.conflictSignals().isEmpty());
        if (conflicts) {
            return EvidenceSufficiency.SOURCE_CONFLICT;
        }
        if (drafting.missing()) {
            return EvidenceSufficiency.DRAFTING_MISSING;
        }
        if (precedent.items().isEmpty()) {
            return EvidenceSufficiency.PRECEDENT_WEAK;
        }
        return EvidenceSufficiency.SUFFICIENT;
    }

    private List<EvidenceItem> items(StructuredPolicyResolver.Resolution resolution,
            AuthorityClass authority, String sourceType) {
        var items = new java.util.ArrayList<EvidenceItem>();
        for (var candidate : resolution.candidates()) {
            items.add(item(candidate, authority, sourceType));
        }
        return List.copyOf(items);
    }

    private EvidenceItem item(
            com.rivell.workbench.knowledge.KnowledgeCandidate candidate,
            AuthorityClass authority, String sourceType) {
        return new EvidenceItem(
                UUID.randomUUID(),
                authority,
                sourceType,
                candidate.artifactId(),
                candidate.id(),
                null,
                null,
                candidate.legalConceptKey() == null ? null
                        : LegalConcept.valueOf(candidate.legalConceptKey()),
                candidate.scope(),
                candidate.effectiveContentText(),
                authority == AuthorityClass.POLICY ? policyFacts(candidate) : null,
                List.of(),
                "STRUCTURED",
                LaneInfo.NONE,
                null,
                PolicyCompatibility.NOT_ASSESSED,
                provenance(candidate),
                null,
                null);
    }

    private EvidenceItem.PolicyFacts policyFacts(
            com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        return new EvidenceItem.PolicyFacts(
                candidate.effectivePositions(),
                candidate.effectiveRequiresApproval(),
                candidate.effectiveEscalationTrigger());
    }

    private List<String> provenance(com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        return knowledgeQueries.findUnit(candidate.extractionUnitId())
                .map(com.rivell.workbench.knowledge.ExtractionUnit::sourceNodeIds)
                .map(ids -> ids.stream().map(UUID::toString).toList())
                .orElse(List.of());
    }
}
