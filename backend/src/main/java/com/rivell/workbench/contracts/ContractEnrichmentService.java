package com.rivell.workbench.contracts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;

import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.ConceptRegistry;

/**
 * M5 enrichment lifecycle: org-scoped trigger, atomic ENRICHING claim, semantic
 * stages with no open transaction, outcome transaction (READY/FAILED). Code owns
 * all transitions; model calls never own control flow. Re-enrichment replaces the
 * machine layer and re-applies stored human corrections by stable keys (party
 * normalized name, clause heading node id, metadata field) — corrections are read
 * BEFORE any write so no run can lose them.
 */
public class ContractEnrichmentService {

    private final ContractQueryService queryService;
    private final ContractRepository contractRepository;
    private final ContractSemanticsRepository semanticsRepository;
    private final ContractSemanticEnricher enricher;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final ConceptRegistry conceptRegistry;

    public ContractEnrichmentService(
            ContractQueryService queryService,
            ContractRepository contractRepository,
            ContractSemanticsRepository semanticsRepository,
            ContractSemanticEnricher enricher,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            ConceptRegistry conceptRegistry) {
        this.queryService = queryService;
        this.contractRepository = contractRepository;
        this.semanticsRepository = semanticsRepository;
        this.enricher = enricher;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.conceptRegistry = conceptRegistry;
    }

    public ContractSemantics enrich(UUID contractId, int versionNumber, ReviewProfile profile) {
        requireEnrichPermission();
        var summary = queryService.getVersionSummary(contractId, versionNumber);
        var organizationId = currentOrganizationId();
        var versionId = summary.versionId();

        var prior = semanticsRepository.find(versionId, organizationId);
        if (!semanticsRepository.beginEnrichment(versionId, organizationId, profile)) {
            throw new EnrichmentInProgressException(contractId, versionNumber);
        }

        try {
            var structure = contractRepository.loadStructure(versionId);
            if (structure.nodes().isEmpty()) {
                throw new ContractNotReadableException();
            }
            var result = enricher.enrich(structure.nodes(), profile, structure.parseWarnings());
            var merged = mergeCorrections(prior, result);
            transactionTemplate.executeWithoutResult(status -> semanticsRepository.replaceSemantics(
                    ready(merged, versionId, organizationId, profile)));
            return getSemantics(contractId, versionNumber);
        } catch (RuntimeException e) {
            persistFailed(versionId, organizationId, profile, e);
            throw e;
        }
    }

    public ContractSemantics getSemantics(UUID contractId, int versionNumber) {
        requireReadPermission();
        var summary = queryService.getVersionSummary(contractId, versionNumber);
        return semanticsRepository
                .find(summary.versionId(), currentOrganizationId())
                .orElseThrow(() -> new SemanticsNotFoundException(contractId, versionNumber));
    }

    /**
     * System-level read for background analysis (no request identity): explicit
     * organization, no authorization check. The caller owns tenant scoping.
     */
    public ContractSemantics getSemanticsForOrganization(
            UUID organizationId, UUID contractVersionId) {
        return semanticsRepository.find(contractVersionId, organizationId)
                .orElseThrow(() -> new SemanticsNotFoundException(contractVersionId));
    }

    /** System-level read resolving the version inside the given organization. */
    public ContractSemantics getSemanticsForOrganization(
            UUID organizationId, UUID contractId, int versionNumber) {
        var summary = queryService.getVersionSummaryForOrganization(
                organizationId, contractId, versionNumber);
        return semanticsRepository.find(summary.versionId(), organizationId)
                .orElseThrow(() -> new SemanticsNotFoundException(contractId, versionNumber));
    }

