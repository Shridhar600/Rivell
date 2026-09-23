package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class DeterministicContractExtractorsTest {

    private static final UUID VERSION_ID = UUID.randomUUID();
    private static final UUID BODY_ID = UUID.randomUUID();

    private static DocumentNode bodyNode() {
        return new DocumentNode(BODY_ID, VERSION_ID, null, DocumentNodeType.BODY, 0,
                null, null, null, SourceSpan.ofBody("word/document.xml"), null);
    }

    private static DocumentNode paragraph(String text, int ordinal) {
        return new DocumentNode(UUID.randomUUID(), VERSION_ID, BODY_ID,
                DocumentNodeType.PARAGRAPH, ordinal, text, null, null,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), null);
    }

    private static DocumentNode heading(String text, String label, int ordinal) {
        return new DocumentNode(UUID.randomUUID(), VERSION_ID, BODY_ID,
                DocumentNodeType.HEADING, ordinal, text, 1, label,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), "Heading1");
    }

    private static DocumentNode title(String text, int ordinal) {
        return new DocumentNode(UUID.randomUUID(), VERSION_ID, BODY_ID,
                DocumentNodeType.TITLE, ordinal, text, null, null,
                SourceSpan.ofParagraph("word/document.xml", ordinal, ordinal), "Title");
    }

    @Test
    void extractsTitleEffectiveDateAndGoverningLawDeterministically() {
        var nodes = List.of(
                bodyNode(),
                title("Mutual Non-Disclosure Agreement", 0),
                paragraph("This Agreement is entered into as of March 15, 2026 by and between "
                        + "Helios Systems Inc. (\"Disclosing Party\") and Vertex Ltd. "
                        + "(\"Receiving Party\").", 1),
                heading("Governing Law", "7", 5),
                paragraph("7.1 This Agreement is governed by the laws of the State of Delaware, "
                        + "United States.", 6));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var metadata = new DeterministicContractExtractors().metadata(VERSION_ID, regions, nodes);

        var byField = metadata.stream()
                .collect(Collectors.toMap(MetadataFact::field, MetadataFact::value));
        assertThat(byField).containsEntry(MetadataField.AGREEMENT_TITLE, "Mutual Non-Disclosure Agreement");
        assertThat(byField).containsEntry(MetadataField.EFFECTIVE_DATE, "2026-03-15");
        assertThat(byField).containsEntry(MetadataField.GOVERNING_LAW, "US");
    }

    @Test
    void extractsPartiesWithDeclaredRolesFromPreamble() {
        var preamble = paragraph("This Agreement is entered into by and between Helios Systems Inc., "
                + "a Delaware corporation (\"Disclosing Party\"), and Vertex Analytics Ltd., "
                + "a United Kingdom company (\"Receiving Party\").", 1);
        var nodes = List.of(bodyNode(), preamble);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var parties = new DeterministicContractExtractors().parties(VERSION_ID, regions, nodes);

        assertThat(parties).hasSize(2);
        assertThat(parties.get(0).rawName()).isEqualTo("Helios Systems Inc.");
        assertThat(parties.get(0).machineRoles()).containsExactly(PartyRole.DISCLOSING_PARTY);
        assertThat(parties.get(1).rawName()).isEqualTo("Vertex Analytics Ltd.");
        assertThat(parties.get(1).machineRoles()).containsExactly(PartyRole.RECEIVING_PARTY);
        assertThat(parties.get(0).interpretations().get(0).kind())
                .isEqualTo(InterpretationKind.DETERMINISTIC);
        assertThat(parties.get(0).interpretations().get(0).sourceNodeIds())
                .containsExactly(preamble.id());
    }

    @Test
    void ambiguousRoleLabelsFallBackToPartyAAndPartyB() {
        var nodes = List.of(
                bodyNode(),
                paragraph("This Agreement is entered into by and between Acme Corp and Beta Ltd "
                        + "(each a \"Party\").", 1));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var parties = new DeterministicContractExtractors().parties(VERSION_ID, regions, nodes);

        assertThat(parties).hasSize(2);
        assertThat(parties.get(0).machineRoles()).containsExactly(PartyRole.PARTY_A);
        assertThat(parties.get(1).machineRoles()).containsExactly(PartyRole.PARTY_B);
    }

    @Test
    void detectsDefinedTermsAndTheirReferences() {
        var feesDefinition = paragraph("1.1 \"Fees\" means the fees set out in Schedule A.", 2);
        var servicesText = paragraph("2.1 Supplier shall provide the Services and invoice the Fees monthly.", 4);
        var nodes = List.of(
                bodyNode(),
                heading("Definitions", "1", 1),
                feesDefinition,
                heading("Services", "2", 3),
                servicesText);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).hasSize(1);
        var term = terms.get(0);
        assertThat(term.term()).isEqualTo("Fees");
        assertThat(term.definition()).isEqualTo("the fees set out in Schedule A.");
        assertThat(term.definingNodeId()).isEqualTo(feesDefinition.id());
        assertThat(term.referenceNodeIds()).containsExactly(servicesText.id());
        assertThat(term.duplicate()).isFalse();
    }

    @Test
    void duplicateDefinedTermIsFlagged() {
        var nodes = List.of(
                bodyNode(),
                heading("Definitions", "1", 1),
                paragraph("1.1 \"Confidential Information\" means all exchanged business information.", 2),
                paragraph("1.2 \"Confidential Information\" means information marked confidential.", 3));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).hasSize(2);
        assertThat(terms.stream().allMatch(DefinedTerm::duplicate)).isTrue();
    }

    @Test
    void resolvesSectionScheduleAndExternalReferences() {
        var nodes = List.of(
                bodyNode(),
                paragraph("This Agreement is entered into by and between Northwind LLC (\"Customer\") "
                        + "and Pallas GmbH (\"Supplier\").", 1),
                heading("Limitation of Liability", "6", 2),
                paragraph("6.1 Carve-outs apply as set forth in Section 6.3 and the caps in Schedule A. "
                        + "Service levels follow the Data Processing Agreement dated November 11, 2025.", 3),
                paragraph("6.3 The exclusions do not apply to breaches of confidentiality.", 4),
                heading("Schedule A — Services", "8", 5));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var references = new DeterministicContractExtractors().references(VERSION_ID, regions, nodes);

        var sectionRef = references.stream()
                .filter(reference -> reference.referenceText().equals("Section 6.3"))
                .findFirst().orElseThrow();
        assertThat(sectionRef.kind()).isEqualTo(ReferenceKind.SECTION);
        assertThat(sectionRef.status()).isEqualTo(ReferenceStatus.RESOLVED);

        var scheduleRef = references.stream()
                .filter(reference -> reference.kind() == ReferenceKind.SCHEDULE)
                .findFirst().orElseThrow();
        assertThat(scheduleRef.status()).isEqualTo(ReferenceStatus.RESOLVED);

        var external = references.stream()
                .filter(reference -> reference.status() == ReferenceStatus.EXTERNAL)
                .findFirst().orElseThrow();
        assertThat(external.referenceText()).contains("dated November 11, 2025");
    }

    @Test
    void brokenSectionReferenceStaysBroken() {
        var nodes = List.of(
                bodyNode(),
                paragraph("This Agreement is entered into by and between Acme Corp and Beta Ltd.", 1),
                heading("Term", "2", 2),
                paragraph("2.1 Orders are placed as set forth in Section 99.", 3));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var references = new DeterministicContractExtractors().references(VERSION_ID, regions, nodes);

        assertThat(references).hasSize(1);
        assertThat(references.get(0).status()).isEqualTo(ReferenceStatus.BROKEN);
        assertThat(references.get(0).targetNodeId()).isNull();
    }
}
