package com.rivell.workbench.platform.docx;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

import org.docx4j.TraversalUtil;
import org.docx4j.openpackaging.contenttype.ContentTypes;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.wml.Br;
import org.docx4j.wml.Body;
import org.docx4j.wml.CTMarkup;
import org.docx4j.wml.CTTrackChange;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.SdtBlock;
import org.docx4j.wml.SdtRun;
import org.docx4j.wml.SectPr;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Text;
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

    /** Plain text with the M1 parser's semantics (runs, hyperlinks, inline SDTs, breaks). */
    static String paragraphText(P paragraph) {
        var text = new StringBuilder();
        for (var rawElement : paragraph.getContent()) {
            appendText(unwrap(rawElement), text);
        }
        return text.toString();
    }

    private static void appendText(Object element, StringBuilder text) {
        if (element instanceof R run) {
            for (var rawRunChild : run.getContent()) {
                appendText(unwrap(rawRunChild), text);
            }
        } else if (element instanceof P.Hyperlink hyperlink) {
            for (var rawLinkChild : hyperlink.getContent()) {
                appendText(unwrap(rawLinkChild), text);
            }
        } else if (element instanceof SdtRun sdtRun) {
            for (var rawSdtChild : sdtRun.getSdtContent().getContent()) {
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

    /**
     * Highest revision id in the main document story. Other stories (headers, footers,
     * footnotes) are never mutated by M9 and are not inspected, because touching their
     * JAXB trees makes docx4j re-serialize them on save, which would break the
     * byte-preservation guarantee for untouched parts.
     */
    static long maxRevisionId(WordprocessingMLPackage wordPackage) {
        var max = BigInteger.ZERO;
        for (var markup : revisionElements(
                wordPackage.getMainDocumentPart().getJaxbElement())) {
            if (markup.getId() != null && markup.getId().compareTo(max) > 0) {
                max = markup.getId();
            }
        }
        return max.longValue();
    }

    static boolean hasPreexistingRevisions(WordprocessingMLPackage wordPackage) {
        return !revisionElements(
                wordPackage.getMainDocumentPart().getJaxbElement()).isEmpty();
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