    public ContractSemantics correctPartyRole(UUID contractId, int versionNumber,
            UUID partyId, List<PartyRole> roles) {
        requireEnrichPermission();
        var summary = queryService.getVersionSummary(contractId, versionNumber);
        var organizationId = currentOrganizationId();
        var semantics = requireReady(summary.versionId(), organizationId, contractId, versionNumber);
        if (semantics.parties().stream().noneMatch(party -> party.id().equals(partyId))) {
            throw new SemanticsNotFoundException(contractId, versionNumber);
        }
        transactionTemplate.executeWithoutResult(status -> {
            var updated = semanticsRepository.updatePartyCorrection(partyId, organizationId,
                    PartyCorrection.of(roles), currentIdentityId());
            if (!updated) {
                throw new SemanticsNotFoundException(contractId, versionNumber);
            }
        });
        return getSemantics(contractId, versionNumber);
    }

    public ContractSemantics correctClause(UUID contractId, int versionNumber,
            UUID clauseId, FactCorrection correction) {
        requireEnrichPermission();
        var summary = queryService.getVersionSummary(contractId, versionNumber);
        var organizationId = currentOrganizationId();
        var semantics = requireReady(summary.versionId(), organizationId, contractId, versionNumber);
        var clause = semantics.clauses().stream()
                .filter(candidate -> candidate.id().equals(clauseId))
                .findFirst()
                .orElseThrow(() -> new SemanticsNotFoundException(contractId, versionNumber));
        validateCorrection(clause, correction);
        var merged = mergeCorrectionPatch(clause.correction(), correction);
        transactionTemplate.executeWithoutResult(status -> {
            var updated = semanticsRepository.updateClauseCorrection(clauseId, organizationId,
                    merged, currentIdentityId());
            if (!updated) {
                throw new SemanticsNotFoundException(contractId, versionNumber);
            }
        });
        return getSemantics(contractId, versionNumber);
    }

    /**
     * Corrections are independent per semantic dimension (AD #5.18): a patch touches
     * only the dimensions it carries. null primaryConcept = leave; blank = clear;
     * null fact value = clear that field's correction. Unrelated corrections survive.
     */
    private FactCorrection mergeCorrectionPatch(FactCorrection current, FactCorrection patch) {
        if (!patch.corrected()) {
            throw new InvalidCorrectionException(null, "Choose a change to make.");
        }
        var primary = patch.primaryConcept() == null
                ? current.primaryConcept()
                : (patch.primaryConcept().isBlank() ? null : patch.primaryConcept());
        var values = new LinkedHashMap<>(current.factValues());
        for (var entry : patch.factValues().entrySet()) {
            if (entry.getValue() == null) {
                values.remove(entry.getKey());
            } else {
                values.put(entry.getKey(), entry.getValue());
            }
        }
        if (primary == null && values.isEmpty()) {
            return FactCorrection.NONE;
        }
        return new FactCorrection(primary, values, UUID.randomUUID());
    }

    private ContractSemantics ready(
            MergedResult merged, UUID versionId, UUID organizationId, ReviewProfile profile) {
        return new ContractSemantics(
                merged.id(), versionId, organizationId, EnrichmentStatus.READY,
                ContractSemantics.PIPELINE_VERSION, profile,
                merged.metadata(), merged.parties(), merged.definedTerms(),
                merged.references(), merged.clauses(), merged.relationships(),
                merged.quality(), merged.observations(), List.of(), null);
    }

    private record MergedResult(
            UUID id,
            List<MetadataFact> metadata,
            List<ContractParty> parties,
            List<DefinedTerm> definedTerms,
            List<ContractReference> references,
            List<ClauseSemantics> clauses,
            List<SemanticRelationship> relationships,
            QualityReasons quality,
            Observations observations) {
    }

