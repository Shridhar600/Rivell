package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractTypeTest {

    @Test
    void aSourceForTheFamilyOrAnyMemberAppliesAcrossTheFamilyButNotAcrossTypes() {
        assertThat(ContractType.MNDA.applicableCodes()).containsExactlyInAnyOrder("MNDA", "NDA");
        assertThat(ContractType.NDA.applicableCodes())
                .containsExactlyInAnyOrder("NDA", "MNDA", "ONDA");
        assertThat(ContractType.DPA.applicableCodes()).containsExactly("DPA");
        assertThat(ContractType.ONDA.label()).isEqualTo("One-way non-disclosure agreement");
    }

    @Test
    void aRecordStatedForTheMsaAlsoGovernsAStatementOfWorkOrDpaUnderIt() {
        assertThat(ContractType.SOW.recordApplicableCodes()).containsExactlyInAnyOrder("SOW", "MSA");
        assertThat(ContractType.DPA.recordApplicableCodes()).containsExactlyInAnyOrder("DPA", "MSA");
        assertThat(ContractType.MNDA.recordApplicableCodes()).containsExactlyInAnyOrder("MNDA", "NDA");
        assertThat(ContractType.SAAS.recordApplicableCodes()).containsExactly("SAAS");
    }
}
