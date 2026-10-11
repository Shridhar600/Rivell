package com.rivell.workbench.contracts;

public enum DocumentNodeType {
    BODY,
    TITLE,
    HEADING,
    PARAGRAPH,
    TABLE,
    TABLE_ROW,
    TABLE_CELL,
    /** A text box anchored after its top-level element; holds the box's text, never a redline target. */
    TEXT_BOX
}
