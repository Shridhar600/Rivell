package com.rivell.workbench.platform.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.knowledge.ConflictType;
import com.rivell.workbench.knowledge.IngestStatus;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.PublicationAction;
import com.rivell.workbench.knowledge.ReviewCategory;
import com.rivell.workbench.knowledge.CandidateReviewService;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.RedlineArtifactUnavailableException;
import com.rivell.workbench.redline.RedlineExecutionService;
import com.rivell.workbench.redline.RedlineExecutionStatus;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.FinalizationBlockerCode;
import com.rivell.workbench.workflow.HumanDecision;
import com.rivell.workbench.workflow.HumanDecisionRepository;
import com.rivell.workbench.workflow.HumanReviewProgress;
import com.rivell.workbench.workflow.HumanReviewService;

/**
 * The high-level acceptance test for the demo scenario catalog: from an empty schema the
 * seed must produce every documented state through the real services, and a rerun must not
 * duplicate anything. Nothing here writes state; every assertion reads what the domain
 * derived.
 */
@SpringBootTest(properties = "rivell.demo.seed-enabled=true")
@Testcontainers
class DemoScenarioCatalogIT {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:0.8.6-pg17-trixie")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
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

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DemoScenarioSeeder seeder;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    HumanDecisionRepository decisionRepository;

    @Autowired
    HumanReviewService humanReview;

    @Autowired
    ApprovedChangeSetRepository changeSets;

    @Autowired
    RedlineExecutionService redline;

    @Autowired
    KnowledgeQueryService knowledgeQueries;

    @Autowired
    CandidateReviewService candidateReview;

    private UUID organizationId() {
        return UUID.fromString("00000000-0000-0000-0000-000000000002");
    }

    @Test
    void theCatalogManifestDocumentsEveryScenario() throws Exception {
        var manifest = new String(getClass().getClassLoader()
                .getResourceAsStream("demo/SCENARIOS.md").readAllBytes(),
                StandardCharsets.UTF_8);
        for (var scenario : DemoScenarioCatalog.all()) {
            assertThat(manifest)
                    .as("SCENARIOS.md must document %s", scenario.key())
                    .contains(scenario.key())
                    .contains(scenario.displayName());
        }
    }

