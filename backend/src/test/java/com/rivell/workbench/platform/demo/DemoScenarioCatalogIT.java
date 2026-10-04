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

import com.rivell.workbench.knowledge.IngestStatus;
import com.rivell.workbench.knowledge.KnowledgeArtifact;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.knowledge.ReviewStatus;
import com.rivell.workbench.redline.ApprovedChangeSet;
import com.rivell.workbench.redline.ApprovedChangeSetRepository;
import com.rivell.workbench.redline.RedlineArtifactUnavailableException;
import com.rivell.workbench.redline.RedlineExecutionService;
import com.rivell.workbench.redline.RedlineExecutionStatus;
import com.rivell.workbench.redline.RedlineFailureCode;
import com.rivell.workbench.review.Finding;
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
            var resolved = analysisRepository.findEvidenceItems(organizationId(), runId, cited);
            assertThat(resolved)
                    .as("%s must resolve every cited evidence item", filename)
                    .hasSize(cited.size());
        }
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

    @Test
    void rerunningTheSeedIsIdempotent() {
        var before = counts();
        var summary = seeder.seed();
        var after = counts();
        assertThat(after).as("a rerun must not add rows").isEqualTo(before);

        assertThat(summary.contracts()).isEqualTo(14);
        assertThat(summary.reviews()).isEqualTo(12);
        assertThat(summary.findings()).isGreaterThan(0);
        assertThat(summary.knowledgeSources()).isEqualTo(5);
        assertThat(summary.publications()).isEqualTo(2);
        assertThat(summary.escalationTriggerPublished()).isTrue();
        assertThat(summary.runtimeMillis()).isGreaterThanOrEqualTo(0);

        var duplicateReviews = jdbc.sql("""
                select count(*) from (
                  select contract_id, contract_version_number, count(*) as n
                  from review group by contract_id, contract_version_number having count(*) > 1
                ) duplicates
                """).query(Long.class).single();
        assertThat(duplicateReviews).as("no contract may accumulate duplicate reviews").isZero();
    }

    private Map<String, Long> counts() {
        return Map.of(
                "contract", count("contract"),
                "review", count("review"),
                "finding", count("finding"),
                "human_decision", count("human_decision"),
                "redline_execution", count("redline_execution"),
                "knowledge_artifact", count("knowledge_artifact"),
                "knowledge_publication", count("knowledge_publication"));
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
