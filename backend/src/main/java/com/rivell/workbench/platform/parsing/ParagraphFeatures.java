package com.rivell.workbench.platform.parsing;

/**
 * Layout facts about one block-level paragraph, read once from the DOCX with styles
 * resolved. Heading detection works only from these facts, never from the raw XML.
 *
 * @param text           visible paragraph text (auto-numbering labels are not part of it)
 * @param styleId        paragraph style id, if any
 * @param styleName      resolved style display name, if any (e.g. "heading 1", "Level 1")
 * @param outlineLevel   effective Word outline level 0–8, or null for body text
 * @param numberingLabel rendered auto-numbering label (e.g. "1.", "4.2", "(a)"), if any
 * @param allEmphasised  every visible character is bold or underlined
 * @param allCaps        has letters and all are upper case (typed or via the caps property)
 * @param centred        effective alignment is centre
 * @param runInTitle     a short emphasised phrase that opens the paragraph and is followed
 *                       by body text in the same paragraph (e.g. "Term." in "5. Term. This…"),
 *                       or null
 */
public record ParagraphFeatures(
        String text,
        String styleId,
        String styleName,
        Integer outlineLevel,
        String numberingLabel,
        boolean allEmphasised,
        boolean allCaps,
        boolean centred,
        String runInTitle) {

    public boolean isBlank() {
        return text == null || text.isBlank();
    }
}
