package com.rivell.workbench.knowledge;

/**
 * The role a table/spreadsheet field label plays in a knowledge record. Position-bearing
 * roles map to {@link PositionRole}; the rest classify where a field's value is consumed
 * (reference, applicability, topic, wording).
 */
enum RecordFieldRole {
    PREFERRED,
    FALLBACK,
    PROHIBITED,
    ESCALATION,
    COMMENTARY,
    REFERENCE,
    APPLICABILITY,
    TOPIC,
    WORDING,
    /** Which variant of a clause the record holds: Preferred, Fallback 1, and so on. */
    VARIANT,
    OTHER;

    boolean isPositionBearing() {
        return this == PREFERRED || this == FALLBACK || this == PROHIBITED
                || this == ESCALATION || this == COMMENTARY;
    }

    PositionRole toPositionRole() {
        return switch (this) {
            case PREFERRED -> PositionRole.PREFERRED;
            case FALLBACK -> PositionRole.FALLBACK;
            case PROHIBITED -> PositionRole.PROHIBITED;
            case ESCALATION -> PositionRole.ESCALATION;
            case COMMENTARY -> PositionRole.COMMENTARY;
            default -> null;
        };
    }
}
