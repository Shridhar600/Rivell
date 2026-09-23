package com.rivell.workbench.knowledge;

/**
 * Negotiation position roles shared by playbook guidance and clause-library variants.
 * Roles are NOT mutually exclusive across a unit (a row can hold Preferred + Fallback +
 * Escalation simultaneously) — playbook classification therefore uses independent
 * judgments per role; clause variants carry exactly one role or UNSPECIFIED.
 */
public enum PositionRole {
    PREFERRED,
    FALLBACK,
    PROHIBITED,
    ESCALATION,
    COMMENTARY,
    UNSPECIFIED
}
