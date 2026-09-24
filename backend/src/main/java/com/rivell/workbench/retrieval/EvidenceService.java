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
        var policyItems = items(policyResolution, AuthorityClass.POLICY,
                "PUBLISHED_PLAYBOOK_RULE");
        var draftingItems = items(draftingResolution, AuthorityClass.APPROVED_DRAFTING,
                "PUBLISHED_CLAUSE_VARIANT");
        var bundle = new EvidenceBundle(UUID.randomUUID(), request,
                policyItems, draftingItems,
                precedentOutcome.items(), currentOutcome.items(), List.of(),
                sufficiency, null);
        var traceId = persistTrace(request, asOfPublication, policyResolution,
                draftingResolution, precedentOutcome, currentOutcome, bundle,
                startedAt);
        return new EvidenceBundle(bundle.id(), request, bundle.policyItems(),
                bundle.approvedDraftingItems(), bundle.precedentItems(),
                bundle.currentContractItems(), bundle.externalReferenceItems(),
                bundle.sufficiency(), traceId);
    }

    private java.util.UUID persistTrace(
            EvidenceRequest request,
            long asOfPublication,
            StructuredPolicyResolver.Resolution policy,
            StructuredPolicyResolver.Resolution drafting,
            PrecedentLane.Outcome precedent,
            CurrentContractLane.Outcome current,
            EvidenceBundle bundle,
            Instant startedAt) {
        var stages = new LinkedHashMap<String, RetrievalTrace.StageSummary>();
        stages.put("policy", stage("policy", policy.candidates().size(), ids(policy)));
        stages.put("drafting", stage("drafting", drafting.candidates().size(), ids(drafting)));
        for (var stage : precedent.stages()) {
            stages.put(stage.stage(), stage);
        }
        stages.put("currentContract", new RetrievalTrace.StageSummary(
                "currentContract", current.items().size(),
                current.items().stream().map(item -> item.evidenceId().toString()).toList(),
                List.of()));
        var degradation = new java.util.LinkedHashSet<String>();
        degradation.addAll(precedent.degradation());
        if (current.degradation() != null) {
            degradation.add(current.degradation());
        }
        var modelVersions = new LinkedHashMap<String, String>(Map.of(
                "indexVersion", "rivell-evidence-index-1"));
        var traceId = java.util.UUID.randomUUID();
        var selected = new java.util.ArrayList<java.util.UUID>();
        for (var item : bundle.items()) {
            selected.add(item.evidenceId());
        }
        repository.insertTrace(new RetrievalTrace(
                traceId, request.organizationId(), request.contractId(),
                request.versionNumber(), request.clauseId(),
                request.purpose().name(),
                requestOf(request, asOfPublication),
                stages,
                selected,
                precedent.rejected(),
                List.copyOf(degradation),
                Map.of("totalMs", java.time.Duration.between(startedAt, clock.instant())
                        .toMillis()),
                modelVersions,
                request.knowledgePin(),
                clock.instant()));
        return traceId;
    }

    private Map<String, Object> requestOf(EvidenceRequest request, long asOfPublication) {
        var map = new LinkedHashMap<String, Object>();
        map.put("purpose", request.purpose().name());
        map.put("legalConcept", request.legalConcept().name());
        map.put("effectiveOn", request.effectiveOn().toString());
        map.put("knowledgePin", request.knowledgePin().publicationNumber());
        map.put("resolvedAsOfPublication", asOfPublication);
        return map;
    }

    private List<String> ids(StructuredPolicyResolver.Resolution resolution) {
        return resolution.candidates().stream()
                .map(candidate -> candidate.id().toString())
                .toList();
    }

    private RetrievalTrace.StageSummary stage(
            String name, int count, List<String> ids) {
        return new RetrievalTrace.StageSummary(name, count, ids, List.of());
    }

    private long resolveAsOf(EvidenceRequest request) {
        if (request.knowledgePin().pinned()) {
            var pin = request.knowledgePin().publicationNumber();
            var known = knowledgeQueries.listPublications().stream()
                    .anyMatch(publication -> publication.publicationNumber() == pin);
            if (!known) {
                throw new IllegalArgumentException(
                        "Pinned knowledge publication does not exist: " + pin);
            }
            return pin;
        }
        var publications = knowledgeQueries.listPublications();
        return publications.isEmpty() ? 0L : publications.get(0).publicationNumber();
    }

    private EvidenceSufficiency sufficiency(
            StructuredPolicyResolver.Resolution policy,
            StructuredPolicyResolver.Resolution drafting,
            PrecedentLane.Outcome precedent,
            CurrentContractLane.Outcome current,
            long asOfPublication) {
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
        if (current.degradation() != null) {
            return EvidenceSufficiency.CONTRACT_CONTEXT_INCOMPLETE;
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
        var candidates = new java.util.ArrayList<>(resolution.candidates());
        if (authority == AuthorityClass.APPROVED_DRAFTING) {
            candidates.sort(java.util.Comparator.comparingInt(
                    EvidenceService::variantRoleOrder));
        }
        var items = new java.util.ArrayList<EvidenceItem>();
        for (var candidate : candidates) {
            items.add(item(candidate, authority, sourceType));
        }
        return List.copyOf(items);
    }

    private static int variantRoleOrder(
            com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        return switch (candidate.effectiveVariantRole()) {
            case PREFERRED -> 0;
            case FALLBACK -> 1;
            default -> 2;
        };
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
