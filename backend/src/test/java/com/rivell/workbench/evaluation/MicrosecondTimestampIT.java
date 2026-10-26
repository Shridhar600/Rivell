package com.rivell.workbench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.redline.RedlineExecution;
import com.rivell.workbench.redline.RedlineExecutionRepository;
import com.rivell.workbench.redline.RedlineExecutionService;
import com.rivell.workbench.redline.RedlineOutcome;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;
import com.rivell.workbench.review.Finding;
import com.rivell.workbench.review.FindingClassification;
import com.rivell.workbench.review.FindingRepository;
import com.rivell.workbench.review.FindingSeverity;
import com.rivell.workbench.review.LiabilityReviewIT;
import com.rivell.workbench.review.Review;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewAnalysisRun;
import com.rivell.workbench.review.ReviewAnalysisService;
import com.rivell.workbench.review.ReviewPackRegistry;
import com.rivell.workbench.review.ReviewPlanCheck;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.ReviewService;
import com.rivell.workbench.workflow.AddFindingCommand;
import com.rivell.workbench.workflow.DecisionAction;
import com.rivell.workbench.workflow.DecisionCommand;
import com.rivell.workbench.workflow.HumanDecisionRepository;
import com.rivell.workbench.workflow.HumanDecisionService;
import com.rivell.workbench.workflow.ReviewFinalizationService;
import com.rivell.workbench.workflow.UserFindingService;

/**
 * PostgreSQL keeps microseconds for {@code timestamptz}, so a value returned to the
 * caller must already carry microsecond precision, or it differs from the same value read back
 * later. The context clock is fixed at a nanosecond instant so these assertions fail on any
 * machine until every returned aggregate is lowered to the precision the database stores.
 */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.FakeIntelligenceConfiguration.class,
        LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class,
        MicrosecondTimestampIT.FixedClockConfiguration.class})
class MicrosecondTimestampIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg17-trixie")
            .asCompatibleSubstituteFor("postgres");

    /** A nanosecond instant no platform clock would round on its own. */
    private static final Instant NANOS = Instant.parse("2026-10-19T12:00:00.123456789Z");
    private static final Instant MICROS = NANOS.truncatedTo(ChronoUnit.MICROS);

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
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NANOS, ZoneOffset.UTC);
        }
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    FindingRepository findingRepository;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    HumanDecisionService decisionService;

    @Autowired
    HumanDecisionRepository decisionRepository;

    @Autowired
    UserFindingService userFindingService;

    @Autowired
    ReviewFinalizationService finalizationService;

    @Autowired
    RedlineExecutionService redlineService;

    @Autowired
    RedlineExecutionRepository executionRepository;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    @Test
    void aCreatedReviewIsReturnedWithThePrecisionTheDatabaseKeeps() {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());

        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        assertThat(review.createdAt()).isEqualTo(MICROS);
        assertThat(review.updatedAt()).isEqualTo(MICROS);
        var readBack = reviewRepository.find(organizationId(), review.id()).orElseThrow();
        assertThat(readBack.createdAt()).isEqualTo(review.createdAt());
        assertThat(readBack.updatedAt()).isEqualTo(review.updatedAt());
    }

    @Test
    void anAddedFindingAndADecisionAreReturnedWithThePrecisionTheDatabaseKeeps() {
        var fixture = analyzedMainCorpus();
        var heading = reviewRepository.findPlanChecks(organizationId(), fixture.review().id())
                .stream().map(ReviewPlanCheck::subjectHeadingNodeId)
                .filter(Objects::nonNull).findFirst().orElseThrow();

        var finding = userFindingService.add(fixture.review().id(),
                new AddFindingCommand(heading, FindingSeverity.HIGH, "Rivell missed the notice window."));
        assertThat(finding.createdAt()).isEqualTo(MICROS);
        var storedFinding = findingRepository.findFinding(
                organizationId(), fixture.review().id(), fixture.run().id(), finding.id())
                .orElseThrow();
        assertThat(storedFinding.createdAt()).isEqualTo(finding.createdAt());

        var decision = decisionService.decide(fixture.review().id(), finding.id(),
                new DecisionCommand(0, null, DecisionAction.REJECT, null, null,
                        "Withdrawn for this test."));
        assertThat(decision.decidedAt()).isEqualTo(MICROS);
        var storedDecision = decisionRepository
                .findCurrent(organizationId(), fixture.review().id(), finding.id())
                .orElseThrow();
        assertThat(storedDecision.decidedAt()).isEqualTo(decision.decidedAt());
    }

    @Test
    void aRedlineExecutionIsReturnedWithThePrecisionTheDatabaseKeeps() {
        var fixture = analyzedMainCorpus();
        for (var finding : fixture.findings()) {
            if (finding.reviewClassification() == FindingClassification.INFORMATIONAL) {
                continue;
            }
            decisionService.decide(fixture.review().id(), finding.id(),
                    new DecisionCommand(0, null, DecisionAction.REJECT, null, null,
                            "Not required for this test."));
        }
        finalizationService.finalizeReview(fixture.review().id());

        var execution = redlineService.execute(fixture.review().id());

        assertThat(execution.outcome()).isEqualTo(RedlineOutcome.NO_CHANGES);
        assertThat(execution.startedAt()).isEqualTo(MICROS);
        assertThat(execution.completedAt()).isEqualTo(MICROS);
        var readBack = executionRepository
                .findByReview(organizationId(), fixture.review().id()).orElseThrow();
        assertThat(readBack.startedAt()).isEqualTo(execution.startedAt());
        assertThat(readBack.completedAt()).isEqualTo(execution.completedAt());
    }

    private record Fixture(Review review, ReviewAnalysisRun run, List<Finding> findings) {
    }

    private Fixture analyzedMainCorpus() {
        var corpus = corpusBuilder.buildMainCorpus(organizationId());
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);
        var run = analysisService.analyze(organizationId(), review.id());
        return new Fixture(review, run,
                findingRepository.findFindings(organizationId(), run.id()));
    }
}
