package com.rivell.workbench.knowledge;
import java.util.UUID;

public class KnowledgeCandidateNotFoundException extends RuntimeException {

    public KnowledgeCandidateNotFoundException(UUID candidateId) {
        super("Knowledge candidate " + candidateId + " does not exist in this organization");
    }
}
