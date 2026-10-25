package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingRepositoryConfiguration {

        @Bean
        @Primary
        KnowledgeRepository failingKnowledgeRepository(KnowledgeRepository delegate) {
            return (KnowledgeRepository) java.lang.reflect.Proxy.newProxyInstance(
                    KnowledgeRepository.class.getClassLoader(),
                    new Class<?>[] {KnowledgeRepository.class},
                    (proxy, method, args) -> {
                        if ("updateReviewState".equals(method.getName())
                                && FailingKnowledgeRepository.armed) {
                            throw new IllegalStateException("simulated persistence failure");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }
    }

    static final class FailingKnowledgeRepository {

        static volatile boolean armed;
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

    @Autowired
    @org.springframework.beans.factory.annotation.Qualifier("jdbcKnowledgeRepository")
    KnowledgeRepository rawRepository;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @org.junit.jupiter.api.BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    /**
     * Review reasons reach persistence as a Set, and an immutable Set's iteration order is not
     * specified - it varies between JVM runs. Persisting the iteration order therefore rewrote
     * the same candidate's row with different bytes after a restart, which is churn on a row that
     * did not change and noise for anything comparing stored rows.
     */
    @Test
    void reviewReasonsArePersistedInAStableOrder() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);
        var organizationId = identityProvider.currentIdentity().organizationId();
        var reasons = new java.util.LinkedHashSet<ReviewReason>(List.of(
                ReviewReason.SUPERSESSION_PROPOSED,
                ReviewReason.DUPLICATE_DETECTED,
                ReviewReason.CONFLICT_DETECTED));
        assertThat(reasons)
                .as("the reasons are supplied in an order that is not alphabetical")
                .containsExactly(ReviewReason.SUPERSESSION_PROPOSED,
                        ReviewReason.DUPLICATE_DETECTED, ReviewReason.CONFLICT_DETECTED);

        rawRepository.updateReviewState(
                candidate.id(), organizationId, ReviewStatus.PENDING, ReviewStatus.EDITED,
                null, null, null, null, candidate.scope(), null, "ordering probe",
                java.time.Instant.now(), java.time.Instant.now(),
                List.of(), ReviewCategory.ELEVATED, reasons);

        var stored = jdbc.sql("select review_reasons from knowledge_candidate where id = :id")
                .param("id", candidate.id())
                .query(String.class)
                .single();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(stored))
                .as("reasons are stored sorted by name, whatever order the set was built in")
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        "[\"CONFLICT_DETECTED\",\"DUPLICATE_DETECTED\",\"SUPERSESSION_PROPOSED\"]"));
    }

    /**
     * DW-088: PostgreSQL timestamptz keeps microseconds. Every transition returns the candidate it
     * wrote, so the returned timestamps must already carry the precision the database keeps. Each
     * step is read back before the next one, because the next transition rewrites the row.
     */
    @Test
    void returnedReviewTimestampsCarryTheTimestampPrecisionTheDatabaseKeeps() throws Exception {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var candidate = firstVariant(ingestClauseLibrary());

        var edited = reviewService.edit(candidate.id(), "Human edited liability language.",
                null, null, null, null);
        assertReturnedMatchesStored(edited, organizationId);
        var approved = reviewService.approve(edited.id(), "Looks right");
        assertReturnedMatchesStored(approved, organizationId);
        var rejected = reviewService.reject(approved.id(), "Changed my mind");
        assertReturnedMatchesStored(rejected, organizationId);
    }

    private void assertReturnedMatchesStored(KnowledgeCandidate returned, UUID organizationId) {
        assertThat(returned.reviewedAt().getNano() % 1000).isZero();
        assertThat(returned.updatedAt().getNano() % 1000).isZero();
        var readBack = rawRepository.findCandidate(returned.id(), organizationId).orElseThrow();
        assertThat(readBack.reviewedAt()).isEqualTo(returned.reviewedAt());
        assertThat(readBack.updatedAt()).isEqualTo(returned.updatedAt());
    }

    @Test
    void approveEditRejectLifecyclePreservesOriginalContent() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);

        var edited = reviewService.edit(candidate.id(), "Human edited liability language.", null, null, null, null);
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

    /**
     * M10 / DW-001: publication numbers are allocated as max + 1 with no lock. Two concurrent
     * publications therefore raced for the same number, and the loser surfaced a raw integrity
     * violation to the user with no route forward except a blind retry. Both must succeed with
     * distinct, consecutive numbers, and the numbering must stay uncorrupt.
     */
    @Test
    void concurrentPublicationsBothSucceedWithDistinctNumbers() throws Exception {
        var first = firstVariant(ingestClauseLibrary());
        reviewService.approve(first.id(), null);
        var second = allVariants(ingestClauseLibrary()).stream()
                .filter(candidate -> !candidate.id().equals(first.id()))
                .findFirst()
                .orElseThrow();
        reviewService.approve(second.id(), null);

        var start = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        List<java.util.concurrent.Callable<KnowledgePublication>> tasks = List.of(
                () -> {
                    start.await();
                    return publicationService.publish(List.of(first.id()), null, "concurrent a");
                },
                () -> {
                    start.await();
                    return publicationService.publish(List.of(second.id()), null, "concurrent b");
                });
        var futures = tasks.stream().map(executor::submit).toList();
        start.countDown();
        List<KnowledgePublication> publications = new ArrayList<>();
        for (var future : futures) {
            publications.add(future.get());
        }
        executor.shutdown();

        assertThat(publications).extracting(KnowledgePublication::publicationNumber)
                .as("concurrent publications must not share a number")
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(publications).extracting(KnowledgePublication::publicationNumber)
                .doesNotHaveDuplicates();
    }

    private List<KnowledgeCandidate> allVariants(KnowledgeArtifact artifact) {
        return queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.candidateType() == CandidateType.CLAUSE_VARIANT)
                .toList();
    }

    @Test
    void publishedCandidatesAreImmutable() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);
        reviewService.approve(candidate.id(), null);
        var publication = publicationService.publish(List.of(candidate.id()), null, "v1");

        assertThat(publication.publicationNumber()).isEqualTo(1);
        assertThat(publication.items()).hasSize(1);
        assertThat(publication.items().get(0).action()).isEqualTo(PublicationAction.ADD);

        var published = queryService.getCandidate(candidate.id());
        assertThat(published.reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);

        assertThatThrownBy(() -> reviewService.approve(candidate.id(), null))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
        assertThatThrownBy(() -> reviewService.edit(candidate.id(), "mutate", null, null, null, null))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
        assertThatThrownBy(() -> reviewService.reject(candidate.id(), null))
                .isInstanceOf(InvalidCandidateReviewStateException.class);
        assertThatThrownBy(() -> publicationService.publish(List.of(candidate.id()), null, null))
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

        var publication1 = publicationService.publish(List.of(candidateA.id()), null, "initial");
        var publication2 = publicationService.publish(List.of(candidateB.id()), null, null);
        assertThat(publication2.publicationNumber()).isEqualTo(2);

        var reloaded = queryService.getPublication(publication2.id());
        assertThat(reloaded.items()).hasSize(1);
        assertThat(reloaded.items().get(0).candidateId()).isEqualTo(candidateB.id());
        assertThat(reloaded.createdAt()).isEqualTo(publication2.createdAt());
        assertThat(queryService.listPublications(null))
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

        var publicationsBefore = queryService.listPublications(null).size();
        assertThatThrownBy(() -> publicationService.publish(
                List.of(pending.id(), rejected.id()), null, null))
                .isInstanceOf(InvalidPublicationException.class);
        assertThat(queryService.listPublications(null)).hasSize(publicationsBefore);
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
            assertThatThrownBy(() -> publicationService.publish(List.of(candidate.id()), null, null))
                    .isInstanceOf(KnowledgeCandidateNotFoundException.class);
        } finally {
            identityProvider.use(ownerIdentity);
        }
    }

    @Test
    void failedPublicationPersistsNothing() throws Exception {
        var artifact = ingestClauseLibrary();
        var candidate = firstVariant(artifact);
        reviewService.approve(candidate.id(), null);
        var publicationsBefore = queryService.listPublications(null).size();

        FailingKnowledgeRepository.armed = true;
        try {
            assertThatThrownBy(() -> publicationService.publish(List.of(candidate.id()), null, "v1"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            FailingKnowledgeRepository.armed = false;
        }

        assertThat(queryService.listPublications(null)).hasSize(publicationsBefore);
        assertThat(queryService.getCandidate(candidate.id()).reviewStatus())
                .isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void humanSupersessionSelectionPersistsOnCandidateAndPublication() throws Exception {
        var first = ingestClauseLibrary();
        var candidateA = firstVariant(first);
        reviewService.approve(candidateA.id(), null);
        publicationService.publish(java.util.List.of(candidateA.id()), null, "v1");

        var second = ingestClauseLibrary();
        var candidateB = firstVariant(second);
        reviewService.approve(candidateB.id(), null);

        var publication = publicationService.publish(
                java.util.List.of(candidateB.id()),
                java.util.Map.of(candidateB.id(), candidateA.id()),
                "B replaces A");

        // publication detail carries the explicit human-authorized SUPERSEDE item
        var reloadedPublication = queryService.getPublication(publication.id());
        assertThat(reloadedPublication.items())
                .anyMatch(item -> item.action() == PublicationAction.SUPERSEDE
                        && item.candidateId().equals(candidateB.id())
                        && item.supersededCandidateId().equals(candidateA.id()));

        // the candidate-level replaces relationship must survive reload
        var reloadedB = queryService.getCandidate(candidateB.id());
        assertThat(reloadedB.supersedingCandidateId()).isEqualTo(candidateA.id());
    }

    @Test
    void crossOrganizationSupersessionIsRejected() throws Exception {
        var first = ingestClauseLibrary();
        var candidateA = firstVariant(first);
        reviewService.approve(candidateA.id(), null);
        publicationService.publish(java.util.List.of(candidateA.id()), null, null);

        // candidate A belongs to the FIRST organization; B is ingested, approved and
        // published in a SECOND organization where A does not exist — the supersession
        // must be rejected (the foreign published candidate is invisible)
        identityProvider.use(new UserIdentity(
                identityProvider.current.userId(),
                java.util.UUID.randomUUID(), java.util.Set.of("ADMIN")));
        var second = ingestClauseLibrary();
        var candidateB = firstVariant(second);
        reviewService.approve(candidateB.id(), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> publicationService.publish(
                java.util.List.of(candidateB.id()),
                java.util.Map.of(candidateB.id(), candidateA.id()), null))
                .isInstanceOf(InvalidPublicationException.class);
    }

    /**
     * A correction that changes wording changes an input the duplicate test actually compares
     * (the effective wording), so the duplicate condition must be re-derived rather than left
     * stale. Before B6 the edit stored the correction and left the ingestion-time DUPLICATE
     * signal in place, so the reviewer and the server both kept reporting a conflict that no
     * longer existed (AD 6.9).
     */
    @Test
    void wordingCorrectionRecomputesAndClearsTheDuplicate() {
        var published = firstVariant(ingestClauseLibrary());
        reviewService.approve(published.id(), null);
        publicationService.publish(List.of(published.id()), null, "v1");

        var duplicate = firstVariant(ingestClauseLibrary());
        assertThat(duplicate.conflictSignals())
                .as("an identical clause library row is an exact duplicate of published knowledge")
                .anyMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);
        assertThat(duplicate.reviewReasons()).contains(ReviewReason.DUPLICATE_DETECTED);

        var resolved = reviewService.edit(duplicate.id(),
                "Liability is uncapped for this engagement.", null, null, null, "no longer a duplicate");

        assertThat(resolved.conflictSignals())
                .as("the duplicate condition must be recomputed from the corrected wording")
                .noneMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);
        assertThat(resolved.reviewReasons()).doesNotContain(ReviewReason.DUPLICATE_DETECTED);

        var reloaded = queryService.getCandidate(duplicate.id());
        assertThat(reloaded.conflictSignals())
                .as("the recomputed condition must be persisted, not just returned")
                .noneMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);
        assertThat(reloaded.reviewReasons()).doesNotContain(ReviewReason.DUPLICATE_DETECTED);
    }

    /**
     * The mirror rule: a correction that touches nothing any condition compares must leave the
     * condition standing. Positions do not participate in exact-duplicate detection, so a
     * positions-only correction is genuinely irrelevant and the duplicate must survive it.
     * Recomputation must not be a blanket "something was edited, clear the flags" reset.
     */
    @Test
    void irrelevantCorrectionLeavesTheDuplicateIntact() {
        var published = firstVariant(ingestClauseLibrary());
        reviewService.approve(published.id(), null);
        publicationService.publish(List.of(published.id()), null, "v1");

        var duplicate = firstVariant(ingestClauseLibrary());
        assertThat(duplicate.conflictSignals())
                .anyMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);

        var edited = reviewService.edit(duplicate.id(), null, null, null,
                new SemanticFields(Set.of(PositionRole.PREFERRED), null, null, null, null),
                "restate as our preferred position");

        assertThat(edited.effectiveContentText())
                .as("the wording the duplicate test compares is untouched")
                .isEqualTo(duplicate.contentText());
        assertThat(edited.conflictSignals())
                .as("an irrelevant correction must not clear a genuine conflict")
                .anyMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);
        assertThat(queryService.getCandidate(duplicate.id()).conflictSignals())
                .anyMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE);
        assertThat(queryService.getCandidate(duplicate.id()).reviewReasons())
                .contains(ReviewReason.DUPLICATE_DETECTED);
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
