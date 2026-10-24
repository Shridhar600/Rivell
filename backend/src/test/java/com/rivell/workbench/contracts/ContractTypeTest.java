package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

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

    @Test
    void everyExistingTypeKeepsTheApplicabilityItHadBeforeParentTypes() {
        assertThat(ContractType.NDA.recordApplicableCodes())
                .containsExactlyInAnyOrder("NDA", "MNDA", "ONDA");
        assertThat(ContractType.ONDA.recordApplicableCodes())
                .containsExactlyInAnyOrder("ONDA", "NDA");
        assertThat(ContractType.MSA.recordApplicableCodes()).containsExactly("MSA");
        assertThat(ContractType.SAAS.recordApplicableCodes()).containsExactly("SAAS");
        assertThat(ContractType.SWL.recordApplicableCodes()).containsExactly("SWL");
        assertThat(ContractType.OTHER.recordApplicableCodes()).containsExactly("OTHER");
    }

    @Test
    void anOrderFormAndAnAmendmentAlsoReadTheMasterAgreementTheySitUnder() {
        assertThat(ContractType.SUPPLY.recordApplicableCodes()).containsExactly("SUPPLY");
        assertThat(ContractType.SERVICES.recordApplicableCodes()).containsExactly("SERVICES");
        assertThat(ContractType.RESELLER.recordApplicableCodes()).containsExactly("RESELLER");
        assertThat(ContractType.ORDER_FORM.recordApplicableCodes())
                .containsExactlyInAnyOrder("ORDER_FORM", "MSA", "SAAS");
        assertThat(ContractType.AMENDMENT.recordApplicableCodes())
                .containsExactlyInAnyOrder("AMENDMENT", "MSA");
    }

    @Test
    void theNewCommercialTypesCarryTheirProductLabels() {
        assertThat(ContractType.SUPPLY.label()).isEqualTo("Supply agreement");
        assertThat(ContractType.SERVICES.label()).isEqualTo("Services agreement");
        assertThat(ContractType.RESELLER.label()).isEqualTo("Reseller or distribution agreement");
        assertThat(ContractType.ORDER_FORM.label()).isEqualTo("Order form");
        assertThat(ContractType.AMENDMENT.label()).isEqualTo("Amendment");
    }

    @Test
    void commercialTypesReadPartiesTheWayAnMsaDoesAndAnAmendmentReadsEveryRole() {
        var commercial = List.of(PartyRole.CUSTOMER, PartyRole.SUPPLIER,
                PartyRole.PARTY_A, PartyRole.PARTY_B);
        assertThat(M5SemanticSpecs.partyRoleSet(ContractType.SUPPLY)).isEqualTo(commercial);
        assertThat(M5SemanticSpecs.partyRoleSet(ContractType.SERVICES)).isEqualTo(commercial);
        assertThat(M5SemanticSpecs.partyRoleSet(ContractType.ORDER_FORM)).isEqualTo(commercial);
        assertThat(M5SemanticSpecs.partyRoleSet(ContractType.RESELLER)).isEqualTo(commercial);
        assertThat(M5SemanticSpecs.partyRoleSet(ContractType.AMENDMENT)).containsExactly(
                PartyRole.CUSTOMER, PartyRole.SUPPLIER,
                PartyRole.DISCLOSING_PARTY, PartyRole.RECEIVING_PARTY,
                PartyRole.PARTY_A, PartyRole.PARTY_B);
    }

    @Test
    void everyModelledTypeBelongsToOneAreaAndOtherBelongsToNone() {
        assertThat(List.of(ContractType.MSA, ContractType.SAAS, ContractType.SWL,
                ContractType.SUPPLY, ContractType.SERVICES, ContractType.RESELLER,
                ContractType.ORDER_FORM, ContractType.SOW, ContractType.AMENDMENT))
                .allSatisfy(type -> assertThat(type.area())
                        .contains(ContractType.Area.COMMERCIAL));
        assertThat(List.of(ContractType.NDA, ContractType.MNDA, ContractType.ONDA))
                .allSatisfy(type -> assertThat(type.area())
                        .contains(ContractType.Area.CONFIDENTIALITY));
        assertThat(ContractType.DPA.area()).contains(ContractType.Area.DATA_PROTECTION);
        assertThat(ContractType.OTHER.area()).isEmpty();
    }

    @Test
    void aPositionStatedForOneCommercialTypeAlsoGovernsTheRestOfTheArea() {
        assertThat(ContractType.RESELLER.positionApplicableCodes())
                .containsExactlyInAnyOrder("MSA", "SAAS", "SWL", "SUPPLY", "SERVICES",
                        "RESELLER", "ORDER_FORM", "SOW", "AMENDMENT");
        assertThat(ContractType.RESELLER.positionApplicableCodes())
                .contains("MSA")
                .doesNotContain("NDA", "MNDA", "ONDA", "DPA", "OTHER");
    }

    @Test
    void aDataProcessingAgreementKeepsItsMsaParentButNotTheRestOfTheCommercialArea() {
        assertThat(ContractType.DPA.positionApplicableCodes())
                .containsExactlyInAnyOrder("DPA", "MSA")
                .doesNotContain("SUPPLY");
    }

    @Test
    void aConfidentialityPositionNeverReachesACommercialContract() {
        assertThat(ContractType.NDA.positionApplicableCodes())
                .containsExactlyInAnyOrder("NDA", "MNDA", "ONDA")
                .doesNotContain("MSA");
    }
}
