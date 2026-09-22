package com.rivell.workbench.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.FilenameSanitizer;
import com.rivell.workbench.foundation.StorageKey;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;

/**
 * Knowledge ingestion: store the immutable source, deterministically extract structure,
 * compile extraction units and typed candidates, persist the outcome.
 *
 * Flow mirrors contract ingestion: bounded upload read, storage first (immutable file),
 * staging transaction (EXTRACTING), extraction outside any transaction, short outcome
 * transaction. A failure after storage but before staging is compensated by removing the
 * stored file; extraction failure persists a FAILED artifact and always retains the bytes.
 */
public class KnowledgeIngestionService {

    private static final String DOCX_EXTENSION = ".docx";
    private static final String XLSX_EXTENSION = ".xlsx";

    private final DocumentStorage documentStorage;
    private final List<KnowledgeStructureExtractor> structureExtractors;
    private final KnowledgeRepository repository;
    private final DuplicateConflictDetector duplicateConflictDetector;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final DataSize maxFileSize;

    public KnowledgeIngestionService(
            DocumentStorage documentStorage,
            List<KnowledgeStructureExtractor> structureExtractors,
            KnowledgeRepository repository,
            DuplicateConflictDetector duplicateConflictDetector,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            DataSize maxFileSize) {
        this.documentStorage = documentStorage;
        this.structureExtractors = List.copyOf(structureExtractors);
        this.repository = repository;
        this.duplicateConflictDetector = duplicateConflictDetector;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.maxFileSize = maxFileSize;
    }

    public KnowledgeArtifact ingest(
            String filename, String contentType, ArtifactType artifactType,
            ArtifactManifest manifest, InputStream source)
            throws IOException {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_UPLOAD)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }

        var safeFilename = FilenameSanitizer.sanitize(
                filename, Set.of(DOCX_EXTENSION, XLSX_EXTENSION));
        var bytes = readBounded(source, maxFileSize.toBytes());
        var sha256 = sha256Hex(bytes);
        var now = Instant.now();
        var artifactId = UUID.randomUUID();
        var storageKey = new StorageKey(
                identity.organizationId() + "/knowledge/" + artifactId + extensionOf(safeFilename));

        documentStorage.store(storageKey, new ByteArrayInputStream(bytes));

        var effectiveManifest = manifest == null
                ? new ArtifactManifest(Set.of(), null, null, null, null, null, null, null, null)
                : manifest;
        var artifact = new KnowledgeArtifact(
                artifactId, identity.organizationId(), artifactType,
                safeFilename, resolvedContentType(safeFilename, contentType),
                bytes.length, sha256, storageKey.value(),
                effectiveManifest, IngestStatus.EXTRACTING, null, List.of(), now);

        try {
            transactionTemplate.executeWithoutResult(status ->
                    repository.insertArtifact(artifact));

            var extraction = extractStructure(artifact, bytes);
            var compiled = extraction.status() == IngestStatus.FAILED
                    ? new KnowledgeCompiler.CompiledKnowledge(List.of(), List.of(), extraction.warnings())
                    : KnowledgeCompiler.compile(artifact, extraction.nodes());

            var publishedBefore = repository.findPublishedCandidates(identity.organizationId());
            var candidates = compiled.units().isEmpty()
                    ? List.<KnowledgeCandidate>of()
                    : compiled.candidates().stream()
                            .map(candidate -> candidate.withConflictSignals(
                                    duplicateConflictDetector.inspect(candidate, publishedBefore)))
                            .toList();

            transactionTemplate.executeWithoutResult(status -> {
                repository.updateIngestOutcome(
                        artifactId, extraction.parserVersion(), extraction.status(),
                        extraction.warnings());
                if (!extraction.nodes().isEmpty()) {
                    repository.insertNodes(artifactId, extraction.nodes());
                    repository.insertUnits(artifactId, compiled.units());
                    repository.insertCandidates(artifactId, candidates);
                }
            });

            return artifact.withIngestOutcome(
                    extraction.parserVersion(), extraction.status(), extraction.warnings());
        } catch (RuntimeException e) {
            deleteQuietly(storageKey);
            throw e;
        }
    }

    private StructureExtraction extractStructure(KnowledgeArtifact artifact, byte[] bytes) {
        for (var extractor : structureExtractors) {
            if (extractor.supports(artifact.filename())) {
                try (var stream = new ByteArrayInputStream(bytes)) {
                    return extractor.extractStructure(artifact.id(), stream);
                } catch (IOException e) {
                    return StructureExtraction.failed(
                            extractorName(extractor),
                            List.of("The source could not be read (" + e.getClass().getSimpleName() + ")"));
                }
            }
        }
        throw new IllegalStateException(
                "No extractor supports the uploaded source " + artifact.filename());
    }

    private static String extractorName(KnowledgeStructureExtractor extractor) {
        return extractor.getClass().getSimpleName().contains("Docx") ? "rivell-docx-parser-2" : "unknown";
    }

    private static String extensionOf(String filename) {
        var lower = filename.toLowerCase(Locale.ROOT);
        return lower.endsWith(XLSX_EXTENSION) ? XLSX_EXTENSION : DOCX_EXTENSION;
    }

    private static String resolvedContentType(String filename, String declaredContentType) {
        var contentType = declaredContentType == null || declaredContentType.isBlank()
                ? null
                : declaredContentType;
        return contentType != null ? contentType : defaultContentType(filename);
    }

    private static String defaultContentType(String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(XLSX_EXTENSION)
                ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    }

    private byte[] readBounded(InputStream source, long limitBytes) throws IOException {
        var limit = (int) Math.min(limitBytes, Integer.MAX_VALUE - 1L);
        var buffer = source.readNBytes(limit + 1);
        if (buffer.length > limit) {
            throw new KnowledgeUploadLimitExceededException(DataSize.ofBytes(limitBytes));
        }
        return buffer;
    }

    private void deleteQuietly(StorageKey storageKey) {
        try {
            documentStorage.delete(storageKey);
        } catch (IOException cleanupFailure) {
            // Orphaned immutable file; reconciliation is deferred (M10-class work).
        }
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
