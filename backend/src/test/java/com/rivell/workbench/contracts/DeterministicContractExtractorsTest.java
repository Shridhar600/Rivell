package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
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

    private static String governingLawOf(String text) {
        return valueOf(governingLawFactOfParagraphs(text));
    }

    private static String governingLawOfParagraphs(String... paragraphs) {
        return valueOf(governingLawFactOfParagraphs(paragraphs));
    }

    private static String valueOf(MetadataFact fact) {
        return fact == null ? null : fact.value();
    }

    private static MetadataFact governingLawFactOfParagraphs(String... paragraphs) {
        var nodes = new ArrayList<DocumentNode>();
        nodes.add(bodyNode());
        var ordinal = 1;
        for (var text : paragraphs) {
            nodes.add(paragraph(text, ordinal++));
        }
        var regions = new ContractRegionSegmenter().segment(nodes);
        return new DeterministicContractExtractors().metadata(VERSION_ID, regions, nodes).stream()
                .filter(fact -> fact.field() == MetadataField.GOVERNING_LAW)
                .findFirst()
                .orElse(null);
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
        assertThat(byField).containsEntry(MetadataField.GOVERNING_LAW, "State of Delaware");
    }

    @Test
    void extractsGoverningLawFromGovernedByTheLawsOfAsWritten() {
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of Wisconsin."))
                .isEqualTo("State of Wisconsin");
    }

    @Test
    void extractsGoverningLawFromGovernedByAndConstruedInAccordanceWithTheLawsOf() {
        assertThat(governingLawOf("This Agreement is governed by and construed in accordance with "
                + "the laws of the State of Wisconsin."))
                .isEqualTo("State of Wisconsin");
    }

    @Test
    void extractsGoverningLawFromConstruedAndEnforcedInAccordanceWithTheLawsOf() {
        assertThat(governingLawOf("This Agreement is construed and enforced in accordance with "
                + "the laws of New South Wales."))
                .isEqualTo("New South Wales");
    }

    @Test
    void extractsGoverningLawFromTheLawsOfShallGovern() {
        assertThat(governingLawOf("The laws of England and Wales shall govern this Agreement."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOf("The law of Ireland governs this Agreement."))
                .isEqualTo("Ireland");
    }

    @Test
    void extractsGoverningLawFromSubjectToTheLawsOf() {
        assertThat(governingLawOf("Subject to the laws of the State of New York, the parties agree "
                + "to the following."))
                .isEqualTo("State of New York");
    }

    @Test
    void extractsGoverningLawFromTheGoverningLawLabel() {
        assertThat(governingLawOf("Governing law: the laws of Ireland"))
                .isEqualTo("Ireland");
        assertThat(governingLawOf("Governing law is the law of Singapore"))
                .isEqualTo("Singapore");
        assertThat(governingLawOf("Governing law shall be the laws of Hong Kong"))
                .isEqualTo("Hong Kong");
    }

    @Test
    void governingLawStopsAtPunctuationAndTrailingCourtOrDisputePhrases() {
        assertThat(governingLawOf("This Agreement is governed by the laws of New South Wales, Australia."))
                .isEqualTo("New South Wales");
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of New York, "
                + "without regard to its conflict of laws rules."))
                .isEqualTo("State of New York");
        assertThat(governingLawOf("This Agreement is governed by the laws of England and Wales "
                + "and the courts of England and Wales have exclusive jurisdiction."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of Illinois "
                + "and any dispute arising out of it is resolved in its courts."))
                .isEqualTo("State of Illinois");
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of Ohio "
                + "and each party consents to its jurisdiction."))
                .isEqualTo("State of Ohio");
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of Texas "
                + "excluding its conflict of laws rules."))
                .isEqualTo("State of Texas");
    }

    @Test
    void governingLawStopsAtPartiesSubmittingToJurisdictionWithoutAComma() {
        assertThat(governingLawOf("This Agreement is governed by the laws of New South Wales "
                + "and the parties submit to the exclusive jurisdiction of the courts of Sydney."))
                .isEqualTo("New South Wales");
        assertThat(governingLawOf("This Agreement is governed by the laws of England and Wales "
                + "and the parties submit to the exclusive jurisdiction of the courts of London."))
                .isEqualTo("England and Wales");
    }

    @Test
    void governingLawDropsADanglingConjunction() {
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of New York "
                + "and excluding its conflict of laws rules."))
                .isEqualTo("State of New York");
    }

    @Test
    void governingLawFragmentIsNoFact() {
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of"))
                .isNull();
        assertThat(governingLawOf("This Agreement is governed by the laws of New")).isNull();
    }

    @Test
    void governingLawPlaceholdersAreNoFact() {
        assertThat(governingLawOf("Governing law is not specified in this Agreement.")).isNull();
        assertThat(governingLawOf("Governing law is set out in Schedule 1.")).isNull();
    }

    @Test
    void governingLawFromClauseTextJoinsANameSplitAcrossNodes() {
        assertThat(DeterministicContractExtractors.governingLawIn(
                "7. Governing Law\n7.1 This Agreement is governed by the laws of the State of\n"
                        + "New York."))
                .contains("State of New York");
    }

    @Test
    void governingLawIsCappedAndEmptyCaptureIsNoFact() {
        assertThat(governingLawOf("This Agreement is governed by the laws of " + "A".repeat(100)))
                .hasSize(80);
        assertThat(governingLawOf("This Agreement is governed by the laws of the.")).isNull();
    }

    @Test
    void governingLawHuntTableCleanRowsStayClean() {
        assertThat(governingLawOf("This Agreement shall be governed by and construed in accordance "
                + "with the laws of the State of New York, without regard to its conflict of laws "
                + "principles.")).isEqualTo("State of New York");
        assertThat(governingLawOf("The laws of England and Wales govern this Agreement."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOf("This Agreement is governed by the law of Ireland."))
                .isEqualTo("Ireland");
        assertThat(governingLawOf("Governing Law: Delaware")).isEqualTo("Delaware");
        assertThat(governingLawOf("governed by the laws of the State of Washington, U.S.A."))
                .isEqualTo("State of Washington");
        assertThat(governingLawOf("governed by the laws of New South Wales, Australia"))
                .isEqualTo("New South Wales");
        assertThat(governingLawOf("the laws of India")).isNull();
        assertThat(governingLawOf("The laws of the State of New York govern the interpretation of "
                + "this Section 1. This Agreement is governed by the laws of the State of Delaware."))
                .isEqualTo("State of Delaware");
    }

    @Test
    void governingLawJoinsANameSplitAcrossParagraphNodesOfOneRegion() {
        assertThat(governingLawOfParagraphs("This Agreement is governed by the laws of the State of New",
                "York.")).isEqualTo("State of New York");
        assertThat(governingLawOfParagraphs("This Agreement is governed by the laws of New South",
                "Wales.")).isEqualTo("New South Wales");
        assertThat(governingLawOfParagraphs("This Agreement is governed by the laws of England and",
                "Wales.")).isEqualTo("England and Wales");
    }

    @Test
    void agreementWideGoverningLawBeatsASectionSpecificSentence() {
        assertThat(governingLawOf("This Section 1 is construed in accordance with the laws of "
                + "the State of New York. The laws of England and Wales govern this Agreement."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOf("The operation of this Section is governed by the laws of "
                + "the State of New York. This Agreement is governed by the laws of "
                + "England and Wales.")).isEqualTo("England and Wales");
        assertThat(governingLawOf("This Section 7.1 is construed in accordance with the laws of "
                + "the State of New York. The laws of England and Wales govern this Agreement."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOf("The operation of this Section 7.1 is governed by the laws of "
                + "the State of New York. This Agreement is governed by the laws of "
                + "England and Wales.")).isEqualTo("England and Wales");
        assertThat(governingLawOfParagraphs("This Section 1 is construed in accordance with the laws of "
                + "the State of New York.", "The laws of England and Wales govern this Agreement."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOfParagraphs("The operation of this Section is governed by the laws of "
                + "the State of New York.", "This Agreement is governed by the laws of "
                        + "England and Wales.")).isEqualTo("England and Wales");
        assertThat(governingLawOfParagraphs("This Section 1",
                "is governed by the laws of the State of New York.",
                "This Agreement is governed by the laws of England and Wales."))
                .isEqualTo("England and Wales");
        assertThat(governingLawOfParagraphs("The laws of the State of New York govern",
                "this Section 1.",
                "This Agreement is governed by the laws of England and Wales."))
                .isEqualTo("England and Wales");
    }

    @Test
    void agreementWideGoverningLawBeatsASectionSpecificSentenceInAnotherRegion() {
        var nodes = List.of(
                bodyNode(),
                heading("Governing Law", "7", 1),
                paragraph("7.1 This Section 1 is construed in accordance with the laws of "
                        + "the State of New York.", 2),
                heading("General", "8", 3),
                paragraph("8.1 This Agreement is governed by the laws of England and Wales.", 4));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var metadata = new DeterministicContractExtractors().metadata(VERSION_ID, regions, nodes);

        assertThat(metadata).filteredOn(fact -> fact.field() == MetadataField.GOVERNING_LAW)
                .singleElement()
                .extracting(MetadataFact::value)
                .isEqualTo("England and Wales");
    }

    @Test
    void governingLawProvenanceNamesTheRegionHeadingAndTextNodes() {
        var governingHeading = heading("Governing Law", "7", 1);
        var text = paragraph("7.1 This Agreement is governed by the laws of the State of Delaware.", 2);
        var nodes = List.of(bodyNode(), governingHeading, text);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var fact = new DeterministicContractExtractors().metadata(VERSION_ID, regions, nodes).stream()
                .filter(metadataFact -> metadataFact.field() == MetadataField.GOVERNING_LAW)
                .findFirst()
                .orElseThrow();

        assertThat(fact.interpretation().sourceNodeIds())
                .containsExactlyInAnyOrder(governingHeading.id(), text.id());
    }

    @Test
    void governingLawBoundsTheCaptureToAJurisdictionName() {
        assertThat(governingLawOf("The laws of the State of New York apply to this Agreement and "
                + "the parties agree that the laws of Singapore shall govern disputes."))
                .isEqualTo("Singapore");
        assertThat(governingLawOf("This Agreement is governed by the laws of the Province of "
                + "Ontario and the federal laws of Canada applicable therein"))
                .isEqualTo("Province of Ontario");
        assertThat(governingLawOf("Subject to the laws of the State of New York the parties agree "
                + "as follows.")).isEqualTo("State of New York");
    }

    @Test
    void governingLawRejectsCourtsAndJurisdictionText() {
        assertThat(governingLawOf("Governing Law: the courts of Singapore")).isNull();
        assertThat(governingLawOf("Governing Law: subject to the exclusive jurisdiction of the "
                + "courts of Singapore.")).isNull();
        assertThat(governingLawOf("subject to the exclusive jurisdiction of the courts of Singapore"))
                .isNull();
        assertThat(governingLawOf("Governing Law: Courts of Singapore")).isNull();
        assertThat(governingLawOf("Governing Law: Arbitration")).isNull();
    }

    @Test
    void governingLawStopsAtAllCapsProseAndKeepsUnicodeNames() {
        assertThat(governingLawOf("THIS AGREEMENT IS GOVERNED BY THE LAWS OF THE STATE OF NEW YORK "
                + "ALL DISPUTES ARISING HEREUNDER SHALL BE RESOLVED BY ARBITRATION."))
                .isEqualTo("STATE OF NEW YORK");
        assertThat(governingLawOf("This Agreement is governed by the laws of the State of New York "
                + "All disputes arising hereunder shall be resolved by arbitration."))
                .isEqualTo("State of New York");
        assertThat(governingLawOf("This Agreement is governed by the laws of Québec."))
                .isEqualTo("Québec");
        assertThat(governingLawOf("This Agreement is governed by the laws of Namibia."))
                .isEqualTo("Namibia");
        assertThat(governingLawOf("This Agreement is governed by the laws of the Province of "
                + "Québec.")).isEqualTo("Province of Québec");
        assertThat(governingLawOf("This Agreement is governed by the laws of the United Kingdom of "
                + "Great Britain and Northern Ireland."))
                .isEqualTo("United Kingdom of Great Britain and Northern Ireland");
    }

    @Test
    void aNameThatRunsIntoTheScanCapOrEndsInAKnownPairIsNoFact() {
        assertThat(governingLawOf("This Agreement is governed by the laws of England and")).isNull();
        var longName = "This Agreement is governed by the laws of " + "A".repeat(20_100);
        assertThat(governingLawOf(longName)).as("truncated at the 20k scan cap").isNull();
    }

    @Test
    void governingLawLabelAlsoAcceptsAndJurisdiction() {
        assertThat(governingLawOf("Governing law and jurisdiction: the laws of Singapore"))
                .isEqualTo("Singapore");
        assertThat(governingLawOf("Governing law and jurisdiction: the courts of Singapore shall "
                + "have exclusive jurisdiction and the laws of Singapore shall govern this Agreement."))
                .isEqualTo("Singapore");
        assertThat(governingLawOf("Governing law and jurisdiction: the courts of Singapore have "
                + "exclusive jurisdiction. This Agreement is governed by the laws of Singapore."))
                .isEqualTo("Singapore");
        assertThat(governingLawOf("Governing law and jurisdiction: the laws of Singapore, and the "
                + "courts of Singapore shall have exclusive jurisdiction."))
                .isEqualTo("Singapore");
    }

    @Test
    void governingLawPlaceholderWordingIsNoFact() {
        for (var value : List.of("N/A", "NA", "TBD", "TBA", "TBC", "as agreed between the parties",
                "a matter for the parties", "at the discretion of the parties", "to be agreed",
                "mutually agreed", "As agreed between the parties", "A matter for the parties")) {
            assertThat(governingLawOf("Governing law: " + value)).as(value).isNull();
        }
    }

    @Test
    void governingLawNormalisesNonBreakingSpacesAndCurlyQuotes() {
        assertThat(governingLawOf("This Agreement is governed by the laws of\u00A0the State of "
                + "New York.")).isEqualTo("State of New York");
        assertThat(governingLawOf("This Agreement is governed by the laws of New\u00A0South\u00A0Wales."))
                .isEqualTo("New South Wales");
        assertThat(governingLawOf("\u201CGoverning Law\u201D: Delaware")).isEqualTo("Delaware");
        assertThat(governingLawOf("Governing Law: \u201CDelaware\u201D")).isEqualTo("Delaware");
    }

    @Test
    void governingLawScanIsBoundedOnAProtocolPathologicalText() {
        var text = "the laws of ".repeat(50_000);
        assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> assertThat(DeterministicContractExtractors.governingLawIn(text)).isEmpty());
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
    void aTermDefinedByAppositionInTheIntroductionKeepsItsDescribingSentence() {
        var introduction = paragraph("A. The Parties wish to consider a possible integration "
                + "between their services, including technical evaluation in a non-production "
                + "sandbox (the \"Purpose\").", 1);
        var use = paragraph("The Purpose does not permit production deployment.", 3);
        var nodes = List.of(bodyNode(), introduction,
                heading("Scope", "1", 2), use);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).extracting(DefinedTerm::term).containsExactly("Purpose");
        var term = terms.get(0);
        assertThat(term.definition()).isEqualTo("The Parties wish to consider a possible "
                + "integration between their services, including technical evaluation in a "
                + "non-production sandbox");
        assertThat(term.definingNodeId()).isEqualTo(introduction.id());
        assertThat(term.referenceNodeIds()).containsExactly(use.id());
    }

    @Test
    void aPartyRoleNamedInBracketsIsNotADefinedTerm() {
        var introduction = paragraph("Northwind Analytics Limited, a company that provides hosted "
                + "analytics services (the \"Service Provider\"), and Kestrel Foods Limited, which "
                + "buys those services for its stores (the \"Buyer\").", 1);
        var nodes = List.of(bodyNode(), introduction);
        var regions = new ContractRegionSegmenter().segment(nodes);

        assertThat(new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes))
                .isEmpty();
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

    /**
     * DW-080: the same term stated twice in one node is one definition (the persistence key is
     * per defining node), and the first wording is the one kept.
     */
    @Test
    void aTermDefinedTwiceInOneParagraphIsKeptOnce() {
        var nodes = List.of(
                bodyNode(),
                heading("Definitions", "1", 1),
                paragraph("“Fees” means the fees set out in Schedule A; "
                        + "“Fees” means the fees payable under an Order.", 2));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).hasSize(1);
        assertThat(terms.get(0).term()).isEqualTo("Fees");
        assertThat(terms.get(0).definition()).isEqualTo("the fees set out in Schedule A");
        assertThat(terms.get(0).duplicate()).isFalse();
    }

    @Test
    void severalCurlyQuotedDefinitionsInOneParagraphEachKeepTheirOwnText() {
        var definitions = paragraph("In this Agreement: “Downtime” means any period during which "
                + "the Production Service is unavailable; “Scheduled Maintenance” means maintenance "
                + "performed by Cloudberry in accordance with the then-current Documentation; and "
                + "“Users” means named users.", 2);
        var nodes = List.of(
                bodyNode(),
                heading("Definitions", "1", 1),
                definitions);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).hasSize(3);
        var byTerm = terms.stream()
                .collect(Collectors.toMap(DefinedTerm::term, term -> term));
        assertThat(byTerm).containsOnlyKeys("Downtime", "Scheduled Maintenance", "Users");
        assertThat(byTerm.get("Downtime").definition())
                .isEqualTo("any period during which the Production Service is unavailable");
        assertThat(byTerm.get("Scheduled Maintenance").definition())
                .isEqualTo("maintenance performed by Cloudberry in accordance with "
                        + "the then-current Documentation");
        assertThat(byTerm.get("Users").definition()).isEqualTo("named users.");
    }

    @Test
    void aDefinitionInAHeadingParagraphIsFoundAndReferencedFromHeadingText() {
        var defining = heading("1. Definitions. “Scheduled Maintenance” means maintenance under "
                + "the Documentation.", "1.", 1);
        var reference = heading("3. Service Levels. Scheduled Maintenance is excluded from "
                + "availability.", "3.", 2);
        var nodes = List.of(bodyNode(), defining, reference);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).hasSize(1);
        var term = terms.get(0);
        assertThat(term.term()).isEqualTo("Scheduled Maintenance");
        assertThat(term.definingNodeId()).isEqualTo(defining.id());
        assertThat(term.referenceNodeIds()).containsExactly(reference.id());
    }

    @Test
    void aDefinitionWithNoTextBeforeTheNextStartIsSkippedInsteadOfFailing() {
        var malformed = paragraph("“Downtime” means “Users” means named users.", 2);
        var nodes = List.of(
                bodyNode(),
                heading("Definitions", "1", 1),
                malformed);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var terms = new DeterministicContractExtractors().definedTerms(VERSION_ID, regions, nodes);

        assertThat(terms).extracting(DefinedTerm::term).containsExactly("Users");
        assertThat(terms.get(0).definition()).isEqualTo("named users.");
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
    void theRoleConventionIsRecognizedAndDescriptionsAreNotParties() {
        var nodes = List.of(
                bodyNode(),
                paragraph("This Agreement is entered into by and between ACME Inc, "
                        + "a Delaware Corporation (the \"Disclosing Party\"), and Beta LLC "
                        + "(the \"Receiving Party\").", 1));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var parties = new DeterministicContractExtractors().parties(VERSION_ID, regions, nodes);

        assertThat(parties).hasSize(2);
        assertThat(parties.get(0).rawName()).isEqualTo("ACME Inc");
        assertThat(parties.get(0).machineRoles()).containsExactly(PartyRole.DISCLOSING_PARTY);
        assertThat(parties.get(1).rawName()).isEqualTo("Beta LLC");
        assertThat(parties.get(1).machineRoles()).containsExactly(PartyRole.RECEIVING_PARTY);
    }

    @Test
    void foreignLegalFormSuffixesIncludingLowercasePlcYieldParties() {
        var nodes = List.of(
                bodyNode(),
                paragraph("This Agreement is entered into by and between Helvetia Systems AG "
                        + "(\"Supplier\"), Nordwind Logistics B.V. (\"Customer\") and "
                        + "Northgate Finance plc (\"Licensor\").", 1));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var parties = new DeterministicContractExtractors().parties(VERSION_ID, regions, nodes);

        assertThat(parties).extracting(ContractParty::rawName).containsExactly(
                "Helvetia Systems AG", "Nordwind Logistics B.V.", "Northgate Finance plc");
        assertThat(parties.get(0).machineRoles()).containsExactly(PartyRole.SUPPLIER);
        assertThat(parties.get(1).machineRoles()).containsExactly(PartyRole.CUSTOMER);
        assertThat(parties.get(2).machineRoles()).containsExactly(PartyRole.LICENSOR);
    }

    @Test
    void shortLegalFormSuffixesDoNotMatchInsideWordsOrSentenceText() {
        var nodes = List.of(
                bodyNode(),
                paragraph("Notwithstanding the foregoing, the parties agree AB testing and SE "
                        + "region pilots, and Danish House and Nordic Grab milestones are "
                        + "excluded.", 1));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var parties = new DeterministicContractExtractors().parties(VERSION_ID, regions, nodes);

        assertThat(parties).isEmpty();
    }

    @Test
    void partyAliasesFromReferredToAsPhrasesAreCaptured() {
        var nodes = List.of(
                bodyNode(),
                paragraph("This Agreement is entered into by and between Helios Systems Inc. "
                        + "(\"Disclosing Party\") and Vertex Analytics Ltd. "
                        + "(\"Receiving Party\"). Helios Systems Inc. may be referred to as "
                        + "\"Helios\".", 1));
        var regions = new ContractRegionSegmenter().segment(nodes);

        var parties = new DeterministicContractExtractors().parties(VERSION_ID, regions, nodes);

        var helios = parties.stream()
                .filter(party -> party.rawName().startsWith("Helios"))
                .findFirst()
                .orElseThrow();
        assertThat(helios.aliases()).contains("Helios");
    }

    @Test
    void scheduleTableTextReachesRegionContent() {
        var table = new java.util.ArrayList<DocumentNode>();
        var tableId = UUID.randomUUID();
        table.add(new DocumentNode(tableId, VERSION_ID, BODY_ID, DocumentNodeType.TABLE, 2,
                null, null, null, SourceSpan.ofTable("word/document.xml", 3, 0), null));
        table.add(new DocumentNode(UUID.randomUUID(), VERSION_ID, tableId,
                DocumentNodeType.TABLE_ROW, 0, null, null, null,
                SourceSpan.ofRow("word/document.xml", 3, 0, 0), null));
        var cell = new DocumentNode(UUID.randomUUID(), VERSION_ID, table.get(1).id(),
                DocumentNodeType.TABLE_CELL, 0, "24x7 managed hosting for the platform",
                null, null,
                SourceSpan.ofCell("word/document.xml", 3, 0, 0, 0), null);
        table.add(new DocumentNode(table.get(1).id(), VERSION_ID, tableId,
                DocumentNodeType.TABLE_ROW, 0, null, null, null,
                SourceSpan.ofRow("word/document.xml", 3, 0, 0), null));
        table.add(cell);
        var nodes = List.of(
                bodyNode(),
                heading("Schedule A — Services", "8", 2),
                table.get(0), table.get(1), cell);
        var regions = new ContractRegionSegmenter().segment(nodes);

        var regionsList = regions;
        assertThat(regionsList).hasSize(1);
        assertThat(regionsList.get(0).contentText()).contains("managed hosting");
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
