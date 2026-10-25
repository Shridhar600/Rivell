package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * A clause variant's wording is its wording column, not the joined row. "Clause", "Text" and a
 * role the field-role decision resolved are all wording, whatever the code table knew first.
 */
class CandidatePayloadWordingTest {

    @Test
    void aClauseHeaderIsWording() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("Clause ID", "G-01");
        columns.put("Clause", "The Supplier shall defend the Customer.");
        columns.put("Variant", "Preferred");

        assertThat(new CandidatePayload(columns).renderedWording())
                .isEqualTo("The Supplier shall defend the Customer.");
    }

    @Test
    void aTextHeaderIsWording() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("Ref", "G-02");
        columns.put("Text", "The Supplier shall notify the Customer.");
        columns.put("Notes", "Use the standard form.");

        assertThat(new CandidatePayload(columns).renderedWording())
                .isEqualTo("The Supplier shall notify the Customer.");
    }

    @Test
    void aModelResolvedWordingRoleIsHonoured() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("Provision", "The Supplier shall indemnify the Customer.");
        columns.put("Variant", "Fallback 1");
        var payload = new CandidatePayload(columns, List.copyOf(columns.keySet()),
                Map.of("Provision", "WORDING"));

        assertThat(payload.renderedWording()).isEqualTo("The Supplier shall indemnify the Customer.");
    }

    @Test
    void aMachineClauseVariantRecomputesItsWordingFromTheResolvedRole() {
        var columns = new LinkedHashMap<String, String>();
        columns.put("Clause ID", "G-01");
        columns.put("Provision", "The Supplier shall defend the Customer.");
        columns.put("Variant", "Preferred");
        var payload = new CandidatePayload(columns, List.copyOf(columns.keySet()),
                Map.of("Provision", "WORDING"));
        var now = Instant.now();
        var candidate = new KnowledgeCandidate(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), CandidateType.CLAUSE_VARIANT,
                ReviewStatus.PENDING, "G-01", "G-01 | The Supplier shall defend the Customer. | Preferred",
                payload, null, List.of(), null, null, null, SemanticFields.EMPTY, null, List.of(),
                null, null, null,
                new ApplicabilityScope(null, Set.of(), null, null, null, null, null),
                List.of(), null, null, null, null, now, now, null);

        assertThat(candidate.effectiveContentText())
                .isEqualTo("The Supplier shall defend the Customer.");
    }
}
