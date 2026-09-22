package com.rivell.workbench.knowledge;

public class KnowledgeCandidateNotFoundException extends RuntimeException {

    public KnowledgeCandidateNotFoundException(java.util.UUID candidateId) {
        super("Knowledge candidate " + candidateId + " does not exist in this organization");
    }
}
