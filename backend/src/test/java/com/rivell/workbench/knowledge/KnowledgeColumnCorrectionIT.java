package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
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
 * F6 defect: a human's structured spreadsheet-column correction was accepted and stored but
 * never became semantically effective. Wording, duplicate/conflict analysis and publication all
 * read {@code contentText}, which is the machine rendering of the <em>original</em> cells, so the
 * published knowledge silently kept the machine value the reviewer had corrected.
 *
 * <p>This drives the real path end to end: upload -> extraction -> candidate -> edit columns ->
 * approve -> publish -> published read, and asserts the published organizational knowledge
 * reflects the human correction exactly while the machine interpretation stays intact.
 */
@SpringBootTest
@Testcontainers
class KnowledgeColumnCorrectionIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    private static final String MACHINE_ROW =
            "Liability cap | 1x annual fees | 2x annual fees";
    private static final String CORRECTED_ROW =
            "Liability cap | 3x annual fees | 2x annual fees";

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
    MutableIdentityProvider identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void correctedSpreadsheetColumnReachesPublishedKnowledge() throws Exception {
        var artifact = ingestMatrix();
        var candidate = liabilityCapRow(artifact);
        assertThat(candidate.contentText())
                .as("machine wording is rendered from the source cells in column order")
                .isEqualTo(MACHINE_ROW);

        var correctedColumns = new LinkedHashMap<>(candidate.payload().columns());
        correctedColumns.put("Preferred", "3x annual fees");
        var edited = reviewService.edit(candidate.id(), null,
                new CandidatePayload(correctedColumns), null, null, "raise the preferred cap");

        assertThat(edited.contentText())
                .as("the machine interpretation must survive a human correction (AD 6.8)")
                .isEqualTo(MACHINE_ROW);
        assertThat(edited.editedPayload().columns())
                .as("the human correction is retained as structured data, not as overwritten text")
                .containsEntry("Preferred", "3x annual fees");
        assertThat(edited.effectiveContentText())
                .as("the human column correction must be effective immediately, in column order")
                .isEqualTo(CORRECTED_ROW);

        var approved = reviewService.approve(edited.id(), null);
        var publication = publicationService.publish(java.util.List.of(approved.id()),
                java.util.Map.of(), "corrected the preferred cap");

        var published = queryService.getCandidate(candidate.id());
        assertThat(published.reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(published.effectiveContentText())
                .as("published wording must reflect the human correction, not the machine cell")
                .isEqualTo(CORRECTED_ROW);
        assertThat(published.contentText())
                .as("publication must not destroy the machine interpretation")
                .isEqualTo(MACHINE_ROW);
        assertThat(published.editedPayload().columns())
                .as("the correction and its provenance survive publication")
                .containsEntry("Preferred", "3x annual fees");

        var organizationId = identityProvider.currentIdentity().organizationId();
        var publishedRead = queryService.findPublishedCandidatesAsOf(
                PublishedKnowledgeQuery.unpublishedNever(organizationId,
                        publication.publicationNumber(), Set.of(), null, null, null,
                        null, null, null, null));
        assertThat(publishedRead)
                .as("the candidate is visible in the published-knowledge read")
                .anySatisfy(row -> assertThat(row.candidate().id()).isEqualTo(candidate.id()));
        assertThat(publishedRead.stream()
                .filter(row -> row.candidate().id().equals(candidate.id()))
                .findFirst()
                .orElseThrow()
                .candidate()
                .effectiveContentText())
                .as("the published read must serve the human-corrected wording")
                .isEqualTo(CORRECTED_ROW);
    }

    /**
     * Publication is the authority boundary: a published candidate is immutable organizational
     * knowledge, so the correction may not be revised afterwards by the ordinary edit path.
     */
    @Test
    void publishedColumnCorrectionIsImmutable() throws Exception {
        var artifact = ingestMatrix();
        var candidate = liabilityCapRow(artifact);
        var correctedColumns = new LinkedHashMap<>(candidate.payload().columns());
        correctedColumns.put("Preferred", "3x annual fees");
        reviewService.edit(candidate.id(), null, new CandidatePayload(correctedColumns),
                null, null, null);
        var approved = queryService.getCandidate(candidate.id());
        reviewService.approve(approved.id(), null);
        publicationService.publish(java.util.List.of(approved.id()), java.util.Map.of(), "v1");

        assertThatThrownBy(() -> reviewService.edit(candidate.id(), null,
                new CandidatePayload(correctedColumns), null, null, "revise after publication"))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
    }

    /**
     * An untouched structured candidate must render exactly as the machine rendered it, so the
     * correction rule cannot silently reword or reorder knowledge nobody corrected.
     */
    @Test
    void uneditedStructuredCandidateRendersAsTheMachineDid() throws Exception {
        var artifact = ingestMatrix();
        var candidate = liabilityCapRow(artifact);

        assertThat(candidate.editedPayload().columns()).isEmpty();
        assertThat(candidate.editedContentText()).isNull();
        assertThat(candidate.effectiveContentText()).isEqualTo(MACHINE_ROW);
        assertThat(queryService.getCandidate(candidate.id()).effectiveContentText())
                .isEqualTo(MACHINE_ROW);
    }

    /**
     * A human who first states the rule in words and then fixes the cell it
     * came from must not lose their words. The structured correction is recorded either way, but
     * the authoritative wording stays the human's own text.
     */
    @Test
    void aLaterColumnCorrectionDoesNotEraseEarlierHumanWording() throws Exception {
        var artifact = ingestMatrix();
        var candidate = liabilityCapRow(artifact);
        var humanWording = "Liability capped at three times annual fees";

        var stated = reviewService.edit(candidate.id(), humanWording,
                null, null, null, "state the cap in words");
        assertThat(stated.effectiveContentText()).isEqualTo(humanWording);

        var correctedColumns = new LinkedHashMap<>(candidate.payload().columns());
        correctedColumns.put("Preferred", "3x annual fees");
        var afterCellFix = reviewService.edit(candidate.id(), null,
                new CandidatePayload(correctedColumns), null, null, "fix the cell");

        assertThat(afterCellFix.effectiveContentText())
                .as("an earlier human correction must survive a later structured correction")
                .isEqualTo(humanWording);
        assertThat(afterCellFix.editedContentText())
                .as("the human wording stays auditable, not overwritten by the rendering")
                .isEqualTo(humanWording);
        assertThat(afterCellFix.editedPayload().columns())
                .as("the structured correction is still recorded alongside the wording")
                .containsEntry("Preferred", "3x annual fees");
        assertThat(afterCellFix.reviewNote())
                .as("the later correction's note is kept, so the audit trail is the latest action")
                .isEqualTo("fix the cell");
        assertThat(queryService.getCandidate(candidate.id()).effectiveContentText())
                .as("the precedence holds after a reload, not only in memory")
                .isEqualTo(humanWording);
    }

    /**
     * The reverse order is the same rule read from the other side: fresh wording wins over an
     * earlier structured correction, and the correction is not lost.
     */
    @Test
    void freshHumanWordingSupersedesAnEarlierStructuredCorrection() throws Exception {
        var artifact = ingestMatrix();
        var candidate = liabilityCapRow(artifact);
        var correctedColumns = new LinkedHashMap<>(candidate.payload().columns());
        correctedColumns.put("Preferred", "3x annual fees");

        var corrected = reviewService.edit(candidate.id(), null,
                new CandidatePayload(correctedColumns), null, null, "fix the cell");
        assertThat(corrected.effectiveContentText()).isEqualTo(CORRECTED_ROW);

        var restated = reviewService.edit(candidate.id(), "Liability capped at 3x fees",
                null, null, null, "restate it");

        assertThat(restated.effectiveContentText()).isEqualTo("Liability capped at 3x fees");
        assertThat(restated.editedPayload().columns())
                .as("the earlier structured correction is still recoverable")
                .containsEntry("Preferred", "3x annual fees");
    }

    private KnowledgeCandidate liabilityCapRow(KnowledgeArtifact artifact) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(row -> "1x annual fees".equals(row.payload().columns().get("Preferred")))
                .findFirst()
                .orElseThrow();
    }

    private KnowledgeArtifact ingestMatrix() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-matrix.xlsx")) {
            assertThat(stream).as("fixture knowledge-matrix.xlsx").isNotNull();
            return ingestionService.ingest("matrix.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    ArtifactType.PLAYBOOK, null, stream);
        }
    }
}
