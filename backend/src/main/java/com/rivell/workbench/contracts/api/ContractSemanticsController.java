package com.rivell.workbench.contracts.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractEnrichmentService;
import com.rivell.workbench.contracts.ContractParty;
import com.rivell.workbench.contracts.ContractQueryService;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.EnrichmentStatus;
import com.rivell.workbench.contracts.ContractRegionSegmenter;
import com.rivell.workbench.contracts.FactSchema;
import com.rivell.workbench.contracts.Interpretation;
import com.rivell.workbench.contracts.LegalFact;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.MetadataFact;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.SemanticRelationship;
import com.rivell.workbench.foundation.ApiResult;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pure HTTP surface for contract semantic enrichment (M5): trigger enrichment with a
 * review profile, inspect the typed semantic contract, and apply human corrections.
 * Composition and error mapping live in services and the global handler.
 */
@RestController
@RequestMapping("/api/contracts")
@Tag(name = "Contracts", description = "Contract semantic enrichment (M5)")
class ContractSemanticsController {

    private final ContractEnrichmentService enrichmentService;
    private final ContractQueryService queryService;

    ContractSemanticsController(
            ContractEnrichmentService enrichmentService,
            ContractQueryService queryService) {
        this.enrichmentService = enrichmentService;
        this.queryService = queryService;
    }

