package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

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

import com.rivell.workbench.contracts.ContractIngestionService;
import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.contracts.PaperSource;
import com.rivell.workbench.contracts.PartyRole;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.ChoiceQuestion;
import com.rivell.workbench.intelligence.DecisionExecutionRequest;
import com.rivell.workbench.intelligence.GenerativeReasoningProvider;
import com.rivell.workbench.intelligence.GenerationRequest;
import com.rivell.workbench.intelligence.GenerationResult;
import com.rivell.workbench.intelligence.ModelBinding;
import com.rivell.workbench.intelligence.ModelBindingCatalog;
import com.rivell.workbench.intelligence.ModelBindings;
import com.rivell.workbench.intelligence.ModelPolicy;
import com.rivell.workbench.intelligence.ProviderExecutionResult;
import com.rivell.workbench.intelligence.SemanticAnswer;
import com.rivell.workbench.intelligence.SemanticDecisionProvider;
import com.rivell.workbench.intelligence.TokenUsage;
import com.rivell.workbench.organization.IdentityProvider;
import com.rivell.workbench.organization.UserIdentity;
import com.rivell.workbench.retrieval.CorpusScriptedProvider;
import com.rivell.workbench.retrieval.M6EvidenceCorpus;

@SpringBootTest
@Testcontainers
public class LiabilityReviewIT {

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
    public static class FakeIntelligenceConfiguration {

        @Bean
        @Primary
        ModelBindingCatalog fakeModelBindingCatalog() {
            return () -> new ModelBindings(
                    new ModelBinding("fake", "fake-decision-model"),
                    new ModelBinding("fake", "fake-gen-model"));
        }

        @Bean
        @Primary
        ModelPolicy fakeModelPolicy(ModelBindingCatalog modelBindingCatalog) {
            return asset -> asset instanceof com.rivell.workbench.intelligence.ReasoningSpec
                    ? modelBindingCatalog.current().generativeProvider()
                    : modelBindingCatalog.current().decisionProvider();
        }

        @Bean
        @Primary
        ReviewScriptedProvider reviewScriptedProvider() {
            return new ReviewScriptedProvider();
        }