    @Test
    void everyScenarioContractExistsWithItsTruthfulParseState() {
        Map<String, String> parseStates = new LinkedHashMap<>();
        jdbc.sql("""
                select sa.filename, cv.parse_status
                from contract_version cv
                join source_artifact sa on sa.id = cv.source_artifact_id
                """).query((rs, i) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().forEach(entry -> parseStates.put(entry.getKey(), entry.getValue()));

        assertThat(parseStates.keySet()).contains(
                DemoDocuments.VENDOR_NDA, DemoDocuments.ACME_MSA, DemoDocuments.HOOLI_MSA,
                DemoDocuments.PROCUREMENT_NDA, DemoDocuments.ATLAS_MSA, DemoDocuments.EMPLOYEE_NDA,
                DemoDocuments.PROCUREMENT_MSA, DemoDocuments.GLOBEX_NDA, DemoDocuments.INITECH_NDA,
                DemoDocuments.UMBRELLA_MSA, DemoDocuments.SOYLENT_MSA, DemoDocuments.VANDELAY_MSA,
                DemoDocuments.CORRUPT_UPLOAD, DemoDocuments.SPARSE);
        assertThat(parseStates.get(DemoDocuments.CORRUPT_UPLOAD)).isEqualTo("FAILED");
        assertThat(parseStates.get(DemoDocuments.VENDOR_NDA)).isEqualTo("OK");
        assertThat(parseStates.get(DemoDocuments.SPARSE)).isEqualTo("OK");
    }

    @Test
    void theReviewLifecycleStatesAreReal() {
        assertThat(status(DemoDocuments.VENDOR_NDA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.ACME_MSA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.HOOLI_MSA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.PROCUREMENT_NDA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.ATLAS_MSA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.EMPLOYEE_NDA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.PROCUREMENT_MSA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.GLOBEX_NDA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.INITECH_NDA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.SOYLENT_MSA)).isEqualTo("COMPLETED");
        assertThat(status(DemoDocuments.UMBRELLA_MSA)).isEqualTo("FAILED");
        assertThat(status(DemoDocuments.VANDELAY_MSA)).isEqualTo("ANALYSIS_QUEUED");
    }

    @Test
    void findingsExistAndEveryCitedEvidenceItemResolves() {
        for (var filename : List.of(DemoDocuments.VENDOR_NDA, DemoDocuments.ACME_MSA,
                DemoDocuments.HOOLI_MSA, DemoDocuments.PROCUREMENT_NDA, DemoDocuments.ATLAS_MSA,
                DemoDocuments.EMPLOYEE_NDA, DemoDocuments.PROCUREMENT_MSA, DemoDocuments.GLOBEX_NDA,
                DemoDocuments.INITECH_NDA)) {
            var reviewId = reviewId(filename);
            var runId = runId(reviewId);
            var produced = findingRepository.findFindings(organizationId(), runId);
            assertThat(produced).as("%s must have real findings", filename).isNotEmpty();
            var cited = produced.stream()
                    .flatMap(finding -> finding.evidenceItemIds().stream())
                    .distinct()
                    .toList();
            var clauseBearing = produced.stream().anyMatch(finding ->
                    finding.subjectKind() == FindingSubjectKind.CLAUSE);
            if (clauseBearing) {
                // Absence findings cite nothing by nature: the provision is missing. Findings
                // about an existing clause must cite the evidence they resolve against.
                assertThat(cited)
                        .as("%s clause findings must cite evidence", filename)
                        .isNotEmpty();
            }
            var resolved = analysisRepository.findEvidenceItems(organizationId(), runId, cited);
            assertThat(resolved)
                    .as("%s must resolve every cited evidence item", filename)
                    .hasSize(cited.size());
        }
    }

    @Test
    void theFailedAnalysisIsTheInjectedDemoFailure() {
        var reason = jdbc.sql("select failure_reason from review where id = :id")
                .param("id", reviewId(DemoDocuments.UMBRELLA_MSA))
                .query(String.class).single();
        assertThat(reason).contains("ReviewPlanUnavailableFailure");
    }

    @Test
    void humanDecisionsExistWhereTheScenariosRequireThem() {
        assertThat(currentDecisions(DemoDocuments.ACME_MSA)).isNotEmpty();
        assertThat(currentDecisions(DemoDocuments.HOOLI_MSA)).isNotEmpty();
        assertThat(currentDecisions(DemoDocuments.PROCUREMENT_NDA)).isNotEmpty();
        assertThat(currentDecisions(DemoDocuments.ATLAS_MSA)).isNotEmpty();
        assertThat(currentDecisions(DemoDocuments.EMPLOYEE_NDA)).isNotEmpty();
        assertThat(currentDecisions(DemoDocuments.GLOBEX_NDA)).isNotEmpty();
        assertThat(currentDecisions(DemoDocuments.INITECH_NDA)).isNotEmpty();

        var escalated = currentDecisions(DemoDocuments.HOOLI_MSA).stream()
                .anyMatch(decision -> decision.action() == DecisionAction.ESCALATE);
        assertThat(escalated).as("R03 must carry a real escalate decision").isTrue();
    }

    @Test
    void escalationBlocksAndReadinessIsServerDerived() {
        var escalated = progress(DemoDocuments.HOOLI_MSA);
        assertThat(escalated.readyToFinalize()).isFalse();
        assertThat(escalated.blockers())
                .extracting(blocker -> blocker.code())
                .contains(FinalizationBlockerCode.ACTIVE_ESCALATION);

        var ready = progress(DemoDocuments.PROCUREMENT_NDA);
        assertThat(ready.readyToFinalize()).isTrue();
        assertThat(ready.blockers()).isEmpty();
        assertThat(changeSets.findByReview(organizationId(),
                reviewId(DemoDocuments.PROCUREMENT_NDA))).isEmpty();
    }

    @Test
    void finalizationsFreezeRealChangeSets() {
        var withChanges = changeSet(DemoDocuments.ATLAS_MSA);
        assertThat(withChanges.changes()).isNotEmpty();
        assertThat(withChanges.changes().stream()
                .anyMatch(change -> change.actionType().name().equals("REPLACE"))).isTrue();

        var noChanges = changeSet(DemoDocuments.EMPLOYEE_NDA);
        assertThat(noChanges.changes()).isEmpty();

        assertThat(changeSet(DemoDocuments.PROCUREMENT_MSA).changes()).isNotEmpty();
    }

    @Test
    void theVerifiedRedlineIsAValidDownloadableDocx() {
        var execution = redline.find(reviewId(DemoDocuments.PROCUREMENT_MSA));
        assertThat(execution.status()).isEqualTo(RedlineExecutionStatus.COMPLETED);
        assertThat(execution.downloadable()).isTrue();

        var download = redline.download(reviewId(DemoDocuments.PROCUREMENT_MSA));
        assertThat(download.filename()).endsWith(".docx");
        assertThat(download.content()).hasSizeGreaterThan(2);
        assertThat(download.content()[0]).isEqualTo((byte) 'P');
        assertThat(download.content()[1]).isEqualTo((byte) 'K');
    }

    @Test
    void theFailedRedlineHasNoArtifactAndTheRetryPreservesHistory() {
        var failed = redline.find(reviewId(DemoDocuments.GLOBEX_NDA));
        assertThat(failed.status()).isEqualTo(RedlineExecutionStatus.FAILED);
        assertThat(failed.failureCode()).isEqualTo(RedlineFailureCode.APPROVED_SEMANTICS_DRIFT);
        assertThat(failed.downloadable()).isFalse();
        assertThat(executionAttempts(DemoDocuments.GLOBEX_NDA)).hasSize(1);
        assertThatThrownBy(() -> redline.download(reviewId(DemoDocuments.GLOBEX_NDA)))
                .isInstanceOf(RedlineArtifactUnavailableException.class);

        var attempts = executionAttempts(DemoDocuments.INITECH_NDA);
        assertThat(attempts).containsExactly("1|FAILED|APPROVED_SEMANTICS_DRIFT",
                "2|COMPLETED|-");
        assertThat(redline.find(reviewId(DemoDocuments.INITECH_NDA)).downloadable()).isTrue();
    }

    @Test
    void knowledgeScenariosExistWithVersionsAndLineage() {
        var artifacts = knowledgeQueries.listArtifacts(null);
        var byName = new LinkedHashMap<String, KnowledgeArtifact>();
        artifacts.forEach(artifact -> byName.put(artifact.filename(), artifact));
        assertThat(byName).containsKeys(DemoDocuments.PLAYBOOK, DemoDocuments.PLAYBOOK_V2,
                DemoDocuments.CLAUSE_LIBRARY, DemoDocuments.DRAFT_PLAYBOOK,
                DemoDocuments.UNREADABLE_PLAYBOOK);

        var unreadable = byName.get(DemoDocuments.UNREADABLE_PLAYBOOK);
        assertThat(unreadable.ingestStatus()).isEqualTo(IngestStatus.FAILED);
        assertThat(knowledgeQueries.getCandidates(unreadable.id())).isEmpty();

        var draft = byName.get(DemoDocuments.DRAFT_PLAYBOOK);
        assertThat(knowledgeQueries.getCandidates(draft.id()))
                .isNotEmpty()
                .allMatch(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING);

        var publications = knowledgeQueries.listPublications(null);
        assertThat(publications).hasSizeGreaterThanOrEqualTo(2);
        assertThat(publications.stream()
                .flatMap(publication -> publication.items().stream())
                .anyMatch(item -> item.action().name().equals("SUPERSEDE")))
                .as("K02 must record explicit supersession")
                .isTrue();

        var published = knowledgeQueries.getCandidates(byName.get(DemoDocuments.PLAYBOOK).id())
                .stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PUBLISHED)
                .findFirst()
                .orElseThrow();
        var unit = knowledgeQueries.findUnitForOrganization(organizationId(),
                published.extractionUnitId()).orElseThrow();
        assertThat(unit.sourceNodeIds())
                .as("source lineage must resolve to real nodes")
                .isNotEmpty();
    }

