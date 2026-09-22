package com.redstar.workbench.contracts;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties("redstar.contracts.upload")
record UploadProperties(DataSize maxFileSize) {

    static final DataSize DEFAULT_MAX_FILE_SIZE = DataSize.ofMegabytes(10);
}