        @Bean
        @Primary
        ReviewGenerativeProvider reviewGenerativeProvider() {
            return new ReviewGenerativeProvider();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class CorpusConfiguration {

        @Bean
        M6EvidenceCorpus m6EvidenceCorpus(
                com.rivell.workbench.knowledge.KnowledgeIngestionService ingestionService,
                com.rivell.workbench.knowledge.CandidateReviewService reviewService,
                com.rivell.workbench.knowledge.KnowledgePublicationService publicationService,
                com.rivell.workbench.knowledge.KnowledgeQueryService queryService,
                ContractIngestionService contractIngestionService,
                com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService) {
            return new M6EvidenceCorpus(ingestionService, reviewService, publicationService,
                    queryService, contractIngestionService, enrichmentService);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
        }
    }

    public static final class MutableIdentity implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        public void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    public static final class ReviewScriptedProvider implements SemanticDecisionProvider {

        private final CorpusScriptedProvider base = new CorpusScriptedProvider();
        private final AtomicInteger totalDecisions = new AtomicInteger();
        private final AtomicInteger policyComparisonDecisions = new AtomicInteger();
        private final AtomicInteger consistencyDecisions = new AtomicInteger();
        private final java.util.List<ComparisonCall> comparisonCalls =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean verificationSatisfies = true;
        volatile boolean verificationIntroducesRisk;
        volatile Double verificationSatisfiesProbability;
        volatile Double verificationRiskProbability;
        volatile double policyComparisonTopProbability = 0.9;
        volatile boolean failPolicyComparisonDecision;
        volatile String policyComparisonAnswerOverride;
        volatile Map<String, Double> policyComparisonProbabilities;
        volatile boolean failConsistencyDecision;
        volatile String consistencyAnswerOverride;
        volatile double consistencyTopProbability = 0.9;

        /** One policy-comparison call: the subject heading and the question keys asked. */
        public record ComparisonCall(String heading, java.util.List<String> questionKeys) {
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            totalDecisions.incrementAndGet();
            if (request.pack().questions().containsKey(M7DecisionSpecs.SATISFIES_POLICY)) {
                var verificationAnswers = new LinkedHashMap<String, SemanticAnswer>();
                verificationAnswers.put(M7DecisionSpecs.SATISFIES_POLICY,
                        new com.rivell.workbench.intelligence.YesNoAnswer(
                                verificationSatisfiesProbability != null
                                        ? verificationSatisfiesProbability
                                        : verificationSatisfies ? 0.95 : 0.05));
                verificationAnswers.put(M7DecisionSpecs.INTRODUCED_NEW_RISK,
                        new com.rivell.workbench.intelligence.YesNoAnswer(
                                verificationRiskProbability != null
                                        ? verificationRiskProbability
                                        : verificationIntroducesRisk ? 0.95 : 0.05));
                return new ProviderExecutionResult(verificationAnswers, "fake-review-v1",
                        new TokenUsage(120, 5));
            }
            if (request.pack().questions().containsKey(M7DecisionSpecs.CONSISTENCY)) {
                consistencyDecisions.incrementAndGet();
                if (failConsistencyDecision) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "consistency decision provider unavailable");
                }
                if (consistencyAnswerOverride != null) {
                    var override = new LinkedHashMap<String, Double>();
                    var residual = (1.0 - consistencyTopProbability) / 2;
                    for (var option : List.of(M7DecisionSpecs.CONSISTENT,
                            M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY,
                            M7DecisionSpecs.NOT_COMPARABLE_CARVE)) {
                        override.put(option, option.equals(consistencyAnswerOverride)
                                ? consistencyTopProbability : residual);
                    }
                    return new ProviderExecutionResult(
                            new LinkedHashMap<>(Map.of(M7DecisionSpecs.CONSISTENCY,
                                    new ChoiceAnswer(consistencyAnswerOverride,
                                            consistencyTopProbability, override))),
                            "fake-review-v1", new TokenUsage(140, 6));
                }
                var carveState = (M7DecisionSpecs.CarveOutConsistencyState)
                        request.state().payload();
                var liability = carveState.liabilityText().toLowerCase();
                var indemnity = carveState.indemnityText().toLowerCase();
                var indemnityDisclaims = indemnity.contains("without regard")
                        || indemnity.contains("without limitation")
                        || indemnity.contains("unlimited");
                var capExcludesIndemnity = liability.contains("do not apply")
                        || liability.contains("does not apply")
                        || liability.contains("excluded");
                var capStatesItApplies = liability.contains("applies to all")
                        || liability.contains("including indemnification");
                var selected = M7DecisionSpecs.CONSISTENT;
                if (indemnity.isBlank()) {
                    selected = M7DecisionSpecs.NOT_COMPARABLE_CARVE;
                } else if (indemnityDisclaims
                        && (capStatesItApplies || !capExcludesIndemnity)) {
                    selected = M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY;
                }
                var consistency = new LinkedHashMap<String, Double>();
                var residual = (1.0 - consistencyTopProbability) / 2;
                for (var option : List.of(M7DecisionSpecs.CONSISTENT,
                        M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY,
                        M7DecisionSpecs.NOT_COMPARABLE_CARVE)) {
                    consistency.put(option, option.equals(selected)
                            ? consistencyTopProbability : residual);
                }
                var carveAnswers = new LinkedHashMap<String, SemanticAnswer>();
                carveAnswers.put(M7DecisionSpecs.CONSISTENCY,
                        new ChoiceAnswer(selected, consistencyTopProbability, consistency));
                return new ProviderExecutionResult(carveAnswers, "fake-review-v1",
                        new TokenUsage(140, 6));
            }
            var firstQuestion = request.pack().questions().values().iterator().next();
            if (firstQuestion instanceof ChoiceQuestion firstChoice
                    && firstChoice.criteria().containsKey(PolicyComparisonSpecs.WALK_AWAY)) {
                return policyComparison(request);
            }
            return base.execute(request);
        }

