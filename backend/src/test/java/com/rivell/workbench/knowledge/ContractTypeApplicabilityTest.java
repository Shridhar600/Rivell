package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContractTypeApplicabilityTest {

    @Test
    void namedTypesMapToCodesAndUnmodelledTypesToOther() {
        assertThat(ContractTypeApplicability.contractTypes("MSA, Supply, Services.", true))
                .containsExactlyInAnyOrder("MSA", "SUPPLY", "SERVICES");
        assertThat(ContractTypeApplicability.contractTypes("Software licence, SaaS order form", true))
                .containsExactlyInAnyOrder("SWL", "ORDER_FORM");
        assertThat(ContractTypeApplicability.contractTypes("NDAs and DPAs", true))
                .containsExactlyInAnyOrder("NDA", "DPA");
        assertThat(ContractTypeApplicability.contractTypes("Mutual NDA / statement of work", true))
                .containsExactlyInAnyOrder("MNDA", "SOW");
        assertThat(ContractTypeApplicability.contractTypes("Supply", true)).containsExactly("SUPPLY");
        assertThat(ContractTypeApplicability.contractTypes("Unmodelled kind", true))
                .containsExactly("OTHER");
    }

    @Test
    void theCommercialTypesMapFromTheNamesPlaybooksUse() {
        assertThat(ContractTypeApplicability.contractTypes(
                "Supply, Purchase agreement, Procurement agreement", true))
                .containsExactly("SUPPLY");
        assertThat(ContractTypeApplicability.contractTypes(
                "Services, Professional services, Consulting agreement, Consultancy agreement", true))
                .containsExactly("SERVICES");
        assertThat(ContractTypeApplicability.contractTypes(
                "Reseller, Distribution agreement, Distributor, Channel partner", true))
                .containsExactly("RESELLER");
        assertThat(ContractTypeApplicability.contractTypes(
                "Order form, Purchase order, Order schedule", true))
                .containsExactly("ORDER_FORM");
        assertThat(ContractTypeApplicability.contractTypes(
                "Amendment, Variation, Addendum", true))
                .containsExactly("AMENDMENT");
    }

    @Test
    void shortCodesMapLikeTheNamesTheyStandFor() {
        assertThat(ContractTypeApplicability.contractTypes("ONDA", true)).containsExactly("ONDA");
        assertThat(ContractTypeApplicability.contractTypes("SWL", true)).containsExactly("SWL");
        assertThat(ContractTypeApplicability.contractTypes("MNDA", true)).containsExactly("MNDA");
        assertThat(ContractTypeApplicability.contractTypes("NDA", true)).containsExactly("NDA");
    }

    @Test
    void hyphensReadAsSpacesBetweenWords() {
        assertThat(ContractTypeApplicability.contractTypes("Master-services agreement", true))
                .containsExactly("MSA");
        assertThat(ContractTypeApplicability.contractTypes("Data-processing addendum", true))
                .containsExactly("DPA");
        assertThat(ContractTypeApplicability.contractTypes("SaaS-order-form", true))
                .containsExactly("ORDER_FORM");
    }

    @Test
    void specificNamesWinOverTheGenericWordsTheyContain() {
        assertThat(ContractTypeApplicability.contractTypes("Master services agreement", true))
                .containsExactly("MSA");
        assertThat(ContractTypeApplicability.contractTypes("Data processing addendum", true))
                .containsExactly("DPA");
        assertThat(ContractTypeApplicability.contractTypes("Statement of work", true))
                .containsExactly("SOW");
        assertThat(ContractTypeApplicability.contractTypes("SaaS order form", true))
                .containsExactly("ORDER_FORM");
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