    private MergedResult mergeCorrections(
            java.util.Optional<ContractSemantics> prior,
            ContractSemanticEnricher.Result result) {
        if (prior.isEmpty()) {
            return new MergedResult(UUID.randomUUID(), result.metadata(), result.parties(),
                    result.definedTerms(), result.references(), result.clauses(),
                    result.relationships(), result.quality(), result.observations());
        }
        var existing = prior.get();

        var correctionsByParty = new LinkedHashMap<String, ContractParty>();
        for (var party : existing.parties()) {
            if (party.roleCorrected()) {
                correctionsByParty.put(party.normalizedName(), party);
            }
        }
        var mergedParties = result.parties().stream()
                .map(party -> {
                    var corrected = correctionsByParty.get(party.normalizedName());
                    return corrected == null ? party
                            : party.withCorrection(corrected.correction(), corrected.correctedBy());
                })
                .toList();

        var correctionsByClause = new LinkedHashMap<UUID, FactCorrection>();
        var correctedBy = new HashMap<UUID, UUID>();
        for (var clause : existing.clauses()) {
            if (clause.correction().corrected()) {
                correctionsByClause.put(clause.headingNodeId(), clause.correction());
                correctedBy.put(clause.headingNodeId(), clause.correctedBy());
            }
        }
        var mergedClauses = result.clauses().stream()
                .map(clause -> {
                    var correction = correctionsByClause.get(clause.headingNodeId());
                    if (correction == null || !correction.corrected()) {
                        return clause;
                    }
                    var correctedClause = clause.withCorrection(correction,
                            correctedBy.get(clause.headingNodeId()));
                    if (correction.primaryConcept() != null) {
                        var effective = ConceptKey.of(correction.primaryConcept());
                        correctedClause = correctedClause.withClassification(
                                correctedClassification(correctedClause.classification(),
                                        effective));
                    }
                    return correctedClause;
                })
                .toList();

        var correctionsByField = new LinkedHashMap<MetadataField, MetadataFact>();
        for (var fact : existing.metadata()) {
            if (fact.corrected()) {
                correctionsByField.put(fact.field(), fact);
            }
        }
        var mergedMetadata = result.metadata().stream()
                .map(fact -> {
                    var corrected = correctionsByField.get(fact.field());
                    if (corrected == null) {
                        return fact;
                    }
                    return new MetadataFact(fact.id(), fact.contractVersionId(), fact.field(),
                            fact.value(), fact.interpretation(), corrected.correctedValue(),
                            corrected.correctedBy());
                })
                .toList();

        return new MergedResult(existing.id(), mergedMetadata, mergedParties,
                result.definedTerms(), result.references(), mergedClauses,
                result.relationships(), result.quality(), result.observations());
    }

    private ConceptClassification correctedClassification(
            ConceptClassification classification, ConceptKey effective) {
        var members = new ArrayList<ConceptKey>(classification.concepts());
        if (!members.contains(effective)) {
            members.add(effective);
        }
        var reordered = new ArrayList<ConceptKey>();
        reordered.add(effective);
        for (var member : members) {
            if (!member.equals(effective)) {
                reordered.add(member);
            }
        }
        var secondaries = reordered.stream()
                .filter(concept -> !concept.equals(effective))
                .toList();
        return new ConceptClassification(reordered, secondaries,
                classification.probabilities(), classification.headingTopic());
    }

