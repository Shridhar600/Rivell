package com.rivell.workbench.contracts.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Product view of the typed semantic contract (M5). Domain meaning only: detected
 * metadata, parties/roles with machine-vs-effective values, defined terms, reference
 * resolution, clause classifications with typed facts, semantic relationships,
 * quality reasons, and provenance summaries. No provider internals, no prompts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SemanticContractResponse(
        UUID contractId,
        int versionNumber,
        String enrichmentStatus,
        String pipelineVersion,
        ReviewProfileView reviewProfile,
        List<MetadataFactView> metadata,
        List<PartyView> parties,
        List<DefinedTermView> definedTerms,
        List<ReferenceView> references,
        List<ClauseView> clauses,
        List<ClauseView> structuralRegions,
        List<RelationshipView> relationships,
        List<String> quality,
        ObservationsView observations) {

    public record ReviewProfileView(
            String contractType,
            String ourSideRole,
            String paperSource,
            String jurisdiction) {
    }

    public record MetadataFactView(
            String field,
            String value,
            String correctedValue,
            boolean corrected,
            List<UUID> sourceNodeIds) {
    }

    public record PartyView(
            UUID id,
            String name,
            List<String> aliases,
            List<String> machineRoles,
            List<String> effectiveRoles,
            boolean corrected,
            List<UUID> sourceNodeIds) {
    }

    public record DefinedTermView(
            UUID id,
            String term,
            String definition,
            UUID definingNodeId,
            List<UUID> referenceNodeIds,
            boolean duplicate) {
    }

    public record ReferenceView(
            UUID id,
            String referenceText,
            String kind,
            String status,
            UUID sourceNodeId,
            UUID targetNodeId,
            List<UUID> candidateNodeIds) {
    }

    public record ClauseView(
            UUID id,
            UUID headingNodeId,
            String regionKind,
            String heading,
            String contentText,
            String primaryConcept,
            List<String> secondaryConcepts,
            String correctedPrimaryConcept,
            List<FactView> machineFacts,
            List<FactView> effectiveFacts,
            List<InterpretationSummary> interpretations,
            boolean corrected) {
    }

    public record FactView(
            String factSchema,
            int schemaVersion,
            Map<String, String> values,
            List<UUID> sourceNodeIds) {
    }

    public record InterpretationSummary(
            String field,
            String kind,
            String proposedValue,
            String specId,
            List<UUID> sourceNodeIds,
            String verification) {
    }

    public record RelationshipView(
            UUID id,
            UUID sourceRegionNodeId,
            UUID targetRegionNodeId,
            String type,
            String detector,
            String basis,
            double probability,
            String verification) {
    }

    public record ObservationsView(
            int regionCount,
            int decisionExecutionCount,
            int questionCount,
            int generativeExtractionCount,
            int deterministicFactCount,
            boolean providerDegraded,
            boolean budgetExhausted) {
    }
}