    @Operation(summary = "Run semantic enrichment on a contract version")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Invalid profile"),
            @ApiResponse(responseCode = "404", description = "Contract or version does not exist "
                    + "in this organization"),
            @ApiResponse(responseCode = "409", description = "Enrichment already running")
    })
    @PostMapping("/{id}/versions/{versionNumber}/enrichment")
    ApiResult<SemanticContractResponse> enrich(
            @PathVariable UUID id,
            @PathVariable int versionNumber,
            @RequestBody ReviewProfileInput profile) {
        var semantics = enrichmentService.enrich(id, versionNumber, profile.toProfile());
        return ApiResult.ok(toResponse(id, versionNumber, semantics));
    }

    @Operation(summary = "Get the typed semantic contract of a contract version")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contract or version or semantics "
                    + "does not exist in this organization")
    })
    @GetMapping("/{id}/versions/{versionNumber}/semantics")
    ApiResult<SemanticContractResponse> getSemantics(
            @PathVariable UUID id, @PathVariable int versionNumber) {
        var semantics = enrichmentService.getSemantics(id, versionNumber);
        return ApiResult.ok(toResponse(id, versionNumber, semantics));
    }

    @Operation(summary = "Correct a party's role (machine interpretation preserved)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "Contract, version, or party does "
                    + "not exist in this organization"),
            @ApiResponse(responseCode = "409", description = "Semantics are not READY")
    })
    @PostMapping("/{id}/versions/{versionNumber}/semantics/parties/{partyId}/role")
    ApiResult<SemanticContractResponse> correctPartyRole(
            @PathVariable UUID id,
            @PathVariable int versionNumber,
            @PathVariable UUID partyId,
            @RequestBody CorrectionRequests.PartyRoleCorrection request) {
        var semantics = enrichmentService.correctPartyRole(id, versionNumber, partyId,
                request.roles());
        return ApiResult.ok(toResponse(id, versionNumber, semantics));
    }

    @Operation(summary = "Correct a clause's primary concept (machine proposal preserved)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Correction violates the model "
                    + "invariants"),
            @ApiResponse(responseCode = "404", description = "Contract, version, or clause does "
                    + "not exist in this organization"),
            @ApiResponse(responseCode = "409", description = "Semantics are not READY")
    })
    @PostMapping("/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/concepts")
    ApiResult<SemanticContractResponse> correctConcept(
            @PathVariable UUID id,
            @PathVariable int versionNumber,
            @PathVariable UUID clauseId,
            @RequestBody CorrectionRequests.ConceptCorrection request) {
        // documented correction semantics: null = no concept change, blank = clear
        // the correction; unknown names are rejected by the domain as 400
        if (request.primary() == null) {
            return ApiResult.ok(toResponse(id, versionNumber,
                    enrichmentService.getSemantics(id, versionNumber)));
        }
        var primary = request.primary();
        if (primary.isBlank()) {
            return ApiResult.ok(toResponse(id, versionNumber, enrichmentService.correctClause(
                    id, versionNumber, clauseId,
                    new com.rivell.workbench.contracts.FactCorrection("",
                            java.util.Map.of(), UUID.randomUUID()))));
        }
        var semantics = enrichmentService.correctClause(id, versionNumber, clauseId,
                com.rivell.workbench.contracts.FactCorrection.ofPrimary(
                        com.rivell.workbench.taxonomy.ConceptKey.of(
                                request.primary())));
        return ApiResult.ok(toResponse(id, versionNumber, semantics));
    }

    @Operation(summary = "Correct a clause's typed fact values (machine proposal preserved)")
    @ResponseStatus(HttpStatus.OK)
    @ApiResponses({
            @ApiResponse(responseCode = "400", description = "Correction violates the fact "
                    + "schema"),
            @ApiResponse(responseCode = "404", description = "Contract, version, or clause does "
                    + "not exist in this organization"),
            @ApiResponse(responseCode = "409", description = "Semantics are not READY")
    })
    @PostMapping("/{id}/versions/{versionNumber}/semantics/clauses/{clauseId}/facts")
    ApiResult<SemanticContractResponse> correctFacts(
            @PathVariable UUID id,
            @PathVariable int versionNumber,
            @PathVariable UUID clauseId,
            @RequestBody CorrectionRequests.FactValuesCorrection request) {
        var semantics = enrichmentService.correctClause(id, versionNumber, clauseId,
                com.rivell.workbench.contracts.FactCorrection.ofFacts(request.values()));
        return ApiResult.ok(toResponse(id, versionNumber, semantics));
    }

    private SemanticContractResponse toResponse(
            UUID contractId, int versionNumber, ContractSemantics semantics) {
        return new SemanticContractResponse(
                contractId,
                versionNumber,
                semantics.enrichmentStatus().name(),
                semantics.failureReason() == null
                        ? null
                        : TenantEnrichmentFailureReason.of(semantics.failureReason()).name(),
                semantics.pipelineVersion(),
                new SemanticContractResponse.ReviewProfileView(
                        semantics.reviewProfile().contractType().name(),
                        nameOf(semantics.reviewProfile().ourSideRole()),
                        nameOf(semantics.reviewProfile().paperSource()),
                        nameOf(semantics.reviewProfile().jurisdiction())),
                semantics.metadata().stream().map(this::toMetadataView).toList(),
                semantics.parties().stream().map(this::toPartyView).toList(),
                semantics.definedTerms().stream().map(term ->
                        new SemanticContractResponse.DefinedTermView(term.id(), term.term(),
                                term.definition(), term.definingNodeId(),
                                term.referenceNodeIds(), term.duplicate())).toList(),
                semantics.references().stream().map(reference ->
                        new SemanticContractResponse.ReferenceView(reference.id(),
                                reference.referenceText(), reference.kind().name(),
                                reference.status().name(), reference.sourceNodeId(),
                                reference.targetNodeId(),
                                reference.candidateNodeIds())).toList(),
                semantics.clauses().stream()
                        .filter(clause -> clause.regionKind()
                                == ContractRegionSegmenter.SemanticRegionKind.CLAUSE)
                        .map(this::toClauseView)
                        .toList(),
                semantics.clauses().stream()
                        .filter(clause -> clause.regionKind()
                                != ContractRegionSegmenter.SemanticRegionKind.CLAUSE)
                        .map(this::toClauseView)
                        .toList(),
                semantics.relationships().stream().map(this::toRelationshipView).toList(),
                semantics.quality().notes().stream()
                        .filter(note -> note != com.rivell.workbench.contracts.ReviewQualityNote.NONE)
                        .map(Enum::name)
                        .toList(),
                semantics.observations() == null ? null : new SemanticContractResponse.ObservationsView(
                        semantics.observations().regionCount(),
                        semantics.observations().decisionExecutionCount(),
                        semantics.observations().questionCount(),
                        semantics.observations().generativeExtractionCount(),
                        semantics.observations().deterministicFactCount(),
                        semantics.observations().providerDegraded(),
                        semantics.observations().budgetExhausted()));
    }

    private SemanticContractResponse.MetadataFactView toMetadataView(MetadataFact fact) {
        return new SemanticContractResponse.MetadataFactView(
                fact.field().name(), fact.value(), fact.correctedValue(), fact.corrected(),
                fact.interpretation().sourceNodeIds());
    }

    private SemanticContractResponse.PartyView toPartyView(ContractParty party) {
        var provenance = party.interpretations().stream()
                .flatMap(interpretation -> interpretation.sourceNodeIds().stream())
                .distinct()
                .toList();
        return new SemanticContractResponse.PartyView(
                party.id(), party.rawName(), party.aliases(),
                party.machineRoles().stream().map(Enum::name).toList(),
                party.effectiveRoles().stream().map(Enum::name).toList(),
                party.roleCorrected(), provenance);
    }

    private SemanticContractResponse.ClauseView toClauseView(ClauseSemantics clause) {
        var heading = clause.contentText();
        var newline = heading.indexOf('\n');
        heading = newline > 0 ? heading.substring(0, newline) : heading;
        var correction = clause.correction();
        return new SemanticContractResponse.ClauseView(
                clause.id(),
                clause.headingNodeId(),
                clause.regionKind().name(),
                heading,
                clause.contentText(),
                clause.primaryConcept()
                        .map(com.rivell.workbench.taxonomy.ConceptKey::value).orElse(null),
                clause.secondaryConcepts().stream()
                        .map(com.rivell.workbench.taxonomy.ConceptKey::value).toList(),
                correction != null && correction.corrected() && correction.primaryConcept() != null
                        ? correction.primaryConcept() : null,
                clause.facts().stream()
                        .map(fact -> factView(fact, clause.interpretations()))
                        .toList(),
                clause.effectiveFacts().stream()
                        .map(fact -> factView(fact, clause.interpretations()))
                        .toList(),
                clause.interpretations().stream()
                        .map(interpretation -> new SemanticContractResponse.InterpretationSummary(
                                interpretation.field(), interpretation.kind().name(),
                                interpretation.proposedValue(), interpretation.specId(),
                                interpretation.sourceNodeIds(),
                                interpretation.verification().name()))
                        .toList(),
                clause.correction().corrected());
    }

    /**
     * Fact provenance: the union of supporting nodes of the interpretations that carry
     * this schema's fields. Multiplicity preserved; absent provenance stays absent.
     */
    private SemanticContractResponse.FactView factView(
            LegalFact fact, List<Interpretation> interpretations) {
        var schema = FactSchema.byId(fact.factSchema());
        var anchors = interpretations.stream()
                .filter(interpretation -> schema.fields().contains(interpretation.field()))
                .flatMap(interpretation -> interpretation.sourceNodeIds().stream())
                .distinct()
                .toList();
        return new SemanticContractResponse.FactView(
                fact.factSchema(), fact.schemaVersion(), fact.values(), anchors);
    }

    private SemanticContractResponse.RelationshipView toRelationshipView(
            SemanticRelationship relationship) {
        return new SemanticContractResponse.RelationshipView(
                relationship.id(), relationship.sourceRegionNodeId(),
                relationship.targetRegionNodeId(), relationship.type().name(),
                relationship.detector().name(), relationship.basis(),
                relationship.probability(), relationship.verification().name());
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
