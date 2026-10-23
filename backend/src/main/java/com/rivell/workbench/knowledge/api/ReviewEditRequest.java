package com.rivell.workbench.knowledge.api;

import java.util.Map;
import java.util.Set;

/**
 * Human edit request for a candidate. Human corrections are stored separately from the
 * machine proposal; scope edits carry human authority over applicability. Semantic field
 * corrections use the canonical enum/value names as strings and are validated server-side.
 */
public record ReviewEditRequest(
        String title,
        String contentText,
        Map<String, String> payloadColumns,
        ScopeEdit scope,
        SemanticEdit semanticFields,
        String note) {

    public record ScopeEdit(
            String contractPackKey,
            Set<String> declaredContractTypes,
            String partyPerspective,
            String paperSource,
            String jurisdiction,
            java.time.LocalDate effectiveFrom,
            java.time.LocalDate effectiveTo) {
    }

    public record SemanticEdit(
            Set<com.rivell.workbench.knowledge.PositionRole> positions,
            Boolean requiresApproval,
            String escalationTrigger,
            com.rivell.workbench.knowledge.PositionRole variantRole) {
    }
}