    private void validateCorrection(ClauseSemantics clause, FactCorrection correction) {
        if (correction.primaryConcept() != null && !correction.primaryConcept().isBlank()) {
            try {
                var key = ConceptKey.of(correction.primaryConcept());
                if (!conceptRegistry.contains(key)) {
                    throw new InvalidCorrectionException(clause.id(), "That topic is not recognized.");
                }
            } catch (IllegalArgumentException e) {
                throw new InvalidCorrectionException(clause.id(), "That topic is not recognized.");
            }
        }
        var changedValues = correction.factValues() == null ? Map.<String, String>of()
                : correction.factValues().entrySet().stream()
                        .filter(entry -> entry.getValue() != null)
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                                java.util.Map.Entry::getValue));
        if (!changedValues.isEmpty()) {
            var schemaId = clause.facts().stream()
                    .map(LegalFact::factSchema)
                    .filter(schema -> changedValues.keySet().stream()
                            .anyMatch(field -> FactSchema.byId(schema).fields().contains(field)))
                    .findFirst()
                    .orElseThrow(() -> new InvalidCorrectionException(clause.id(),
                            "These fields do not belong to this clause."));
            var schema = FactSchema.byId(schemaId);
            var merged = new LinkedHashMap<String, String>();
            for (var fact : clause.facts()) {
                if (fact.factSchema().equals(schemaId)) {
                    merged.putAll(fact.values());
                }
            }
            merged.putAll(changedValues);
            new LegalFact(schemaId, schema.version(), merged);
        }
    }

    private ContractSemantics requireReady(UUID versionId, UUID organizationId,
            UUID contractId, int versionNumber) {
        var semantics = semanticsRepository.find(versionId, organizationId)
                .orElseThrow(() -> new SemanticsNotFoundException(contractId, versionNumber));
        if (semantics.enrichmentStatus() == EnrichmentStatus.ENRICHING) {
            throw new EnrichmentInProgressException(contractId, versionNumber);
        }
        if (semantics.enrichmentStatus() == EnrichmentStatus.FAILED) {
            throw new EnrichmentFailedException(contractId, versionNumber);
        }
        return semantics;
    }

    /**
     * Maps a failure to a code-owned reason. Provider classes are the only ones that carry an
     * operational meaning here; anything else is deliberately coarse, because the useful detail
     * belongs in server logs rather than in reviewable state.
     */
    private static EnrichmentFailureReason classify(RuntimeException cause) {
        if (cause instanceof com.rivell.workbench.intelligence.NoProviderConfiguredException) {
            return EnrichmentFailureReason.NO_PROVIDER_CONFIGURED;
        }
        if (cause instanceof com.rivell.workbench.intelligence.ProviderTimeoutException) {
            return EnrichmentFailureReason.PROVIDER_TIMEOUT;
        }
        if (cause instanceof com.rivell.workbench.intelligence.ProviderUnavailableException) {
            return EnrichmentFailureReason.PROVIDER_UNAVAILABLE;
        }
        if (cause instanceof com.rivell.workbench.intelligence.ProviderRejectedException) {
            return EnrichmentFailureReason.PROVIDER_REJECTED;
        }
        if (cause instanceof com.rivell.workbench.intelligence.ProviderProtocolException
                || cause instanceof com.rivell.workbench.intelligence.SemanticDecisionException) {
            return EnrichmentFailureReason.PROVIDER_PROTOCOL;
        }
        if (cause instanceof IllegalArgumentException) {
            return EnrichmentFailureReason.UNSUPPORTED_SOURCE;
        }
        return EnrichmentFailureReason.INTERNAL;
    }

    private void persistFailed(
            UUID versionId, UUID organizationId, ReviewProfile profile, RuntimeException cause) {
        // The stored warning names the code-owned reason, never the exception class: a class name
        // is an implementation detail that changes on refactor and tells a reader nothing about
        // whether the failure is worth retrying. The exception itself stays in the server log.
        var failure = classify(cause);
        var reason = List.of("Enrichment failed (" + failure.name() + ")");
        transactionTemplate.executeWithoutResult(status -> {
            var existing = semanticsRepository.find(versionId, organizationId);
            var semanticsId = existing.map(ContractSemantics::id).orElseGet(UUID::randomUUID);
            semanticsRepository.replaceSemantics(new ContractSemantics(
                    semanticsId, versionId, organizationId, EnrichmentStatus.FAILED,
                    ContractSemantics.PIPELINE_VERSION, profile,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    QualityReasons.of(List.of(ReviewQualityNote.PROVIDER_DEGRADATION)),
                    null, reason, failure));
        });
    }

    private void requireEnrichPermission() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.CONTRACT_ENRICH)) {
            throw new UnauthorizedContractAccessException(identity.userId());
        }
    }

    private void requireReadPermission() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.CONTRACT_READ)) {
            throw new UnauthorizedContractAccessException(identity.userId());
        }
    }

    private UUID currentIdentityId() {
        return identityProvider.currentIdentity().userId();
    }

    private UUID currentOrganizationId() {
        return identityProvider.currentIdentity().organizationId();
    }
}
