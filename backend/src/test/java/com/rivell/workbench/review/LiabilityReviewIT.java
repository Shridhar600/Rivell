package com.rivell.workbench.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
class LiabilityReviewIT {

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
    static class FakeIntelligenceConfiguration {

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
    static class CorpusConfiguration {

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
    static class MutableIdentityConfiguration {

        @Bean
        @Primary
        MutableIdentity mutableIdentityProvider() {
            return new MutableIdentity();
        }
    }

    static final class MutableIdentity implements IdentityProvider {

        volatile UserIdentity current = new UserIdentity(
                UUID.randomUUID(), UUID.randomUUID(), Set.of("ADMIN"));

        void use(UserIdentity identity) {
            current = identity;
        }

        @Override
        public UserIdentity currentIdentity() {
            return current;
        }
    }

    public static final class ReviewScriptedProvider implements SemanticDecisionProvider {

        private final CorpusScriptedProvider base = new CorpusScriptedProvider();
        private final AtomicInteger liabilityDecisions = new AtomicInteger();
        private final AtomicInteger consistencyDecisions = new AtomicInteger();

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            if (request.pack().questions().containsKey(M7DecisionSpecs.CONSISTENCY)) {
                consistencyDecisions.incrementAndGet();
                var carveState = (M7DecisionSpecs.CarveOutConsistencyState)
                        request.state().payload();
                var liability = carveState.liabilityText().toLowerCase();
                var indemnity = carveState.indemnityText().toLowerCase();
                var selected = M7DecisionSpecs.CONSISTENT;
                if ((indemnity.contains("without regard") || indemnity.contains("without limitation")
                        || indemnity.contains("unlimited"))
                        && (liability.contains("do not apply") || liability.contains("excluded"))) {
                    selected = M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY;
                } else if (indemnity.isBlank()) {
                    selected = M7DecisionSpecs.NOT_COMPARABLE_CARVE;
                }
                var consistency = new LinkedHashMap<String, Double>();
                for (var option : List.of(M7DecisionSpecs.CONSISTENT,
                        M7DecisionSpecs.CAP_CARVEOUT_CONTRADICTS_INDEMNITY,
                        M7DecisionSpecs.NOT_COMPARABLE_CARVE)) {
                    consistency.put(option, option.equals(selected) ? 0.9 : 0.03);
                }
                var carveAnswers = new LinkedHashMap<String, SemanticAnswer>();
                carveAnswers.put(M7DecisionSpecs.CONSISTENCY,
                        new ChoiceAnswer(selected, 0.9, consistency));
                return new ProviderExecutionResult(carveAnswers, "fake-review-v1",
                        new TokenUsage(140, 6));
            }
            if (!request.pack().questions().containsKey(M7DecisionSpecs.DEVIATION_TYPE)) {
                return base.execute(request);
            }
            liabilityDecisions.incrementAndGet();
            var state = (M7DecisionSpecs.LiabilityDeviationState) request.state().payload();
            var text = state.clauseText() == null ? "" : state.clauseText().toLowerCase();
            var multiple = state.capMultiple() == null ? "" : state.capMultiple();
            var preferred = state.preferredPosition() == null
                    ? "" : state.preferredPosition().toLowerCase();
            String selected;
            if (text.contains("uncapped") || text.contains("unlimited")) {
                selected = M7DecisionSpecs.PROHIBITED_STRUCTURE;
            } else if (preferred.isBlank()
                    || !(preferred.contains("12 months") || preferred.contains("twelve (12)"))
                    || multiple.isBlank()) {
                selected = M7DecisionSpecs.NOT_COMPARABLE;
            } else if (new BigDecimal(multiple).compareTo(BigDecimal.ONE) > 0) {
                selected = M7DecisionSpecs.CAP_ABOVE_PREFERRED;
            } else {
                selected = M7DecisionSpecs.NO_DEVIATION;
            }
            var probabilities = new LinkedHashMap<String, Double>();
            for (var option : List.of(M7DecisionSpecs.NO_DEVIATION,
                    M7DecisionSpecs.CAP_ABOVE_PREFERRED,
                    M7DecisionSpecs.PROHIBITED_STRUCTURE,
                    M7DecisionSpecs.NOT_COMPARABLE)) {
                probabilities.put(option, option.equals(selected) ? 0.9 : 0.03);
            }
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            answers.put(M7DecisionSpecs.DEVIATION_TYPE,
                    new ChoiceAnswer(selected, 0.9, probabilities));
            return new ProviderExecutionResult(answers, "fake-review-v1",
                    new TokenUsage(180, 8));
        }

        int liabilityDecisions() {
            return liabilityDecisions.get();
        }

        void reset() {
            liabilityDecisions.set(0);
            consistencyDecisions.set(0);
        }
    }

    public static final class ReviewGenerativeProvider implements GenerativeReasoningProvider {

