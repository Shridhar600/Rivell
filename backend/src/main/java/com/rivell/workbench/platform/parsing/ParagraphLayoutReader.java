package com.rivell.workbench.platform.parsing;

import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.docx4j.model.PropertyResolver;
import org.docx4j.model.listnumbering.Emulator;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Br;
import org.docx4j.wml.JcEnumeration;
import org.docx4j.wml.P;
import org.docx4j.wml.P.Hyperlink;
import org.docx4j.wml.R;
import org.docx4j.wml.RPr;
import org.docx4j.wml.SdtRun;
import org.docx4j.wml.Text;
import org.docx4j.wml.UnderlineEnumeration;

/**
 * Reads {@link ParagraphFeatures} from a docx4j paragraph, resolving styles the way Word
 * does (paragraph style chain, then direct formatting).
 *
 * <p>Not thread-safe and stateful: the list-numbering emulator advances its counters on
 * every call, so each paragraph must be read exactly once, in document order.
 */
final class ParagraphLayoutReader {

    private static final Pattern LEADING_LABEL = Pattern.compile(
            "(?i)^\\s*(?:(?:article|section|clause)\\s+)?"
                    + "(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){0,4}|[ivxlc]{1,6}|\\([a-z0-9]{1,4}\\))[.)]?\\s+");
    private static final int MIN_RUN_IN_BODY_CHARS = 20;

    private final WordprocessingMLPackage wordPackage;
    private final Consumer<String> warnings;
    private final PropertyResolver resolver;

    ParagraphLayoutReader(WordprocessingMLPackage wordPackage, Consumer<String> warnings) {
        this.wordPackage = wordPackage;
        this.warnings = warnings;
        PropertyResolver resolved;
        try {
            resolved = wordPackage.getMainDocumentPart().getPropertyResolver();
        } catch (Exception e) {
            warnings.accept("STYLES_UNRESOLVED");
            resolved = null;
        }
        this.resolver = resolved;
    }

    ParagraphFeatures read(P paragraph, String text) {
        var pPr = paragraph.getPPr();
        var styleId = pPr != null && pPr.getPStyle() != null ? pPr.getPStyle().getVal() : null;
        var effective = effectivePPr(paragraph);
        var outline = effective != null && effective.getOutlineLvl() != null
                ? effective.getOutlineLvl().getVal().intValue() : null;
        var centred = effective != null && effective.getJc() != null
                && effective.getJc().getVal() == JcEnumeration.CENTER;

        var marked = new MarkedText();
        for (Object raw : paragraph.getContent()) {
            collect(unwrap(raw), paragraph, marked);
        }
        var visible = marked.text.toString();
        return new ParagraphFeatures(
                text, styleId, styleName(styleId),
                outline != null && outline <= 8 ? outline : null,
                numberingLabel(paragraph, styleId),
                marked.allEmphasised(), marked.allCaps(visible), centred,
                runInTitle(visible, marked));
    }

    private org.docx4j.wml.PPr effectivePPr(P paragraph) {
        if (resolver == null) {
            return paragraph.getPPr();
        }
        try {
            return resolver.getEffectivePPr(paragraph.getPPr());
        } catch (Exception e) {
            warnings.accept("STYLES_UNRESOLVED");
            return paragraph.getPPr();
        }
    }

    private String styleName(String styleId) {
        if (styleId == null) {
            return null;
        }
        var stylesPart = wordPackage.getMainDocumentPart().getStyleDefinitionsPart();
        var style = stylesPart == null ? null : stylesPart.getStyleById(styleId);
        return style != null && style.getName() != null ? style.getName().getVal() : null;
    }

    private String numberingLabel(P paragraph, String styleId) {
        var pPr = paragraph.getPPr();
        var numPr = pPr == null ? null : pPr.getNumPr();
        var numId = numPr != null && numPr.getNumId() != null
                ? numPr.getNumId().getVal().toString() : null;
        var ilvl = numPr != null && numPr.getIlvl() != null
                ? numPr.getIlvl().getVal().toString() : null;
        if (numId == null && ilvl == null && styleId == null) {
            return null;
        }
        try {
            var triple = Emulator.getNumber(wordPackage, styleId, numId, ilvl);
            if (triple == null) {
                return null;
            }
            var label = triple.getBullet() != null && !triple.getBullet().isBlank()
                    ? triple.getBullet() : triple.getNumString();
            return label == null || label.isBlank() ? null : label.trim();
        } catch (Exception e) {
            warnings.accept("NUMBERING_UNRESOLVED");
            return null;
        }
    }

