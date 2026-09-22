package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
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
class KnowledgeIngestionIT {

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
    KnowledgeIngestionService ingestionService;

    @Autowired
    KnowledgeQueryService queryService;

    @Autowired
    CandidateReviewService reviewService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    DocumentStorage documentStorage;

    @Autowired
    MutableIdentityProvider identityProvider;

    @Test
    void ingestsPlaybookDocxIntoStructureUnitsAndTypedCandidates() throws Exception {
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        var artifact = ingest("fixtures/knowledge-playbook.docx", "playbook.docx",
                ArtifactType.PLAYBOOK, manifest);

        assertThat(artifact.ingestStatus()).isEqualTo(IngestStatus.EXTRACTED);
        assertThat(artifact.parserVersion()).isEqualTo("rivell-docx-parser-2");
        assertThat(artifact.filename()).isEqualTo("playbook.docx");
        assertThat(artifact.sha256()).hasSize(64);

        var units = queryService.getUnits(artifact.id());
        assertThat(units).extracting(ExtractionUnit::kind)
                .contains(ExtractionUnitKind.HEADING_SECTION, ExtractionUnitKind.TABLE);
        var section = units.stream()
                .filter(unit -> unit.kind() == ExtractionUnitKind.HEADING_SECTION)
                .filter(unit -> "Limitation of Liability".equals(unit.title()))
                .findFirst().orElseThrow();
        assertThat(section.contentText()).contains("1x annual fees", "GC approval");

        var candidates = queryService.getCandidates(artifact.id());
        assertThat(candidates).isNotEmpty();
        var rule = candidates.stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.PLAYBOOK_RULE)
                .filter(candidate -> "Limitation of Liability".equals(candidate.rawLabel()))
                .findFirst().orElseThrow();
        assertThat(rule.effectiveContentText()).contains("1x annual fees", "2x annual fees");
        assertThat(rule.scope().contractPackKey()).isEqualTo("MSA_PACK");
        assertThat(rule.scope().declaredContractTypes()).containsExactly("MSA");
        assertThat(rule.conflictSignals()).isEmpty();

