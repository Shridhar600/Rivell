package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.rivell.workbench.contracts.ContractEnrichmentIT;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.ReasoningSpec;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

/** R3: a review that spends its wall-clock budget still completes, every remaining check degraded. */
@SpringBootTest
@Testcontainers
@Import({LiabilityReviewIT.CorpusConfiguration.class,
        LiabilityReviewIT.MutableIdentityConfiguration.class})
@TestPropertySource(properties = "rivell.review.analysis-budget=30m")
class ReviewDeadlineIT {

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

    static final class BudgetClock extends Clock {

        private Instant instant = Instant.parse("2026-10-24T10:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /**
     * Answers through the ordinary review script, then moves the injected clock past the
     * budget after the first policy comparison, so the next check sees the deadline spent.
     */
    static final class BudgetProvider implements SemanticDecisionProvider {

        private final BudgetClock clock;
        private final LiabilityReviewIT.ReviewScriptedProvider delegate =
                new LiabilityReviewIT.ReviewScriptedProvider();
        private final AtomicInteger comparisons = new AtomicInteger();

        BudgetProvider(BudgetClock clock) {
            this.clock = clock;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            var result = delegate.execute(request);
            if (request.pack().questions().keySet().stream()
                    .anyMatch(name -> name.startsWith("position_"))
                    && comparisons.incrementAndGet() == 1) {
                clock.advance(Duration.ofMinutes(31));
            }
            return result;
        }
    }

    static final class Catalog implements ModelBindingCatalog {

        private final ModelBindings bindings = new ModelBindings(
                new ModelBinding("fake", "fake-decision-model"),
                new ModelBinding("fake", "fake-gen-model"));

        @Override
        public ModelBindings current() {
            return bindings;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BudgetConfiguration {

        @Bean
        @Primary
        BudgetClock budgetClock() {
            return new BudgetClock();
        }

        @Bean
        @Primary
        ModelBindingCatalog budgetModelBindingCatalog() {
            return new Catalog();
        }

        @Bean
        @Primary
        ModelPolicy budgetModelPolicy(ModelBindingCatalog catalog) {
            return asset -> asset instanceof ReasoningSpec
                    ? catalog.current().generativeProvider()
                    : catalog.current().decisionProvider();
        }

        @Bean
        @Primary
        SemanticDecisionProvider budgetSemanticDecisionProvider(BudgetClock clock) {
            return new BudgetProvider(clock);
        }

        @Bean
        @Primary
        GenerativeReasoningProvider budgetGenerativeProvider() {
            return new ContractEnrichmentIT.M5FakeGenerativeProvider();
        }
    }

    @Autowired
    LiabilityReviewIT.MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    ReviewRepository reviewRepository;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
    }

    @Test
    void whenTheBudgetIsSpentTheRemainingChecksDegradeAndTheRunCompletes() {
        var organizationId = identityProvider.currentIdentity().organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(
                corpus.contract().contractId(), 1, ReviewPackRegistry.MSA_PACK);

        var run = analysisService.analyze(organizationId, review.id());

        var checks = reviewRepository.findPlanChecks(organizationId, review.id());
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).hasSize(checks.size());

        var degraded = 0;
        var reachedDegradation = false;
        for (var check : checks) {
            var execution = executions.stream()
                    .filter(candidate -> candidate.planCheckId().equals(check.id()))
                    .findFirst().orElseThrow();
            var reason = execution.detail().get("degradedReason");
            var deadline = "review_deadline_exceeded".equals(reason);
            if (deadline) {
                reachedDegradation = true;
                degraded++;
            } else {
                assertThat(reachedDegradation)
                        .as("no check runs after the budget is spent")
                        .isFalse();
            }
        }
        assertThat(degraded).isPositive().isLessThan(checks.size());
        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
    }
}
