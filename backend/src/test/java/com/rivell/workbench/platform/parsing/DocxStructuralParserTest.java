package com.rivell.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.ParseStatus;
import com.rivell.workbench.contracts.SourceSpan;
import com.rivell.workbench.platform.docx.DocxTestPackages;

class DocxStructuralParserTest {

    private static final String PARSER_VERSION = "rivell-docx-parser-3";
    private static final UUID VERSION_ID = UUID.randomUUID();
    private final DocxStructuralParser parser =
            new DocxStructuralParser(new DocxParserProperties(200L * 1024 * 1024));

    @Test
    void parsesSimpleContractIntoOrderedOutline() {
        var result = parse("fixtures/simple-contract.docx");

        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        assertThat(result.parserVersion()).isEqualTo(PARSER_VERSION);
        assertThat(result.warnings()).isEmpty();

        var nodes = result.nodes();
        assertThat(nodes).hasSize(7);
        assertThat(nodes.get(0).nodeType()).isEqualTo(DocumentNodeType.BODY);
        assertThat(nodes.get(0).parentId()).isNull();
        assertThat(nodes.get(0).ordinal()).isZero();

        var bodyId = nodes.get(0).id();
        assertThat(nodes.get(1).nodeType()).isEqualTo(DocumentNodeType.TITLE);
        assertThat(nodes.get(1).parentId()).isEqualTo(bodyId);
        assertThat(nodes.get(1).text()).isEqualTo("Mutual Non-Disclosure Agreement");
        assertThat(nodes.get(1).rawStyleId()).isEqualTo("Title");
        assertThat(nodes.get(1).ordinal()).isZero();
        assertThat(nodes.get(1).sourceSpan().bodyIndex()).isZero();
        assertThat(nodes.get(1).sourceSpan().paragraphIndex()).isZero();

        assertThat(nodes.get(2).nodeType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        assertThat(nodes.get(2).ordinal()).isEqualTo(1);
        assertThat(nodes.get(2).sourceSpan().bodyIndex()).isEqualTo(1);
        assertThat(nodes.get(2).text()).startsWith("This Agreement is entered into");

        assertThat(nodes.get(3).nodeType()).isEqualTo(DocumentNodeType.HEADING);
        assertThat(nodes.get(3).headingLevel()).isEqualTo(1);
        assertThat(nodes.get(3).text()).isEqualTo("1. Confidential Information");
        assertThat(nodes.get(3).rawStyleId()).isEqualTo("Heading1");
        assertThat(nodes.get(3).sourceSpan().bodyIndex()).isEqualTo(2);

        assertThat(nodes.get(4).nodeType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        assertThat(nodes.get(4).sourceSpan().bodyIndex()).isEqualTo(3);

        assertThat(nodes.get(5).nodeType()).isEqualTo(DocumentNodeType.HEADING);
        assertThat(nodes.get(5).headingLevel()).isEqualTo(1);
        assertThat(nodes.get(5).text()).isEqualTo("2. Term");
        assertThat(nodes.get(5).sourceSpan().bodyIndex()).isEqualTo(4);

        assertThat(nodes.get(6).nodeType()).isEqualTo(DocumentNodeType.PARAGRAPH);
        assertThat(nodes.get(6).sourceSpan().bodyIndex()).isEqualTo(5);

        assertThat(nodes).allSatisfy(node -> {
            assertThat(node.ownerAggregateId()).isEqualTo(VERSION_ID);
            assertThat(node.sourceSpan().partPath()).isEqualTo("word/document.xml");
            assertThat(node.sourceSpan().hasNonNegativeIndices()).isTrue();
        });
    }

    /**
     * A counterparty NDA written without Word heading styles: bold typed numbers
     * ("7. Public statements") and a bold all-caps title, the way most real drafts arrive.
     * Every section must become a heading, the title a title, and the bold party names in
     * the signature block must stay body text.
     */
    @Test
    void recoversSectionsFromATypedNumberContractWithoutHeadingStyles() {
        var result = parse("fixtures/blind-mutual-nda-typed-headings.docx");

        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        var titles = result.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TITLE)
                .map(com.rivell.workbench.contracts.DocumentNode::text).toList();
        var headings = result.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .map(com.rivell.workbench.contracts.DocumentNode::text).toList();
        assertThat(titles).containsExactly("MUTUAL NON-DISCLOSURE AGREEMENT");
        assertThat(headings).containsExactly(
                "1. Definitions", "2. Use and protection", "3. Disclosure to Representatives",
                "4. Exclusions", "5. Evaluation boundaries and data", "6. Required disclosure",
                "7. Public statements", "8. Term and duration of obligations",
                "9. Return or destruction", "10. Ownership; no license; feedback",
                "11. Remedies", "12. General", "13. Governing law and forum",
                "14. Counterparts and electronic signatures");
    }

    @Test
    void recoversUnnumberedBoldHeadingsIncludingOnesThatIntroduceATable() {
        var result = parse("fixtures/blind-sow-table-introduced-headings.docx");

        assertHeadingsInOrder(result, "Scope of Services", "Definitions", "Deliverables and Acceptance",
                "Milestones, Fees and Payment", "Governance, Roles and Responsibilities", "Key Personnel",
                "Super-Users and Training", "Assumptions and Dependencies", "Variations",
                "Intellectual Property in Project Deliverables", "Data Protection and Security",
                "Subcontracting", "Liability Under this Statement of Work", "Governing Law and Jurisdiction",
                "Reporting Pack Specification");
    }

    @Test
    void recoversUnderlinedRunInHeadingsIncludingAnUnpunctuatedSchedule() {
        var result = parse("fixtures/blind-nda-run-in-headings.docx");

        assertHeadingsInOrder(result, "1. Purpose.", "2. Definition of Confidential Information.",
                "3. Obligations of the Recipient.", "4. Permitted Disclosures.", "5. Residual Knowledge.",
                "6. Return or Destruction.", "7. Compliance Warranties.",
                "8. Evaluation Outcome and Reference Rights.", "9. Term and Survival.",
                "10. Governing Law and Dispute Resolution.", "11. General.",
                "12. No Licence and No Warranty.", "13. Clean Room Protocol.",
                "Schedule 1 Information Expected to be Disclosed");
    }

    @Test
    void aTextBoxIsHeldOnceAfterItsAnchorAndContentATrackedChangeCannotDeleteIsMarked() {
        var nodes = parse("fixtures/coverage-mixed-parts.docx").nodes();

        var anchor = nodes.stream().filter(n -> n.text() != null
                && n.text().startsWith("Commercial summary follows.")).findFirst().orElseThrow();
        var boxes = nodes.stream()
                .filter(n -> n.nodeType() == com.rivell.workbench.contracts.DocumentNodeType.TEXT_BOX)
                .toList();
        assertThat(boxes).as("DrawingML and its VML fallback are one box").singleElement()
                .satisfies(box -> {
                    assertThat(box.text())
                            .isEqualTo("Key terms: the liability cap is twelve months of fees.");
                    assertThat(box.parentId()).isEqualTo(anchor.parentId());
                    assertThat(box.ordinal()).isEqualTo(anchor.ordinal() + 1);
                    assertThat(box.sourceSpan().bodyIndex()).isEqualTo(anchor.sourceSpan().bodyIndex());
                });
        assertThat(anchor.untrackableContent()).as("the anchor run holds a drawing").isTrue();
        assertThat(nodes).filteredOn(n -> n.text() != null && n.text().startsWith("This Agreement lasts"))
                .singleElement().as("a footnote reference")
                .satisfies(n -> assertThat(n.untrackableContent()).isTrue());
        assertThat(nodes).filteredOn(n -> n.text() != null && n.text().startsWith("Late amounts follow"))
                .singleElement().as("a field").satisfies(n -> assertThat(n.untrackableContent()).isTrue());
        assertThat(nodes).filteredOn(n -> n.text() != null && n.text().startsWith("The Customer pays"))
                .singleElement().satisfies(n -> assertThat(n.untrackableContent()).isFalse());
    }

    @Test
    void aRunInHeadingRecordsWhichPartOfItsParagraphIsTheHeading() {
        var result = parse("fixtures/blind-nda-run-in-headings.docx");

        var byText = result.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .toList();
        assertThat(byText).filteredOn(node -> node.text().startsWith("5. Residual Knowledge."))
                .singleElement()
                .satisfies(node -> assertThat(node.runInHeading()).isEqualTo("5. Residual Knowledge."));
        assertThat(byText).filteredOn(node -> node.text().startsWith("Schedule 1"))
                .singleElement()
                .satisfies(node -> assertThat(node.runInHeading())
                        .isEqualTo("Schedule 1 Information Expected to be Disclosed"));
        assertThat(parse("fixtures/m9-simple-replace.docx").nodes())
                .filteredOn(node -> node.nodeType() == DocumentNodeType.HEADING)
                .isNotEmpty()
                .allSatisfy(node -> assertThat(node.runInHeading()).isNull());
    }

    private static void assertHeadingsInOrder(com.rivell.workbench.contracts.ParseResult result, String... expectedPrefixes) {
        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        var headings = result.nodes().stream()
                .filter(node -> node.nodeType() == DocumentNodeType.HEADING)
                .map(com.rivell.workbench.contracts.DocumentNode::text).toList();
        var cursor = 0;
        for (var expected : expectedPrefixes) {
            while (cursor < headings.size() && !headings.get(cursor).contains(expected)) {
                cursor++;
            }
            assertThat(cursor).as("heading '%s' in %s", expected, headings).isLessThan(headings.size());
            cursor++;
        }
    }

    @Test
    void parsesTableWithNestedRowsAndCells() {
        var result = parse("fixtures/with-table.docx");
        var nodes = result.nodes();

        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        assertThat(nodes.get(1).nodeType()).isEqualTo(DocumentNodeType.TITLE);
        assertThat(nodes.get(3).nodeType()).isEqualTo(DocumentNodeType.HEADING);
        assertThat(nodes.get(4).nodeType()).isEqualTo(DocumentNodeType.TABLE);
        assertThat(nodes.get(4).sourceSpan().bodyIndex()).isEqualTo(3);
        assertThat(nodes.get(4).sourceSpan().tableIndex()).isZero();

        var table = nodes.get(4);
        var children = nodes.stream().filter(n -> table.id().equals(n.parentId())).toList();
        assertThat(children).hasSize(3);
        assertThat(children).allMatch(n -> n.nodeType() == DocumentNodeType.TABLE_ROW);
        assertThat(children.get(0).ordinal()).isZero();
        assertThat(children.get(1).ordinal()).isEqualTo(1);

        var firstRowCells = nodes.stream().filter(n -> children.get(0).id().equals(n.parentId())).toList();
        assertThat(firstRowCells).hasSize(2);
        assertThat(firstRowCells).allMatch(n -> n.nodeType() == DocumentNodeType.TABLE_CELL);
        assertThat(firstRowCells.get(0).text()).isEqualTo("Term");
        assertThat(firstRowCells.get(1).text()).isEqualTo("Duration");
        assertThat(firstRowCells.get(0).sourceSpan().rowIndex()).isZero();
        assertThat(firstRowCells.get(0).sourceSpan().cellIndex()).isZero();
        assertThat(firstRowCells.get(1).sourceSpan().cellIndex()).isEqualTo(1);

        var secondRowCells = nodes.stream().filter(n -> children.get(1).id().equals(n.parentId())).toList();
        assertThat(secondRowCells.get(0).text()).isEqualTo("Support");
        assertThat(secondRowCells.get(1).text()).isEqualTo("12 months");

        assertThat(nodes).anySatisfy(node -> {
            assertThat(node.nodeType()).isEqualTo(DocumentNodeType.HEADING);
            assertThat(node.text()).isEqualTo("2. Governing Law");
        });
    }

    @Test
    void resolvesAutomaticNumberingForDecimalAndBulletLists() {
        var result = parse("fixtures/numbered-list.docx");
        var nodes = result.nodes();

        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        var labels = nodes.stream()
                .filter(node -> node.numberingLabel() != null)
                .map(DocumentNode::numberingLabel)
                .toList();

        // three decimal items, two bullet items
        assertThat(labels).hasSize(5);
        assertThat(labels.get(0)).startsWith("1");
        assertThat(labels.get(1)).startsWith("2");
        assertThat(labels.get(2)).startsWith("3");
        assertThat(labels.get(3)).isNotBlank();
        assertThat(labels.get(4)).isNotBlank();

        // unnumbered paragraphs keep a null label
        assertThat(nodes.get(1).numberingLabel()).isNull();
        assertThat(nodes.get(nodes.size() - 1).numberingLabel()).isNull();
    }

    @Test
    void anchorsSpansToTrueBodyPositionsAndCapturesContainers() {
        var result = parse("fixtures/with-sdt-and-breaks.docx");
        var nodes = result.nodes();

        // BODY + heading + before-para + 2 sdt paragraphs + break/tab paragraph + hyperlink;
        // the foreign element the loader removed surfaces as a limitation warning
        assertThat(nodes).hasSize(7);
        assertThat(result.warnings()).contains("SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED");

        var heading = nodes.get(1);
        assertThat(heading.nodeType()).isEqualTo(DocumentNodeType.HEADING);
        assertThat(heading.sourceSpan().bodyIndex()).isZero();
        assertThat(heading.sourceSpan().paragraphIndex()).isZero();

        // the loader dropped the unsupported element; subsequent paragraphs keep their
        // deterministic positions in the parsed body
        var beforeSdt = nodes.get(2);
        assertThat(beforeSdt.text()).isEqualTo("Before the content control.");
        assertThat(beforeSdt.sourceSpan().bodyIndex()).isEqualTo(1);
        assertThat(beforeSdt.sourceSpan().paragraphIndex()).isEqualTo(1);

        // sdt paragraphs anchor to the sdt's body index and keep a global paragraph sequence
        var firstInSdt = nodes.get(3);
        assertThat(firstInSdt.text()).isEqualTo("Inside content control.");
        assertThat(firstInSdt.sourceSpan().bodyIndex()).isEqualTo(2);
        assertThat(firstInSdt.sourceSpan().paragraphIndex()).isEqualTo(2);

        var secondInSdt = nodes.get(4);
        assertThat(secondInSdt.sourceSpan().bodyIndex()).isEqualTo(2);
        assertThat(secondInSdt.sourceSpan().paragraphIndex()).isEqualTo(3);

        var withBreaks = nodes.get(5);
        assertThat(withBreaks.text()).isEqualTo("line one\nline two\tafter tab");
        assertThat(withBreaks.sourceSpan().bodyIndex()).isEqualTo(3);

        var withHyperlink = nodes.get(6);
        assertThat(withHyperlink.text()).isEqualTo("visit the site");
        assertThat(withHyperlink.sourceSpan().bodyIndex()).isEqualTo(4);
        assertThat(withHyperlink.sourceSpan().paragraphIndex()).isEqualTo(5);
    }

    private String withHyperlinkText(com.rivell.workbench.contracts.DocumentNode node) {
        return node.text();
    }

    @Test
    void warnsAboutPartsThatAreNotRepresented() {
        var result = parse("fixtures/with-omitted-parts.docx");

        assertThat(result.status()).isEqualTo(ParseStatus.OK);
        assertThat(result.warnings()).contains(
                "HEADERS_OR_FOOTERS_NOT_REPRESENTED",
                "FOOTNOTES_NOT_REPRESENTED");

        var nodes = result.nodes();
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(1).text()).isEqualTo("Consulting Terms");
        assertThat(nodes.get(2).text()).isEqualTo("The rates apply for the initial term.");
    }

