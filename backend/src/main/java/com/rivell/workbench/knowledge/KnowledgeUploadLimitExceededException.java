package com.rivell.workbench.knowledge;

import org.springframework.util.unit.DataSize;

public class KnowledgeUploadLimitExceededException extends RuntimeException {

    public KnowledgeUploadLimitExceededException(DataSize limit) {
        super("Knowledge upload exceeds the configured limit of " + limit.toMegabytes() + "MB");
    }
}
