package com.rivell.workbench.knowledge;

public class KnowledgeArtifactNotFoundException extends RuntimeException {

    public KnowledgeArtifactNotFoundException(java.util.UUID artifactId) {
        super("Knowledge artifact " + artifactId + " does not exist in this organization");
    }
}
