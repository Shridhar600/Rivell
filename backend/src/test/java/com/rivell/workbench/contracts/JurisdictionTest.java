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

    @Test
    void jurisdictionEqualityNormalisesSpellingAndTheUsAndUkAliases() {
        assertThat(Jurisdiction.normalize("New Zealand")).isEqualTo("newzealand");
        assertThat(Jurisdiction.normalize("NEW_ZEALAND")).isEqualTo("newzealand");
        assertThat(Jurisdiction.same("New Zealand", "NEW_ZEALAND")).isTrue();

        assertThat(Jurisdiction.normalize("United Kingdom")).isEqualTo("uk");
        assertThat(Jurisdiction.normalize("UK")).isEqualTo("uk");
        assertThat(Jurisdiction.same("United Kingdom", "UK")).isTrue();
        assertThat(Jurisdiction.same("UK", "United Kingdom")).isTrue();

        assertThat(Jurisdiction.normalize("United States")).isEqualTo("us");
        assertThat(Jurisdiction.normalize("US")).isEqualTo("us");
        assertThat(Jurisdiction.same("United States", "US")).isTrue();
        assertThat(Jurisdiction.same("US", "United States")).isTrue();

        assertThat(Jurisdiction.same("United States", "United Kingdom")).isFalse();
        assertThat(Jurisdiction.same(null, null)).isTrue();
        assertThat(Jurisdiction.same(null, "US")).isFalse();
    }
}
