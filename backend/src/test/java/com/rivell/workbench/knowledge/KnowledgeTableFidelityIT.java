package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Reviewer finding 2: a DOCX table may label two different columns with the same header text.
 * The structured payload is keyed by header label, so the second cell silently overwrote the
 * first and a source cell vanished from the authoritative wording - the row then read as if the
 * source said one thing where it said another. That is data loss, not a rendering preference,
 * because the dropped cell is the one a reviewer would have corrected.
 */
@SpringBootTest
@Testcontainers
class KnowledgeTableFidelityIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

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
    MutableIdentityProvider identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
    }

    @Test
    void aRepeatedTableHeaderKeepsEverySourceCell() throws Exception {
        var artifact = ingest();

        var liabilityCap = candidate(artifact, "Liability cap");
        assertThat(liabilityCap.payload().columns())
                .as("every source cell must survive as its own column")
                .containsOnlyKeys("Clause", "Cap", "Cap (2)", "Note");
        assertThat(liabilityCap.payload().columns().get("Cap")).isEqualTo("1x annual fees");
        assertThat(liabilityCap.payload().columns().get("Cap (2)")).isEqualTo("3x annual fees");
        assertThat(liabilityCap.contentText())
                .as("the authoritative wording must carry both same-labelled cells")
                .isEqualTo("Liability cap | 1x annual fees | 3x annual fees | first tier");

        var indemnityCap = candidate(artifact, "Indemnity cap");
        assertThat(indemnityCap.payload().columns())
                .as("every row of the table must resolve the repeated label the same way")
                .containsOnlyKeys("Clause", "Cap", "Cap (2)", "Note");
        assertThat(indemnityCap.contentText())
                .isEqualTo("Indemnity cap | 2x annual fees | 4x annual fees | second tier");
    }

    /**
     * The disambiguation must be stable across rows and re-reads, otherwise a correction saved
     * against "Cap (2)" would attach to a different column on the next read.
     */
    @Test
    void theDisambiguatedColumnNamesAreStableAcrossReads() throws Exception {
        var artifact = ingest();
        var first = candidate(artifact, "Liability cap");
        var reloaded = queryService.getCandidates(artifact.id()).stream()
                .filter(row -> "Liability cap".equals(row.rawLabel()))
                .findFirst()
                .orElseThrow();

        assertThat(reloaded.payload().orderedKeys())
                .isEqualTo(first.payload().orderedKeys());
        assertThat(reloaded.payload().orderedKeys())
                .containsExactly("Clause", "Cap", "Cap (2)", "Note");
    }

    private KnowledgeCandidate candidate(KnowledgeArtifact artifact, String rawLabel) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(row -> rawLabel.equals(row.rawLabel()))
                .findFirst()
                .orElseThrow();
    }

    private KnowledgeArtifact ingest() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/duplicate-header-playbook.docx")) {
            assertThat(stream).as("fixture duplicate-header-playbook.docx").isNotNull();
            return ingestionService.ingest("duplicate-header-playbook.docx", DOCX_MEDIA_TYPE,
                    ArtifactType.PLAYBOOK, null, stream);
        }
    }
}
