package com.rivell.workbench.knowledge;

import java.util.Set;

/**
 * The one derivation of a record's own "Applies to" scope. The compiler and the one-time
 * re-derivation of already-stored candidates both call this method, so a taxonomy change cannot
 * leave old rows reading a retired vocabulary.
 */
final class SourceApplicabilityDerivation {

    private SourceApplicabilityDerivation() {
    }

    /**
     * The contract types a candidate's source states it applies to: its APPLICABILITY field, or
     * for a heading section the "Applies to:" line of the unit text. Empty when the source states
     * nothing.
     */
    static Set<String> derive(CandidatePayload payload, ExtractionUnitKind unitKind,
            String unitContentText) {
        var label = applicabilityLabel(payload);
        if (label != null) {
            var codeKnown = RecordLabelRoles.known(label).orElse(null)
                    == RecordFieldRole.APPLICABILITY;
            return ContractTypeApplicability.contractTypes(payload.columns().get(label), codeKnown);
        }
        if (unitKind == ExtractionUnitKind.HEADING_SECTION) {
            return ContractTypeApplicability.statedIn(unitContentText)
                    .map(text -> ContractTypeApplicability.contractTypes(text, true))
                    .orElse(Set.of());
        }
        return Set.of();
    }

    private static String applicabilityLabel(CandidatePayload payload) {
        for (var key : payload.orderedKeys()) {
            var value = payload.columns().get(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            if (payload.roleOf(key).filter(role -> role == RecordFieldRole.APPLICABILITY)
                    .isPresent()) {
                return key;
            }
        }
        return null;
    }
}
