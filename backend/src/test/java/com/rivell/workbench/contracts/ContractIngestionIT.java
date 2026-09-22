package com.rivell.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.foundation.DocumentStorage;
import com.rivell.workbench.foundation.StorageKey;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

@SpringBootTest
@Testcontainers
class ContractIngestionIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("rivell")
            .withUsername("rivell")
            .withPassword("rivell");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("rivell.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        IdentityProvider mutableIdentityProvider() {
            return new MutableIdentityProvider();
        }
    }

    static final class MutableIdentityProvider implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    @Autowired
    ContractIngestionService ingestionService;

    @Autowired
    ContractQueryService queryService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    DocumentStorage documentStorage;

    @Autowired
    MutableIdentityProvider identityProvider;

    @Test
    void ingestsDocxIntoImmutableArtifactVersionAndNodeTree() {
        var result = ingest("fixtures/simple-contract.docx", "simple-contract.docx");

        assertThat(result.contractId()).isNotNull();
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.OK);
        assertThat(result.parseWarnings()).isEmpty();
        assertThat(result.filename()).isEqualTo("simple-contract.docx");
        assertThat(result.sha256()).hasSize(64);
        assertThat(result.nodeCount()).isEqualTo(7);

        var storedSha = jdbcClient.sql("select sha256 from source_artifact where id = :id")
                .param("id", result.sourceArtifactId())
                .query(String.class).single();
        assertThat(storedSha).isEqualTo(result.sha256());

        var parseStatus = jdbcClient.sql("select parse_status from contract_version where id = :id")
                .param("id", result.contractVersionId())
                .query(String.class).single();
        assertThat(parseStatus).isEqualTo("OK");

        var nodeCount = jdbcClient.sql(
                        "select count(*) from document_node where contract_version_id = :id")
                .param("id", result.contractVersionId())
                .query(Integer.class).single();
        assertThat(nodeCount).isEqualTo(7);
    }

    @Test
    void storesExactOriginalBytesForDownloadRoundtrip() throws Exception {
        byte[] original;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/simple-contract.docx")) {
            assertThat(stream).isNotNull();
            original = stream.readAllBytes();
        }

        var result = ingestBytes(original, "nda.docx");

        try (InputStream stored = documentStorage.open(new StorageKey(result.storageKey()))) {
            assertThat(stored.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void ingestsTableFixtureWithNestedNodes() {
        var result = ingest("fixtures/with-table.docx", "msa.docx");

        assertThat(result.parseStatus()).isEqualTo(ParseStatus.OK);
        var cellCount = jdbcClient.sql("""
                select count(*) from document_node
                where contract_version_id = :id and node_type = 'TABLE_CELL'
                """)
                .param("id", result.contractVersionId())
                .query(Integer.class).single();
        assertThat(cellCount).isEqualTo(6);
    }

    @Test
    void persistsFailedParseStatusWithWarningsForInvalidDocxBytes() throws Exception {
        byte[] invalid;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/invalid-bytes.docx")) {
            assertThat(stream).isNotNull();
            invalid = stream.readAllBytes();
        }

        var result = ingestBytes(invalid, "broken.docx");

        assertThat(result.parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.parseWarnings()).isNotEmpty();
        assertThat(result.nodeCount()).isZero();

        var status = jdbcClient.sql("select parse_status from contract_version where id = :id")
                .param("id", result.contractVersionId())
                .query(String.class).single();
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void sanitizesPathTraversalAndControlCharactersInFilename() {
        var result = ingest("fixtures/simple-contract.docx", "..\\evil\\dir/contract #1.docx");

        assertThat(result.filename()).isEqualTo("contract #1.docx");

        var stored = jdbcClient.sql("select filename from source_artifact where id = :id")
                .param("id", result.sourceArtifactId())
                .query(String.class).single();
        assertThat(stored).isEqualTo("contract #1.docx");
    }

    @Test
    void rejectsNonDocxFilenames() {
        assertThatThrownBy(() -> ingest("fixtures/simple-contract.docx", "contract.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest("fixtures/simple-contract.docx", " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest("fixtures/simple-contract.docx", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void crossOrganizationAccessIsIndistinguishableFromMissingContract() {
        var result = ingest("fixtures/simple-contract.docx", "other-org.docx");
        var ownerIdentity = identityProvider.current;
        var otherOrg = UUID.randomUUID();

        // same organization: readable
        assertThat(queryService.getContract(result.contractId()).id())
                .isEqualTo(result.contractId());
        assertThat(queryService.getVersionSummary(result.contractId(), 1).versionNumber())
                .isEqualTo(1);

        // another organization: not found, not forbidden
        identityProvider.use(new UserIdentity(
                ownerIdentity.userId(), otherOrg, Set.of("ADMIN")));
        try {
            assertThatThrownBy(() -> queryService.getContract(result.contractId()))
                    .isInstanceOf(ContractNotFoundException.class);
            assertThatThrownBy(() -> queryService.getVersionSummary(result.contractId(), 1))
                    .isInstanceOf(ContractVersionNotFoundException.class);
        } finally {
            identityProvider.use(ownerIdentity);
        }
    }

    @Test
    void unauthorizedIdentityCannotIngest() {
        var before = identityProvider.current;
        identityProvider.use(new UserIdentity(before.userId(), before.organizationId(), Set.of("REVIEWER")));
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/simple-contract.docx")) {
            assertThatThrownBy(() -> ingestionService.ingest(
                    "nda.docx", "application/octet-stream", stream))
                    .isInstanceOf(UnauthorizedContractAccessException.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        identityProvider.use(before);
    }

    private ContractIngestionResult ingest(String fixture, String filename) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return ingestionService.ingest(filename, "application/octet-stream", stream);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ContractIngestionResult ingestBytes(byte[] bytes, String filename) {
        try {
            return ingestionService.ingest(
                    filename, "application/octet-stream", new java.io.ByteArrayInputStream(bytes));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
