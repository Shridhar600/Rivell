package com.rivell.workbench.knowledge;

/**
 * Node type of the deterministic structural parse of a knowledge source. DOCX mirrors the
 * contract document tree; spreadsheets add workbook/sheet/row/cell structure.
 */
public enum KnowledgeNodeType {
    BODY,
    TITLE,
    HEADING,
    PARAGRAPH,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    WORKBOOK,
    SHEET,
    SPREADSHEET_ROW,
    SPREADSHEET_CELL
}
