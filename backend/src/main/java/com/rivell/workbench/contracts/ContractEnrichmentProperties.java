package com.rivell.workbench.contracts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Enrichment thresholds and budgets. All thresholds are task-specific and
 * deliberately UNCALIBRATED initial policy; budgets reject pathological model
 * fan-out deterministically.
 */
@ConfigurationProperties("rivell.contracts.enrichment")
public record ContractEnrichmentProperties(
        double conceptThreshold,
        double primaryConceptThreshold,
        double roleThreshold,
        double verificationThreshold,
        double disputedThreshold,
        double relationshipThreshold,
        int maxRegions,
        int maxRelationshipPairs,
        int maxGenerativeExtractions) {

    public ContractEnrichmentProperties {
        if (conceptThreshold <= 0.0) {
            conceptThreshold = 0.80;
        }
        if (primaryConceptThreshold <= 0.0) {
            primaryConceptThreshold = 0.80;
        }
        if (roleThreshold <= 0.0) {
            roleThreshold = 0.80;
        }
        if (verificationThreshold <= 0.0) {
            verificationThreshold = 0.85;
        }
        if (disputedThreshold <= 0.0) {
            disputedThreshold = 0.35;
        }
        if (relationshipThreshold <= 0.0) {
            relationshipThreshold = 0.80;
        }
        if (maxRegions <= 0) {
            maxRegions = 60;
        }
        if (maxRelationshipPairs <= 0) {
            maxRelationshipPairs = 8;
        }
        if (maxGenerativeExtractions <= 0) {
            maxGenerativeExtractions = 4;
        }
    }
}
