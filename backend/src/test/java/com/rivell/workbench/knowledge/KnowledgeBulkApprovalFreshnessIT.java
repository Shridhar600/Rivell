package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;

/**
 * Bulk approval is the one review path that approves without a human reading
 * the row, so its eligibility must be judged against current published knowledge rather than the
 * category stored when the candidate was extracted.
 *
 * <p>Conditions are a snapshot by construction. A candidate is categorized against published truth
 * at ingestion; if another candidate is published afterwards, the stored category is stale and the
 * candidate may now duplicate or contradict published knowledge. Acting on the stored value would
 * publish knowledge nobody reviewed, in bulk, on the strength of a decision made before the
 * conflict existed. Individually reviewed and published candidates remain advisory-only -
 * this is the boundary where the product decided freshness must be checked.
 */
// The scripted provider is what makes semantic compilation succeed without an API key, and a
// candidate is only ROUTINE when its concept classified confidently. Each test uses its own
// organization, so the demo seed that this property also triggers cannot affect these assertions.
@SpringBootTest(properties = "rivell.demo.seed-enabled=true")
@Testcontainers
class KnowledgeBulkApprovalFreshnessIT {

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
    CandidateReviewService reviewService;

    @Autowired
    KnowledgePublicationService publicationService;

    @Autowired
    MutableIdentityProvider identityProvider;

    @BeforeEach
    void freshOrganization() {
        identityProvider.current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
    }

    @Test
    void bulkApprovalRefusesACandidateThatPublishedTruthHasMadeNonRoutine() throws Exception {
        var first = ingestRoutinePlaybook("routine-first.docx");
        var second = ingestRoutinePlaybook("routine-second.docx");
        var stale = routineCandidates(second).get(0);
        assertThat(stale.reviewStatus()).isEqualTo(ReviewStatus.PENDING);
        assertThat(stale.reviewCategory())
                .as("at extraction nothing was published yet, so it is genuinely routine")
                .isEqualTo(ReviewCategory.ROUTINE);

        publish(routineCandidates(first));

        var result = reviewService.bulkApprove(List.of(stale.id()));

        assertThat(result.approvedIds())
                .as("a candidate that now duplicates published knowledge must not be bulk approved")
                .isEmpty();
        assertThat(result.skipped()).containsKey(stale.id());
        assertThat(result.skipped().get(stale.id()))
                .as("the refusal must name the condition that makes it ineligible")
                .contains("it may duplicate published knowledge");
        assertThat(queryService.getCandidate(stale.id()).reviewStatus())
                .as("a refused candidate keeps its pending state for a human to look at")
                .isEqualTo(ReviewStatus.PENDING);
    }

    @Test
    void bulkApprovalStillApprovesGenuinelyRoutineCandidates() throws Exception {
        var artifact = ingestRoutinePlaybook("routine-alone.docx");
        var routine = routineCandidates(artifact);
        assertThat(routine).hasSizeGreaterThanOrEqualTo(2);

        var result = reviewService.bulkApprove(
                routine.stream().map(KnowledgeCandidate::id).toList());

        assertThat(result.skipped()).isEmpty();
        assertThat(result.approvedIds()).hasSameSizeAs(routine);
        assertThat(queryService.getCandidate(routine.get(0).id()).reviewStatus())
                .isEqualTo(ReviewStatus.APPROVED);
    }

    private void publish(List<KnowledgeCandidate> candidates) {
        var approved = candidates.stream()
                .map(candidate -> reviewService.approve(candidate.id(), null).id())
                .toList();
        publicationService.publish(approved, Map.of(), "publish the routine playbook");
    }

    private List<KnowledgeCandidate> routineCandidates(KnowledgeArtifact artifact) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                .toList();
    }

    private KnowledgeArtifact ingestRoutinePlaybook(String filename) throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("demo/documents/Rivell Demo Routine Playbook.docx")) {
            assertThat(stream).as("demo routine playbook").isNotNull();
            return ingestionService.ingest(filename, DOCX_MEDIA_TYPE, ArtifactType.PLAYBOOK,
                    new ArtifactManifest(Set.of(), null, null, null, null, null, null, null,
                            "Rivell local demo scenario data"),
                    stream);
        }
    }
}
