package com.rivell.workbench.knowledge;

/**
 * What a compiled record is, decided per record by {@code knowledge-record-kind}. Only
 * POSITION records (playbooks) and POSITION/WORDING records (clause libraries) become
 * candidates; every other kind stays an extraction unit so no source content is lost.
 */
public enum KnowledgeRecordKind {
    POSITION,
    WORDING,
    ESCALATION,
    GUIDANCE,
    DOCUMENT_CONTROL,
    OTHER
}
