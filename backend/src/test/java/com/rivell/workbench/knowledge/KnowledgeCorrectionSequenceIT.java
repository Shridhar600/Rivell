package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * The provenance rule for corrected wording.
 *
 * <p>{@code editedContentText} carries exactly one meaning: wording a human typed. A structured
 * correction is not wording, it is a set of corrected values, and its rendering is derived from
 * those values on read. The first B6 pass stored the rendering in {@code editedContentText}, which
 * made a mechanically derived string indistinguishable from human prose - so the next structured
 * correction read it back as explicit human wording, outranked its own fresh rendering, and never
 * became effective. A published row could therefore keep wording a reviewer had already corrected.
 *
 * <p>The rule these tests pin down:
 * <ul>
 *   <li>explicit wording supplied by a human keeps explicit-human precedence;</li>
 *   <li>a derived rendering never masquerades as explicit prose, because it is not stored;</li>
 *   <li>a new structured correction supersedes an older one, and the corrections compose, so an
 *       earlier correction to an untouched column survives;</li>
 *   <li>state agrees with itself after a reload, and publication serves the newest correction.</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
class KnowledgeCorrectionSequenceIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private static final String MACHINE_ROW = "Liability cap | 1x annual fees | 2x annual fees";

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
    CandidateReviewService reviewService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Autowired
    MutableIdentityProvider identityProvider;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void freshOrganization() {
        identityProvider.current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
    }

    /**
     * Two structured corrections to two different columns. The second must become effective, and
     * the first must still be there where the second did not touch it.
     */
    @Test
    void aSecondStructuredCorrectionSupersedesTheFirstAndBothApply() throws Exception {
        var candidate = liabilityCapRow();

        var first = reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Preferred", "3x annual fees")),
                null, null, "raise the preferred cap");
        assertThat(first.effectiveContentText())
                .isEqualTo("Liability cap | 3x annual fees | 2x annual fees");
        assertThat(first.editedContentText())
                .as("a structured correction is not wording, so it is not stored as wording")
                .isNull();

        var second = reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Fallback", "4x annual fees")),
                null, null, "raise the fallback cap");

        assertThat(second.effectiveContentText())
                .as("the second structured correction must become effective")
                .isEqualTo("Liability cap | 3x annual fees | 4x annual fees");
        assertThat(second.editedPayload().columns())
                .as("corrections compose: the earlier correction survives where it was not touched")
                .containsEntry("Preferred", "3x annual fees")
                .containsEntry("Fallback", "4x annual fees");
        assertThat(second.contentText())
                .as("the machine interpretation is never overwritten")
                .isEqualTo(MACHINE_ROW);
    }

    /** The same sequence, read back from the database rather than from the returned object. */
    @Test
    void bothStructuredCorrectionsSurviveAReload() throws Exception {
        var candidate = liabilityCapRow();
        reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Preferred", "3x annual fees")),
                null, null, "raise the preferred cap");
        reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Fallback", "4x annual fees")),
                null, null, "raise the fallback cap");

        var reloaded = queryService.getCandidate(candidate.id());
        assertThat(reloaded.reviewStatus()).isEqualTo(ReviewStatus.EDITED);
        assertThat(reloaded.effectiveContentText())
                .isEqualTo("Liability cap | 3x annual fees | 4x annual fees");
        assertThat(reloaded.editedPayload().columns())
                .containsEntry("Preferred", "3x annual fees")
                .containsEntry("Fallback", "4x annual fees");
        assertThat(reloaded.editedContentText()).isNull();
        assertThat(reloaded.contentText()).isEqualTo(MACHINE_ROW);
    }

    /** Human wording first, then a structured correction: the wording keeps precedence. */
    @Test
    void humanWordingKeepsPrecedenceOverALaterStructuredCorrection() throws Exception {
        var candidate = liabilityCapRow();
        var humanWording = "Liability capped at three times annual fees";

        reviewService.edit(candidate.id(), humanWording, null, null, null, "state the cap in words");
        var corrected = reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Preferred", "3x annual fees")),
                null, null, "raise the preferred cap");

        assertThat(corrected.effectiveContentText())
                .as("explicit human wording outranks a derived rendering")
                .isEqualTo(humanWording);
        assertThat(corrected.editedContentText())
                .as("the explicit wording is retained as explicit, not re-derived")
                .isEqualTo(humanWording);
        assertThat(corrected.editedPayload().columns())
                .as("the structured correction is still recorded alongside it")
                .containsEntry("Preferred", "3x annual fees");

        var reloaded = queryService.getCandidate(candidate.id());
        assertThat(reloaded.effectiveContentText()).isEqualTo(humanWording);
        assertThat(reloaded.editedContentText()).isEqualTo(humanWording);
    }

    /** Structured correction first, then explicit wording: the new wording wins outright. */
    @Test
    void explicitWordingSupersedesAnEarlierStructuredCorrection() throws Exception {
        var candidate = liabilityCapRow();
        reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Preferred", "3x annual fees")),
                null, null, "raise the preferred cap");

        var stated = reviewService.edit(candidate.id(), "Liability capped at 3x annual fees",
                null, null, null, "restate it");

        assertThat(stated.effectiveContentText()).isEqualTo("Liability capped at 3x annual fees");
        assertThat(stated.editedContentText()).isEqualTo("Liability capped at 3x annual fees");
        assertThat(stated.editedPayload().columns())
                .as("the earlier structured correction stays recoverable under the new wording")
                .containsEntry("Preferred", "3x annual fees");

        var reloaded = queryService.getCandidate(candidate.id());
        assertThat(reloaded.effectiveContentText()).isEqualTo("Liability capped at 3x annual fees");
        assertThat(reloaded.editedPayload().columns())
                .containsEntry("Preferred", "3x annual fees");
    }

    /**
     * Publication and retrieval must serve the newest correction. The artifact is a PRECEDENT
     * because only precedent and reference candidates are indexable, and the index row carries a
     * content hash rather than the text, so hashing the expected wording is what proves retrieval
     * holds the newest correction and not the one it replaced.
     */
    @Test
    void publicationAndRetrievalServeTheNewestStructuredCorrection() throws Exception {
        var artifact = ingestPrecedent();
        var candidate = candidateWithLabel(artifact, "Liability cap");
        reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Cap", "2x annual fees")),
                null, null, "raise the first cap");
        var second = reviewService.edit(candidate.id(), null,
                new CandidatePayload(Map.of("Cap (2)", "4x annual fees")),
                null, null, "raise the second cap");
        var expected = "Liability cap | 2x annual fees | 4x annual fees | first tier";
        assertThat(second.effectiveContentText()).isEqualTo(expected);

        var approved = reviewService.approve(candidate.id(), "approved");
        var publication = publicationService.publish(
                java.util.List.of(approved.id()), Map.of(), "publish the corrected row");

        var published = queryService.getCandidate(candidate.id());
        assertThat(published.reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(published.effectiveContentText())
                .as("published knowledge must serve the newest correction")
                .isEqualTo(expected);

        var organizationId = identityProvider.currentIdentity().organizationId();
        var publishedRead = queryService.findPublishedCandidatesAsOf(
                PublishedKnowledgeQuery.unpublishedNever(organizationId,
                        publication.publicationNumber(), Set.of(), null, null, null,
                        null, null, null, null));
        assertThat(publishedRead)
                .anySatisfy(row -> {
                    assertThat(row.candidate().id()).isEqualTo(candidate.id());
                    assertThat(row.candidate().effectiveContentText()).isEqualTo(expected);
                });

        var indexedHash = jdbc.sql("""
                select content_hash from retrieval_evidence_index
                where organization_id = :organizationId and candidate_id = :candidateId
                """)
                .param("organizationId", organizationId)
                .param("candidateId", candidate.id())
                .query(String.class)
                .single();
        assertThat(indexedHash.trim())
                .as("retrieval must hold the newest effective wording, not the superseded one")
                .isEqualTo(sha256Hex(expected));
    }

    private String sha256Hex(String text) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var bytes = digest.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var hex = new StringBuilder();
        for (var value : bytes) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }

    private KnowledgeCandidate liabilityCapRow() throws Exception {
        var artifact = ingestMatrix();
        return queryService.getCandidates(artifact.id()).stream()
                .filter(row -> "1x annual fees".equals(row.payload().columns().get("Preferred")))
                .findFirst()
                .orElseThrow();
    }

    private KnowledgeCandidate candidateWithLabel(KnowledgeArtifact artifact, String label) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(row -> label.equals(row.rawLabel()))
                .findFirst()
                .orElseThrow();
    }

    private KnowledgeArtifact ingestMatrix() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-matrix.xlsx")) {
            assertThat(stream).as("fixture knowledge-matrix.xlsx").isNotNull();
            return ingestionService.ingest("matrix.xlsx", XLSX_MEDIA_TYPE,
                    ArtifactType.PLAYBOOK, null, stream);
        }
    }

    private KnowledgeArtifact ingestPrecedent() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/duplicate-header-playbook.docx")) {
            assertThat(stream).as("fixture duplicate-header-playbook.docx").isNotNull();
            return ingestionService.ingest("precedent.docx", DOCX_MEDIA_TYPE,
                    ArtifactType.EXECUTED_PRECEDENT, null, stream);
        }
    }
}