        private final com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider base =
                new com.rivell.workbench.contracts.ContractEnrichmentIT.M5FakeGenerativeProvider();
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String id() {
            return "fake";
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            calls.incrementAndGet();
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

    @Test
    void cleanPreferredClauseProducesNoSignalsAndOneBoundedDecision() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.LIBRARY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        var review = enrichedReview("m7-msa-preferred.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(executions.get(0).evidenceTraceId()).isNotNull();
        assertThat(executions.get(0).detail().get("deviationType"))
                .isEqualTo(M7DecisionSpecs.NO_DEVIATION);
        assertThat(analysisRepository.countSignals(organizationId, run.id())).isZero();
        assertThat(scriptedProvider.liabilityDecisions()).isEqualTo(1);
        assertThat(reviewRepository.find(organizationId, review.id())
                .orElseThrow().status()).isEqualTo(ReviewStatus.COMPLETED);
    }

    @Test
    void deviationProducesPolicyDraftingAndEscalationSignals() {
        var organizationId = organizationId();
        var corpus = corpusBuilder.buildMainCorpus(organizationId);
        var review = reviewService.create(corpus.contract().contractId(), 1, null);
        generativeProvider.reset();

        var run = analysisService.analyze(organizationId, review.id());

        assertThat(generativeProvider.calls()).isZero();
        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).hasSize(3);
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.issueFamily()).isEqualTo(IssueFamily.LIABILITY_CAP_DEVIATION);
            assertThat(signal.subjectKind()).isEqualTo(FindingSubjectKind.CLAUSE);
            assertThat(signal.subjectHeadingNodeId()).isNotNull();
            assertThat(signal.evidenceItemIds()).isNotEmpty();
        });
        var deviation = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.POLICY_DEVIATION)
                .findFirst().orElseThrow();
        var payload = (LiabilityDeviationPayload) deviation.payload();
        assertThat(payload.deviationType()).isEqualTo(M7DecisionSpecs.CAP_ABOVE_PREFERRED);
        assertThat(payload.capMultiple()).isEqualTo("3");
        assertThat(deviation.decisionSpecId()).isEqualTo(M7DecisionSpecs.LIABILITY_DEVIATION_SPEC);
        assertThat(deviation.jevExecutionId()).isNotNull();

        var drafting = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.APPROVED_DRAFTING_AVAILABLE)
                .map(signal -> (ApprovedDraftingPayload) signal.payload())
                .findFirst().orElseThrow();
        assertThat(drafting.evidenceText()).isIn(
                "The total liability of Supplier shall not exceed the fees paid in the "
                        + "preceding 12 months.",
                "The total liability of Supplier shall not exceed two times the fees paid "
                        + "in the preceding 12 months.");

        var escalation = signals.stream()
                .filter(signal -> signal.signalType() == SignalType.ESCALATION_REQUIRED)
                .map(signal -> (EscalationPayload) signal.payload())
                .findFirst().orElseThrow();
        assertThat(escalation.trigger()).isNotBlank();
        assertThat(scriptedProvider.liabilityDecisions()).isEqualTo(1);
    }

    @Test
    void policyMissingIsPreservedWithoutInferenceOrDecision() throws Exception {
        var organizationId = organizationId();
        var review = enrichedReview("m7-msa-preferred.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).hasSize(1);
        var signal = signals.get(0);
        assertThat(signal.signalType()).isEqualTo(SignalType.POLICY_UNAVAILABLE);
        assertThat(signal.issueFamily()).isEqualTo(IssueFamily.POLICY_UNAVAILABLE);
        assertThat(((PolicyUnavailablePayload) signal.payload()).sufficiency())
                .isEqualTo("POLICY_MISSING");
        assertThat(signal.evidenceItemIds()).isNotEmpty();
        assertThat(scriptedProvider.liabilityDecisions()).isZero();
    }

    @Test
    void ambiguousPolicyIsPreservedWithoutSelection() throws Exception {
        var organizationId = organizationId();
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_MAIN,
                M6EvidenceCorpus.msaManifest2026(), null);
        corpusBuilder.publishAll(organizationId, M6EvidenceCorpus.Fixture.POLICY_AMBIGUOUS,
                M6EvidenceCorpus.policyManifest(java.time.LocalDate.of(2026, 1, 1),
                        java.time.LocalDate.of(2026, 12, 31)),
                null);
        var review = enrichedReview("m7-msa-preferred.docx");

        var run = analysisService.analyze(organizationId, review.id());

        var signals = analysisRepository.findSignals(organizationId, run.id());
        assertThat(signals).hasSize(1);
        assertThat(signals.get(0).signalType()).isEqualTo(SignalType.POLICY_AMBIGUOUS);
        assertThat(((PolicyAmbiguousPayload) signals.get(0).payload()).applicableRuleCount())
                .isGreaterThanOrEqualTo(2);
        assertThat(signals.get(0).evidenceItemIds()).isNotEmpty();
        assertThat(scriptedProvider.liabilityDecisions()).isZero();
    }

    @Autowired
    ReviewRepository reviewRepository;

    @Test
    void uncappedClauseIsClassifiedProhibitedEvenWithoutExtractableFacts() throws Exception {
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
        assertThat(((LiabilityDeviationPayload) deviation.payload()).deviationType())
                .isEqualTo(M7DecisionSpecs.PROHIBITED_STRUCTURE);
        assertThat(signals).extracting(RawReviewSignal::signalType)
                .contains(SignalType.APPROVED_DRAFTING_AVAILABLE,
                        SignalType.ESCALATION_REQUIRED);
        var executions = analysisRepository.findExecutions(organizationId, run.id());
        assertThat(executions.get(0).status()).isEqualTo(CheckExecutionStatus.COMPLETED);
        assertThat(executions.get(0).detail().get("factsAvailable")).isEqualTo(false);
        assertThat(generativeProvider.calls()).isZero();
    }
}