        private ProviderExecutionResult policyComparison(DecisionExecutionRequest request) {
            policyComparisonDecisions.incrementAndGet();
            if (failPolicyComparisonDecision) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "policy comparison provider unavailable");
            }
            var state = (PolicyComparisonSpecs.PolicyComparisonState) request.state().payload();
            var clause = state.clause() == null ? "" : state.clause().toLowerCase();
            var initialKeys = new java.util.ArrayList<>(request.pack().questions().keySet());
            java.util.Collections.sort(initialKeys);
            comparisonCalls.add(new ComparisonCall(
                    state.heading() == null ? "" : state.heading(), initialKeys));
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = (ChoiceQuestion) entry.getValue();
                var selected = policyComparisonAnswerOverride != null
                        ? policyComparisonAnswerOverride
                        : policyComparisonSelection(clause, question.instructions());
                var probabilities = policyComparisonProbabilities != null
                        ? new LinkedHashMap<>(policyComparisonProbabilities)
                        : uniformProbabilities(question, selected);
                answers.put(entry.getKey(), new ChoiceAnswer(
                        selected, probabilities.getOrDefault(selected, 0.5), probabilities));
            }
            return new ProviderExecutionResult(answers, "fake-review-v1",
                    new TokenUsage(220, 12));
        }

        private LinkedHashMap<String, Double> uniformProbabilities(
                ChoiceQuestion question, String selected) {
            var probabilities = new LinkedHashMap<String, Double>();
            var residual = (1.0 - policyComparisonTopProbability) / 5;
            for (var option : question.criteria().keySet()) {
                probabilities.put(option, option.equals(selected)
                        ? policyComparisonTopProbability : residual);
            }
            return probabilities;
        }

        /**
         * Answers one position question from the clause and the position text the question
         * carries: uncapped exposure against a prohibition walks away, an explicit multiple
         * inside a listed fallback stays within it, and a clause with no comparable number
         * meets the preferred position.
         */
        private static String policyComparisonSelection(String clause, String instructions) {
            var position = instructions == null ? "" : instructions.toLowerCase();
            if ((clause.contains("uncapped") || clause.contains("unlimited"))
                    && (position.contains("uncapped") || position.contains("unlimited")
                        || position.contains("never accept"))) {
                return PolicyComparisonSpecs.WALK_AWAY;
            }
            var clauseMultiple = multipleOf(clause);
            if (clauseMultiple != null && clauseMultiple > 1.0) {
                var fallback = multipleOf(position);
                var preferred = position.contains("fees paid") ? 1.0 : null;
                if (preferred != null && clauseMultiple <= preferred) {
                    return PolicyComparisonSpecs.MEETS_PREFERRED;
                }
                if (fallback != null && clauseMultiple <= fallback) {
                    return PolicyComparisonSpecs.WITHIN_FALLBACK;
                }
                if (fallback != null || preferred != null) {
                    return PolicyComparisonSpecs.BEYOND_FALLBACK;
                }
            }
            return PolicyComparisonSpecs.MEETS_PREFERRED;
        }

        private static Double multipleOf(String text) {
            var matcher = java.util.regex.Pattern
                    .compile("\\b(\\d+(?:\\.\\d+)?)\\s*times").matcher(text);
            if (matcher.find()) {
                return Double.valueOf(matcher.group(1));
            }
            var word = java.util.regex.Pattern
                    .compile("\\b(one|two|three|four|five|six)(?:\\s*\\(\\d+\\))?\\s*times")
                    .matcher(text);
            if (word.find()) {
                return switch (word.group(1)) {
                    case "one" -> 1.0;
                    case "two" -> 2.0;
                    case "three" -> 3.0;
                    case "four" -> 4.0;
                    case "five" -> 5.0;
                    default -> 6.0;
                };
            }
            return null;
        }

        int policyComparisonDecisions() {
            return policyComparisonDecisions.get();
        }

        public java.util.List<ComparisonCall> policyComparisonCalls() {
            return java.util.List.copyOf(comparisonCalls);
        }

        int consistencyDecisions() {
            return consistencyDecisions.get();
        }

        public int totalDecisions() {
            return totalDecisions.get();
        }

        public void reset() {
            totalDecisions.set(0);
            policyComparisonDecisions.set(0);
            comparisonCalls.clear();
            consistencyDecisions.set(0);
            verificationSatisfies = true;
            verificationIntroducesRisk = false;
            verificationSatisfiesProbability = null;
            verificationRiskProbability = null;
            policyComparisonTopProbability = 0.9;
            failPolicyComparisonDecision = false;
            policyComparisonAnswerOverride = null;
            policyComparisonProbabilities = null;
            failConsistencyDecision = false;
            consistencyAnswerOverride = null;
            consistencyTopProbability = 0.9;
        }
    }

    public static final class ReviewGenerativeProvider implements GenerativeReasoningProvider {

        private final com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider base =
                new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
        private final AtomicInteger calls = new AtomicInteger();
        volatile boolean failOpenEnded;
        volatile String openEndedProposed;
        volatile String openEndedExplanation;

        @Override
        public String id() {
            return "fake";
        }

        int calls() {
            return calls.get();
        }

        public int generations() {
            return calls.get();
        }

        public void reset() {
            calls.set(0);
            failOpenEnded = false;
            openEndedProposed = null;
            openEndedExplanation = null;
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            calls.incrementAndGet();
            if (request.spec().fields().containsKey("proposedLanguage")) {
                if (failOpenEnded) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "generative provider unavailable");
                }
                var proposed = openEndedProposed != null ? openEndedProposed
                        : "Supplier's aggregate liability shall not exceed two times the "
                                + "fees paid in the preceding 12 months.";
                var explanation = openEndedExplanation != null ? openEndedExplanation
                        : "Caps the liability at the policy-preferred basis while removing "
                                + "the indeterminate insurance-proceeds structure.";
                return new GenerationResult(Map.of(
                        "proposedLanguage", proposed,
                        "explanation", explanation),
                        "fake-gen-v1", new TokenUsage(160, 40));
            }
            if (request.spec().fields().containsKey("escalationTrigger")) {
                if (request.state().payload() instanceof Map<?, ?> payload
                        && payload.get("text") instanceof String text) {
                    var matcher = Pattern.compile("([^.]*[Ee]scalat[^.]*)\\.",
                            Pattern.CASE_INSENSITIVE).matcher(text);
                    if (matcher.find()) {
                        return new GenerationResult(
                                Map.of("escalationTrigger", matcher.group(1).trim()),
                                "fake-gen-v1", new TokenUsage(70, 10));
                    }
                }
                throw new com.rivell.workbench.intelligence.ProviderRejectedException(
                        "no escalation trigger stated");
            }
            if (request.state().payload() instanceof Map<?, ?> payload
                    && payload.get("text") instanceof String text
                    && !Pattern.compile("([a-z]+) times the", Pattern.CASE_INSENSITIVE)
                            .matcher(text).find()
                    && text.contains("twelve (12) months")
                    && text.contains("shall not exceed")) {
                return new GenerationResult(Map.of(
                        "liabilityCapMultiple", "1",
                        "capBasis", "PRECEDING_12_MONTH_FEES"),
                        "fake-gen-v1", new TokenUsage(90, 8));
            }
            return base.generate(request);
        }
    }

    @Autowired
    MutableIdentity identityProvider;

    @Autowired
    ReviewService reviewService;

    @Autowired
    ReviewAnalysisService analysisService;

    @Autowired
    ReviewAnalysisRepository analysisRepository;

    @Autowired
    ReviewScriptedProvider scriptedProvider;

    @Autowired
    ReviewGenerativeProvider generativeProvider;

    @Autowired
    M6EvidenceCorpus corpusBuilder;

    @Autowired
    ContractIngestionService contractIngestionService;

    @Autowired
    com.rivell.workbench.contracts.ContractEnrichmentService enrichmentService;

    @BeforeEach
    void freshOrganization() {
        identityProvider.use(new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN")));
        scriptedProvider.reset();
        generativeProvider.reset();
    }

    private UUID organizationId() {
        return identityProvider.currentIdentity().organizationId();
    }

    private Review enrichedReview(String fixture) throws Exception {
        var bytes = Files.readAllBytes(java.nio.file.Path.of(
                "src/test/resources/fixtures", fixture));
        var ingested = contractIngestionService.ingest(fixture,
                ContractIngestionService.DOCX_CONTENT_TYPE, new ByteArrayInputStream(bytes));
        enrichmentService.enrich(ingested.contractId(), 1,
                new ReviewProfile(ContractType.MSA, PartyRole.CUSTOMER,
                        PaperSource.THIRD_PARTY, Jurisdiction.US));
        return reviewService.create(ingested.contractId(), 1, null);
    }

    @Autowired
    ReviewRepository reviewRepository;

    @Test
    void preferredClauseMakesNoFindingAndOnlyMatchedClausesAreCompared() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-preferred.docx");

        var run = analysisService.analyze(organizationId, review.id());

        // the liability, confidentiality and termination clauses share a topic with a
        // published position; the governing-law clause makes no call
        assertThat(scriptedProvider.policyComparisonDecisions()).isEqualTo(3);
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).allSatisfy(execution ->
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED));
        assertThat(executions.stream()
                .filter(execution -> execution.detail().containsKey("positionCount"))
                .filter(execution -> !"0".equals(
                        execution.detail().get("positionCount").toString()))
                .count()).isEqualTo(3);
        assertThat(executions.stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .allMatch(execution -> execution.detail().get("positions").toString()
                        .contains("flagged=false"))).isTrue();
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void capAbovePreferredProducesBeyondFallbackWithDraftingAndEscalation() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(corpus.contract().contractId(), 1, null);
        generativeProvider.reset();

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(generativeProvider.calls()).isZero();
        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).hasSize(3);
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.issueFamily()).isEqualTo(IssueFamily.POSITION_NEEDS_FALLBACK);
            assertThat(signal.subjectKind()).isEqualTo(FindingSubjectKind.CLAUSE);
            assertThat(signal.subjectHeadingNodeId()).isNotNull();
            assertThat(signal.evidenceItemIds()).isNotEmpty();
        });
        var deviation = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        var payload = (PositionDeviationPayload) deviation.payload();
        assertThat(payload.category()).isEqualTo(PolicyComparisonSpecs.BEYOND_FALLBACK);
        assertThat(payload.flagProbability()).isGreaterThanOrEqualTo(0.6);
        assertThat(deviation.decisionSpecId())
                .isEqualTo(PolicyComparisonSpecs.POLICY_COMPARISON_SPEC);
        assertThat(deviation.jevExecutionId()).isNotNull();

        var drafting = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.APPROVED_DRAFTING_AVAILABLE)
                .map(signal -> (ApprovedDraftingPayload) signal.payload())
                .findFirst().orElseThrow();
        assertThat(drafting.evidenceText()).isEqualTo(
                "The total liability of Supplier shall not exceed two times the fees paid "
                        + "in the preceding 12 months.");
        assertThat(drafting.variantRole()).isEqualTo("FALLBACK");

        var escalation = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.ESCALATION_REQUIRED)
                .map(signal -> (EscalationPayload) signal.payload())
                .findFirst().orElseThrow();
        assertThat(escalation.trigger()).isNotBlank();
        assertThat(scriptedProvider.policyComparisonDecisions()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void noPublishedPositionsMakesNoCallAndNoFinding() throws Exception {
        var organizationId = organizationId();
        var review = enrichedReview("m7-msa-preferred.docx");

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(scriptedProvider.policyComparisonDecisions()).isZero();
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).isNotEmpty().allSatisfy(execution -> {
            assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
            assertThat(execution.detail().get("positionCount")).isEqualTo(0);
        });
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void twoApplicablePositionsAreJudgedInOneCallNotAsAmbiguity() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_AMBIGUOUS,
                M6EvidenceCorpus.policyManifest(java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2026, 12, 31)),
                null);
        var review = enrichedReview("m7-msa-uncapped.docx");

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(scriptedProvider.policyComparisonCalls())
                .anySatisfy(call -> assertThat(call.questionKeys())
                        .containsExactly("position_1", "position_2"));
        var signals = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .toList();
        assertThat(signals).hasSize(1);
        assertThat(((PositionDeviationPayload) signals.get(0).payload()).category())
                .isEqualTo(PolicyComparisonSpecs.WALK_AWAY);
    }

    @Test
    void uncappedClauseWalksAwayAndStillProposesApprovedLanguage() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx");
        generativeProvider.reset();

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        var deviation = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        assertThat(((PositionDeviationPayload) deviation.payload()).category())
                .isEqualTo(PolicyComparisonSpecs.WALK_AWAY);
        assertThat(deviation.issueFamily()).isEqualTo(IssueFamily.POSITION_WALK_AWAY);
        assertThat(signals).extracting(RawReviewSignal::signalType)
                .contains(SignalType.APPROVED_DRAFTING_AVAILABLE,
                        SignalType.ESCALATION_REQUIRED);
        var loi = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.detail().containsKey("positions"))
                .filter(execution -> execution.detail().get("positions").toString()
                        .contains("WALK_AWAY"))
                .findFirst().orElseThrow();
        assertThat(loi.status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(generativeProvider.calls()).isZero();
    }

    @Test
    void comparisonProviderFailureIsUncertaintyPerPositionWithTrace() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-preferred.docx");
        generativeProvider.reset();
        scriptedProvider.failPolicyComparisonDecision = true;

        var run = analysisService.analyze(organizationId, review.id());

        var degraded = analysisRepository.findExecutions(organizationId, run.id()).stream()
                .filter(execution -> execution.status() == CheckExecutionStatus.DEGRADED)
                .toList();
        assertThat(degraded).hasSize(3);
        assertThat(degraded).allSatisfy(execution -> {
            assertThat(execution.evidenceTraceId()).isNotNull();
            assertThat(execution.detail().get("degradedReason").toString())
                    .contains("provider_unavailable");
        });
        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).hasSize(3);
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.signalType()).isEqualTo(SignalType.DEVIATION_UNCERTAIN);
            assertThat(signal.issueFamily()).isEqualTo(IssueFamily.POSITION_UNCERTAIN);
            assertThat(((DeviationUncertainPayload) signal.payload()).reason())
                    .isEqualTo("bounded_decision_unavailable");
            assertThat(signal.evidenceItemIds()).isNotEmpty();
        });
        assertThat(reviewRepository.find(organizationId, review.id()).orElseThrow().status())
                .isEqualTo(ReviewStatus.COMPLETED);
        assertThat(generativeProvider.calls()).isZero();
    }

    @Test
    void uniformLowProbabilitiesAcrossFlaggingOptionsDoNotFlag() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx");
        scriptedProvider.policyComparisonProbabilities = Map.of(
                PolicyComparisonSpecs.MEETS_PREFERRED, 0.5,
                PolicyComparisonSpecs.WITHIN_FALLBACK, 0.1,
                PolicyComparisonSpecs.BEYOND_FALLBACK, 0.1,
                PolicyComparisonSpecs.WALK_AWAY, 0.1,
                PolicyComparisonSpecs.UNCLEAR, 0.1,
                PolicyComparisonSpecs.NOT_ADDRESSED, 0.1);

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        assertThat(scriptedProvider.policyComparisonDecisions()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void flaggingFollowsTheSummedProbabilityAndArgmaxNotTheSelectedOption() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-uncapped.docx");
        scriptedProvider.policyComparisonAnswerOverride =
                PolicyComparisonSpecs.MEETS_PREFERRED;
        scriptedProvider.policyComparisonProbabilities = Map.of(
                PolicyComparisonSpecs.MEETS_PREFERRED, 0.2,
                PolicyComparisonSpecs.WITHIN_FALLBACK, 0.1,
                PolicyComparisonSpecs.BEYOND_FALLBACK, 0.4,
                PolicyComparisonSpecs.WALK_AWAY, 0.05,
                PolicyComparisonSpecs.UNCLEAR, 0.2,
                PolicyComparisonSpecs.NOT_ADDRESSED, 0.05);

        var run = analysisService.analyze(organizationId, review.id());

        var deviation = analysisRepository.findSignals(organizationId, run.id()).stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        var payload = (PositionDeviationPayload) deviation.payload();
        assertThat(payload.category()).isEqualTo(PolicyComparisonSpecs.BEYOND_FALLBACK);
        assertThat(payload.flagProbability()).isEqualTo(0.75, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(payload.probabilities().get(PolicyComparisonSpecs.BEYOND_FALLBACK))
                .isEqualTo(0.4);
        assertThat(deviation.quality().get("confidence")).isEqualTo(0.2);
    }
}