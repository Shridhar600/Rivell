package com.redstar.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.redstar.workbench.foundation.DocumentStorage;
import com.redstar.workbench.foundation.StorageKey;
import com.redstar.workbench.organization.IdentityProvider;
import com.redstar.workbench.organization.UserIdentity;
import com.redstar.workbench.platform.storage.LocalDocumentStorage;

/**
 * Failure path: a persistence failure after the immutable file was stored must not leave
 * an orphaned file behind.
 */
class ContractIngestionFailureTest {

    private static final UserIdentity IDENTITY = new UserIdentity(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            Set.of("ADMIN"));

    @TempDir
    Path root;

    @Test
    void removesStoredFileWhenPersistenceFails() throws IOException {
        var storage = new LocalDocumentStorage(root);
        var failingRepository = new ContractRepository() {
            @Override
            public void insertSourceArtifact(
                    UUID artifactId, UUID organizationId, String filename, String contentType,
                    long sizeBytes, String sha256, String storageKey) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public void insertContract(UUID contractId, UUID organizationId, String title,
                    java.time.Instant createdAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void insertVersion(UUID versionId, UUID contractId, UUID sourceArtifactId,
                    int versionNumber, ContractRole role, String parserVersion,
                    ParseStatus parseStatus, List<String> parseWarnings, java.time.Instant createdAt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateVersionParseOutcome(UUID versionId, String parserVersion,
                    ParseStatus status, List<String> warnings) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void updateContractTitle(UUID contractId, String title) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void insertNodes(UUID contractVersionId, List<DocumentNode> nodes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<Contract> findContract(UUID contractId, UUID organizationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<ContractVersionSummary> findLatestVersionSummary(
                    UUID contractId, UUID organizationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<ContractVersionSummary> findVersionSummary(
                    UUID contractId, int versionNumber, UUID organizationId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ContractStructure loadStructure(UUID versionId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Optional<ContractSource> findSourceForVersion(
                    UUID versionId, UUID organizationId) {
                throw new UnsupportedOperationException();
            }
        };

        var service = new ContractIngestionService(
                storage,
                (source, versionId) -> {
                    throw new UnsupportedOperationException("not reached");
                },
                failingRepository,
                new TransactionTemplate(noopTransactionManager()),
                () -> new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")),
                (identity, permission) -> true,
                org.springframework.util.unit.DataSize.ofMegabytes(10));

        byte[] docx;
        try (InputStream fixture = getClass().getClassLoader()
                .getResourceAsStream("fixtures/simple-contract.docx")) {
            docx = fixture.readAllBytes();
        }

        assertThatThrownBy(() -> service.ingest(
                "nda.docx", "application/octet-stream", new ByteArrayInputStream(docx)))
                .isInstanceOf(IllegalStateException.class);

        try (var stored = Files.walk(root)) {
            assertThat(stored.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private PlatformTransactionManager noopTransactionManager() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition)
                    throws TransactionException {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
            }
        };
    }
}
