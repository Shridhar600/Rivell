package com.redstar.workbench.platform.parsing;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Body;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Tr;
import org.springframework.stereotype.Component;

import com.redstar.workbench.contracts.DocumentNode;
import com.redstar.workbench.contracts.DocumentNodeType;
import com.redstar.workbench.contracts.DocumentStructureParser;
import com.redstar.workbench.contracts.ParseResult;
import com.redstar.workbench.contracts.ParseStatus;
import com.redstar.workbench.contracts.SourceSpan;

@Component
public class DocxStructuralParser implements DocumentStructureParser {

    public static final String PARSER_VERSION = "redstar-docx-parser-1";

    private static final String PART_PATH = "word/document.xml";

    @Override
    public ParseResult parse(InputStream source, UUID contractVersionId) {
        try {
            var wordPackage = WordprocessingMLPackage.load(source);
            var body = wordPackage.getMainDocumentPart().getJaxbElement().getBody();
            var nodes = walkBody(body, contractVersionId);
            return new ParseResult(PARSER_VERSION, ParseStatus.OK, List.of(), nodes);
        } catch (Exception e) {
            var warning = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName()
                    : e.getMessage();
            return new ParseResult(PARSER_VERSION, ParseStatus.FAILED, List.of(warning), List.of());
        }
    }

    private List<DocumentNode> walkBody(Body body, UUID contractVersionId) {
        var nodes = new ArrayList<DocumentNode>();
        var bodyId = UUID.randomUUID();
        nodes.add(new DocumentNode(
                bodyId, contractVersionId, null, DocumentNodeType.BODY, 0,
                null, null, SourceSpan.ofBody(PART_PATH, 0), null));

        var ordinal = 0;
        var bodyIndex = 0;
        var tableIndex = 0;
        for (Object rawElement : body.getContent()) {
            var element = unwrap(rawElement);
            if (element instanceof P paragraph) {
                nodes.add(paragraphNode(
                        paragraph, contractVersionId, bodyId, ordinal, bodyIndex, bodyIndex));
                ordinal++;
                bodyIndex++;
            } else if (element instanceof Tbl table) {
                tableNodes(table, contractVersionId, bodyId, ordinal, bodyIndex, tableIndex, nodes);
                ordinal++;
                bodyIndex++;
                tableIndex++;
            }
        }
        return nodes;
    }

    private DocumentNode paragraphNode(
            P paragraph, UUID contractVersionId, UUID parentId, int ordinal, int bodyIndex, int paragraphIndex) {
        var styleId = styleId(paragraph);
        var text = textOf(paragraph);
        var nodeType = DocumentNodeType.PARAGRAPH;
        Integer headingLevel = null;
        if ("Title".equals(styleId)) {
            nodeType = DocumentNodeType.TITLE;
        } else if (styleId != null && styleId.matches("Heading\\d*")) {
            nodeType = DocumentNodeType.HEADING;
            headingLevel = headingLevel(styleId);
        }
        return new DocumentNode(
                UUID.randomUUID(), contractVersionId, parentId, nodeType, ordinal,
                text, headingLevel, SourceSpan.ofParagraph(PART_PATH, bodyIndex, paragraphIndex), styleId);
    }

    private void tableNodes(
            Tbl table, UUID contractVersionId, UUID parentId, int ordinal, int bodyIndex, int tableIndex,
            List<DocumentNode> nodes) {
        var tableId = UUID.randomUUID();
        nodes.add(new DocumentNode(
                tableId, contractVersionId, parentId, DocumentNodeType.TABLE, ordinal,
                null, null, SourceSpan.ofTable(PART_PATH, bodyIndex, tableIndex), null));

        var rowIndex = 0;
        for (Object rawRow : table.getContent()) {
            if (!(unwrap(rawRow) instanceof Tr row)) {
                continue;
            }
            var rowId = UUID.randomUUID();
            nodes.add(new DocumentNode(
                    rowId, contractVersionId, tableId, DocumentNodeType.TABLE_ROW, rowIndex,
                    null, null, SourceSpan.ofRow(PART_PATH, bodyIndex, tableIndex, rowIndex), null));

            var cellIndex = 0;
            for (Object rawCell : row.getContent()) {
                if (!(unwrap(rawCell) instanceof Tc cell)) {
                    continue;
                }
                nodes.add(new DocumentNode(
                        UUID.randomUUID(), contractVersionId, rowId, DocumentNodeType.TABLE_CELL, cellIndex,
                        cellText(cell), null,
                        SourceSpan.ofCell(PART_PATH, bodyIndex, tableIndex, rowIndex, cellIndex), null));
                cellIndex++;
            }
            rowIndex++;
        }
    }

    private String styleId(P paragraph) {
        if (paragraph.getPPr() == null || paragraph.getPPr().getPStyle() == null) {
            return null;
        }
        return paragraph.getPPr().getPStyle().getVal();
    }

    private int headingLevel(String styleId) {
        var digits = styleId.replaceAll("\\D", "");
        return digits.isEmpty() ? 1 : Integer.parseInt(digits);
    }

    private String textOf(P paragraph) {
        var text = new StringBuilder();
        for (Object rawElement : paragraph.getContent()) {
            if (unwrap(rawElement) instanceof R run) {
                for (Object rawRunChild : run.getContent()) {
                    if (unwrap(rawRunChild) instanceof org.docx4j.wml.Text textElement) {
                        text.append(textElement.getValue());
                    }
                }
            }
        }
        return text.toString();
    }

    private String cellText(Tc cell) {
        var text = new StringBuilder();
        for (Object rawElement : cell.getContent()) {
            if (unwrap(rawElement) instanceof P paragraph) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(textOf(paragraph));
            }
        }
        return text.toString();
    }

    private Object unwrap(Object element) {
        return element instanceof jakarta.xml.bind.JAXBElement<?> jaxbElement
                ? jaxbElement.getValue()
                : element;
    }
}
