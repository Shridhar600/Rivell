package com.rivell.workbench.knowledge;

/**
 * Where an approval rule was stated. The most specific rule wins for one stance,
 * and the level keeps that precedence auditable.
 */
public enum AuthorityLevel {
    /** Stated on the entry itself ("Approver: General Counsel"). */
    ENTRY,
    /** Stated in the document's approval matrix or approval path section. */
    MATRIX,
    /** Stated in an approval table row. */
    TABLE
}
