package com.rivell.workbench.knowledge;

/**
 * Action a publication item applies to organizational knowledge (Architecture Decision
 * #7.19). Only ADD and explicit SUPERSEDE exist; UPDATE would need semantic
 * supersession analysis.
 */
public enum PublicationAction {
    ADD,
    SUPERSEDE
}
