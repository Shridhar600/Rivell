package com.rivell.workbench.knowledge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.DocumentWorkLimiter;
import com.rivell.workbench.foundation.FilenameSanitizer;
import com.rivell.workbench.foundation.StorageKey;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.organization.AuthorizationService;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.Permissions;
import java.util.HexFormat;

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

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final String DOCX_EXTENSION = ".docx";
    private static final String XLSX_EXTENSION = ".xlsx";

    private final DocumentStorage documentStorage;
    private final List<KnowledgeStructureExtractor> structureExtractors;
    private final KnowledgeRepository repository;
    private final SemanticCompilationService semanticCompilation;
    private final TransactionTemplate transactionTemplate;
    private final IdentityProvider identityProvider;
    private final AuthorizationService authorizationService;
    private final DocumentWorkLimiter documentWorkLimiter;
    private final DataSize maxFileSize;

    public KnowledgeIngestionService(
            DocumentStorage documentStorage,
            List<KnowledgeStructureExtractor> structureExtractors,
            KnowledgeRepository repository,
            SemanticCompilationService semanticCompilation,
            TransactionTemplate transactionTemplate,
            IdentityProvider identityProvider,
            AuthorizationService authorizationService,
            DocumentWorkLimiter documentWorkLimiter,
            DataSize maxFileSize) {
        this.documentStorage = documentStorage;
        this.structureExtractors = List.copyOf(structureExtractors);
        this.repository = repository;
        this.semanticCompilation = semanticCompilation;
        this.transactionTemplate = transactionTemplate;
        this.identityProvider = identityProvider;
        this.authorizationService = authorizationService;
        this.documentWorkLimiter = documentWorkLimiter;
        this.maxFileSize = maxFileSize;
    }

    /**
     * The document-work slot is taken before the bytes are read, stored or recorded. A
     * busy limit therefore refuses the upload before any artifact row or file exists. The slot
     * covers only the file work (read, store, structure extraction); it is released before the
     * semantic compile, which can take minutes and does not hold the document in memory.
     */
    public KnowledgeArtifact ingest(
            String filename, String contentType, ArtifactType artifactType,
            ArtifactManifest manifest, InputStream source)
            throws IOException {
        return ingest(filename, contentType, artifactType, manifest, source, null);
    }

    /**
     * With a client-supplied key, ingestion is idempotent: a retry returns the artifact the first
     * attempt stored instead of compiling and storing a second one. The replay is resolved before
     * any file work, and the unique index on (organization_id, client_request_id) is the
     * authority when two identical requests race.
     */
    public KnowledgeArtifact ingest(
            String filename, String contentType, ArtifactType artifactType,
            ArtifactManifest manifest, InputStream source, UUID clientRequestId)
            throws IOException {
        var identity = identityProvider.currentIdentity();
        if (!authorizationService.isAuthorized(identity, Permissions.KNOWLEDGE_UPLOAD)) {
            throw new UnauthorizedKnowledgeAccessException(identity.userId());
        }
        if (clientRequestId != null) {
            var replay = repository.findByClientRequestId(
                    identity.organizationId(), clientRequestId);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        Staged staged;
        try {
            staged = documentWorkLimiter.run(() -> {
                try {
                    return stageWithinLimit(identity.organizationId(), filename, contentType,
                            artifactType, manifest, clientRequestId, source);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } catch (DataIntegrityViolationException conflict) {
            // The pre-check cannot see a concurrent identical request, so the loser's insert
            // violated the unique index. The index decides the winner; read it back so the retry
            // receives the stored artifact rather than a 500.
            if (clientRequestId != null) {
                var winner = repository.findByClientRequestId(
                        identity.organizationId(), clientRequestId);
                if (winner.isPresent()) {
                    return winner.get();
                }
            }
            throw conflict;
        }
        try {
            return complete(staged);
        } catch (RuntimeException e) {
            markFailedQuietly(staged.artifact().id(), e);
            throw e;
        }
    }

    /** What the file work produced, handed from inside the document slot to the compile after it. */
    private record Staged(
            KnowledgeArtifact artifact,
            StructureExtraction extraction,
            KnowledgeDocumentTitle.DocumentTitle documentTitle,
            KnowledgeCompiler.CompiledKnowledge compiled) {
    }

    private Staged stageWithinLimit(
            UUID organizationId, String filename, String contentType, ArtifactType artifactType,
            ArtifactManifest manifest, UUID clientRequestId, InputStream source)
            throws IOException {
        var safeFilename = FilenameSanitizer.sanitize(
                filename, Set.of(DOCX_EXTENSION, XLSX_EXTENSION));
        var bytes = readBounded(source, maxFileSize.toBytes());
        var sha256 = sha256Hex(bytes);
        // Lowered to the precision PostgreSQL keeps for timestamptz: a nanosecond value would be
        // stored rounded, so the returned artifact would not match a later read of it.
        var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var artifactId = UUID.randomUUID();
        var storageKey = new StorageKey(
                organizationId + "/knowledge/" + artifactId + extensionOf(safeFilename));

        documentStorage.store(storageKey, new ByteArrayInputStream(bytes));

        var effectiveManifest = manifest == null
                ? new ArtifactManifest(Set.of(), null, null, null, null, null, null, null, null)
                : manifest;
        var artifact = new KnowledgeArtifact(
                artifactId, organizationId, artifactType,
                safeFilename, resolvedContentType(safeFilename, contentType),
                bytes.length, sha256, storageKey.value(),
                effectiveManifest, IngestStatus.EXTRACTING, null, List.of(), null, now, null,
                clientRequestId);

        try {
            transactionTemplate.executeWithoutResult(status ->
                    repository.insertArtifact(artifact));
        } catch (DataIntegrityViolationException conflict) {
            deleteQuietly(storageKey);
            throw conflict;
        }
        try {
            var extraction = extractStructure(artifact, bytes);
            var documentTitle = extraction.status() == IngestStatus.FAILED
                    ? null : KnowledgeDocumentTitle.of(extraction.nodes());
            var compiled = extraction.status() == IngestStatus.FAILED
                    ? new KnowledgeCompiler.CompiledKnowledge(List.of(), List.of(), extraction.warnings())
                    : KnowledgeCompiler.compile(artifact, extraction.nodes());
            return new Staged(artifact, extraction, documentTitle, compiled);
        } catch (RuntimeException e) {
            markFailedQuietly(artifactId, e);
            throw e;
        }
    }

    private KnowledgeArtifact complete(Staged staged) {
        var artifact = staged.artifact();
        var artifactId = artifact.id();
        try (var ignored = org.slf4j.MDC.putCloseable("artifactId", artifactId.toString())) {
            return completeInScope(staged, artifact, artifactId);
        }
    }

    private KnowledgeArtifact completeInScope(Staged staged, KnowledgeArtifact artifact,
            UUID artifactId) {
        var organizationId = artifact.organizationId();
        var extraction = staged.extraction();
        var documentTitle = staged.documentTitle();
        var compiled = staged.compiled();
        var initialAssessment = artifact.assessment();
        var initialCandidates = compiled.candidates();
        var semanticAvailable = true;
        ArtifactTypeAssessment assessment = initialAssessment;
        List<KnowledgeCandidate> candidates = initialCandidates;
        List<ExtractionUnit> units = compiled.units();
        if (extraction.status() != IngestStatus.FAILED && !compiled.units().isEmpty()) {
            var publishedBefore = repository.findPublishedCandidates(organizationId);
            var semantic = semanticCompilation.compile(
                    artifact, compiled.units(), compiled.candidates(), publishedBefore);
            semanticAvailable = semantic.semanticAvailable();
            assessment = semantic.assessment();
            candidates = semantic.candidates();
            units = semantic.units();
        }
        var finalAssessment = assessment;
        var finalCandidates = candidates;
        var finalUnits = units;

        var outcomeWarnings = new ArrayList<>(extraction.warnings());
        if (!semanticAvailable) {
            outcomeWarnings.add("SEMANTIC_COMPILATION_UNAVAILABLE");
        }
        var outcomeStatus = extraction.status();
        transactionTemplate.executeWithoutResult(status -> {
            repository.updateIngestOutcome(
                    artifactId, extraction.parserVersion(), outcomeStatus,
                    outcomeWarnings);
            if (extraction.coverage() != null) {
                repository.updateParseCoverage(artifactId, extraction.coverage());
            }
            if (finalAssessment != null) {
                repository.updateArtifactAssessment(artifactId, finalAssessment);
            }
            if (documentTitle != null) {
                repository.updateDocumentTitle(artifactId, documentTitle.text());
            }
            if (!extraction.nodes().isEmpty()) {
                repository.insertNodes(artifactId, extraction.nodes());
                repository.insertUnits(artifactId, finalUnits);
                repository.insertCandidates(artifactId, finalCandidates);
            }
        });

        return artifact
                .withDocumentTitle(documentTitle == null ? null : documentTitle.text())
                .withAssessment(finalAssessment)
                .withIngestOutcome(
                        extraction.parserVersion(), outcomeStatus, outcomeWarnings);
    }

    /**
     * After the staging transaction committed, the immutable bytes are the source of
     * truth: compensation marks the artifact FAILED and retains the bytes rather than
     * deleting stored legal sources. Only a pre-staging failure (bytes stored but the
     * artifact row never committed) compensates by deleting the orphan file.
     */
    private void markFailedQuietly(UUID artifactId, RuntimeException cause) {
        log.warn("Knowledge artifact {} could not be processed ({}: {})", artifactId,
                cause.getClass().getSimpleName(), cause.getMessage());
        try {
            transactionTemplate.executeWithoutResult(status -> repository.updateIngestOutcome(
                    artifactId, null, IngestStatus.FAILED,
                    List.of("Rivell could not process this file. Upload it again.")));
        } catch (RuntimeException compensationFailure) {
            // compensation failed; reconciliation is deferred (M10-class work)
        }
    }

    private StructureExtraction extractStructure(KnowledgeArtifact artifact, byte[] bytes) {
        for (var extractor : structureExtractors) {
            if (extractor.supports(artifact.filename())) {
                try (var stream = new ByteArrayInputStream(bytes)) {
                    return extractor.extractStructure(artifact.id(), stream);
                } catch (IOException e) {
                    log.warn("Knowledge artifact {} could not be read ({}: {})", artifact.id(),
                            e.getClass().getSimpleName(), e.getMessage());
                    return StructureExtraction.failed(
                            extractorName(extractor),
                            List.of("Rivell could not read this file. Check that it opens, then upload it again."));
                }
            }
        }
        throw new IllegalStateException(
                "No extractor supports the uploaded source " + artifact.filename());
    }

    private static String extractorName(KnowledgeStructureExtractor extractor) {
        return extractor.getClass().getSimpleName().contains("Docx") ? "rivell-docx-parser-3" : "unknown";
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
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
