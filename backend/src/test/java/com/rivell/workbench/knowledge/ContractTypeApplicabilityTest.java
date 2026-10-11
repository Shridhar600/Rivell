package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractTypeApplicabilityTest {

    @Test
    void namedTypesMapToCodesAndUnmodelledTypesToOther() {
        assertThat(ContractTypeApplicability.contractTypes("MSA, Supply, Services.", true))
                .containsExactlyInAnyOrder("MSA", "OTHER");
        assertThat(ContractTypeApplicability.contractTypes("Software licence, SaaS order form", true))
                .containsExactlyInAnyOrder("SWL", "SAAS");
        assertThat(ContractTypeApplicability.contractTypes("NDAs and DPAs", true))
                .containsExactlyInAnyOrder("NDA", "DPA");
        assertThat(ContractTypeApplicability.contractTypes("Mutual NDA / statement of work", true))
                .containsExactlyInAnyOrder("MNDA", "SOW");
        assertThat(ContractTypeApplicability.contractTypes("Supply", true)).containsExactly("OTHER");
    }

    @Test
    void textThatDoesNotNarrowAppliesEverywhere() {
        assertThat(ContractTypeApplicability.contractTypes("All agreements", true)).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes("All contract types except NDAs", true)).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes("  ", true)).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes(null, true)).isEmpty();
    }

    @Test
    void aLabelOnlyTheModelReadAsApplicabilityNarrowsOnlyWhenItNamesAModelledType() {
        assertThat(ContractTypeApplicability.contractTypes("Preferred", false)).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes("Category buyer", false)).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes("MSA, SOW", false))
                .containsExactlyInAnyOrder("MSA", "SOW");
        assertThat(ContractTypeApplicability.contractTypes("SOW under an existing master agreement", false))
                .containsExactly("SOW");
    }

    @Test
    void aSentenceIsNotAListOfContractTypes() {
        assertThat(ContractTypeApplicability.contractTypes(
                "The licence covers internal use by named users only", true)).isEmpty();
    }

    @Test
    void aProseRecordStatesApplicabilityOnItsOwnLine() {
        assertThat(ContractTypeApplicability.statedIn(
                "Applies to: MSA, Supply, Services.\nPreferred position: Cap at 12 months' fees."))
                .contains("MSA, Supply, Services.");
        assertThat(ContractTypeApplicability.statedIn(
                "The cap applies to all claims under the agreement.")).isEmpty();
    }
}
