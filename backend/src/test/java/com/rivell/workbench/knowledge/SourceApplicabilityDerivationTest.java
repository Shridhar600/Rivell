package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SourceApplicabilityDerivationTest {

    @Test
    void aKnownAppliesToFieldUsesItsRecordedValue() {
        var payload = new CandidatePayload(Map.of("Applies to", "MSA, Supply, Services."));

        assertThat(SourceApplicabilityDerivation.derive(
                payload, ExtractionUnitKind.SPREADSHEET_ROW, "Clause text."))
                .containsExactlyInAnyOrder("MSA", "SUPPLY", "SERVICES");
    }

    @Test
    void aModelResolvedApplicabilityLabelIsReadFromTheStoredFieldRole() {
        var payload = new CandidatePayload(
                Map.of("Which deals", "Supply"), List.of("Which deals"))
                .withFieldRoles(Map.of("Which deals", "APPLICABILITY"));

        assertThat(SourceApplicabilityDerivation.derive(
                payload, ExtractionUnitKind.SPREADSHEET_ROW, "Clause text."))
                .containsExactly("SUPPLY");
    }

    @Test
    void aHeadingSectionUsesItsAppliesToLineWhenThePayloadHasNoField() {
        assertThat(SourceApplicabilityDerivation.derive(
                CandidatePayload.EMPTY, ExtractionUnitKind.HEADING_SECTION,
                "Applies to: Services.\nPreferred position: Thirty days."))
                .containsExactly("SERVICES");
    }

    @Test
    void aRecordThatStatesNothingDerivesNothing() {
        assertThat(SourceApplicabilityDerivation.derive(
                CandidatePayload.EMPTY, ExtractionUnitKind.TABLE, "General terms."))
                .isEmpty();
    }
}