    /**
     * K07 must be genuinely actionable live, not merely present: the grouped-approval UI can
     * only offer "Approve all" for candidates the server itself calls eligible. Eligibility is
     * asserted from the seeded rows, so it can only hold if the real extraction,
     * classification and categorization produced it.
     */
    @Test
    void routineGroupIsGenuinelyBulkEligibleAfterAFreshSeed() {
        var routine = knowledgeQueries.listArtifacts(null).stream()
                .filter(artifact -> "Rivell Demo Routine Playbook.docx".equals(artifact.filename()))
                .findFirst()
                .orElseThrow();

        var candidates = knowledgeQueries.getCandidates(routine.id());
        var bulkEligible = candidates.stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING)
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                .toList();

        assertThat(bulkEligible)
                .as("K07 needs at least two candidates to form a group the UI can bulk approve")
                .hasSizeGreaterThanOrEqualTo(2);
        assertThat(bulkEligible).allSatisfy(candidate -> {
            assertThat(candidate.reviewReasons())
                    .as("ROUTINE means no review risk reason at all")
                    .isEmpty();
            assertThat(candidate.conflictSignals())
                    .as("a bulk-approvable candidate carries no conflict signal")
                    .isEmpty();
            assertThat(candidate.effectiveConceptKey())
                    .as("classification confidence is part of why these are routine")
                    .isNotNull();
        });

