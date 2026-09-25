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
    private final com.rivell.workbench.intelligence.EmbeddingService embeddingService;
    private final com.rivell.workbench.organization.IdentityProvider identityProvider;
    private final com.rivell.workbench.organization.AuthorizationService authorizationService;
    private final Clock clock;

    public EvidenceService(
            StructuredPolicyResolver resolver,
            PrecedentLane precedentLane,
            CurrentContractLane currentContractLane,
            RetrievalRepository repository,
            com.rivell.workbench.knowledge.KnowledgeQueryService knowledgeQueries,
            com.rivell.workbench.intelligence.EmbeddingService embeddingService,
            com.rivell.workbench.organization.IdentityProvider identityProvider,
            com.rivell.workbench.organization.AuthorizationService authorizationService,
            Clock clock) {
        this.resolver = resolver;
        this.precedentLane = precedentLane;
        this.currentContractLane = currentContractLane;
        this.repository = repository;
        this.knowledgeQueries = knowledgeQueries;
        this.embeddingService = embeddingService;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.clock = clock;
    }

    public EvidenceBundle build(EvidenceRequest request) {
        var identity = identityProvider.currentIdentity();
        if (!identity.organizationId().equals(request.organizationId())) {
            throw new UnauthorizedEvidenceAccessException(identity.userId());
        }
        if (!authorizationService.isAuthorized(identity,
                com.rivell.workbench.organization.Permissions.RETRIEVAL_READ)) {
            throw new UnauthorizedEvidenceAccessException(identity.userId());
        }
        return buildInternal(request);
    }

    /** System-level build for background analysis (explicit organization, no identity). */
    public EvidenceBundle buildForOrganization(EvidenceRequest request) {
        return buildInternal(request);
    }

    private EvidenceBundle buildInternal(EvidenceRequest request) {
        var startedAt = clock.instant();
        var stageStart = startedAt;
        var stageTimings = new LinkedHashMap<String, Long>();
        var asOfPublication = resolveAsOf(request);
        stageTimings.put("pinResolution",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        stageStart = clock.instant();
        var policyResolution = resolver.resolve(request, Set.of(CandidateType.PLAYBOOK_RULE),
                asOfPublication);
        var draftingResolution = resolver.resolve(request, Set.of(CandidateType.CLAUSE_VARIANT),
                asOfPublication);
        stageTimings.put("structured",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        stageStart = clock.instant();
        var policyContext = new PolicyContext(
                policyResolution.candidates().size() == 1,
                policyResolution.candidates().stream()
                        .map(KnowledgeCandidate::effectiveContentText)
                        .toList());
        var precedentOutcome = precedentLane.gather(request, asOfPublication, policyContext);
        stageTimings.put("precedent",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        stageStart = clock.instant();
        var currentOutcome = currentContractLane.gather(request);
        stageTimings.put("currentContract",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        var sufficiency = sufficiency(policyResolution, draftingResolution,
                precedentOutcome, currentOutcome, asOfPublication);
        var policyItems = items(policyResolution, request.organizationId(),
                AuthorityClass.POLICY, "PUBLISHED_PLAYBOOK_RULE");
        var draftingItems = items(draftingResolution, request.organizationId(),
                AuthorityClass.APPROVED_DRAFTING, "PUBLISHED_CLAUSE_VARIANT");
        var bundle = new EvidenceBundle(UUID.randomUUID(), request,
                policyItems, draftingItems,
                precedentOutcome.items(), currentOutcome.items(), List.of(),
                sufficiency, degradationOf(precedentOutcome, currentOutcome), null);
        var traceId = persistTrace(request, asOfPublication, policyResolution,
                draftingResolution, precedentOutcome, currentOutcome, bundle,
                startedAt, stageTimings);
        return new EvidenceBundle(bundle.id(), request, bundle.policyItems(),
                bundle.approvedDraftingItems(), bundle.precedentItems(),
                bundle.currentContractItems(), bundle.externalReferenceItems(),
                bundle.sufficiency(), bundle.degradationWarnings(), traceId);
    }

    private List<String> degradationOf(
            PrecedentLane.Outcome precedent, CurrentContractLane.Outcome current) {
        var degradation = new java.util.LinkedHashSet<String>();
        degradation.addAll(precedent.degradation());
        if (current.degradation() != null) {
            degradation.add(current.degradation());
        }
        return List.copyOf(degradation);
    }

    private java.util.UUID persistTrace(
            EvidenceRequest request,
            long asOfPublication,
            StructuredPolicyResolver.Resolution policy,
            StructuredPolicyResolver.Resolution drafting,
            PrecedentLane.Outcome precedent,
            CurrentContractLane.Outcome current,
            EvidenceBundle bundle,
            Instant startedAt,
            Map<String, Long> stageTimings) {
        var stages = new LinkedHashMap<String, RetrievalTrace.StageSummary>();
        stages.put("policy", stage("policy", policy.candidates().size(), ids(policy)));
        stages.put("drafting", stage("drafting", drafting.candidates().size(), ids(drafting)));
        for (var stage : precedent.stages()) {
            stages.put(stage.stage(), stage);
        }
        stages.put("currentContract", new RetrievalTrace.StageSummary(
                "currentContract", current.items().size(),
                current.items().stream().map(item -> item.evidenceId().toString()).toList(),
                List.of(), List.of()));
        var degradation = new java.util.LinkedHashSet<String>();
        degradation.addAll(precedent.degradation());
        if (current.degradation() != null) {
            degradation.add(current.degradation());
        }
        var modelVersions = new LinkedHashMap<String, String>();
        for (var version : precedent.modelVersions()) {
            modelVersions.put(version, version);
        }
        if (embeddingService.isConfigured()
                && !embeddingService.modelIdentity().isBlank()) {
            modelVersions.put("embeddings", embeddingService.modelIdentity());
        }
        var traceId = java.util.UUID.randomUUID();
        var selected = new java.util.ArrayList<java.util.UUID>();
        for (var item : bundle.items()) {
            selected.add(item.evidenceId());
        }
        var timings = new LinkedHashMap<>(stageTimings);
        timings.put("totalMs", java.time.Duration.between(startedAt, clock.instant()).toMillis());
        repository.insertTrace(new RetrievalTrace(
                traceId, request.organizationId(), request.contractId(),
                request.versionNumber(), request.clauseId(),
                request.purpose().name(),
                requestOf(request, asOfPublication),
                stages,
                selected,
                precedent.rejected(),
                List.copyOf(degradation),
                timings,
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
        var profile = request.reviewProfile();
        if (profile != null) {
            map.put("reviewProfile", Map.of(
                    "contractType", profile.contractType() == null ? ""
                            : profile.contractType().name(),
                    "ourSideRole", profile.ourSideRole() == null ? ""
                            : profile.ourSideRole().name(),
                    "paperSource", profile.paperSource() == null ? ""
                            : profile.paperSource().name(),
                    "jurisdiction", profile.jurisdiction() == null ? ""
                            : profile.jurisdiction().name()));
        }
        map.put("clauseText", request.clauseText());
        return map;
    }

    private List<String> ids(StructuredPolicyResolver.Resolution resolution) {
        return resolution.candidates().stream()
                .map(candidate -> candidate.id().toString())
                .toList();
    }

    private RetrievalTrace.StageSummary stage(
            String name, int count, List<String> ids) {
        return new RetrievalTrace.StageSummary(name, count, ids, List.of(), List.of());
    }

    private long resolveAsOf(EvidenceRequest request) {
        if (request.knowledgePin().pinned()) {
            if (request.knowledgePin().empty()) {
                return 0L;
            }
            var pin = request.knowledgePin().publicationNumber();
            if (!knowledgeQueries.publicationNumberExistsForOrganization(
                    request.organizationId(), pin)) {
                throw new IllegalArgumentException(
                        "Pinned knowledge publication does not exist: " + pin);
            }
            return pin;
        }
        return knowledgeQueries.maxPublicationNumberForOrganization(request.organizationId());
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
        // degraded discovery never silently claims full sufficiency
        if (!precedent.degradation().isEmpty()) {
            return EvidenceSufficiency.PARTIAL;
        }
        if (precedent.items().isEmpty()) {
            return EvidenceSufficiency.PRECEDENT_WEAK;
        }
        var policyResolved = policy.candidates().size() == 1;
        if (policyResolved && precedent.items().stream()
                .noneMatch(item -> item.policyCompatibility()
                        == PolicyCompatibility.COMPATIBLE)) {
            // precedent lane ran; every candidate conflicts with applicable policy
            return EvidenceSufficiency.PRECEDENT_WEAK;
        }
        return EvidenceSufficiency.SUFFICIENT;
    }

    private List<EvidenceItem> items(StructuredPolicyResolver.Resolution resolution,
            UUID organizationId, AuthorityClass authority, String sourceType) {
        var matches = new java.util.ArrayList<com.rivell.workbench.knowledge.PublishedCandidate>(
                resolution.published());
        if (authority == AuthorityClass.APPROVED_DRAFTING) {
            matches.sort(java.util.Comparator.comparingInt(
                    EvidenceService::variantRoleOrder));
        }
        var items = new java.util.ArrayList<EvidenceItem>();
        for (var match : matches) {
            items.add(item(match, organizationId, authority, sourceType));
        }
        return List.copyOf(items);
    }

    private static int variantRoleOrder(
            com.rivell.workbench.knowledge.PublishedCandidate match) {
        return switch (match.candidate().effectiveVariantRole()) {
            case PREFERRED -> 0;
            case FALLBACK -> 1;
            default -> 2;
        };
    }

    private EvidenceItem item(
            com.rivell.workbench.knowledge.PublishedCandidate match,
            UUID organizationId, AuthorityClass authority, String sourceType) {
        var candidate = match.candidate();
        return new EvidenceItem(
                UUID.randomUUID(),
                authority,
                sourceType,
                candidate.artifactId(),
                candidate.id(),
                null,
                null,
                candidate.effectiveConceptKey() == null ? null
                        : LegalConcept.valueOf(candidate.effectiveConceptKey()),
                candidate.scope(),
                candidate.effectiveContentText(),
                authority == AuthorityClass.POLICY ? policyFacts(candidate) : null,
                List.of(),
                "STRUCTURED",
                LaneInfo.NONE,
                null,
                PolicyCompatibility.NOT_ASSESSED,
                provenance(organizationId, candidate),
                match.publicationId(),
                match.publicationNumber());
    }

    private EvidenceItem.PolicyFacts policyFacts(
            com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        return new EvidenceItem.PolicyFacts(
                candidate.effectivePositions(),
                candidate.effectiveRequiresApproval(),
                candidate.effectiveEscalationTrigger());
    }

    private List<String> provenance(UUID organizationId,
            com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        return knowledgeQueries.findUnitForOrganization(
                        organizationId, candidate.extractionUnitId())
                .map(com.rivell.workbench.knowledge.ExtractionUnit::sourceNodeIds)
                .map(ids -> ids.stream().map(UUID::toString).toList())
                .orElse(List.of());
    }
}
