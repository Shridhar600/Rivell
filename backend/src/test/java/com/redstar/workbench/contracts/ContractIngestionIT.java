package com.redstar.workbench.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.redstar.workbench.foundation.DocumentStorage;
import com.redstar.workbench.foundation.StorageKey;

@SpringBootTest
@Testcontainers
class ContractIngestionIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("redstar")
            .withUsername("redstar")
            .withPassword("redstar");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("redstar.storage.local.root",
                () -> "target/test-documents/" + UUID.randomUUID());
    }

    @Autowired
    ContractIngestionService ingestionService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    DocumentStorage documentStorage;

    @Test
    void ingestsDocxIntoImmutableArtifactVersionAndNodeTree() {
        var result = ingest("fixtures/simple-contract.docx", "simple-contract.docx");

        assertThat(result.contractId()).isNotNull();
        assertThat(result.versionNumber()).isEqualTo(1);
        assertThat(result.parseStatus()).isEqualTo(ParseStatus.OK);
        assertThat(result.sha256()).hasSize(64);
        assertThat(result.nodeCount()).isEqualTo(7);

        var storedSha = jdbcClient.sql("select sha256 from source_artifact where id = :id")
                .param("id", result.sourceArtifactId())
                .query(String.class).single();
        assertThat(storedSha).isEqualTo(result.sha256());

        var title = jdbcClient.sql("select title from contract where id = :id")
                .param("id", result.contractId())
                .query(String.class).single();
        assertThat(title).isEqualTo("Mutual Non-Disclosure Agreement");

        var nodeCount = jdbcClient.sql(
                        "select count(*) from document_node where contract_version_id = :id")
                .param("id", result.contractVersionId())
                .query(Integer.class).single();
        assertThat(nodeCount).isEqualTo(7);

        var bodyIndex = jdbcClient.sql("""
                select body_index from document_node
                where contract_version_id = :id and node_type = 'BODY'
                """)
                .param("id", result.contractVersionId())
                .query(Integer.class).single();
        assertThat(bodyIndex).isZero();
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
    void persistsFailedParseStatusForInvalidDocxBytes() throws Exception {
        byte[] invalid;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("fixtures/invalid-bytes.docx")) {
            assertThat(stream).isNotNull();
            invalid = stream.readAllBytes();
        }

        var result = ingestBytes(invalid, "broken.docx");

        assertThat(result.parseStatus()).isEqualTo(ParseStatus.FAILED);
        assertThat(result.nodeCount()).isZero();

        var status = jdbcClient.sql("select parse_status from contract_version where id = :id")
                .param("id", result.contractVersionId())
                .query(String.class).single();
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void rejectsNonDocxFilenames() {
        assertThatThrownBy(() -> ingest("fixtures/simple-contract.docx", "contract.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest("fixtures/simple-contract.docx", "contract.doc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ingest("fixtures/simple-contract.docx", " "))
                .isInstanceOf(IllegalArgumentException.class);
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
