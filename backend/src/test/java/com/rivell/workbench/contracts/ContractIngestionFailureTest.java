package com.rivell.workbench.contracts;

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

import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.platform.storage.LocalDocumentStorage;

class ContractIngestionFailureTest {

    @TempDir
    Path root;

    @Test
    void removesStoredFileWhenStagingFails() throws IOException {
        var service = service(new FailingRepository(true));

        assertThatThrownBy(() -> service.ingest(
                "nda.docx", "application/octet-stream", new ByteArrayInputStream(fixture())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(storedFiles()).isEmpty();
    }

    @Test
    void keepsStoredFileAndMarksTheVersionFailedWhenSavingTheParseFails() throws IOException {
        var repository = new FailingRepository(false);
        var service = service(repository);

        assertThatThrownBy(() -> service.ingest(
                "nda.docx", "application/octet-stream", new ByteArrayInputStream(fixture())))
                .isInstanceOf(IllegalStateException.class);

        assertThat(storedFiles()).hasSize(1);
        assertThat(repository.lastParseStatus).isEqualTo(ParseStatus.FAILED);
        assertThat(repository.lastParseWarnings).isNotEmpty();
    }

    private ContractIngestionService service(ContractRepository repository) {
        return new ContractIngestionService(
                new LocalDocumentStorage(root),
                (source, versionId) -> new ParseResult("test-parser", ParseStatus.OK, List.of(), List.of()),
                repository,
                new TransactionTemplate(noopTransactionManager()),
                () -> new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")),
                (identity, permission) -> true,
                new com.rivell.workbench.foundation.DocumentWorkLimiter(1),
                org.springframework.util.unit.DataSize.ofMegabytes(10));
    }

    private byte[] fixture() throws IOException {
        try (InputStream fixture = getClass().getClassLoader()
                .getResourceAsStream("fixtures/simple-contract.docx")) {
            return fixture.readAllBytes();
        }
    }

    private List<Path> storedFiles() throws IOException {
        try (var stored = Files.walk(root)) {
            return stored.filter(Files::isRegularFile).toList();
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

    private static final class FailingRepository implements ContractRepository {

        private final boolean failStaging;
        private ParseStatus lastParseStatus;
        private List<String> lastParseWarnings;

        FailingRepository(boolean failStaging) {
            this.failStaging = failStaging;
        }

        @Override
        public void insertSourceArtifact(
                UUID artifactId, UUID organizationId, String filename, String contentType,
                long sizeBytes, String sha256, String storageKey) {
            if (failStaging) {
                throw new IllegalStateException("database unavailable");
            }
        }

        @Override
        public void insertContract(UUID contractId, UUID organizationId, String title,
                java.time.Instant createdAt) {
        }

        @Override
        public void insertVersion(UUID versionId, UUID contractId, UUID sourceArtifactId,
                int versionNumber, ContractRole role, String parserVersion,
                ParseStatus parseStatus, List<String> parseWarnings, java.time.Instant createdAt) {
        }

        @Override
        public void updateVersionParseOutcome(UUID versionId, String parserVersion,
                ParseStatus status, List<String> warnings, ParseCoverage coverage) {
            lastParseStatus = status;
            lastParseWarnings = warnings;
        }

        @Override
        public java.util.Optional<ParseCoverage> findParseCoverage(UUID versionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateContractTitle(UUID contractId, String title) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertNodes(UUID contractVersionId, List<DocumentNode> nodes) {
            throw new IllegalStateException("database unavailable");
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
        public List<UUID> findHeadingNodeIds(UUID versionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContractStructure loadStructure(UUID versionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.List<ContractListEntry> findContracts(
                UUID organizationId, java.time.Instant afterCreatedAt, UUID afterId,
                int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public java.util.Optional<ContractSource> findSourceForVersion(
                UUID versionId, UUID organizationId) {
            throw new UnsupportedOperationException();
        }
    }
}
