package com.redstar.workbench.platform.parsing;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.docx4j.model.listnumbering.Emulator;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.FootnotesPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.Body;
import org.docx4j.wml.Br;
import org.docx4j.wml.P;
import org.docx4j.wml.P.Hyperlink;
import org.docx4j.wml.R;
import org.docx4j.wml.SdtBlock;
import org.docx4j.wml.SdtRun;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.springframework.stereotype.Component;

import com.redstar.workbench.contracts.DocumentNode;
import com.redstar.workbench.contracts.DocumentNodeType;
import com.redstar.workbench.contracts.DocumentStructureParser;
import com.redstar.workbench.contracts.ParseResult;
import com.redstar.workbench.contracts.ParseStatus;
import com.redstar.workbench.contracts.SourceSpan;

/**
 * Deterministic DOCX structural parser.
 *
 * Spans: {@code bodyIndex} is the true index of the element in the main document body's
 * content list; elements skipped with a warning cannot shift later spans. Elements inside
 * a body-level structured document tag anchor to that tag's bodyIndex. {@code paragraphIndex} /
 * {@code tableIndex} are document-order counts of their kind in the main part.
 *
 * Ordering: {@code ordinal} is the per-parent sibling position in physical document order
 * (one stable sibling sequence across all node types); rows and cells use row/cell position.
 */
@Component
public class DocxStructuralParser implements DocumentStructureParser {

    public static final String PARSER_VERSION = "redstar-docx-parser-2";

    private static final String PART_PATH = "word/document.xml";

    private final DocxParserProperties properties;

    public DocxStructuralParser(DocxParserProperties properties) {
        this.properties = properties;
    }

    @Override
    public ParseResult parse(InputStream source, UUID contractVersionId) {
        try {
            var docxBytes = source.readAllBytes();
            var scan = ZipGuard.scan(docxBytes, properties.maxUncompressedBytes());
            var rawTopLevelChildren =
                    ZipGuard.countTopLevelBodyChildren(scan.mainDocumentXml());
            var wordPackage = WordprocessingMLPackage.load(
                    new java.io.ByteArrayInputStream(docxBytes));
            var body = wordPackage.getMainDocumentPart().getJaxbElement().getBody();
            var walk = new BodyWalk(contractVersionId, wordPackage);
            walk.body(body);
            var warnings = new ArrayList<>(walk.warnings());
            if (rawTopLevelChildren != body.getContent().size()) {
                warnings.add("SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED");
            }
            addDeferredPartWarnings(wordPackage, warnings);
            return new ParseResult(PARSER_VERSION, ParseStatus.OK, warnings, walk.nodes());
        } catch (Exception e) {
            return new ParseResult(
                    PARSER_VERSION, ParseStatus.FAILED,
                    List.of("The document could not be parsed (" + e.getClass().getSimpleName() + ")"),
                    List.of());
        }
    }

    private static void addDeferredPartWarnings(
            WordprocessingMLPackage wordPackage, List<String> warnings) {
        boolean headerOrFooter = false;
        boolean footnotes = false;
        for (Part part : wordPackage.getParts().getParts().values()) {
            if (part instanceof HeaderPart header && contentHasText(header.getContent())) {
                headerOrFooter = true;
            } else if (part instanceof FooterPart footer && contentHasText(footer.getContent())) {
                headerOrFooter = true;
            } else if (part instanceof FootnotesPart footnotesPart) {
                footnotes |= hasRealFootnotes(footnotesPart);
            }
        }
        if (headerOrFooter) {
            warnings.add("HEADERS_OR_FOOTERS_NOT_REPRESENTED");
        }
        if (footnotes) {
            warnings.add("FOOTNOTES_NOT_REPRESENTED");
        }
    }

    private static boolean contentHasText(List<Object> elements) {
        return elements.stream().anyMatch(element -> {
            if (element instanceof P paragraph) {
                return paragraphText(paragraph).matches(".*\\S.*");
            }
            return false;
        });
    }

