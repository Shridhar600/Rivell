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

    public ContractEnrichmentService(
            ContractQueryService queryService,
            ContractRepository contractRepository,
            ContractSemanticsRepository semanticsRepository,
            ContractSemanticEnricher enricher,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService) {
        this.queryService = queryService;
        this.contractRepository = contractRepository;
        this.semanticsRepository = semanticsRepository;
        this.enricher = enricher;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
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
                throw new IllegalStateException("Contract version has no parsed structure");
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
        transactionTemplate.executeWithoutResult(status -> {
            var updated = semanticsRepository.updateClauseCorrection(clauseId, organizationId,
                    correction, currentIdentityId());
            if (!updated) {
                throw new SemanticsNotFoundException(contractId, versionNumber);
            }
        });
        return getSemantics(contractId, versionNumber);
    }

    private ContractSemantics ready(
            MergedResult merged, UUID versionId, UUID organizationId, ReviewProfile profile) {
        return new ContractSemantics(
                merged.id(), versionId, organizationId, EnrichmentStatus.READY,
                ContractSemantics.PIPELINE_VERSION, profile,
                merged.metadata(), merged.parties(), merged.definedTerms(),
                merged.references(), merged.clauses(), merged.relationships(),
                merged.quality(), merged.observations(), List.of());
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
                        var effective = LegalConcept.valueOf(correction.primaryConcept());
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
            ConceptClassification classification, LegalConcept effective) {
        var members = new ArrayList<LegalConcept>(classification.concepts());
        if (!members.contains(effective)) {
            members.add(effective);
        }
        var reordered = new ArrayList<LegalConcept>();
        reordered.add(effective);
        for (var member : members) {
            if (member != effective) {
                reordered.add(member);
            }
        }
        var secondaries = reordered.stream()
                .filter(concept -> concept != effective)
                .toList();
        return new ConceptClassification(reordered, secondaries, classification.probabilities());
    }

    private void validateCorrection(ClauseSemantics clause, FactCorrection correction) {
        if (correction.primaryConcept() != null) {
            LegalConcept concept;
            try {
                concept = LegalConcept.valueOf(correction.primaryConcept());
            } catch (IllegalArgumentException e) {
                throw new InvalidCorrectionException(clause.id(), "Unknown concept");
            }
        }
        if (correction.factValues() != null && !correction.factValues().isEmpty()) {
            var schemaId = clause.facts().stream()
                    .map(LegalFact::factSchema)
                    .filter(schema -> correction.factValues().keySet().stream()
                            .anyMatch(field -> FactSchema.byId(schema).fields().contains(field)))
                    .findFirst()
                    .orElseThrow(() -> new InvalidCorrectionException(clause.id(),
                            "Corrected fields do not belong to the clause's fact schemas"));
            var schema = FactSchema.byId(schemaId);
            var merged = new LinkedHashMap<String, String>();
            for (var fact : clause.facts()) {
                if (fact.factSchema().equals(schemaId)) {
                    merged.putAll(fact.values());
                }
            }
            merged.putAll(correction.factValues());
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

    private void persistFailed(
            UUID versionId, UUID organizationId, ReviewProfile profile, RuntimeException cause) {
        var reason = List.of("Enrichment failed (" + cause.getClass().getSimpleName() + ")");
        transactionTemplate.executeWithoutResult(status -> {
            var existing = semanticsRepository.find(versionId, organizationId);
            var semanticsId = existing.map(ContractSemantics::id).orElseGet(UUID::randomUUID);
            semanticsRepository.replaceSemantics(new ContractSemantics(
                    semanticsId, versionId, organizationId, EnrichmentStatus.FAILED,
                    ContractSemantics.PIPELINE_VERSION, profile,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    QualityReasons.of(List.of(ReviewQualityNote.PROVIDER_DEGRADATION)),
                    null, reason));
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
