package com.rivell.workbench.contracts;

public class UploadLimitExceededException extends RuntimeException {

    public UploadLimitExceededException(org.springframework.util.unit.DataSize limit) {
        super("Upload exceeds the maximum allowed file size of " + limit.toKilobytes() + "KB");
    }
}
