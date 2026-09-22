package com.rivell.workbench.knowledge;

/**
 * Kind of an extraction unit: one coherent source region believed to express one legal
 * knowledge unit (Architecture Decision #7.2).
 */
public enum ExtractionUnitKind {
    /** A DOCX heading plus its trailing paragraphs. */
    HEADING_SECTION,
    /** A DOCX table (one candidate per row at compile time). */
    TABLE,
    /** One data row of a spreadsheet (below its header row). */
    SPREADSHEET_ROW
}