    /** Mirrors the parser's text walk, tagging each character with its run's formatting. */
    private void collect(Object element, P paragraph, MarkedText out) {
        if (element instanceof R run) {
            var format = runFormat(run, paragraph);
            for (Object child : run.getContent()) {
                collectRunChild(unwrap(child), format, out);
            }
        } else if (element instanceof Hyperlink hyperlink) {
            for (Object child : hyperlink.getContent()) {
                collect(unwrap(child), paragraph, out);
            }
        } else if (element instanceof SdtRun sdtRun) {
            for (Object child : sdtRun.getSdtContent().getContent()) {
                collect(unwrap(child), paragraph, out);
            }
        }
    }

    private static void collectRunChild(Object child, RunFormat format, MarkedText out) {
        if (child instanceof Text text) {
            out.append(text.getValue(), format);
        } else if (child instanceof Br || child instanceof R.Cr) {
            out.append("\n", format);
        } else if (child instanceof R.Tab) {
            out.append("\t", format);
        }
    }

    private record RunFormat(boolean emphasis, boolean caps) {
    }

    private RunFormat runFormat(R run, P paragraph) {
        RPr rPr = run.getRPr();
        if (resolver != null) {
            try {
                rPr = resolver.getEffectiveRPr(run.getRPr(), paragraph.getPPr());
            } catch (Exception e) {
                warnings.accept("STYLES_UNRESOLVED");
            }
        }
        if (rPr == null) {
            return new RunFormat(false, false);
        }
        var bold = rPr.getB() != null && rPr.getB().isVal();
        var underline = rPr.getU() != null && rPr.getU().getVal() != null
                && rPr.getU().getVal() != UnderlineEnumeration.NONE;
        var caps = rPr.getCaps() != null && rPr.getCaps().isVal();
        return new RunFormat(bold || underline, caps);
    }

    /**
     * "5. Term. This Agreement…": an emphasised phrase right after any leading label,
     * closed by punctuation, followed by ordinary body text in the same paragraph.
     */
    private static String runInTitle(String text, MarkedText marked) {
        var label = LEADING_LABEL.matcher(text);
        var start = label.find() ? label.end() : 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        var end = start;
        var lastEmphasised = -1;
        while (end < text.length()
                && (marked.emphasis(end) || Character.isWhitespace(text.charAt(end)))) {
            if (marked.emphasis(end) && !Character.isWhitespace(text.charAt(end))) {
                lastEmphasised = end;
            }
            end++;
        }
        if (lastEmphasised < start) {
            return null;
        }
        var title = text.substring(start, lastEmphasised + 1).strip();
        var rest = text.substring(lastEmphasised + 1).strip();
        var closed = title.endsWith(".") || title.endsWith(":")
                || rest.startsWith(".") || rest.startsWith(":")
                || rest.startsWith("–") || rest.startsWith("—") || rest.startsWith("-");
        var bodyChars = rest.replaceFirst("^[.:–—-]+", "").strip().length();
        if (!closed || bodyChars < MIN_RUN_IN_BODY_CHARS || !title.chars().anyMatch(Character::isLetter)) {
            return null;
        }
        return title;
    }

    private static Object unwrap(Object element) {
        return element instanceof jakarta.xml.bind.JAXBElement<?> jaxb ? jaxb.getValue() : element;
    }

    private static final class MarkedText {
        private final StringBuilder text = new StringBuilder();
        private final java.util.BitSet emphasis = new java.util.BitSet();
        private final java.util.BitSet caps = new java.util.BitSet();

        void append(String value, RunFormat format) {
            var from = text.length();
            text.append(value);
            if (format.emphasis()) {
                emphasis.set(from, text.length());
            }
            if (format.caps()) {
                caps.set(from, text.length());
            }
        }

        boolean emphasis(int index) {
            return emphasis.get(index);
        }

        boolean allEmphasised() {
            var any = false;
            for (int i = 0; i < text.length(); i++) {
                if (!Character.isWhitespace(text.charAt(i))) {
                    if (!emphasis.get(i)) {
                        return false;
                    }
                    any = true;
                }
            }
            return any;
        }

        boolean allCaps(String visible) {
            var letters = 0;
            for (int i = 0; i < visible.length(); i++) {
                var c = visible.charAt(i);
                if (Character.isLetter(c)) {
                    letters++;
                    if (Character.isLowerCase(c) && !caps.get(i)) {
                        return false;
                    }
                }
            }
            return letters >= 3;
        }
    }
}
