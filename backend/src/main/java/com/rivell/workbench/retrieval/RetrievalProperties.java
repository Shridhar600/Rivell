package com.rivell.workbench.retrieval;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval engine configuration. Index identity changes (embedding model, index
 * version) must be explicit: retrieval only compares vectors recorded under one
 * model identity, and reindexing is a deliberate operation.
 */
@ConfigurationProperties("rivell.retrieval")
public record RetrievalProperties(
        boolean indexingEnabled,
        String indexVersion,
        int lexicalLimit,
        int vectorLimit,
        int fusionLimit,
        int rerankBudget) {

    private static final String INDEX_VERSION = "rivell-evidence-index-1";

    public RetrievalProperties {
        if (indexVersion == null || indexVersion.isBlank()) {
            indexVersion = INDEX_VERSION;
        }
        if (lexicalLimit < 1) {
            lexicalLimit = 10;
        }
        if (vectorLimit < 1) {
            vectorLimit = 10;
        }
        if (fusionLimit < 1) {
            fusionLimit = 6;
        }
        if (rerankBudget < 1) {
            rerankBudget = 6;
        }
    }
}
