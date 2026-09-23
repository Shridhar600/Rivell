package com.rivell.workbench.knowledge;

/** The extraction unit does not exist in this organization. */
public class KnowledgeUnitNotFoundException extends RuntimeException {

    public KnowledgeUnitNotFoundException(java.util.UUID unitId) {
        super("Extraction unit " + unitId + " does not exist in this organization");
    }
}
