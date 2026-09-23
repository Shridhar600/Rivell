package com.rivell.workbench.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Task-specific semantic thresholds. Every threshold is task- and primitive-specific;
 * none is calibrated yet — all are explicit initial policies pending eval calibration.
 * Never copy one threshold to another decision.
 */
@ConfigurationProperties("rivell.intelligence.semantics")
public record SemanticPolicyProperties(
        double artifactTypeMatch,
        double artifactTypeAmbiguityGap,
        double positionProposal,
        double approvalProposal,
        double variantRoleProposal,
        double scopeNarrowProposal,
        double jurisdictionProposal,
        double verificationVerified,
        double verificationDisputed,
        double conflictProposal,
        double supersedeProposal) {

    public SemanticPolicyProperties {
        artifactTypeMatch = threshold(artifactTypeMatch, 0.70);
        artifactTypeAmbiguityGap = nonNegative(artifactTypeAmbiguityGap, 0.15);
        positionProposal = threshold(positionProposal, 0.80);
        approvalProposal = threshold(approvalProposal, 0.80);
        variantRoleProposal = threshold(variantRoleProposal, 0.80);
        scopeNarrowProposal = threshold(scopeNarrowProposal, 0.80);
        verificationVerified = threshold(verificationVerified, 0.85);
        verificationDisputed = threshold(verificationDisputed, 0.35);
        conflictProposal = threshold(conflictProposal, 0.80);
        supersedeProposal = threshold(supersedeProposal, 0.80);
    }

    private static double threshold(double value, double fallback) {
        if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
            return fallback;
        }
        return value;
    }

    private static double nonNegative(double value, double fallback) {
        if (!Double.isFinite(value) || value < 0.0) {
            return fallback;
        }
        return value;
    }
}