    @Test
    void bodyChildOrdinalsFollowPhysicalDocumentOrderForInterleavedBlocks() {
        var result = parse("fixtures/interleaved-blocks.docx");
        var nodes = result.nodes();
        var bodyId = nodes.get(0).id();

        // BODY + heading + paragraph + table + paragraph + table(with nested) + paragraph
        assertThat(nodes).hasSize(7 + 2 + 1 + 2 + 1 + 2);

        var bodyChildren = nodes.stream()
                .filter(node -> bodyId.equals(node.parentId()))
                .toList();

        // one stable sibling order that agrees with the physical document order
        var orderedTypes = bodyChildren.stream()
                .sorted(java.util.Comparator.comparingInt(DocumentNode::ordinal))
                .map(node -> node.nodeType() + ":" + (node.text() == null ? "" : node.text()))
                .toList();

        assertThat(orderedTypes).containsExactly(
                "HEADING:Interleaved Structure",
                "PARAGRAPH:First paragraph before any table.",
                "TABLE:",
                "PARAGRAPH:Paragraph between the two tables.",
                "TABLE:",
                "PARAGRAPH:Last paragraph after all tables.");

        // ordinals are contiguous sibling positions, not per-type counters
        assertThat(bodyChildren.stream().map(DocumentNode::ordinal).sorted())
                .containsExactly(0, 1, 2, 3, 4, 5);

        // spans agree with sibling order: bodyIndex strictly increases with ordinal
        var bodyIndexes = bodyChildren.stream()
                .sorted(java.util.Comparator.comparingInt(DocumentNode::ordinal))
                .map(node -> node.sourceSpan().bodyIndex())
                .toList();
        assertThat(bodyIndexes).isSorted();

        // paragraphIndex/tableIndex remain per-kind locators
        var paragraphs = bodyChildren.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.PARAGRAPH
                        || node.nodeType() == DocumentNodeType.HEADING)
                .map(DocumentNode::sourceSpan)
                .map(SourceSpan::paragraphIndex)
                .toList();
        assertThat(paragraphs).containsExactly(0, 1, 2, 3);
        var tables = bodyChildren.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TABLE)
                .map(node -> node.sourceSpan().tableIndex())
                .toList();
        assertThat(tables).containsExactly(0, 1);

        // deferred nested table inside a cell is reported, not silently lost
        assertThat(result.warnings()).contains("UNSUPPORTED_NESTED_TABLE");
    }

    @Test
    void reportsFailedStatusForInvalidBytes() {
        var result = parse("fixtures/invalid-bytes.docx");

        assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.parserVersion()).isEqualTo(PARSER_VERSION);
        assertThat(result.nodes()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
        assertThat(result.warnings().get(0)).startsWith("Rivell could not read this Word file");
    }

    /**
     * A relationship to a missing part makes docx4j refuse the whole package. Word opens
     * such a file after offering to repair it, and Rivell's analysis must not 500 over it.
     */
    @Test
    void repairsADamagedPackageAndSaysSoInsteadOfFailing() {
        var damaged = DocxTestPackages.withEntry(
                DocxTestPackages.fixture("m9-simple-replace.docx"),
                DocxTestPackages.RELS_ENTRY,
                DocxTestPackages.relsDocument(DocxTestPackages.STYLES_RELATIONSHIP,
                        DocxTestPackages.FOOTNOTES_RELATIONSHIP));

        var result = parser.parse(new ByteArrayInputStream(damaged), VERSION_ID);

        assertThat(result.status())
                .as("parse warnings: %s", result.warnings())
                .isEqualTo(ParseStatus.OK);
        assertThat(result.nodes()).isNotEmpty();
        assertThat(result.warnings()).contains("DAMAGED_PACKAGE_REPAIRED");
    }

    private com.rivell.workbench.contracts.ParseResult parse(String fixture) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return parser.parse(stream, VERSION_ID);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