    private static String paragraphText(P paragraph) {
        var text = new StringBuilder();
        for (Object rawElement : paragraph.getContent()) {
            var element = unwrap(rawElement);
            if (element instanceof R run) {
                for (Object rawRunChild : run.getContent()) {
                    var runChild = unwrap(rawRunChild);
                    if (runChild instanceof Text textElement) {
                        text.append(textElement.getValue());
                    }
                }
            }
        }
        return text.toString();
    }

    private static boolean hasRealFootnotes(FootnotesPart footnotesPart) {
        var footnotes = footnotesPart.getJaxbElement().getFootnote();
        return footnotes.stream().anyMatch(footnote -> {
            var id = footnote.getId();
            return id != null && id.signum() > 0 && contentHasText(footnote.getContent());
        });
    }

    private static Object unwrap(Object element) {
        return element instanceof jakarta.xml.bind.JAXBElement<?> jaxbElement
                ? jaxbElement.getValue()
                : element;
    }

    private final class BodyWalk {

        private final UUID contractVersionId;
        private final WordprocessingMLPackage wordPackage;
        private final List<DocumentNode> nodes = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private UUID bodyNodeId;
        private int paragraphSequence;
        private int tableSequence;
        private BodyWalk(UUID contractVersionId, WordprocessingMLPackage wordPackage) {
            this.contractVersionId = contractVersionId;
            this.wordPackage = wordPackage;
        }

        private List<DocumentNode> nodes() {
            return nodes;
        }

        private Set<String> warnings() {
            return warnings;
        }

        private void body(Body body) {
            var bodyNodeId = UUID.randomUUID();
            nodes.add(new DocumentNode(
                    bodyNodeId, contractVersionId, null, DocumentNodeType.BODY, 0,
                    null, null, null, SourceSpan.ofBody(PART_PATH), null));
            content(body.getContent(), null, bodyNodeId, 0);
        }

        /**
         * Walks one block-level container. {@code anchorBodyIndex} is the body index of the
         * containing top-level element when nested inside a structured document tag.
         * {@code firstOrdinal} is the next sibling position under {@code parentId}; returns
         * the next unused sibling position so structured document tag content continues the
         * parent's order instead of restarting it.
         */
        private int content(
                List<Object> elements, Integer anchorBodyIndex, UUID parentId, int firstOrdinal) {
            var siblingOrdinal = firstOrdinal;
            for (int i = 0; i < elements.size(); i++) {
                var element = unwrap(elements.get(i));
                var bodyIndex = anchorBodyIndex != null ? anchorBodyIndex : i;
                if (element instanceof P paragraph) {
                    nodes.add(paragraphNode(
                            paragraph, bodyIndex, parentId, siblingOrdinal, paragraphSequence));
                    siblingOrdinal++;
                    paragraphSequence++;
                } else if (element instanceof Tbl table) {
                    tableNodes(table, bodyIndex, parentId, siblingOrdinal, tableSequence);
                    siblingOrdinal++;
                    tableSequence++;
                } else if (element instanceof SdtBlock sdtBlock) {
                    siblingOrdinal = content(
                            sdtBlock.getSdtContent().getContent(), bodyIndex, parentId, siblingOrdinal);
                } else if (element instanceof SectPr) {
                    // Section properties describe layout, not content.
                } else {
                    warning("UNSUPPORTED_BODY_ELEMENT: "
                            + (element == null ? "null" : element.getClass().getSimpleName()));
                }
            }
            return siblingOrdinal;
        }

        private DocumentNode paragraphNode(
                P paragraph, int bodyIndex, UUID parentId, int ordinal, int paragraphSequence) {
            var styleId = styleId(paragraph);
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
                    textOf(paragraph), headingLevel, numberingLabel(paragraph, styleId),
                    SourceSpan.ofParagraph(PART_PATH, bodyIndex, paragraphSequence), styleId);
        }

