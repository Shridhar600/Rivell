package com.rivell.workbench.platform.parsing;

import java.util.List;

import jakarta.xml.bind.JAXBElement;

import org.docx4j.wml.Br;
import org.docx4j.wml.CTCustomXmlRun;
import org.docx4j.wml.CTSimpleField;
import org.docx4j.wml.CTSmartTagRun;
import org.docx4j.wml.P;
import org.docx4j.wml.R;
import org.docx4j.wml.RunIns;
import org.docx4j.wml.RunTrackChange;
import org.docx4j.wml.SdtRun;
import org.docx4j.wml.Text;

/**
 * The one definition of a paragraph's text, shared by the structural parser and the redline
 * renderer's anchor checks so the two can never disagree.
 *
 * <p>Accept-all view: text in tracked insertions and moves-to counts; deleted and moved-from
 * text does not. Field results count; field codes ({@code w:instrText}) do not. Text inside
 * inline containers (hyperlinks, content controls, simple fields, smart tags, custom XML) counts.
 * Breaks are {@code \n}, tabs {@code \t}.
 */
public final class WordText {

    private WordText() {
    }

    public static String paragraphText(P paragraph) {
        var text = new StringBuilder();
        append(paragraph.getContent(), text);
        return text.toString();
    }

    private static void append(List<Object> content, StringBuilder text) {
        for (var raw : content) {
            if (raw instanceof JAXBElement<?> element && skipped(element.getName().getLocalPart())) {
                continue;
            }
            var value = raw instanceof JAXBElement<?> element ? element.getValue() : raw;
            if (value instanceof R run) {
                append(run.getContent(), text);
            } else if (value instanceof P.Hyperlink hyperlink) {
                append(hyperlink.getContent(), text);
            } else if (value instanceof SdtRun sdtRun && sdtRun.getSdtContent() != null) {
                append(sdtRun.getSdtContent().getContent(), text);
            } else if (value instanceof RunIns insertion) {
                append(insertion.getCustomXmlOrSmartTagOrSdt(), text);
            } else if (value instanceof RunTrackChange moveTo) {
                append(moveTo.getAccOrBarOrBox(), text);
            } else if (value instanceof CTSimpleField field) {
                append(field.getContent(), text);
            } else if (value instanceof CTSmartTagRun smartTag) {
                append(smartTag.getContent(), text);
            } else if (value instanceof CTCustomXmlRun customXml) {
                append(customXml.getContent(), text);
            } else if (value instanceof Text textElement) {
                text.append(textElement.getValue());
            } else if (value instanceof Br || value instanceof R.Cr) {
                text.append('\n');
            } else if (value instanceof R.Tab) {
                text.append('\t');
            }
        }
    }

    /** Field codes, deleted text and moved-from runs are not part of the accept-all text. */
    private static boolean skipped(String localName) {
        return switch (localName) {
            case "instrText", "delText", "delInstrText", "moveFrom" -> true;
            default -> false;
        };
    }
}
