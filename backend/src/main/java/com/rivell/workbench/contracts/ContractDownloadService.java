package com.rivell.workbench.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.StorageKey;

/**
 * Opens the immutable original document of a contract version for download.
 */
public class ContractDownloadService {

    public record ContractDownload(String filename, String contentType, byte[] content) {
    }

    private final ContractQueryService queryService;
    private final DocumentStorage documentStorage;

    public ContractDownloadService(ContractQueryService queryService, DocumentStorage documentStorage) {
        this.queryService = queryService;
        this.documentStorage = documentStorage;
    }

    public ContractDownload open(UUID contractId, int versionNumber) throws IOException {
        var source = queryService.getSourceForDownload(contractId, versionNumber);
        var key = new StorageKey(source.storageKey());
        try (InputStream content = documentStorage.open(key)) {
            return new ContractDownload(source.filename(), source.contentType(), content.readAllBytes());
        }
    }
}