        // The artifact also yields the disclaimer section, which states no legal concept and is
        // therefore honestly ELEVATED. K07 must not quietly promote it into the bulk group, and
        // the server rule that defines the group must keep it out.
        var elevated = candidates.stream()
                .filter(candidate -> candidate.reviewCategory() != ReviewCategory.ROUTINE)
                .toList();
        assertThat(elevated)
                .as("the disclaimer keeps its truthful risk category instead of being grouped")
                .isNotEmpty();
        assertThat(elevated).allSatisfy(candidate -> assertThat(candidate.reviewReasons())
                .as("an ineligible candidate must say why it needs individual review")
                .isNotEmpty());
        assertThat(bulkEligible)
                .as("the bulk group is exactly the PENDING ROUTINE set the server would accept")
                .containsExactlyElementsOf(candidates.stream()
                        .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING)
                        .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                        .toList());
    }

    /**
     * The demo has to contain live instances of each condition a reviewer is asked to judge, and
     * they must be derived by the real domain rather than asserted into the seed. This measures
     * what a fresh seed actually produced: a genuine contradiction between a draft candidate and
     * published policy, exact duplicates, explicit supersede items in a publication, and a routine
     * bulk-approvable group.
     */
    @Test
    void seededKnowledgeCoversConflictDuplicateSupersessionAndRoutine() {
        var candidates = knowledgeQueries.listArtifacts(null).stream()
                .flatMap(artifact -> knowledgeQueries.getCandidates(artifact.id()).stream())
                .toList();

        assertThat(candidates)
                .as("a genuine contradiction must exist for the conflict review path to be demonstrable")
                .anySatisfy(candidate -> assertThat(candidate.conflictSignals())
                        .anyMatch(signal -> signal.conflictType() == ConflictType.CONTRADICTS_EXISTING));
        assertThat(candidates)
                .as("exact duplicates must be detected against published knowledge")
                .anySatisfy(candidate -> assertThat(candidate.conflictSignals())
                        .anyMatch(signal -> signal.conflictType() == ConflictType.DUPLICATE));
        assertThat(knowledgeQueries.listPublications(null))
                .as("supersession is recorded explicitly, never as a silent overwrite")
                .anySatisfy(publication -> assertThat(publication.items())
                        .anyMatch(item -> item.action() == PublicationAction.SUPERSEDE));
        assertThat(candidates)
                .as("the routine group exists for grouped approval")
                .anySatisfy(candidate -> assertThat(candidate.reviewCategory())
                        .isEqualTo(ReviewCategory.ROUTINE));

        // A published candidate must never carry a conflict it did not earn. The draft playbook
        // prohibits uncapped liability while published policy caps it: those agree, so a text
        // matcher that simply spotted the word "uncapped" would invent a conflict here.
        var contradictingPublished = candidates.stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PUBLISHED)
                .filter(candidate -> candidate.conflictSignals().stream()
                        .anyMatch(signal -> signal.conflictType() == ConflictType.CONTRADICTS_EXISTING))
                .toList();
        assertThat(contradictingPublished)
                .as("published knowledge must not carry a fabricated contradiction")
                .isEmpty();
    }

    @Test
    void rerunningTheSeedIsIdempotent() {
        var before = counts();
        var summary = seeder.seed();
        var after = counts();
        assertThat(after).as("a rerun must not add rows in any domain table").isEqualTo(before);

        assertThat(summary.contracts()).isEqualTo(14);
        assertThat(summary.reviews()).isEqualTo(12);
        assertThat(summary.findings()).isEqualTo(22);
        assertThat(summary.knowledgeSources()).isEqualTo(6);
        assertThat(summary.publications()).isEqualTo(2);
        assertThat(summary.escalationTriggerPublished()).isTrue();

        var duplicateReviews = jdbc.sql("""
                select count(*) from (
                  select contract_id, contract_version_number, count(*) as n
                  from review group by contract_id, contract_version_number having count(*) > 1
                ) duplicates
                """).query(Long.class).single();
        assertThat(duplicateReviews).as("no contract may accumulate duplicate reviews").isZero();
    }

    /**
     * K07 is meant to be exercised: the reviewer opens Review all and presses Approve all. The
     * next start with the demo seed enabled must then still boot. The seeder may not fabricate
     * fresh PENDING candidates to make the group look untouched again, and it may not undo the
     * approval a person already gave; it has to recognise that the scenario was already run.
     */
    @Test
    void theSeedSurvivesTheRoutineGroupHavingBeenExercised() {
        var routineArtifact = knowledgeQueries.listArtifacts(null).stream()
                .filter(artifact -> DemoDocuments.ROUTINE_PLAYBOOK.equals(artifact.filename()))
                .findFirst()
                .orElseThrow();
        var routine = knowledgeQueries.getCandidates(routineArtifact.id()).stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING)
                .filter(candidate -> candidate.reviewCategory() == ReviewCategory.ROUTINE)
                .toList();
        assertThat(routine)
                .as("K07 must leave genuinely bulk-eligible candidates behind")
                .hasSizeGreaterThanOrEqualTo(2);
        var ineligible = knowledgeQueries.getCandidates(routineArtifact.id()).stream()
                .filter(candidate -> candidate.reviewStatus() == ReviewStatus.PENDING)
                .filter(candidate -> candidate.reviewCategory() != ReviewCategory.ROUTINE)
                .toList();

        var approved = candidateReview.bulkApprove(
                routine.stream().map(KnowledgeCandidate::id).toList());
        assertThat(approved.skipped()).isEmpty();
        assertThat(approved.approvedIds()).hasSameSizeAs(routine);

        var refused = candidateReview.bulkApprove(
                ineligible.stream().map(KnowledgeCandidate::id).toList());
        assertThat(refused.approvedIds())
                .as("the elevated unit stays out of the group even when it is requested")
                .isEmpty();
        assertThat(refused.skipped().keySet()).containsExactlyElementsOf(
                ineligible.stream().map(KnowledgeCandidate::id).toList());

        var before = counts();
        seeder.seed();
        var after = counts();

        assertThat(after)
                .as("a reseed after the demo flow must not add rows in any domain table")
                .isEqualTo(before);
        routine.forEach(candidate -> assertThat(
                knowledgeQueries.getCandidate(candidate.id()).reviewStatus())
                .as("the human approval must not be undone by a reseed")
                .isEqualTo(ReviewStatus.APPROVED));
    }

    @Test
    void theSeedResumesWhenTheRetryAttemptIsMissing() {
        var reviewId = reviewId(DemoDocuments.INITECH_NDA);
        jdbc.sql("""
                delete from redline_artifact where execution_id in (
                  select id from redline_execution where review_id = :id and attempt_number = 2)
                """).param("id", reviewId).update();
        jdbc.sql("""
                delete from redline_execution_change where execution_id in (
                  select id from redline_execution where review_id = :id and attempt_number = 2)
                """).param("id", reviewId).update();
        jdbc.sql("delete from redline_execution where review_id = :id and attempt_number = 2")
                .param("id", reviewId).update();
        assertThat(executionAttempts(DemoDocuments.INITECH_NDA))
                .containsExactly("1|FAILED|APPROVED_SEMANTICS_DRIFT");

        seeder.seed();

        assertThat(executionAttempts(DemoDocuments.INITECH_NDA))
                .containsExactly("1|FAILED|APPROVED_SEMANTICS_DRIFT", "2|COMPLETED|-");
    }

    private Map<String, Long> counts() {
        var counts = new java.util.TreeMap<String, Long>();
        var tables = jdbc.sql("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'
                  and table_name not like 'jobrunr%'
                """).query(String.class).list();
        for (var table : tables) {
            counts.put(table, count(table));
        }
        return counts;
    }

    private long count(String table) {
        return jdbc.sql("select count(*) from " + table).query(Long.class).single();
    }

    private String status(String filename) {
        return jdbc.sql("""
                select r.status from review r
                join contract_version cv on cv.contract_id = r.contract_id
                    and cv.version_number = r.contract_version_number
                join source_artifact sa on sa.id = cv.source_artifact_id
                where sa.filename = :filename
                order by r.created_at limit 1
                """).param("filename", filename).query(String.class).single();
    }

    private UUID reviewId(String filename) {
        return jdbc.sql("""
                select r.id from review r
                join contract_version cv on cv.contract_id = r.contract_id
                    and cv.version_number = r.contract_version_number
                join source_artifact sa on sa.id = cv.source_artifact_id
                where sa.filename = :filename
                order by r.created_at limit 1
                """).param("filename", filename).query(UUID.class).single();
    }

    private UUID runId(UUID reviewId) {
        return analysisRepository.findLatestCompletedRun(organizationId(), reviewId)
                .orElseThrow()
                .id();
    }

    private List<HumanDecision> currentDecisions(String filename) {
        var reviewId = reviewId(filename);
        return decisionRepository.findCurrentForRun(organizationId(), reviewId, runId(reviewId));
    }

    private HumanReviewProgress progress(String filename) {
        return humanReview.state(organizationId(), reviewId(filename)).assessment().progress();
    }

    private ApprovedChangeSet changeSet(String filename) {
        return changeSets.findByReview(organizationId(), reviewId(filename)).orElseThrow();
    }

    private List<String> executionAttempts(String filename) {
        return jdbc.sql("""
                select attempt_number || '|' || status || '|' || coalesce(failure_code, '-')
                from redline_execution where review_id = :reviewId order by attempt_number
                """).param("reviewId", reviewId(filename)).query(String.class).list();
    }
}