        private void tableNodes(
                Tbl table, int bodyIndex, UUID parentId, int ordinal, int tableSequence) {
            var tableId = UUID.randomUUID();
            nodes.add(new DocumentNode(
                    tableId, contractVersionId, parentId, DocumentNodeType.TABLE, ordinal,
                    null, null, null, SourceSpan.ofTable(PART_PATH, bodyIndex, tableSequence), null));

            var rowIndex = 0;
            for (Object rawRow : table.getContent()) {
                if (!(unwrap(rawRow) instanceof Tr row)) {
                    continue;
                }
                var rowId = UUID.randomUUID();
                nodes.add(new DocumentNode(
                        rowId, contractVersionId, tableId, DocumentNodeType.TABLE_ROW, rowIndex,
                        null, null, null,
                        SourceSpan.ofRow(PART_PATH, bodyIndex, tableSequence, rowIndex), null));

                var cellIndex = 0;
                for (Object rawCell : row.getContent()) {
                    if (!(unwrap(rawCell) instanceof Tc cell)) {
                        continue;
                    }
                    nodes.add(new DocumentNode(
                            UUID.randomUUID(), contractVersionId, rowId,
                            DocumentNodeType.TABLE_CELL, cellIndex,
                            cellText(cell), null, null,
                            SourceSpan.ofCell(PART_PATH, bodyIndex, tableSequence, rowIndex, cellIndex),
                            null));
                    cellIndex++;
                }
                rowIndex++;
            }
        }

        private String numberingLabel(P paragraph, String styleId) {
            var pPr = paragraph.getPPr();
            var numPr = pPr == null ? null : pPr.getNumPr();
            var numId = numPr != null && numPr.getNumId() != null
                    ? numPr.getNumId().getVal().toString()
                    : null;
            var ilvl = numPr != null && numPr.getIlvl() != null
                    ? numPr.getIlvl().getVal().toString()
                    : null;
            if (numId == null && ilvl == null && styleId == null) {
                return null;
            }
            try {
                var triple = Emulator.getNumber(wordPackage, styleId, numId, ilvl);
                if (triple == null) {
                    return null;
                }
                var label = triple.getBullet() != null && !triple.getBullet().isBlank()
                        ? triple.getBullet()
                        : triple.getNumString();
                return label == null || label.isBlank() ? null : label.trim();
            } catch (Exception e) {
                warning("NUMBERING_UNRESOLVED");
                return null;
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
                appendText(unwrap(rawElement), text);
            }
            return text.toString();
        }

        private void appendText(Object element, StringBuilder text) {
            if (element instanceof R run) {
                for (Object rawRunChild : run.getContent()) {
                    appendText(unwrap(rawRunChild), text);
                }
            } else if (element instanceof Hyperlink hyperlink) {
                for (Object rawLinkChild : hyperlink.getContent()) {
                    appendText(unwrap(rawLinkChild), text);
                }
            } else if (element instanceof SdtRun sdtRun) {
                for (Object rawSdtChild : sdtRun.getSdtContent().getContent()) {
                    appendText(unwrap(rawSdtChild), text);
                }
            } else if (element instanceof Text textElement) {
                text.append(textElement.getValue());
            } else if (element instanceof Br || element instanceof R.Cr) {
                text.append('\n');
            } else if (element instanceof R.Tab) {
                text.append('\t');
            }
        }

        private String cellText(Tc cell) {
            var text = new StringBuilder();
            for (Object rawElement : cell.getContent()) {
                var element = unwrap(rawElement);
                if (element instanceof P paragraph) {
                    if (!text.isEmpty()) {
                        text.append('\n');
                    }
                    text.append(textOf(paragraph));
                } else if (element instanceof Tbl) {
                    warning("UNSUPPORTED_NESTED_TABLE");
                }
            }
            return text.toString();
        }

        private void warning(String message) {
            warnings.add(message);
        }
    }
}
