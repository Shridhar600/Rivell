package com.rivell.workbench.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("rivell.knowledge.upload")
record KnowledgeUploadProperties(DataSize maxFileSize) {

    static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(10);

    KnowledgeUploadProperties {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0) {
            maxFileSize = DEFAULT_MAX_FILE_SIZE;
        }
    }
}
