package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractTypeApplicabilityTest {

    @Test
    void namedTypesMapToCodesAndUnmodelledTypesToOther() {
        assertThat(ContractTypeApplicability.contractTypes("MSA, Supply, Services."))
                .containsExactlyInAnyOrder("MSA", "OTHER");
        assertThat(ContractTypeApplicability.contractTypes("Software licence, SaaS order form"))
                .containsExactlyInAnyOrder("SWL", "SAAS");
        assertThat(ContractTypeApplicability.contractTypes("NDAs and DPAs"))
                .containsExactlyInAnyOrder("NDA", "DPA");
        assertThat(ContractTypeApplicability.contractTypes("Mutual NDA / statement of work"))
                .containsExactlyInAnyOrder("MNDA", "SOW");
        assertThat(ContractTypeApplicability.contractTypes("Supply")).containsExactly("OTHER");
    }

    @Test
    void textThatDoesNotNarrowAppliesEverywhere() {
        assertThat(ContractTypeApplicability.contractTypes("All agreements")).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes("All contract types except NDAs")).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes("  ")).isEmpty();
        assertThat(ContractTypeApplicability.contractTypes(null)).isEmpty();
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
