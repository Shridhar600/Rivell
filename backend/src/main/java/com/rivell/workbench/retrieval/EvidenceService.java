package com.rivell.workbench.retrieval;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.knowledge.CandidateType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.PublishedCandidate;

/**
 * Orchestrates authority-aware evidence construction (AD #8): resolves the pinned
 * knowledge state, runs the structured policy/approved-drafting lanes, the precedent
 * discovery lane, and the current-contract lane, then derives a deterministic
 * sufficiency state and persists the retrieval trace. Returns evidence — never a
 * legal decision, never a Finding, never a recommendation.
 */
public class EvidenceService {

    /** Approved variants carried with search-found positions: what a topic comparison carries. */
    private static final int SEARCH_DRAFTING_CAP = 8;

    private final StructuredPolicyResolver resolver;
    private final PrecedentLane precedentLane;
    private final CurrentContractLane currentContractLane;
    private final RetrievalRepository repository;
    private final PositionSearch positionSearch;
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
            PositionSearch positionSearch,
            com.rivell.workbench.knowledge.KnowledgeQueryService knowledgeQueries,
            com.rivell.workbench.intelligence.EmbeddingService embeddingService,
            com.rivell.workbench.organization.IdentityProvider identityProvider,
            com.rivell.workbench.organization.AuthorizationService authorizationService,
            Clock clock) {
        this.resolver = resolver;
        this.precedentLane = precedentLane;
        this.currentContractLane = currentContractLane;
        this.repository = repository;
        this.positionSearch = positionSearch;
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
        return buildInternal(request, null);
    }

    /** System-level build for background analysis (explicit organization, no identity). */
    public EvidenceBundle buildForOrganization(EvidenceRequest request) {
        return buildInternal(request, null);
    }

    public EvidenceBundle buildForOrganization(EvidenceRequest request,
            com.rivell.workbench.intelligence.EmbeddingLookup embeddingLookup) {
        return buildInternal(request, embeddingLookup);
    }

    /**
     * Policy-comparison build (design 6a item 2): policy positions and approved drafting
     * are resolved by topic membership instead of concept equality, capped at
     * {@code maxPositions}. Everything else — authority, scope filters, lanes, trace —
     * is the same evidence construction.
     */
    public EvidenceBundle buildForPolicyComparison(
            EvidenceRequest request, java.util.List<ConceptKey> topics,
            ConceptKey primaryTopic, int maxPositions) {
        return buildForPolicyComparison(request, topics, primaryTopic, maxPositions, null);
    }

    public EvidenceBundle buildForPolicyComparison(
            EvidenceRequest request, java.util.List<ConceptKey> topics,
            ConceptKey primaryTopic, int maxPositions,
            com.rivell.workbench.intelligence.EmbeddingLookup embeddingLookup) {
        if (topics.isEmpty()) {
            throw new IllegalArgumentException(
                    "Policy comparison requires at least one clause topic");
        }
        var asOfPublication = resolveAsOf(request);
        var topicKeys = topics.stream().map(ConceptKey::value).toList();
        var primaryKey = primaryTopic == null ? null : primaryTopic.value();
        var policy = resolver.resolveByTopics(request, Set.of(CandidateType.PLAYBOOK_RULE),
                asOfPublication, topicKeys, primaryKey, maxPositions);
        var drafting = resolver.resolveByTopics(request, Set.of(CandidateType.CLAUSE_VARIANT),
                asOfPublication, topicKeys, primaryKey, maxPositions);
        var warnings = new java.util.ArrayList<String>();
        if (policy.truncated() || drafting.truncated()) {
            // more positions matched than the cap allows; the omission is visible, not silent
            warnings.add("POLICY_POSITIONS_TRUNCATED");
        }
        return assemble(request, asOfPublication, policy, drafting, warnings, true, null,
                embeddingLookup);
    }

    /**
     * Evidence for a clause that expresses no taxonomy topic: no position or precedent can
     * match it, so only the current contract's own text is gathered, for the uncovered-clause
     * triage to cite. The request's concept is the clause's family placeholder and is not
     * used to retrieve anything.
     */
    public EvidenceBundle buildForUncoveredClause(EvidenceRequest request) {
        return buildForUncoveredClause(request, null);
    }

    public EvidenceBundle buildForUncoveredClause(EvidenceRequest request,
            com.rivell.workbench.intelligence.EmbeddingLookup embeddingLookup) {
        var asOfPublication = resolveAsOf(request);
        var none = new StructuredPolicyResolver.Resolution(List.of(), asOfPublication, false);
        return assemble(request, asOfPublication, none, none, List.of(), false, null,
                embeddingLookup);
    }

    /**
     * Engine v2 SEARCH route (spec §6): positions found by a clause's text for a clause no topic
     * paired, or whose topic-matched positions all judged it off their subject. Up to
     * {@code limit} positions not in {@code alreadyJudged} and not carrying a topic a human
     * correction removed from the clause (DW-085), in fused order, each carrying its lane
     * ranks; approved drafting for the found positions' topics, as a topic comparison would carry
     * it; no precedents. The contract lane is gathered as for any clause, for the comparison and
     * the triage to cite. The bundle's
     * sufficiency describes structured policy resolution and does not apply to search results:
     * several found positions are candidates for one clause, not conflicting policy.
     */
    public EvidenceBundle buildForPositionSearch(
            EvidenceRequest request, Set<UUID> alreadyJudged, Set<String> excludedTopics, int limit) {
        return buildForPositionSearch(request, alreadyJudged, excludedTopics, limit, null);
    }

    public EvidenceBundle buildForPositionSearch(
            EvidenceRequest request, Set<UUID> alreadyJudged, Set<String> excludedTopics, int limit,
            com.rivell.workbench.intelligence.EmbeddingLookup embeddingLookup) {
        var asOfPublication = resolveAsOf(request);
        var found = positionSearch.search(request, asOfPublication, alreadyJudged, excludedTopics,
                limit, embeddingLookup);
        var topics = found.resolution().candidates().stream()
                .flatMap(candidate -> candidate.topicKeys().stream()).distinct().toList();
        var drafting = topics.isEmpty()
                ? new StructuredPolicyResolver.Resolution(List.of(), asOfPublication, false)
                : resolver.resolveByTopics(request, Set.of(CandidateType.CLAUSE_VARIANT),
                        asOfPublication, topics, null, SEARCH_DRAFTING_CAP);
        return assemble(request, asOfPublication, found.resolution(), drafting,
                found.degradation(), false, found, embeddingLookup);
    }

    /** The text of every published playbook position that applies to the request's contract. */
    public List<String> applicablePositionTexts(EvidenceRequest request, int limit) {
        return resolver.resolveAll(request, Set.of(CandidateType.PLAYBOOK_RULE), resolveAsOf(request), limit)
                .candidates().stream().map(KnowledgeCandidate::effectiveContentText).toList();
    }

    /**
     * Engine v2 omissions lane (spec §4): every applicable published playbook position of
     * the contract, with its label, for the omission check to subtract the positions the
     * clause checks addressed. No drafting, no precedents.
     */
    public ApplicablePositions buildForOmissions(EvidenceRequest request, int limit) {
        var asOfPublication = resolveAsOf(request);
        var policy = resolver.resolveAll(request, Set.of(CandidateType.PLAYBOOK_RULE),
                asOfPublication, limit);
        var none = new StructuredPolicyResolver.Resolution(List.of(), asOfPublication, false);
        var bundle = assemble(request, asOfPublication, policy, none, List.of(), false, null,
                null);
        return new ApplicablePositions(bundle, positionLabels(policy.published()),
                policy.truncated());
    }

    /** The label a reader sees for each omission position: the person's title when there is one. */
    static Map<UUID, String> positionLabels(List<PublishedCandidate> published) {
        var labels = new LinkedHashMap<UUID, String>();
        for (var match : published) {
            labels.put(match.candidate().id(), match.candidate().effectiveTitle());
        }
        return labels;
    }

    private EvidenceBundle buildInternal(EvidenceRequest request,
            com.rivell.workbench.intelligence.EmbeddingLookup embeddingLookup) {
        var asOfPublication = resolveAsOf(request);
        return assemble(request, asOfPublication,
                resolver.resolve(request, Set.of(CandidateType.PLAYBOOK_RULE), asOfPublication),
                resolver.resolve(request, Set.of(CandidateType.CLAUSE_VARIANT), asOfPublication),
                List.of(), true, null, embeddingLookup);
    }

    private EvidenceBundle assemble(
            EvidenceRequest request, long asOfPublication,
            StructuredPolicyResolver.Resolution policyResolution,
            StructuredPolicyResolver.Resolution draftingResolution,
            List<String> extraWarnings, boolean withPrecedents, PositionSearch.Found search,
            com.rivell.workbench.intelligence.EmbeddingLookup embeddingLookup) {
        var startedAt = clock.instant();
        var stageStart = startedAt;
        var stageTimings = new LinkedHashMap<String, Long>();
        stageTimings.put("pinResolution",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        stageStart = clock.instant();
        stageTimings.put("structured",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        stageStart = clock.instant();
        var policyContext = new PolicyContext(
                policyResolution.candidates().size() == 1,
                policyResolution.candidates().stream()
                        .map(KnowledgeCandidate::effectiveContentText)
                        .toList());
        var precedentOutcome = withPrecedents
                ? precedentLane.gather(request, asOfPublication, policyContext, embeddingLookup)
                : PrecedentLane.Outcome.empty();
        stageTimings.put("precedent",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        stageStart = clock.instant();
        var currentOutcome = currentContractLane.gather(request);
        stageTimings.put("currentContract",
                java.time.Duration.between(stageStart, clock.instant()).toMillis());
        var sufficiency = sufficiency(policyResolution, draftingResolution,
                precedentOutcome, currentOutcome, asOfPublication);
        // one unit read for both structured lanes: every item's provenance comes from this map
        var provenance = provenance(request.organizationId(), policyResolution, draftingResolution);
        var policyItems = items(policyResolution, AuthorityClass.POLICY, "PUBLISHED_PLAYBOOK_RULE",
                search == null ? Map.of() : search.lanes(), provenance);
        var draftingItems = items(draftingResolution, AuthorityClass.APPROVED_DRAFTING,
                "PUBLISHED_CLAUSE_VARIANT", Map.of(), provenance);
        var warnings = new java.util.ArrayList<String>(extraWarnings);
        warnings.addAll(degradationOf(precedentOutcome, currentOutcome));
        var bundle = new EvidenceBundle(UUID.randomUUID(), request,
                policyItems, draftingItems,
                precedentOutcome.items(), currentOutcome.items(), List.of(),
                sufficiency, warnings, null);
        var traceId = persistTrace(request, asOfPublication, policyResolution,
                draftingResolution, precedentOutcome, currentOutcome, search, bundle,
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
            PositionSearch.Found search,
            EvidenceBundle bundle,
            Instant startedAt,
            Map<String, Long> stageTimings) {
        var stages = new LinkedHashMap<String, RetrievalTrace.StageSummary>();
        stages.put("policy", stage("policy", policy.candidates().size(), ids(policy)));
        stages.put("drafting", stage("drafting", drafting.candidates().size(), ids(drafting)));
        for (var stage : precedent.stages()) {
            stages.put(stage.stage(), stage);
        }
        if (search != null) {
            for (var stage : search.stages()) {
                stages.put(stage.stage(), stage);
            }
        }
        stages.put("currentContract", new RetrievalTrace.StageSummary(
                "currentContract", current.items().size(),
                current.items().stream().map(item -> item.evidenceId().toString()).toList(),
                List.of(), List.of()));
        var degradation = new java.util.LinkedHashSet<String>();
        degradation.addAll(precedent.degradation());
        if (search != null) {
            degradation.addAll(search.degradation());
        }
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
        map.put("legalConcept", request.legalConcept().value());
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
                        "Knowledge version " + pin + " does not exist.");
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
            AuthorityClass authority, String sourceType,
            Map<UUID, LaneInfo> lanes, Map<UUID, List<String>> provenance) {
        var matches = new java.util.ArrayList<com.rivell.workbench.knowledge.PublishedCandidate>(
                resolution.published());
        if (authority == AuthorityClass.APPROVED_DRAFTING) {
            matches.sort(java.util.Comparator.comparingInt(
                    EvidenceService::variantRoleOrder));
        }
        var items = new java.util.ArrayList<EvidenceItem>();
        for (var match : matches) {
            items.add(item(match, authority, sourceType,
                    lanes.get(match.candidate().id()), provenance));
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
            AuthorityClass authority, String sourceType, LaneInfo lanes,
            Map<UUID, List<String>> provenance) {
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
                        : ConceptKey.of(candidate.effectiveConceptKey()),
                candidate.scope(),
                candidate.effectiveContentText(),
                authority == AuthorityClass.POLICY ? policyFacts(candidate) : null,
                List.of(),
                lanes == null ? "STRUCTURED" : "SEARCH",
                lanes == null ? LaneInfo.NONE : lanes,
                null,
                PolicyCompatibility.NOT_ASSESSED,
                provenance.getOrDefault(candidate.extractionUnitId(), List.of()),
                match.publicationId(),
                match.publicationNumber(),
                authority == AuthorityClass.APPROVED_DRAFTING
                        ? candidate.effectiveVariantRole()
                        : null,
                candidate.topicKeys());
    }

    private EvidenceItem.PolicyFacts policyFacts(
            com.rivell.workbench.knowledge.KnowledgeCandidate candidate) {
        var fields = candidate.effectivePositionFieldValues();
        var preferred = fields.get(com.rivell.workbench.knowledge.PositionRole.PREFERRED);
        var prohibited = fields.get(com.rivell.workbench.knowledge.PositionRole.PROHIBITED);
        // the wording the source states explicitly wins over the role-tagged column value; a
        // deliberate human clear stays cleared rather than falling back to the old column
        var typedPreferred = candidate.effectivePreferredText();
        var typedFallbacks = candidate.effectiveFallbackTexts();
        var typedProhibited = candidate.effectiveProhibitedText();
        var fallbacks = !typedFallbacks.isEmpty() ? typedFallbacks
                : candidate.fallbackTextsCleared() ? List.<String>of()
                : fields.getOrDefault(com.rivell.workbench.knowledge.PositionRole.FALLBACK, List.of());
        return new EvidenceItem.PolicyFacts(
                candidate.effectivePositions(),
                candidate.effectiveRequiresApproval(),
                candidate.effectiveEscalationTrigger(),
                typedPreferred != null ? typedPreferred
                        : candidate.preferredTextCleared() ? null
                        : preferred == null || preferred.isEmpty() ? null : preferred.getFirst(),
                fallbacks,
                typedProhibited != null ? typedProhibited
                        : candidate.prohibitedTextCleared() ? null
                        : prohibited == null || prohibited.isEmpty() ? null : prohibited.getFirst(),
                candidate.effectiveAuthorityRules(),
                candidate.effectiveAuthorityConflicts());
    }

    /**
     * Source nodes per extraction unit for the units the structured lanes cite, read in one query.
     * A unit the organization cannot see is left out, so its item carries no provenance.
     */
    private Map<UUID, List<String>> provenance(UUID organizationId,
            StructuredPolicyResolver.Resolution... resolutions) {
        var unitIds = new java.util.LinkedHashSet<UUID>();
        for (var resolution : resolutions) {
            resolution.candidates().forEach(candidate -> unitIds.add(candidate.extractionUnitId()));
        }
        if (unitIds.isEmpty()) {
            return Map.of();
        }
        var byUnit = new LinkedHashMap<UUID, List<String>>();
        for (var unit : knowledgeQueries.findUnitsForOrganization(organizationId, unitIds).values()) {
            byUnit.put(unit.id(), unit.sourceNodeIds().stream().map(UUID::toString).toList());
        }
        return Map.copyOf(byUnit);
    }
}
