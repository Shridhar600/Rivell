package com.rivell.workbench.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.StorageKey;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Contract ingestion: store immutable source, parse, persist canonical structure.
 *
 * Flow: bounded upload read, storage first (immutable file), then a staging transaction that
 * records {@link ParseStatus#PARSING}, parsing outside any transaction, then a short outcome
 * transaction. A failure after storage but before staging is compensated by removing the
 * stored file.
 */
public class ContractIngestionService {

    public static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentStorage documentStorage;
    private final DocumentStructureParser documentStructureParser;
    private final ContractRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final DataSize maxFileSize;

    public ContractIngestionService(
            DocumentStorage documentStorage,
            DocumentStructureParser documentStructureParser,
            ContractRepository repository,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            DataSize maxFileSize) {
        this.documentStorage = documentStorage;
        this.documentStructureParser = documentStructureParser;
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.maxFileSize = maxFileSize;
    }

    public ContractIngestionResult ingest(String filename, String contentType, InputStream source)
            throws IOException {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.CONTRACT_UPLOAD)) {
            throw new UnauthorizedContractAccessException(identity.userId());
        }

        var safeFilename = sanitizeFilename(filename);
        var bytes = readBounded(source, maxFileSize.toBytes());
        var sha256 = sha256Hex(bytes);
        var now = Instant.now();

        var artifactId = UUID.randomUUID();
        var storageKey = new StorageKey(
                identity.organizationId() + "/contracts/" + artifactId + ".docx");
        documentStorage.store(storageKey, new java.io.ByteArrayInputStream(bytes));

        UUID contractId;
        UUID versionId;
        try {
            var staged = transactionTemplate.execute(status -> {
                var ids = new UUID[] {UUID.randomUUID(), UUID.randomUUID()};
                repository.insertSourceArtifact(
                        artifactId, identity.organizationId(), safeFilename,
                        contentType == null || contentType.isBlank()
                                ? DOCX_CONTENT_TYPE
                                : contentType,
                        bytes.length, sha256, storageKey.value());
                repository.insertContract(ids[0], identity.organizationId(), null, now);
                repository.insertVersion(
                        ids[1], ids[0], artifactId, 1, ContractRole.ORIGINAL,
                        null, ParseStatus.PARSING, List.of(), now);
                return new StagedIngestion(ids[0], ids[1]);
            });
            contractId = staged.contractId();
            versionId = staged.versionId();

            ParseResult parseResult;
            try (var parsedSource = new java.io.ByteArrayInputStream(bytes)) {
                parseResult = documentStructureParser.parse(parsedSource, versionId);
            }

            transactionTemplate.executeWithoutResult(status -> {
                repository.updateVersionParseOutcome(
                        versionId, parseResult.parserVersion(),
                        parseResult.status(), parseResult.warnings());
                repository.insertNodes(versionId, parseResult.nodes());
                parsedTitle(parseResult.nodes()).ifPresent(title ->
                        repository.updateContractTitle(contractId, title));
            });

            return new ContractIngestionResult(
                    contractId,
                    versionId,
                    artifactId,
                    1,
                    safeFilename,
                    parseResult.status(),
                    parseResult.warnings(),
                    sha256,
                    storageKey.value(),
                    parseResult.nodes().size());
        } catch (RuntimeException | IOException e) {
            deleteQuietly(storageKey);
            throw e;
        }
    }

    private byte[] readBounded(InputStream source, long limitBytes) throws IOException {
        var limit = (int) Math.min(limitBytes, Integer.MAX_VALUE - 1L);
        var buffer = source.readNBytes(limit + 1);
        if (buffer.length > limit) {
            throw new UploadLimitExceededException(DataSize.ofBytes(limitBytes));
        }
        return buffer;
    }

    private java.util.Optional<String> parsedTitle(java.util.List<DocumentNode> nodes) {
        return nodes.stream()
                .filter(node -> node.nodeType() == DocumentNodeType.TITLE)
                .map(DocumentNode::text)
                .filter(text -> text != null && !text.isBlank())
                .findFirst();
    }

    private void deleteQuietly(StorageKey storageKey) {
        try {
            documentStorage.delete(storageKey);
        } catch (IOException cleanupFailure) {
            // Orphaned immutable file; reconciliation is out of M1 scope.
        }
    }

    private record StagedIngestion(UUID contractId, UUID versionId) {
    }

    static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        var base = filename.trim();
        var slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        var cleaned = base.replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        if (!cleaned.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new IllegalArgumentException("Only .docx files are supported");
        }
        return cleaned;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
