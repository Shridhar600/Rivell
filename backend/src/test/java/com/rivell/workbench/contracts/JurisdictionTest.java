package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.knowledge.KnownJurisdiction;

class JurisdictionTest {

    private static final List<String> DECLARED_JURISDICTIONS = List.of(
            "US", "UK", "AUSTRALIA", "CANADA", "IRELAND", "INDIA", "NEW_ZEALAND",
            "SINGAPORE", "HONG_KONG", "GERMANY", "FRANCE", "NETHERLANDS", "OTHER",
            "UNSPECIFIED");

    @Test
    void reviewAndKnowledgeJurisdictionsShareTheDeclaredOrder() {
        assertThat(Arrays.stream(Jurisdiction.values()).map(Enum::name))
                .containsExactlyElementsOf(DECLARED_JURISDICTIONS);
        assertThat(Arrays.stream(KnownJurisdiction.values()).map(Enum::name))
                .containsExactlyElementsOf(DECLARED_JURISDICTIONS);
    }

    @Test
    void previouslyStoredValuesStillLoad() {
        assertThat(Jurisdiction.valueOf("US")).isEqualTo(Jurisdiction.US);
        assertThat(Jurisdiction.valueOf("UK")).isEqualTo(Jurisdiction.UK);
        assertThat(Jurisdiction.valueOf("UNSPECIFIED")).isEqualTo(Jurisdiction.UNSPECIFIED);
    }

    @Test
    void knowledgeDescriptionsArePlainCriteria() {
        assertThat(KnownJurisdiction.AUSTRALIA.description())
                .isEqualTo("The unit names Australia or an Australian state or territory.");
    }
}
