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
 * M5 enrichment lifecycle: org-scoped trigger, staging transaction (ENRICHING),
 * semantic stages with no open transaction, outcome transaction (READY/FAILED).
 * Code owns all transitions; model calls never own control flow. Re-enrichment
 * replaces the machine layer and re-applies stored human corrections by stable
 * keys (party normalized name, clause heading node id, metadata field).
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

        var existingStatus = semanticsRepository.findStatus(summary.versionId(), organizationId);
        if (existingStatus.isPresent() && existingStatus.get() == EnrichmentStatus.ENRICHING) {
            throw new EnrichmentInProgressException(contractId, versionNumber);
        }

        var stagedId = UUID.randomUUID();
        transactionTemplate.executeWithoutResult(status -> semanticsRepository.replaceSemantics(
                new ContractSemantics(stagedId, summary.versionId(), organizationId,
                        EnrichmentStatus.ENRICHING, "rivell-contract-enrichment-1", profile,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        QualityReasons.NONE, null)));

        try {
            var structure = contractRepository.loadStructure(summary.versionId());
            if (structure.nodes().isEmpty()) {
                throw new IllegalStateException("Contract version has no parsed structure");
            }
            var result = enricher.enrich(structure.nodes(), profile, structure.parseWarnings());
            var merged = mergeCorrections(summary.versionId(), organizationId, stagedId, result);
            transactionTemplate.executeWithoutResult(status ->
                    semanticsRepository.replaceSemantics(ready(merged, summary.versionId(),
                            organizationId, profile)));
            return getSemantics(contractId, versionNumber);
        } catch (RuntimeException e) {
            persistFailed(stagedId, summary.versionId(), organizationId, profile);
            throw e;
        }
    }

    public ContractSemantics getSemantics(UUID contractId, int versionNumber) {
        requireEnrichPermission();
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
        transactionTemplate.executeWithoutResult(status ->
                semanticsRepository.updatePartyCorrection(partyId, organizationId,
                        PartyCorrection.of(roles), currentIdentityId()));
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
        transactionTemplate.executeWithoutResult(status ->
                semanticsRepository.updateClauseCorrection(clauseId, organizationId,
                        correction, currentIdentityId()));
        return getSemantics(contractId, versionNumber);
    }

    private ContractSemantics ready(
            MergedResult merged, UUID versionId, UUID organizationId, ReviewProfile profile) {
        return new ContractSemantics(
                merged.id(), versionId, organizationId, EnrichmentStatus.READY,
                "rivell-contract-enrichment-1", profile,
                merged.metadata(), merged.parties(), merged.definedTerms(),
                merged.references(), merged.clauses(), merged.relationships(),
                merged.quality(), merged.observations());
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
            UUID versionId, UUID organizationId, UUID stagedId,
            ContractSemanticEnricher.Result result) {
        var prior = semanticsRepository.find(versionId, organizationId);
        if (prior.isEmpty()) {
            return new MergedResult(stagedId, result.metadata(), result.parties(),
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

        var correctionsByField = new HashMap<MetadataField, String>();
        for (var fact : existing.metadata()) {
            if (fact.corrected()) {
                correctionsByField.put(fact.field(), fact.correctedValue());
            }
        }
        var mergedMetadata = result.metadata().stream()
                .map(fact -> correctionsByField.containsKey(fact.field())
                        ? new MetadataFact(fact.id(), versionId, fact.field(), fact.value(),
                                fact.interpretation(), correctionsByField.get(fact.field()),
                                null)
                        : fact)
                .toList();

        return new MergedResult(stagedId, mergedMetadata, mergedParties,
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
            if (!clause.classification().concepts().contains(concept)) {
                throw new InvalidCorrectionException(clause.id(),
                        "Corrected concept must be one of the classified concepts");
            }
        }
        if (correction.factValues() != null && !correction.factValues().isEmpty()) {
            if (clause.facts().isEmpty()) {
                throw new InvalidCorrectionException(clause.id(),
                        "The clause carries no typed facts to correct");
            }
            var schemaId = clause.facts().get(0).factSchema();
            var merged = new LinkedHashMap<String, String>();
            for (var fact : clause.facts()) {
                if (fact.factSchema().equals(schemaId)) {
                    merged.putAll(fact.values());
                }
            }
            merged.putAll(correction.factValues());
            new LegalFact(schemaId, 1, merged);
        }
    }

    private ContractSemantics requireReady(UUID versionId, UUID organizationId,
            UUID contractId, int versionNumber) {
        var semantics = semanticsRepository.find(versionId, organizationId)
                .orElseThrow(() -> new SemanticsNotFoundException(contractId, versionNumber));
        if (semantics.enrichmentStatus() != EnrichmentStatus.READY) {
            throw new EnrichmentInProgressException(contractId, versionNumber);
        }
        return semantics;
    }

    private void persistFailed(
            UUID semanticsId, UUID versionId, UUID organizationId, ReviewProfile profile) {
        transactionTemplate.executeWithoutResult(status -> semanticsRepository.replaceSemantics(
                new ContractSemantics(semanticsId, versionId, organizationId,
                        EnrichmentStatus.FAILED, "rivell-contract-enrichment-1", profile,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        QualityReasons.of(List.of(ReviewQualityNote.PROVIDER_DEGRADATION)),
                        null)));
    }

    private void requireEnrichPermission() {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.CONTRACT_ENRICH)) {
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
