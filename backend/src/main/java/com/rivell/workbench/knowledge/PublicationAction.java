package com.rivell.workbench.knowledge;

/**
 * Action a publication item applies to organizational knowledge (Architecture Decision
 * #7.19). M2 supports ADD and explicit SUPERSEDE; UPDATE arrives with real semantic
 * supersession analysis (M4).
 */
public enum PublicationAction {
    ADD,
    SUPERSEDE
}
