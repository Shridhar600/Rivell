package com.rivell.workbench.platform.docx;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import org.docx4j.TraversalUtil;
import org.docx4j.openpackaging.contenttype.ContentTypes;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.wml.Body;
import org.docx4j.wml.CTMarkup;
import org.docx4j.wml.CTTrackChange;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.SdtBlock;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Tr;

import jakarta.xml.bind.JAXBElement;

/** Shared docx4j helpers for the M9 renderer and verifier. */
final class DocxRedlineSupport {

    private DocxRedlineSupport() {
    }

    static Object unwrap(Object element) {
        return element instanceof JAXBElement<?> jaxbElement ? jaxbElement.getValue() : element;
    }

    /** A top-level (or SDT-nested) paragraph with the M1 parser's anchor semantics. */
    record ParagraphRef(int bodyIndex, int paragraphIndex, P paragraph) {
    }

    static List<ParagraphRef> paragraphs(List<Object> bodyContent) {
        var refs = new ArrayList<ParagraphRef>();
        collectParagraphs(bodyContent, null, refs, new int[] {0});
        return refs;
    }

    private static void collectParagraphs(
            List<Object> content, Integer anchorBodyIndex, List<ParagraphRef> refs,
            int[] ordinal) {
        for (var i = 0; i < content.size(); i++) {
            var element = unwrap(content.get(i));
            var bodyIndex = anchorBodyIndex != null ? anchorBodyIndex : i;
            if (element instanceof P paragraph) {
                refs.add(new ParagraphRef(bodyIndex, ordinal[0]++, paragraph));
            } else if (element instanceof SdtBlock sdtBlock) {
                collectParagraphs(sdtBlock.getSdtContent().getContent(), bodyIndex, refs,
                        ordinal);
            }
        }
    }

    /** Plain text with exactly the structural parser's semantics (one shared definition). */
    static String paragraphText(P paragraph) {
        return com.rivell.workbench.platform.parsing.WordText.paragraphText(paragraph);
    }

    /**
     * Every revision mark in the tree, including property marks ({@code pPrChange},
     * {@code rPrChange}, paragraph-mark {@code ins}/{@code del}) that generic traversal
     * helpers cannot descend into.
     */
    static List<CTMarkup> markupElements(Object root) {
        var markup = new ArrayList<CTMarkup>();
        collectMarkup(root, markup);
        return markup;
    }

    private static void collectMarkup(Object node, List<CTMarkup> markup) {
        if (node == null) {
            return;
        }
        if (node instanceof CTMarkup element) {
            markup.add(element);
        }
        collectPropertyMarkup(node, markup);
        for (var child : children(node)) {
            collectMarkup(child, markup);
        }
    }

    private static void collectPropertyMarkup(Object node, List<CTMarkup> markup) {
        if (node instanceof P paragraph) {
            var pPr = paragraph.getPPr();
            if (pPr != null) {
                if (pPr.getPPrChange() != null) {
                    markup.add(pPr.getPPrChange());
                }
                if (pPr.getRPr() != null) {
                    addTrackChanges(pPr.getRPr().getIns(), pPr.getRPr().getDel(),
                            pPr.getRPr().getMoveFrom(), pPr.getRPr().getMoveTo(), markup);
                }
                if (pPr.getSectPr() != null && pPr.getSectPr().getSectPrChange() != null) {
                    markup.add(pPr.getSectPr().getSectPrChange());
                }
            }
        } else if (node instanceof R run) {
            if (run.getRPr() != null && run.getRPr().getRPrChange() != null) {
                markup.add(run.getRPr().getRPrChange());
            }
        } else if (node instanceof Tbl table) {
            if (table.getTblPr() != null && table.getTblPr().getTblPrChange() != null) {
                markup.add(table.getTblPr().getTblPrChange());
            }
        } else if (node instanceof Tr row) {
            if (row.getTrPr() != null && row.getTrPr().getTrPrChange() != null) {
                markup.add(row.getTrPr().getTrPrChange());
            }
        } else if (node instanceof Tc cell) {
            if (cell.getTcPr() != null && cell.getTcPr().getTcPrChange() != null) {
                markup.add(cell.getTcPr().getTcPrChange());
            }
        } else if (node instanceof SectPr section) {
            if (section.getSectPrChange() != null) {
                markup.add(section.getSectPrChange());
            }
        }
    }

    private static void addTrackChanges(CTTrackChange first, CTTrackChange second,
            CTTrackChange third, CTTrackChange fourth, List<CTMarkup> markup) {
        for (var change : new CTTrackChange[] {first, second, third, fourth}) {
            if (change != null) {
                markup.add(change);
            }
        }
    }

    static List<Object> children(Object node) {
        var children = TraversalUtil.getChildrenImpl(node);
        return children == null ? List.of() : children;
    }

    /** Only true revision marks (not bookmarks/ranges, which share the markup base). */
    static List<CTTrackChange> revisionElements(Object root) {
        var revisions = new ArrayList<CTTrackChange>();
        for (var markup : markupElements(root)) {
            if (markup instanceof CTTrackChange change) {
                revisions.add(change);
            }
        }
        return revisions;
    }

