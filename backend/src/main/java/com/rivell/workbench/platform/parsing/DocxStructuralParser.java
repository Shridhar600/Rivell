package com.rivell.workbench.platform.parsing;


import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.FooterPart;
import org.docx4j.openpackaging.parts.WordprocessingML.FootnotesPart;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.Body;
import org.docx4j.wml.P;
import org.docx4j.wml.SdtBlock;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
import org.docx4j.wml.Tr;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.rivell.workbench.contracts.DocumentNode;
import com.rivell.workbench.contracts.DocumentNodeType;
import com.rivell.workbench.contracts.DocumentStructureParser;
import com.rivell.workbench.contracts.ParseCoverage;
import com.rivell.workbench.contracts.ParseResult;
import com.rivell.workbench.contracts.ParseStatus;
import com.rivell.workbench.contracts.SourceSpan;
import com.rivell.workbench.foundation.DocumentWorkLimiter;
import com.rivell.workbench.platform.docx.DocxPackageRepair;


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

    public static final String PARSER_VERSION = "rivell-docx-parser-3";

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DocxStructuralParser.class);

    private static final String PART_PATH = "word/document.xml";

    private final DocxParserProperties properties;
    private final HeadingDetector headingDetector;
    private final DocumentWorkLimiter limiter;

    public DocxStructuralParser(DocxParserProperties properties) {
        this(properties, new SignalHeadingDetector());
    }

    public DocxStructuralParser(DocxParserProperties properties, HeadingDetector headingDetector) {
        this(properties, headingDetector,
                new DocumentWorkLimiter(DocumentWorkLimiter.DEFAULT_MAX_CONCURRENT));
    }

    @Autowired
    public DocxStructuralParser(DocxParserProperties properties, HeadingDetector headingDetector,
            DocumentWorkLimiter limiter) {
        this.properties = properties;
        this.headingDetector = headingDetector;
        this.limiter = limiter;
    }

    @Override
    public ParseResult parse(InputStream source, UUID ownerAggregateId) {
        return limiter.run(() -> parseWithinLimit(source, ownerAggregateId));
    }

    private ParseResult parseWithinLimit(InputStream source, UUID ownerAggregateId) {
        try {
            var docxBytes = source.readAllBytes();
            var scan = ZipGuard.scan(docxBytes, properties.maxUncompressedBytes());
            var rawTopLevelChildren =
                    ZipGuard.countTopLevelBodyChildren(scan.mainDocumentXml());
            var repair = DocxPackageRepair.repair(docxBytes);
            var wordPackage = WordprocessingMLPackage.load(
                    new ByteArrayInputStream(repair.bytes()));
            var body = wordPackage.getMainDocumentPart().getJaxbElement().getBody();
            var walk = new BodyWalk(ownerAggregateId, wordPackage,
                    DocxTextInventory.textBoxes(scan.mainDocumentXml()));
            walk.body(body);
            var warnings = new ArrayList<>(walk.warnings());
            if (repair.repaired()) {
                warnings.add("DAMAGED_PACKAGE_REPAIRED");
            }
            if (rawTopLevelChildren != body.getContent().size()) {
                warnings.add("SOME_BODY_CONTENT_COULD_NOT_BE_REPRESENTED");
            }
            if (DocxTextInventory.hasTrackedChanges(scan.mainDocumentXml())) {
                // Text is read in the accept-all view; the user must know the file was a markup.
                warnings.add("TRACKED_CHANGES_PRESENT");
            }
            addDeferredPartWarnings(wordPackage, warnings);
            var coverage = coverage(docxBytes, walk.nodes(), warnings);
            return new ParseResult(PARSER_VERSION, ParseStatus.OK, warnings, walk.nodes(), coverage);
        } catch (Exception e) {
            log.warn("Word file could not be parsed ({}: {})", e.getClass().getSimpleName(), e.getMessage());
            return new ParseResult(
                    PARSER_VERSION, ParseStatus.FAILED,
                    List.of("Rivell could not read this Word file. Check that it opens in Word, then upload it again."),
                    List.of());
        }
    }

    /** The coverage check never fails a parse: a check that cannot run is a warning. */
    private static ParseCoverage coverage(
            byte[] docxBytes, List<DocumentNode> nodes, List<String> warnings) {
        try {
            return TextCoverage.measure(DocxTextInventory.read(docxBytes), nodes);
        } catch (Exception e) {
            warnings.add("COVERAGE_CHECK_FAILED");
            return null;
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
        return WordText.paragraphText(paragraph);
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

        private final UUID ownerAggregateId;
        private final WordprocessingMLPackage wordPackage;
        private final List<DocumentNode> nodes = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private final Deque<HeadingDecision> decisions = new ArrayDeque<>();
        private final Deque<Optional<String>> numberingLabels = new ArrayDeque<>();
        private final Deque<Optional<String>> runInTitles = new ArrayDeque<>();
        private int paragraphSequence;
        private int tableSequence;
        private final Map<Integer, List<String>> textBoxes;

        private BodyWalk(UUID ownerAggregateId, WordprocessingMLPackage wordPackage,
                Map<Integer, List<String>> textBoxes) {
            this.textBoxes = textBoxes;
            this.ownerAggregateId = ownerAggregateId;
            this.wordPackage = wordPackage;
        }

        private List<DocumentNode> nodes() {
            return nodes;
        }

        private Set<String> warnings() {
            return warnings;
        }

        private void body(Body body) {
            var layout = new ParagraphLayoutReader(wordPackage, this::warning);
            var features = new ArrayList<ParagraphFeatures>();
            collect(body.getContent(), layout, features);
            decisions.addAll(headingDetector.classify(features));
            for (var paragraph : features) {
                numberingLabels.add(paragraph.numberingLabel() == null
                        ? Optional.empty()
                        : Optional.of(paragraph.numberingLabel()));
                runInTitles.add(Optional.ofNullable(paragraph.runInTitle()));
            }
            var bodyNodeId = UUID.randomUUID();
            nodes.add(new DocumentNode(
                    bodyNodeId, ownerAggregateId, null, DocumentNodeType.BODY, 0,
                    null, null, null, SourceSpan.ofBody(PART_PATH), null));
            content(body.getContent(), null, bodyNodeId, 0);
        }

        /** Pre-pass in exactly the order {@link #content} visits block-level paragraphs. */
        private void collect(
                List<Object> elements, ParagraphLayoutReader layout, List<ParagraphFeatures> out) {
            for (var raw : elements) {
                var element = unwrap(raw);
                if (element instanceof P paragraph) {
                    out.add(layout.read(paragraph, textOf(paragraph)));
                } else if (element instanceof SdtBlock sdtBlock) {
                    collect(sdtBlock.getSdtContent().getContent(), layout, out);
                } else if (element instanceof Tbl) {
                    markLastTextBeforeTable(out);
                }
            }
        }

        private static void markLastTextBeforeTable(List<ParagraphFeatures> out) {
            for (int i = out.size() - 1; i >= 0; i--) {
                if (!out.get(i).isBlank()) {
                    out.set(i, out.get(i).withFollowedByTable());
                    return;
                }
            }
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
                if (anchorBodyIndex == null) {
                    for (var box : textBoxes.getOrDefault(i, List.of())) {
                        nodes.add(new DocumentNode(UUID.randomUUID(), ownerAggregateId, parentId,
                                DocumentNodeType.TEXT_BOX, siblingOrdinal++, box, null, null,
                                new SourceSpan(PART_PATH, i, null, null, null, null, null, null),
                                null));
                    }
                }
            }
            return siblingOrdinal;
        }

        private DocumentNode paragraphNode(
                P paragraph, int bodyIndex, UUID parentId, int ordinal, int paragraphSequence) {
            var styleId = styleId(paragraph);
            var decision = decisions.removeFirst();
            var label = numberingLabels.removeFirst().orElse(null);
            var runInTitle = runInTitles.removeFirst().orElse(null);
            var nodeType = switch (decision.kind()) {
                case TITLE -> DocumentNodeType.TITLE;
                case HEADING -> DocumentNodeType.HEADING;
                case BODY -> DocumentNodeType.PARAGRAPH;
            };
            var text = textOf(paragraph);
            return new DocumentNode(
                    UUID.randomUUID(), ownerAggregateId, parentId, nodeType, ordinal,
                    text, decision.level(), label,
                    SourceSpan.ofParagraph(PART_PATH, bodyIndex, paragraphSequence), styleId,
                    nodeType == DocumentNodeType.HEADING ? runInHeading(text, runInTitle) : null,
                    !com.rivell.workbench.platform.docx.TrackableContent.paragraph(paragraph));
        }

        /**
         * The heading part of a heading paragraph that goes on with clause text: everything up to
         * the end of the run-in title and its closing punctuation. Null when the paragraph is the
         * heading alone.
         */
        static String runInHeading(String text, String runInTitle) {
            if (text == null || runInTitle == null || runInTitle.isBlank()) {
                return null;
            }
            var start = text.indexOf(runInTitle);
            if (start < 0) {
                return null;
            }
            var end = start + runInTitle.length();
            while (end < text.length() && ".:–—-".indexOf(text.charAt(end)) >= 0) {
                end++;
            }
            return text.substring(end).isBlank() ? null : text.substring(0, end);
        }

        private void tableNodes(
                Tbl table, int bodyIndex, UUID parentId, int ordinal, int tableSequence) {
            var tableId = UUID.randomUUID();
            nodes.add(new DocumentNode(
                    tableId, ownerAggregateId, parentId, DocumentNodeType.TABLE, ordinal,
                    null, null, null, SourceSpan.ofTable(PART_PATH, bodyIndex, tableSequence), null));

            var rowIndex = 0;
            for (Object rawRow : table.getContent()) {
                if (!(unwrap(rawRow) instanceof Tr row)) {
                    continue;
                }
                var rowId = UUID.randomUUID();
                nodes.add(new DocumentNode(
                        rowId, ownerAggregateId, tableId, DocumentNodeType.TABLE_ROW, rowIndex,
                        null, null, null,
                        SourceSpan.ofRow(PART_PATH, bodyIndex, tableSequence, rowIndex), null));

                var cellIndex = 0;
                for (Object rawCell : row.getContent()) {
                    if (!(unwrap(rawCell) instanceof Tc cell)) {
                        continue;
                    }
                    nodes.add(new DocumentNode(
                            UUID.randomUUID(), ownerAggregateId, rowId,
                            DocumentNodeType.TABLE_CELL, cellIndex,
                            cellText(cell), null, null,
                            SourceSpan.ofCell(PART_PATH, bodyIndex, tableSequence, rowIndex, cellIndex),
                            null));
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

        private String textOf(P paragraph) {
            return WordText.paragraphText(paragraph);
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

        void warning(String message) {
            warnings.add(message);
        }
    }
}
