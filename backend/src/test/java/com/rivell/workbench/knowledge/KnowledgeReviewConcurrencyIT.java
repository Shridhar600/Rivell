package com.rivell.workbench.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * Reviewer finding 6: the pre-publication state transition is a compare-and-set, and a
 * compare-and-set that matched no row must never be reported as success.
 *
 * <p>The corruption this prevents: publication validates that a candidate is APPROVED, then
 * writes the publication and moves the candidate to PUBLISHED. A concurrent edit in that window
 * moves the candidate to EDITED, so the CAS matched nothing. The write silently affected zero
 * rows, the transaction still committed, and the organization ended up with a publication item for
 * a candidate that was never PUBLISHED - published knowledge that no approval ever covered.
 *
 * <p>The race is reproduced deterministically rather than by timing: the repository is wrapped so
 * that the first compare-and-set performs the competing edit on its own connection and waits for
 * it to commit, which is exactly what a second user's edit does at that moment.
 */
// The scripted provider is what makes semantic compilation succeed without an API key, and only a
// confidently classified candidate is ROUTINE, which is what bulk approval requires. Each test uses
// its own organization, so the demo seed this property also triggers cannot affect the assertions.
@SpringBootTest(properties = "rivell.demo.seed-enabled=true")
@Testcontainers
class KnowledgeReviewConcurrencyIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");
    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

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

    /**
     * Runs the competing edit once, on its own connection and its own transaction, and waits for
     * it to commit. Running it on another thread is what makes it a real competitor rather than
     * part of the transaction it is interrupting - otherwise the edit would roll back together
     * with the publication it invalidated, and nothing would be proven.
     */
    static final class ConcurrentEdit implements Runnable {

        private Runnable action = () -> {
        };
        private boolean armed;

        void arm(Runnable action) {
            this.action = action;
            this.armed = true;
        }

        @Override
        public void run() {
            if (!armed) {
                return;
            }
            armed = false;
            var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                executor.submit(action).get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("the competing edit did not commit", e);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InterceptingRepositoryConfiguration {

        @Bean
        ConcurrentEdit concurrentEdit() {
            return new ConcurrentEdit();
        }

        @Bean
        @Primary
        KnowledgeRepository interceptingKnowledgeRepository(
                @Qualifier("jdbcKnowledgeRepository") KnowledgeRepository delegate,
                ConcurrentEdit concurrentEdit) {
            return new InterceptingKnowledgeRepository(delegate, concurrentEdit);
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
    ConcurrentEdit concurrentEdit;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    @Qualifier("jdbcKnowledgeRepository")
    KnowledgeRepository rawRepository;

    @Autowired
    MutableIdentityProvider identityProvider;

    @TestConfiguration(proxyBeanMethods = false)
    static class IdentityConfiguration {

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

    private UUID organizationId;

    @BeforeEach
    void freshOrganization() {
        identityProvider.current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));
        organizationId = identityProvider.current.organizationId();
    }

    @Test
    void aConcurrentEditDuringPublicationCannotCommitAPublicationItem() throws Exception {
        var candidate = approvedCandidate();
        concurrentEdit.arm(() -> reviewService.edit(candidate.id(), "renegotiated in the cap row",
                null, null, null, "a second user edited this while it was being published"));

        assertThatThrownBy(() -> publicationService.publish(
                List.of(candidate.id()), java.util.Map.of(), "publish the approved cap"))
                .isInstanceOf(InvalidPublicationException.class);

        assertThat(queryService.getCandidate(candidate.id()).reviewStatus())
                .as("the candidate keeps the state the competing edit gave it")
                .isEqualTo(ReviewStatus.EDITED);
        assertThat(jdbc.sql("select count(*) from knowledge_publication where organization_id = :org")
                .param("org", organizationId).query(Long.class).single())
                .as("the publication must not commit against a candidate that was no longer approved")
                .isZero();
        assertThat(jdbc.sql("""
                select count(*) from knowledge_publication_item i
                join knowledge_publication p on p.id = i.publication_id
                where p.organization_id = :org
                """).param("org", organizationId).query(Long.class).single())
                .isZero();
    }

    @Test
    void aConcurrentEditDuringApprovalIsRefusedRatherThanSilentlyDropped() throws Exception {
        var artifact = ingestMatrix();
        var candidate = queryService.getCandidates(artifact.id()).get(0);
        concurrentEdit.arm(() -> reviewService.edit(candidate.id(), "edited by the other user",
                null, null, null, "a second user got there first"));

        assertThatThrownBy(() -> reviewService.approve(candidate.id(), "approve"))
                .isInstanceOf(InvalidCandidateReviewStateException.class);

        assertThat(queryService.getCandidate(candidate.id()).reviewStatus())
                .as("a refused approval must not report success")
                .isEqualTo(ReviewStatus.EDITED);
    }

    /**
     * A compare-and-set lost inside a bulk approval is one candidate's outcome, not the batch's.
     * BulkApprovalResult already reports per-candidate skips, so aborting the whole batch because
     * one candidate moved under the reviewer turned a partial success into a failure and left the
     * other approvals unapplied. The integrity guarantee is unchanged: the candidate that lost the
     * race is not approved.
     */
    @Test
    void aCandidateThatLosesTheRaceIsReportedWithoutAbortingTheBatch() throws Exception {
        var artifact = ingestRoutinePlaybook();
        var routine = queryService.getCandidates(artifact.id()).stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING)
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                .toList();
        assertThat(routine).hasSizeGreaterThanOrEqualTo(2);
        var loser = routine.get(0);
        var winner = routine.get(1);
        concurrentEdit.arm(() -> reviewService.edit(loser.id(), "edited by the other user",
                null, null, null, "a second user got there first"));

        var result = reviewService.bulkApprove(
                java.util.List.of(loser.id(), winner.id()));

        assertThat(result.approvedIds())
                .as("the candidate that did not lose the race must still be approved")
                .containsExactly(winner.id());
        assertThat(result.skipped()).containsOnlyKeys(loser.id());
        assertThat(queryService.getCandidate(loser.id()).reviewStatus())
                .as("integrity: the candidate that lost the race is not approved")
                .isEqualTo(ReviewStatus.EDITED);
        assertThat(queryService.getCandidate(winner.id()).reviewStatus())
                .isEqualTo(ReviewStatus.APPROVED);
    }

    /**
     * The contract the other tests rely on, asserted directly: the compare-and-set reports
     * whether it matched a row, and a caller that ignores that answer is relying on luck.
     */
    @Test
    void aCompareAndSetThatMatchesNoRowReportsFailure() throws Exception {
        var artifact = ingestMatrix();
        var candidate = queryService.getCandidates(artifact.id()).get(0);

        var applied = rawRepository.updateReviewState(
                candidate.id(), organizationId, ReviewStatus.APPROVED, ReviewStatus.PUBLISHED,
                null, null, null, null, candidate.scope(), null, null, null,
                java.time.Instant.now(), candidate.conflictSignals(),
                candidate.reviewCategory(), candidate.reviewReasons());

        assertThat(applied)
                .as("an update whose expected status no longer holds must report failure")
                .isFalse();
        assertThat(queryService.getCandidate(candidate.id()).reviewStatus())
                .as("a failed compare-and-set must not change the row")
                .isEqualTo(ReviewStatus.PENDING);
    }

    private KnowledgeCandidate approvedCandidate() throws Exception {
        var artifact = ingestMatrix();
        var candidate = queryService.getCandidates(artifact.id()).get(0);
        return reviewService.approve(candidate.id(), "approved");
    }

    private KnowledgeArtifact ingestRoutinePlaybook() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("demo/documents/Rivell Demo Routine Playbook.docx")) {
            assertThat(stream).as("demo routine playbook").isNotNull();
            return ingestionService.ingest("routine.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    ArtifactType.PLAYBOOK,
                    new ArtifactManifest(Set.of(), null, null, null, null, null, null, null,
                            "Rivell local demo scenario data"),
                    stream);
        }
    }

    private KnowledgeArtifact ingestMatrix() throws Exception {
        try (var stream = getClass().getClassLoader()
                .getResourceAsStream("fixtures/knowledge-matrix.xlsx")) {
            assertThat(stream).as("fixture knowledge-matrix.xlsx").isNotNull();
            return ingestionService.ingest("matrix.xlsx", XLSX_MEDIA_TYPE,
                    ArtifactType.PLAYBOOK, null, stream);
        }
    }

    /**
     * Forwards to the real repository and runs the competing edit immediately before the first
     * state compare-and-set, which is the one instant the publication path cannot defend against
     * by re-reading.
     */
    private static final class InterceptingKnowledgeRepository implements KnowledgeRepository {

        private final KnowledgeRepository delegate;
        private final ConcurrentEdit concurrentEdit;

        InterceptingKnowledgeRepository(
                KnowledgeRepository delegate, ConcurrentEdit concurrentEdit) {
            this.delegate = delegate;
            this.concurrentEdit = concurrentEdit;
        }

        @Override
        public boolean updateReviewState(
                UUID candidateId, UUID organizationId, ReviewStatus expectedStatus,
                ReviewStatus reviewStatus, String editedContentText, String editedTitle,
                CandidatePayload editedPayload, SemanticFields editedSemanticFields,
                ApplicabilityScope scope, UUID supersedingCandidateId, String reviewNote,
                java.time.Instant reviewedAt, java.time.Instant updatedAt,
                List<ConflictSignal> conflictSignals, ReviewCategory reviewCategory,
                Set<ReviewReason> reviewReasons) {
            concurrentEdit.run();
            return delegate.updateReviewState(candidateId, organizationId, expectedStatus,
                    reviewStatus, editedContentText, editedTitle, editedPayload,
                    editedSemanticFields, scope,
                    supersedingCandidateId, reviewNote, reviewedAt, updatedAt, conflictSignals,
                    reviewCategory, reviewReasons);
        }

        @Override
        public void insertArtifact(KnowledgeArtifact artifact) {
            delegate.insertArtifact(artifact);
        }

        @Override
        public void updateDocumentTitle(UUID artifactId, String documentTitle) {
            delegate.updateDocumentTitle(artifactId, documentTitle);
        }

        @Override
        public void updateIngestOutcome(UUID artifactId, String parserVersion,
                IngestStatus status, List<String> warnings) {
            delegate.updateIngestOutcome(artifactId, parserVersion, status, warnings);
        }

        @Override
        public void updateParseCoverage(UUID artifactId,
                com.rivell.workbench.contracts.ParseCoverage coverage) {
            delegate.updateParseCoverage(artifactId, coverage);
        }

        @Override
        public java.util.Optional<com.rivell.workbench.contracts.ParseCoverage> findParseCoverage(
                UUID artifactId, UUID organizationId) {
            return delegate.findParseCoverage(artifactId, organizationId);
        }

        @Override
        public void updateArtifactAssessment(UUID artifactId, ArtifactTypeAssessment assessment) {
            delegate.updateArtifactAssessment(artifactId, assessment);
        }

        @Override
        public Optional<KnowledgeArtifact> findArtifact(UUID artifactId, UUID organizationId) {
            return delegate.findArtifact(artifactId, organizationId);
        }

        @Override
        public List<KnowledgeArtifact> listArtifacts(UUID organizationId, Integer limit) {
            return delegate.listArtifacts(organizationId, limit);
        }

        @Override
        public Optional<KnowledgeSource> findSource(UUID artifactId, UUID organizationId) {
            return delegate.findSource(artifactId, organizationId);
        }

        @Override
        public void insertNodes(UUID artifactId, List<KnowledgeSourceNode> nodes) {
            delegate.insertNodes(artifactId, nodes);
        }

        @Override
        public void insertUnits(UUID artifactId, List<ExtractionUnit> units) {
            delegate.insertUnits(artifactId, units);
        }

        @Override
        public void insertCandidates(UUID artifactId, List<KnowledgeCandidate> candidates) {
            delegate.insertCandidates(artifactId, candidates);
        }

        @Override
        public List<KnowledgeSourceNode> loadStructure(UUID artifactId, UUID organizationId) {
            return delegate.loadStructure(artifactId, organizationId);
        }

        @Override
        public List<ExtractionUnit> loadUnits(UUID artifactId, UUID organizationId) {
            return delegate.loadUnits(artifactId, organizationId);
        }

        @Override
        public Optional<ExtractionUnit> findUnit(UUID unitId, UUID organizationId) {
            return delegate.findUnit(unitId, organizationId);
        }

        @Override
        public boolean overrideRecordKind(UUID unitId, UUID organizationId,
                KnowledgeRecordKind kind, UUID by, java.time.Instant at) {
            return delegate.overrideRecordKind(unitId, organizationId, kind, by, at);
        }

        @Override
        public List<KnowledgeCandidate> loadCandidates(UUID artifactId, UUID organizationId) {
            return delegate.loadCandidates(artifactId, organizationId);
        }

        @Override
        public Optional<KnowledgeCandidate> findCandidate(UUID candidateId, UUID organizationId) {
            return delegate.findCandidate(candidateId, organizationId);
        }

        @Override
        public boolean updateConceptCorrection(UUID candidateId, UUID organizationId,
                KnowledgeCandidate updated, ReviewStatus expectedStatus, UUID correctedBy,
                java.time.Instant updatedAt) {
            return delegate.updateConceptCorrection(candidateId, organizationId, updated,
                    expectedStatus, correctedBy, updatedAt);
        }

        @Override
        public List<KnowledgeCandidate> findPublishedCandidates(UUID organizationId) {
            return delegate.findPublishedCandidates(organizationId);
        }

        @Override
        public List<PublishedCandidate> findPublishedCandidatesAsOf(PublishedKnowledgeQuery query) {
            return delegate.findPublishedCandidatesAsOf(query);
        }

        @Override
        public List<UUID> organizationsWithPublishedKnowledge() {
            return delegate.organizationsWithPublishedKnowledge();
        }

        @Override
        public long maxPublicationNumber(UUID organizationId) {
            return delegate.maxPublicationNumber(organizationId);
        }

        @Override
        public boolean publicationNumberExists(UUID organizationId, long publicationNumber) {
            return delegate.publicationNumberExists(organizationId, publicationNumber);
        }

        @Override
        public void insertPublication(KnowledgePublication publication) {
            delegate.insertPublication(publication);
        }

        @Override
        public List<KnowledgePublication> listPublications(UUID organizationId, Integer limit) {
            return delegate.listPublications(organizationId, limit);
        }

        @Override
        public Optional<KnowledgePublication> findPublication(
                UUID publicationId, UUID organizationId) {
            return delegate.findPublication(publicationId, organizationId);
        }

        @Override
        public List<PublicationItem> loadPublicationItems(
                UUID publicationId, UUID organizationId) {
            return delegate.loadPublicationItems(publicationId, organizationId);
        }

        @Override
        public long nextPublicationNumber(UUID organizationId) {
            return delegate.nextPublicationNumber(organizationId);
        }
    }
}