    private static final Set<String> REVISION_ELEMENT_NAMES = Set.of(
            "ins", "del", "moveFrom", "moveTo", "pPrChange", "rPrChange",
            "tblPrChange", "tblGridChange", "tblPrExChange", "trPrChange", "tcPrChange",
            "sectPrChange", "cellIns", "cellDel", "cellMerge", "numberingChange");

    /**
     * Highest revision id across every XML part of the package, read from the raw bytes.
     * Reading the raw entries (instead of the loaded parts) keeps untouched stories
     * byte-identical on save: touching another part's JAXB tree would make docx4j
     * re-serialize it. Fails closed when the package cannot be scanned, because an id
     * collision would corrupt revision grouping.
     *
     * <p>Reads every XML part in full. This is only safe because the caller has already run
     * {@code ZipGuard} over the same bytes, which drains each entry and enforces the total
     * uncompressed ceiling: the sum of everything read here is bounded by that ceiling. Callers must
     * not invoke this on unguarded bytes.
     */
    static long maxRevisionIdInBytes(byte[] sourceBytes) {
        long max = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(sourceBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var name = entry.getName();
                if (!name.endsWith(".xml") || name.equals("[Content_Types].xml")
                        || name.endsWith(".rels")) {
                    continue;
                }
                max = Math.max(max, maxRevisionIdInXml(zip.readAllBytes()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("The package revision ids could not be scanned", e);
        }
        return max;
    }

    private static int countRevisionElementsInXml(byte[] xml) throws Exception {
        var reader = xmlReader(xml);
        var count = 0;
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && REVISION_ELEMENT_NAMES.contains(reader.getLocalName())) {
                    count++;
                }
            }
        } finally {
            reader.close();
        }
        return count;
    }

    private static long maxRevisionIdInXml(byte[] xml) throws Exception {
        var reader = xmlReader(xml);
        long max = 0;
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if (!REVISION_ELEMENT_NAMES.contains(reader.getLocalName())) {
                    continue;
                }
                for (var i = 0; i < reader.getAttributeCount(); i++) {
                    if ("id".equals(reader.getAttributeLocalName(i))) {
                        try {
                            max = Math.max(max, Long.parseLong(reader.getAttributeValue(i)));
                        } catch (NumberFormatException ignored) {
                            // Non-numeric ids cannot collide with allocated revision ids.
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        return max;
    }

    private static XMLStreamReader xmlReader(byte[] xml) throws Exception {
        var factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    /**
     * Any revision element in the main document part fails execution. Detection scans
     * the raw {@code word/document.xml} bytes instead of the JAXB tree, because property
     * revisions ({@code numberingChange}, {@code cellMerge}, {@code tblGridChange}, ...)
     * live in holders that generic traversal cannot reach.
     */
    static boolean hasPreexistingRevisions(byte[] sourceBytes) {
        return revisionElementCount(sourceBytes, "word/document.xml") > 0;
    }

    /** Raw-XML revision element count of one package part; -1 when the part is absent. */
    static int revisionElementCount(byte[] packageBytes, String partName) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (partName.equals(entry.getName())) {
                    return countRevisionElementsInXml(zip.readAllBytes());
                }
            }
            return -1;
        } catch (Exception e) {
            throw new IllegalStateException("The package revision elements could not be scanned",
                    e);
        }
    }

    /** The bytes of one package entry; fails when the entry is absent. */
    static byte[] entryBytes(byte[] packageBytes, String entryName) throws java.io.IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new java.io.IOException("Package entry " + entryName + " is missing");
    }

    /**
     * The source package with one entry's bytes replaced. Every other entry is copied byte for
     * byte, in the source order, so parts the caller never edited cannot change.
     */
    static byte[] withEntryReplaced(byte[] sourceBytes, String entryName, byte[] replacement)
            throws java.io.IOException {
        var output = new java.io.ByteArrayOutputStream();
        var replaced = false;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(sourceBytes));
                var out = new java.util.zip.ZipOutputStream(output)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                if (entryName.equals(entry.getName())) {
                    out.write(replacement);
                    replaced = true;
                } else {
                    zip.transferTo(out);
                }
                out.closeEntry();
            }
        }
        if (!replaced) {
            throw new java.io.IOException("Package entry " + entryName + " is missing");
        }
        return output.toByteArray();
    }

    static boolean isDocxMainPart(WordprocessingMLPackage wordPackage) {
        try {
            var contentType = wordPackage.getContentTypeManager()
                    .getContentType(new PartName("/word/document.xml"));
            return ContentTypes.WORDPROCESSINGML_DOCUMENT.equals(contentType);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isProtected(WordprocessingMLPackage wordPackage) {
        var settingsPart = wordPackage.getMainDocumentPart().getDocumentSettingsPart();
        if (settingsPart == null || settingsPart.getJaxbElement() == null) {
            return false;
        }
        var protection = settingsPart.getJaxbElement().getDocumentProtection();
        return protection != null && protection.isEnforcement();
    }

    static XMLGregorianCalendar xmlDate(Instant instant) {
        try {
            return DatatypeFactory.newInstance().newXMLGregorianCalendar(instant.toString());
        } catch (Exception e) {
            throw new IllegalStateException("Could not format the execution timestamp", e);
        }
    }

    static Body body(WordprocessingMLPackage wordPackage) {
        return wordPackage.getMainDocumentPart().getJaxbElement().getBody();
    }
}
