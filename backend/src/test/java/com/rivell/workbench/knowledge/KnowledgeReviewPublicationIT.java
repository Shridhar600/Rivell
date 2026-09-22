package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.UUID;

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

@SpringBootTest
@Testcontainers
class KnowledgeReviewPublicationIT {

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
    MutableIdentityProvider identityProvider;

    @org.junit.jupiter.api.BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void approveEditRejectLifecyclePreservesOriginalContent() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);

        var edited = reviewService.edit(candidate.id(), "Human edited liability language.", null, null);
        assertThat(edited.reviewStatus()).isEqualTo(ReviewStatus.EDITED);
        assertThat(edited.editedContentText()).isEqualTo("Human edited liability language.");
        assertThat(edited.contentText())
                .isEqualTo("The total liability of Supplier shall not exceed the fees paid in the preceding 12 months.");
        assertThat(edited.effectiveContentText()).isEqualTo("Human edited liability language.");

        var approved = reviewService.approve(edited.id(), "Looks right");
        assertThat(approved.reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(approved.effectiveContentText()).isEqualTo("Human edited liability language.");

        var rejected = reviewService.reject(approved.id(), "Changed my mind");
        assertThat(rejected.reviewStatus()).isEqualTo(ReviewStatus.REJECTED);
        assertThat(rejected.reviewNote()).isEqualTo("Changed my mind");
    }

    @Test
    void publishedCandidatesAreImmutable() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);
        reviewService.approve(candidate.id(), null);
        var publication = publicationService.publish(List.of(candidate.id()), "v1");

        assertThat(publication.publicationNumber()).isEqualTo(1);
        assertThat(publication.items()).hasSize(1);
        assertThat(publication.items().get(0).action()).isEqualTo(PublicationAction.ADD);

        var published = queryService.getCandidate(candidate.id());
        assertThat(published.reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);

        assertThatThrownBy(() -> reviewService.approve(candidate.id(), null))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
        assertThatThrownBy(() -> reviewService.edit(candidate.id(), "mutate", null, null))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
        assertThatThrownBy(() -> reviewService.reject(candidate.id(), null))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
        assertThatThrownBy(() -> publicationService.publish(List.of(candidate.id()), null))
                .isInstanceOf(InvalidPublicationException.class);
    }

    @Test
    void publicationsAreNumberedAndReproducible() throws Exception {
        var first = ingestClauseLibrary();
        var second = ingestClauseLibrary();

        var candidateA = firstVariant(first);
        var candidateB = firstVariant(second);
        reviewService.approve(candidateA.id(), null);
        reviewService.approve(candidateB.id(), null);

        var publication1 = publicationService.publish(List.of(candidateA.id()), "initial");
        var publication2 = publicationService.publish(List.of(candidateB.id()), null);
        assertThat(publication2.publicationNumber()).isEqualTo(2);

        var reloaded = queryService.getPublication(publication2.id());
        assertThat(reloaded.items()).hasSize(1);
        assertThat(reloaded.items().get(0).candidateId()).isEqualTo(candidateB.id());
        assertThat(reloaded.createdAt()).isEqualTo(publication2.createdAt());
        assertThat(queryService.listPublications())
                .extracting(KnowledgePublication::publicationNumber)
                .containsExactlyInAnyOrder(2L, 1L);

        var earlier = queryService.getCandidate(candidateA.id());
        assertThat(earlier.effectiveContentText()).isEqualTo(firstContent());
    }

    @Test
    void publicationRejectsMixedInvalidCandidates() throws Exception {
        var artifact = ingestClauseLibrary();
        var pending = firstVariant(artifact);
        var rejected = firstVariant(ingestClauseLibrary());

        reviewService.reject(rejected.id(), null);

        var publicationsBefore = queryService.listPublications().size();
        assertThatThrownBy(() -> publicationService.publish(
                List.of(pending.id(), rejected.id()), null))
                .isInstanceOf(InvalidPublicationException.class);
        assertThat(queryService.listPublications()).hasSize(publicationsBefore);
    }

    @Test
    void crossOrganizationPublicationIsRejected() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);
        reviewService.approve(candidate.id(), null);

        var ownerIdentity = identityProvider.current;
        identityProvider.use(new UserIdentity(
                ownerIdentity.userId(), UUID.randomUUID(), Set.of("ADMIN")));
        try {
            assertThatThrownBy(() -> publicationService.publish(List.of(candidate.id()), null))
                    .isInstanceOf(KnowledgeCandidateNotFoundException.class);
        } finally {
            identityProvider.use(ownerIdentity);
        }
    }

    private KnowledgeArtifact ingestClauseLibrary() {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/clause-library.docx")) {
            assertThat(stream).isNotNull();
            return ingestionService.ingest("library.docx", "application/octet-stream",
                    ArtifactType.CLAUSE_LIBRARY, null, stream);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private KnowledgeCandidate firstVariant(KnowledgeArtifact artifact) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.CLAUSE_VARIANT)
                .findFirst().orElseThrow();
    }

    private String firstContent() {
        return "The total liability of Supplier shall not exceed the fees paid in the preceding 12 months.";
    }
}
