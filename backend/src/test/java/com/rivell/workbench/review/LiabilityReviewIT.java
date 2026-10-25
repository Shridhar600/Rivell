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
        /** Every policy comparison fails as if the analysis account had no credit. */
        volatile boolean failPolicyComparisonOutOfCredit;
        volatile String policyComparisonAnswerOverride;
        /** Probability the uncovered-clause triage answers for every clause it sees. */
        volatile double uncoveredProbability = 0.05;
        /** The uncovered-clause triage fails as if the provider were down. */
        volatile boolean failUncoveredTriage;
        final java.util.List<String> uncoveredHeadings =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        volatile Map<String, Double> policyComparisonProbabilities;
        /** When not empty, {@link #policyComparisonProbabilities} apply only to these headings. */
        final java.util.Set<String> probabilityHeadings =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Headings whose primary-topic choice comes back indecisive, so the clause has none. */
        final java.util.Set<String> indecisivePrimaryHeadings =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final AtomicInteger indecisivePrimaryAnswers = new AtomicInteger();
        /** Headings the section-topics decision finds no topic in (every topic answers no). */
        final java.util.Set<String> topiclessHeadings =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        public int indecisivePrimaryAnswers() {
            return indecisivePrimaryAnswers.get();
        }
        volatile boolean failConsistencyDecision;
        volatile String consistencyAnswerOverride;
        volatile double consistencyTopProbability = 0.9;

        /** Answer for a position whose text contains the key (wins over {@link #headingAnswers}). */
        final Map<String, String> positionAnswers = new java.util.concurrent.ConcurrentHashMap<>();
        /** Answer for every position asked about a clause whose heading contains the key. */
        final Map<String, String> headingAnswers = new java.util.concurrent.ConcurrentHashMap<>();
        /** Comparisons of clauses whose heading contains one of these fail as if the provider were down. */
        final java.util.Set<String> failComparisonHeadings =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** A comparison call whose heading contains the key and a question the value fails. */
        final Map<String, String> failComparisonPairs = new java.util.concurrent.ConcurrentHashMap<>();

        /** Probability the presence decision answers per position whose question contains the key. */
        final Map<String, Double> presenceAnswers = new java.util.concurrent.ConcurrentHashMap<>();
        /** Probability the presence decision answers when no scripted answer matches. */
        volatile double defaultPresenceProbability = 0.1;
        /** The presence decision fails as if the provider were down. */
        volatile boolean failPresence;
        private final AtomicInteger presenceCalls = new AtomicInteger();
        /** The question instructions of every presence question asked, in order. */
        final java.util.List<String> presenceQuestions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        /** Probability the R3-002 walk-away absence question answers per walk-away text. */
        final Map<String, Double> walkAwayAnswers = new java.util.concurrent.ConcurrentHashMap<>();
        /** Probability the walk-away absence question answers when nothing is scripted. */
        volatile double defaultWalkAwayProbability = 0.05;
        /** The walk-away absence decision fails as if the provider were down. */
        volatile boolean failWalkAwayAbsence;
        private final AtomicInteger walkAwayCalls = new AtomicInteger();
        /** The question instructions of every walk-away absence question asked, in order. */
        final java.util.List<String> walkAwayQuestions =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        /** Answer for every position when the options arrive reversed (order probe); null = as asked. */
        volatile String reversedOrderAnswer;
        /** The reversed-order (probe) call fails as if the provider were down. */
        volatile boolean failReversedOrder;

        /** The fit check's answer for a search-found rule; null = 0.95 (fits). */
        volatile Double ruleFitProbability;
        /** The fit check's answer for a library wording offer; null = 0.95 (fits). */
        volatile Double wordingFitProbability;
        /** The fit check's answer for the meets-position question; null = wordingFitProbability. */
        volatile Double wordingMeetsProbability;
        /** The fit check's answer for the cures-gap question; null = wordingFitProbability. */
        volatile Double wordingCuresProbability;
        /** The fit check fails as if the provider were down. */
        volatile boolean failFitDecision;
        private final AtomicInteger fitCalls = new AtomicInteger();
        /** One fit call: the clause heading and the question keys, in call order. */
        public record FitCall(String heading, java.util.List<String> questionKeys) {
        }

        final java.util.List<FitCall> fitCallsMade =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /**
         * One policy-comparison call: the subject heading, the question keys, the first option offered,
         * the definitions the state carried and the state itself (serialized by the test's mapper).
         */
        public record ComparisonCall(String heading, java.util.List<String> questionKeys,
                String firstOption, java.util.List<String> definitions, Object state) {
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public ProviderExecutionResult execute(DecisionExecutionRequest request) {
            totalDecisions.incrementAndGet();
            if (request.pack().questions().get("primary_topic")
                    instanceof com.rivell.workbench.intelligence.ChoiceQuestion primary
                    && request.state().payload() instanceof Map<?, ?> payload
                    && payload.get("heading") instanceof String heading
                    && indecisivePrimaryHeadings.stream().anyMatch(heading::contains)) {
                indecisivePrimaryAnswers.incrementAndGet();
                var even = new LinkedHashMap<String, Double>();
                primary.criteria().keySet().forEach(option -> even.put(option,
                        1.0 / primary.criteria().size()));
                var first = primary.criteria().keySet().iterator().next();
                return new ProviderExecutionResult(new LinkedHashMap<>(Map.of("primary_topic",
                        new com.rivell.workbench.intelligence.ChoiceAnswer(first, even.get(first), even))),
                        "fake-review-v1", new TokenUsage(1, 1));
            }
            if (request.pack().questions().keySet().stream().anyMatch(name -> name.startsWith("topic_"))
                    && request.state().payload() instanceof Map<?, ?> topicPayload
                    && topicPayload.get("heading") instanceof String topicHeading
                    && topiclessHeadings.stream().anyMatch(topicHeading::contains)) {
                var none = new LinkedHashMap<String, SemanticAnswer>();
                request.pack().questions().keySet().forEach(name -> none.put(name,
                        new com.rivell.workbench.intelligence.YesNoAnswer(0.02)));
                return new ProviderExecutionResult(none, "fake-review-v1", new TokenUsage(1, 1));
            }
            if (request.pack().questions().containsKey(M7DecisionSpecs.SATISFIES_POLICY)) {
                var verificationAnswers = new LinkedHashMap<String, SemanticAnswer>();
                verificationAnswers.put(M7DecisionSpecs.SATISFIES_POLICY,
                        new com.rivell.workbench.intelligence.YesNoAnswer(
                                verificationSatisfiesProbability != null
                                        ? verificationSatisfiesProbability
                                        : verificationSatisfies ? 0.95 : 0.05));
                var risk = new com.rivell.workbench.intelligence.YesNoAnswer(
                        verificationRiskProbability != null
                                ? verificationRiskProbability
                                : verificationIntroducesRisk ? 0.95 : 0.05);
                verificationAnswers.put(M7DecisionSpecs.REMOVAL_HURTS_US, risk);
                verificationAnswers.put(M7DecisionSpecs.ADDITION_HURTS_US, risk);
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
            if (request.pack().questions().containsKey(PolicyComparisonSpecs.NEEDS_REVIEW)) {
                if (failUncoveredTriage) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "uncovered triage provider unavailable");
                }
                var state = (PolicyComparisonSpecs.PolicyComparisonState) request.state().payload();
                uncoveredHeadings.add(state.heading());
                return new ProviderExecutionResult(new LinkedHashMap<>(Map.of(
                        PolicyComparisonSpecs.NEEDS_REVIEW,
                        new com.rivell.workbench.intelligence.YesNoAnswer(uncoveredProbability))),
                        "fake-review-v1", new TokenUsage(90, 2));
            }
            if (request.pack().questions().keySet().stream()
                    .anyMatch(name -> name.startsWith("presence_"))) {
                return presenceDecision(request);
            }
            if (request.pack().questions().keySet().stream()
                    .anyMatch(name -> name.startsWith("walk_away_"))) {
                return walkAwayAbsenceDecision(request);
            }
            if (request.pack().questions().keySet().stream()
                    .anyMatch(name -> name.startsWith("fit_"))) {
                return fitDecision(request);
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
            if (failPolicyComparisonOutOfCredit) {
                throw new com.rivell.workbench.intelligence.ProviderOutOfCreditException(
                        "Provider account has no credits (HTTP 429)");
            }
            if (failPolicyComparisonDecision) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "policy comparison provider unavailable");
            }
            var firstOption = ((ChoiceQuestion) request.pack().questions().values().iterator().next())
                    .criteria().keySet().iterator().next();
            var reversedOrder = PolicyComparisonSpecs.NOT_ADDRESSED.equals(firstOption);
            if (reversedOrder && failReversedOrder) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "order probe provider unavailable");
            }
            var state = (PolicyComparisonSpecs.PolicyComparisonState) request.state().payload();
            var subjectHeading = state.heading() == null ? "" : state.heading();
            if (failComparisonHeadings.stream().anyMatch(subjectHeading::contains)) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "policy comparison provider unavailable for " + subjectHeading);
            }
            if (failComparisonPairs.entrySet().stream().anyMatch(entry ->
                    subjectHeading.contains(entry.getKey())
                            && request.pack().questions().values().stream()
                                    .anyMatch(question -> question instanceof ChoiceQuestion choice
                                            && choice.instructions() != null
                                            && choice.instructions().toLowerCase(java.util.Locale.ROOT)
                                                    .contains(entry.getValue()
                                                            .toLowerCase(java.util.Locale.ROOT))))) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "policy comparison pair provider unavailable for " + subjectHeading);
            }
            var clause = state.clause() == null ? "" : state.clause().toLowerCase();
            var initialKeys = new java.util.ArrayList<>(request.pack().questions().keySet());
            java.util.Collections.sort(initialKeys);
            comparisonCalls.add(new ComparisonCall(
                    state.heading() == null ? "" : state.heading(), initialKeys, firstOption,
                    state.definitions(), state));
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = (ChoiceQuestion) entry.getValue();
                var scriptedAnswer = scriptedAnswer(subjectHeading, question.instructions());
                var selected = reversedOrder && reversedOrderAnswer != null
                        ? reversedOrderAnswer
                        : policyComparisonAnswerOverride != null
                                ? policyComparisonAnswerOverride
                                : scriptedAnswer != null
                                        ? scriptedAnswer
                                        : policyComparisonSelection(clause, question.instructions());
                var scripted = policyComparisonProbabilities != null
                        && (probabilityHeadings.isEmpty() || probabilityHeadings.stream()
                                .anyMatch(heading -> state.heading() != null
                                        && state.heading().contains(heading)));
                var probabilities = scripted
                        ? new LinkedHashMap<>(policyComparisonProbabilities)
                        : uniformProbabilities(question, selected);
                answers.put(entry.getKey(), new ChoiceAnswer(
                        selected, probabilities.getOrDefault(selected, 0.5), probabilities));
            }
            return new ProviderExecutionResult(answers, "fake-review-v1",
                    new TokenUsage(220, 12));
        }

        private String scriptedAnswer(String heading, String instructions) {
            var position = instructions == null ? "" : instructions.toLowerCase();
            for (var entry : positionAnswers.entrySet()) {
                if (position.contains(entry.getKey().toLowerCase())) {
                    return entry.getValue();
                }
            }
            for (var entry : headingAnswers.entrySet()) {
                if (heading.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        }

        /**
         * The part-4 fit check: one yes/no per candidate, all of one clause's candidates in one
         * call. Rules and wording can be scripted apart, so one test can fit a hit and reject the
         * wording offered for it.
         */
        private ProviderExecutionResult fitDecision(DecisionExecutionRequest request) {
            fitCalls.incrementAndGet();
            if (failFitDecision) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "fit decision provider unavailable");
            }
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            var keys = new java.util.ArrayList<String>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = (com.rivell.workbench.intelligence.YesNoQuestion) entry.getValue();
                answers.put(entry.getKey(),
                        new com.rivell.workbench.intelligence.YesNoAnswer(
                                fitProbability(question.instructions())));
                keys.add(entry.getKey());
            }
            java.util.Collections.sort(keys);
            var state = (com.rivell.workbench.review.check.ClauseFitSpecs.FitState)
                    request.state().payload();
            fitCallsMade.add(new FitCall(state.heading(), List.copyOf(keys)));
            return new ProviderExecutionResult(answers, "fake-review-v1", new TokenUsage(80, 2));
        }

        private double fitProbability(String instructions) {
            if (!instructions.contains("clause-library wording")) {
                return ruleFitProbability != null ? ruleFitProbability : 0.95;
            }
            var wordingDefault = wordingFitProbability != null ? wordingFitProbability : 0.95;
            if (instructions.contains("meet the playbook position")) {
                return wordingMeetsProbability != null ? wordingMeetsProbability : wordingDefault;
            }
            return wordingCuresProbability != null ? wordingCuresProbability : wordingDefault;
        }

        /** Engine v2 omissions lane: one yes/no per candidate position, scripted by its text. */
        private ProviderExecutionResult presenceDecision(DecisionExecutionRequest request) {
            presenceCalls.incrementAndGet();
            if (failPresence) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "presence decision provider unavailable");
            }
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = (com.rivell.workbench.intelligence.YesNoQuestion) entry.getValue();
                var instructions = question.instructions();
                presenceQuestions.add(instructions);
                var probability = defaultPresenceProbability;
                for (var scripted : presenceAnswers.entrySet()) {
                    if (instructions.toLowerCase(java.util.Locale.ROOT)
                            .contains(scripted.getKey().toLowerCase(java.util.Locale.ROOT))) {
                        probability = scripted.getValue();
                        break;
                    }
                }
                answers.put(entry.getKey(),
                        new com.rivell.workbench.intelligence.YesNoAnswer(probability));
            }
            return new ProviderExecutionResult(answers, "fake-review-v1", new TokenUsage(80, 2));
        }

        /** R3-002: one yes/no per walk-away, scripted by its text. */
        private ProviderExecutionResult walkAwayAbsenceDecision(
                DecisionExecutionRequest request) {
            walkAwayCalls.incrementAndGet();
            if (failWalkAwayAbsence) {
                throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                        "walk-away absence decision provider unavailable");
            }
            var answers = new LinkedHashMap<String, SemanticAnswer>();
            for (var entry : request.pack().questions().entrySet()) {
                var question = (com.rivell.workbench.intelligence.YesNoQuestion) entry.getValue();
                var instructions = question.instructions();
                walkAwayQuestions.add(instructions);
                var probability = defaultWalkAwayProbability;
                for (var scripted : walkAwayAnswers.entrySet()) {
                    if (instructions.toLowerCase(java.util.Locale.ROOT)
                            .contains(scripted.getKey().toLowerCase(java.util.Locale.ROOT))) {
                        probability = scripted.getValue();
                        break;
                    }
                }
                answers.put(entry.getKey(),
                        new com.rivell.workbench.intelligence.YesNoAnswer(probability));
            }
            return new ProviderExecutionResult(answers, "fake-review-v1", new TokenUsage(80, 2));
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

        public int presenceCalls() {
            return presenceCalls.get();
        }

        public int walkAwayCalls() {
            return walkAwayCalls.get();
        }

        public java.util.List<String> walkAwayQuestions() {
            return java.util.List.copyOf(walkAwayQuestions);
        }

        public int fitCalls() {
            return fitCalls.get();
        }

        public java.util.List<FitCall> fitCallsMade() {
            return java.util.List.copyOf(fitCallsMade);
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
            failPolicyComparisonOutOfCredit = false;
            policyComparisonAnswerOverride = null;
            positionAnswers.clear();
            headingAnswers.clear();
            failComparisonHeadings.clear();
            failComparisonPairs.clear();
            presenceAnswers.clear();
            defaultPresenceProbability = 0.1;
            failPresence = false;
            presenceCalls.set(0);
            presenceQuestions.clear();
            walkAwayAnswers.clear();
            defaultWalkAwayProbability = 0.05;
            failWalkAwayAbsence = false;
            walkAwayCalls.set(0);
            walkAwayQuestions.clear();
            policyComparisonProbabilities = null;
            probabilityHeadings.clear();
            reversedOrderAnswer = null;
            failReversedOrder = false;
            ruleFitProbability = null;
            wordingFitProbability = null;
            wordingMeetsProbability = null;
            wordingCuresProbability = null;
            failFitDecision = false;
            fitCalls.set(0);
            fitCallsMade.clear();
            indecisivePrimaryHeadings.clear();
            indecisivePrimaryAnswers.set(0);
            topiclessHeadings.clear();
            uncoveredProbability = 0.05;
            uncoveredHeadings.clear();
            failUncoveredTriage = false;
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
        /** The wording call fails as if the analysis account had no credit. */
        volatile boolean openEndedOutOfCredit;
        volatile String openEndedProposed;
        volatile String openEndedExplanation;
        /** Headings the whole-contract spotting read names (MEDIUM); every other clause is fine. */
        final java.util.Set<String> spottedHeadings = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Headings the whole-contract spotting read names as HIGH. */
        final java.util.Set<String> spottedHighHeadings =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        final AtomicInteger spottingCalls = new AtomicInteger();
        /** Overrides the whole-contract read's raw issue lines, when a test needs a malformed one. */
        volatile java.util.function.Function<IssueSpottingSpecs.SpottingState, String>
                spottingOutput;
        /** The whole-contract read fails as if the provider were down. */
        volatile boolean failSpotting;
        /** The whole-contract read fails as if the analysis account had no credit. */
        volatile boolean spottingOutOfCredit;
        /** The second opinion's answer (engine v2). Its quote is the clause's first words unless set. */
        volatile String secondOpinionCategory = PolicyComparisonSpecs.MEETS_PREFERRED;
        volatile String secondOpinionQuote;
        volatile boolean failSecondOpinion;
        final AtomicInteger secondOpinionCalls = new AtomicInteger();
        /** The definitions each second-opinion call carried, in call order. */
        final java.util.List<java.util.List<String>> secondOpinionDefinitions =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        /**
         * The fallback-condition check (severity R3 P3): the condition lines it answers, where
         * {@code {CLAUSE}} stands for the clause's first words (a quote the clause holds). Null: one MET line.
         */
        volatile String fallbackConditionLines;
        volatile String fallbackListed = "YES";
        volatile boolean failFallbackConditions;
        final AtomicInteger fallbackConditionCalls = new AtomicInteger();
        final java.util.List<FallbackConditionSpecs.FallbackConditionState> fallbackConditionStates =
                new java.util.concurrent.CopyOnWriteArrayList<>();

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
            openEndedOutOfCredit = false;
            openEndedProposed = null;
            openEndedExplanation = null;
            spottedHeadings.clear();
            spottedHighHeadings.clear();
            spottingCalls.set(0);
            spottingOutput = null;
            failSpotting = false;
            spottingOutOfCredit = false;
            secondOpinionCategory = PolicyComparisonSpecs.MEETS_PREFERRED;
            secondOpinionQuote = null;
            failSecondOpinion = false;
            secondOpinionCalls.set(0);
            secondOpinionDefinitions.clear();
            fallbackConditionLines = null;
            fallbackListed = "YES";
            failFallbackConditions = false;
            fallbackConditionCalls.set(0);
            fallbackConditionStates.clear();
        }

        @Override
        public GenerationResult generate(GenerationRequest request) {
            calls.incrementAndGet();
            if (request.state() instanceof AdjudicationSpecs.SecondOpinionState opinion) {
                secondOpinionCalls.incrementAndGet();
                secondOpinionDefinitions.add(java.util.List.copyOf(opinion.definitions()));
                if (failSecondOpinion) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "second opinion provider unavailable");
                }
                var quote = secondOpinionQuote != null ? secondOpinionQuote
                        : opinion.clause().substring(0, Math.min(40, opinion.clause().length()));
                return new GenerationResult(Map.of(
                        "category", secondOpinionCategory,
                        "quote", quote,
                        "reason", "The quoted words decide how the clause meets the position."),
                        "fake-gen-v1", new TokenUsage(400, 30));
            }
            if (request.state() instanceof FallbackConditionSpecs.FallbackConditionState conditions) {
                fallbackConditionCalls.incrementAndGet();
                fallbackConditionStates.add(conditions);
                if (failFallbackConditions) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "fallback condition provider unavailable");
                }
                var words = conditions.clause().substring(0, Math.min(40, conditions.clause().length()))
                        .replaceAll("\\s+", " ").strip();
                var lines = fallbackConditionLines != null
                        ? fallbackConditionLines.replace("{CLAUSE}", words)
                        : "1 | MET | " + words + " | the clause meets the listed fallback";
                return new GenerationResult(Map.of("fallbackListed", fallbackListed, "conditions", lines),
                        "fake-gen-v1", new TokenUsage(300, 40));
            }
            if (request.state() instanceof IssueSpottingSpecs.SpottingState spotting) {
                spottingCalls.incrementAndGet();
                if (spottingOutOfCredit) {
                    throw new com.rivell.workbench.intelligence.ProviderOutOfCreditException(
                            "Provider account has no credits (HTTP 429)");
                }
                if (failSpotting) {
                    throw new com.rivell.workbench.intelligence.ProviderUnavailableException(
                            "whole-contract spotting unavailable");
                }
                if (spottingOutput != null) {
                    return new GenerationResult(Map.of("issues", spottingOutput.apply(spotting)),
                            "fake-gen-v1", new TokenUsage(900, 30));
                }
                var lines = spotting.clauses().stream()
                        .map(clause -> {
                            var severity = spottedHighHeadings.stream().anyMatch(clause.heading()::contains)
                                    ? "HIGH"
                                    : spottedHeadings.stream().anyMatch(clause.heading()::contains)
                                            ? "MEDIUM" : null;
                            if (severity == null) {
                                return null;
                            }
                            var quote = clause.text().strip().lines().findFirst()
                                    .filter(line -> !line.isBlank())
                                    .orElse(clause.heading());
                            if (quote.length() > 160) {
                                quote = quote.substring(0, 160);
                            }
                            return clause.number() + " | " + severity + " | " + quote
                                    + " | one-sided term against us";
                        })
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining("\n"));
                return new GenerationResult(Map.of("issues", lines.isEmpty() ? "NONE" : lines),
                        "fake-gen-v1", new TokenUsage(900, 30));
            }
            if (request.spec().fields().containsKey("proposedLanguage")) {
                if (openEndedOutOfCredit) {
                    throw new com.rivell.workbench.intelligence.ProviderOutOfCreditException(
                            "Provider account has no credits (HTTP 429)");
                }
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

        // council §A.4: the confidentiality record is a typed position again (the fake reads the
        // imperative survival duty as preferred), so three clauses are compared from positions
        // and the governing-law clause is searched once
        assertThat(scriptedProvider.policyComparisonDecisions()).isEqualTo(4);
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

        // P6/v2: the whole-contract read runs once and names nothing; no wording is generated
        assertThat(generativeProvider.spottingCalls).hasValue(1);
        assertThat(generativeProvider.calls()).isEqualTo(1);
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
        assertThat(executions).isNotEmpty().allSatisfy(execution ->
                assertThat(execution.status()).isEqualTo(CheckExecutionStatus.COMPLETED));
        assertThat(executions).filteredOn(execution ->
                        execution.detail().containsKey("positionCount"))
                .allSatisfy(execution ->
                        assertThat(execution.detail().get("positionCount")).isEqualTo(0));
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
                // the absence lane has no comparison to fail; it degrades on incomplete inputs
                .filter(execution -> !"omission_inputs_incomplete"
                        .equals(execution.detail().get("degradedReason")))
                .toList();
        // three position-bearing clauses degrade on the failed comparison; the clause the text
        // search found a position for degrades on the failed search judgment
        assertThat(degraded).hasSize(4);
        assertThat(degraded).allSatisfy(execution ->
                assertThat(execution.evidenceTraceId()).isNotNull());
        var comparisonFailures = degraded.stream()
                .filter(execution -> !"position_search_comparison_failed"
                        .equals(execution.detail().get("degradedReason")))
                .toList();
        assertThat(comparisonFailures).hasSize(3);
        assertThat(comparisonFailures).allSatisfy(execution ->
                assertThat(execution.detail().get("degradedReason").toString())
                        .contains("provider_unavailable"));
        var searchFailure = degraded.stream()
                .filter(execution -> "position_search_comparison_failed"
                        .equals(execution.detail().get("degradedReason")))
                .findFirst().orElseThrow();
        assertThat(searchFailure.signalCount()).isZero();
        // never an invented "not addressed": nobody judged the found position
        assertThat(searchFailure.detail()).doesNotContainKey("uncoveredProbability");
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
        // the search-failed check returns before the playbook-silent read: no generative call
        assertThat(generativeProvider.spottingCalls).hasValue(0);
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
        // flag mass 0.75 sits in the doubt band: the second opinion agrees, so the judgment stands
        generativeProvider.secondOpinionCategory = PolicyComparisonSpecs.BEYOND_FALLBACK;

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