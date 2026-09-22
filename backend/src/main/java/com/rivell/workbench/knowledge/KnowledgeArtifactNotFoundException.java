package com.rivell.workbench.knowledge;
import java.util.UUID;

public class KnowledgeArtifactNotFoundException extends RuntimeException {

    public KnowledgeArtifactNotFoundException(UUID artifactId) {
        super("Knowledge artifact " + artifactId + " does not exist in this organization");
    }
}