        var dataRow = candidates.stream()
                .filter(candidate -> candidate.payload().columns().containsKey("Preferred"))
                .filter(candidate -> "1x annual fees".equals(candidate.payload().columns().get("Preferred")))
                .findFirst().orElseThrow();
        assertThat(dataRow.payload().columns().get("Fallback")).isEqualTo("2x annual fees");
        assertThat(dataRow.rawLabel()).isEqualTo("Liability cap");
    }

    @Test
    void compilesClauseLibraryCandidatesWithExactText() throws Exception {
        var artifact = ingest("fixtures/clause-library.docx", "library.docx",
                ArtifactType.CLAUSE_LIBRARY, null);

        var candidates = queryService.getCandidates(artifact.id());
        var variant = candidates.stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.CLAUSE_VARIANT)
                .findFirst().orElseThrow();
        assertThat(variant.effectiveContentText())
                .isEqualTo("The total liability of Supplier shall not exceed the fees paid in the preceding 12 months.");
    }

    @Test
    void parsesXlsxWithSheetsRowsCellsMergedRegionsAndRowCandidates() throws Exception {
        var manifest = new ArtifactManifest(
                Set.of("MSA"), "MSA_PACK", "Customer", "Third-Party", "US", "en", null, null, null);
        var artifact = ingest("fixtures/knowledge-matrix.xlsx", "matrix.xlsx",
                ArtifactType.PLAYBOOK, manifest);

        assertThat(artifact.ingestStatus()).isEqualTo(IngestStatus.EXTRACTED);
        assertThat(artifact.parserVersion()).isEqualTo("rivell-xlsx-parser-1");

        var nodes = queryService.getStructure(artifact.id());
        assertThat(nodes).anyMatch(node -> node.nodeType() == KnowledgeNodeType.WORKBOOK);
        assertThat(nodes).anyMatch(node -> node.nodeType() == KnowledgeNodeType.SHEET
                && "Positions".equals(node.sheetName()));
        assertThat(nodes).anyMatch(node -> node.nodeType() == KnowledgeNodeType.SPREADSHEET_CELL
                && node.columnIndex() != null && node.columnIndex() == 2
                && "2x annual fees".equals(node.text()));

        // merged region A1:C1 is preserved on the anchor cell
        var anchor = nodes.stream().filter(node ->
                node.nodeType() == KnowledgeNodeType.SPREADSHEET_CELL
                        && "Notes".equals(node.sheetName())
                        && node.rowIndex() != null && node.rowIndex() == 0
                        && node.columnIndex() != null && node.columnIndex() == 0)
                .findFirst().orElseThrow();
        assertThat(anchor.mergedRegion()).isNotNull();
        assertThat(anchor.mergedRegion().lastColumn()).isEqualTo(2);

        var candidates = queryService.getCandidates(artifact.id());
        var row = candidates.stream()
                .filter(candidate -> candidate.payload().columns().containsKey("Preferred"))
                .filter(candidate -> "1x annual fees".equals(candidate.payload().columns().get("Preferred")))
                .findFirst().orElseThrow();
        assertThat(row.candidateType()).isEqualTo(CandidateType.PLAYBOOK_RULE);
        assertThat(row.scope().contractPackKey()).isEqualTo("MSA_PACK");
    }

    @Test
    void persistsFailedIngestStatusForInvalidXlsxBytes() throws Exception {
        var artifact = ingest("fixtures/invalid-bytes.xlsx", "broken.xlsx",
                ArtifactType.PLAYBOOK, null);

        assertThat(artifact.ingestStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(artifact.parseWarnings()).isNotEmpty();
        assertThat(queryService.getCandidates(artifact.id())).isEmpty();

        var status = jdbcClient.sql("select ingest_status from knowledge_artifact where id = :id")
                .param("id", artifact.id()).query(String.class).single();
        assertThat(status).isEqualTo("FAILED");
    }

    @Test
    void storesExactOriginalBytes() throws Exception {
        byte[] original;
        try (InputStream stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-matrix.xlsx")) {
            assertThat(stream).isNotNull();
            original = stream.readAllBytes();
        }
        var artifact = ingestBytes(original, "matrix.xlsx", ArtifactType.PLAYBOOK, null);
        try (InputStream stored = documentStorage.open(new StorageKey(artifact.storageKey()))) {
            assertThat(stored.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    void repeatedIngestOfIdenticalBytesCreatesDistinctArtifacts() throws Exception {
        var first = ingest("fixtures/clause-library.docx", "library.docx",
                ArtifactType.CLAUSE_LIBRARY, null);
        var second = ingest("fixtures/clause-library.docx", "library.docx",
                ArtifactType.CLAUSE_LIBRARY, null);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.sha256()).isEqualTo(second.sha256());
        assertThat(queryService.getCandidates(second.id()))
                .hasSameSizeAs(queryService.getCandidates(first.id()));
    }

    @Test
    void flagsExactDuplicateAgainstPublishedKnowledge() throws Exception {
        var first = ingest("fixtures/clause-library.docx", "library.docx",
                ArtifactType.CLAUSE_LIBRARY, null);
        var approved = queryService.getCandidates(first.id()).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.CLAUSE_VARIANT)
                .findFirst().orElseThrow();
        reviewService.approve(approved.id(), null);
        publicationService.publish(List.of(approved.id()), null);

        var second = ingest("fixtures/clause-library.docx", "library2.docx",
                ArtifactType.CLAUSE_LIBRARY, null);
        var reingested = queryService.getCandidates(second.id()).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.CLAUSE_VARIANT)
                .findFirst().orElseThrow();
        assertThat(reingested.conflictSignals())
                .anyMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);
    }

    @Test
    void crossOrganizationCandidatesAreInvisible() throws Exception {
        var artifact = ingest("fixtures/clause-library.docx", "isolation.docx",
                ArtifactType.CLAUSE_LIBRARY, null);
        var ownerIdentity = identityProvider.current;
        var otherOrg = UUID.randomUUID();

        assertThat(queryService.getArtifact(artifact.id()).id()).isEqualTo(artifact.id());

        identityProvider.use(new UserIdentity(
                ownerIdentity.userId(), otherOrg, Set.of("ADMIN")));
        try {
            assertThatThrownBy(() -> queryService.getArtifact(artifact.id()))
                    .isInstanceOf(KnowledgeArtifactNotFoundException.class);
            assertThatThrownBy(() -> queryService.getStructure(artifact.id()))
                    .isInstanceOf(KnowledgeArtifactNotFoundException.class);
            var ownerId = ownerIdentity.userId();
            var candidateId = java.util.UUID.fromString(jdbcClient.sql(
                            "select id from knowledge_candidate where artifact_id = :id limit 1")
                    .param("id", artifact.id()).query(String.class).single());
            assertThatThrownBy(() -> queryService.getCandidate(candidateId))
                    .isInstanceOf(KnowledgeCandidateNotFoundException.class);
        } finally {
            identityProvider.use(ownerIdentity);
        }
    }

    private KnowledgeArtifact ingest(
            String fixture, String filename, ArtifactType artifactType,
            ArtifactManifest manifest) {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(fixture)) {
            assertThat(stream).as("classpath resource %s", fixture).isNotNull();
            return ingestionService.ingest(
                    filename, "application/octet-stream", artifactType, manifest, stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private KnowledgeArtifact ingestBytes(
            byte[] bytes, String filename, ArtifactType artifactType, ArtifactManifest manifest) {
        try {
            return ingestionService.ingest(filename, "application/octet-stream", artifactType,
                    manifest, new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
