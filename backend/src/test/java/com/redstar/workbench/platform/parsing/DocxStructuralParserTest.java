package com.redstar.workbench.platform.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.redstar.workbench.contracts.DocumentNodeType;
import com.redstar.workbench.contracts.ParseStatus;

class DocxStructuralParserTest {

    private static final String PARSER_VERSION = "redstar-docx-parser-1";
    private static final UUID VERSION_ID = UUID.randomUUID();
    private final DocxStructuralParser parser = new DocxStructuralParser();

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
            assertThat(node.contractVersionId()).isEqualTo(VERSION_ID);
            assertThat(node.sourceSpan().partPath()).isEqualTo("word/document.xml");
            assertThat(node.sourceSpan().hasNonNegativeIndices()).isTrue();
        });
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
    void reportsFailedStatusForInvalidBytes() {
        var result = parse("fixtures/invalid-bytes.docx");

        assertThat(result.status()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.parserVersion()).isEqualTo(PARSER_VERSION);
        assertThat(result.nodes()).isEmpty();
        assertThat(result.warnings()).isNotEmpty();
    }

    private com.redstar.workbench.contracts.ParseResult parse(String fixture) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return parser.parse(stream, VERSION_ID);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
