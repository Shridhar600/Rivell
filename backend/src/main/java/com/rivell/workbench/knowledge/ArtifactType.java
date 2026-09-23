package com.rivell.workbench.knowledge;

/**
 * Legal knowledge source artifact type, selected by the uploader at ingestion time.
 * The user-declared type is the authoritative ingestion route (Architecture Decision #6.1).
 */
public enum ArtifactType {
    PLAYBOOK,
    CLAUSE_LIBRARY,
    TEMPLATE,
    EXECUTED_PRECEDENT,
    NEGOTIATION_HISTORY,
    EXTERNAL_REFERENCE
}
