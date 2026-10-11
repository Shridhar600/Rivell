package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SpecStateMismatchException;
import com.rivell.workbench.intelligence.UnknownDecisionSpecException;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.retrieval.EvidenceBundle;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.retrieval.KnowledgePin;
import com.rivell.workbench.review.ApprovedDraftingPayload;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.ClauseTopics;
import com.rivell.workbench.review.DeviationUncertainPayload;
import com.rivell.workbench.review.EscalationPayload;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.M7ReasoningSpecs;
import com.rivell.workbench.review.OpenEndedAnalysisPayload;
import com.rivell.workbench.review.PolicyComparisonProperties;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.PositionDeviationPayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.SignalPayload;
import com.rivell.workbench.review.SignalType;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.ConceptRegistry;

/**
 * General policy comparison (design §6a): one Jev call per classified clause judged
 * against every published position that shares a topic with it — one Choice per
 * position. The code owns the outcome: a position flags by summed probability of the
 * four issue-bearing options, and the category is their argmax. Flagged positions
 * become findings with approved fallback wording when the clause library carries it,
 * verified machine language otherwise, and escalation whenever the position names an
 * approver. Provider failure is recorded per position, never a silent pass.
 */
public final class PolicyComparisonCheckExecutor implements ReviewCheckExecutor {

    private static final int MAX_CLAUSE_LENGTH = 4000;
    private static final int MAX_CANDIDATE_LENGTH = 2000;
    private static final double MAX_COPY_OVERLAP = 0.9;
    private static final double TRUE_BAND = 0.6;
    private static final double FALSE_BAND = 0.4;

    private final SemanticDecisionService decisionService;
    private final GenerativeReasoningService reasoningService;
    private final EvidenceService evidenceService;
    private final ConceptRegistry conceptRegistry;
    private final PolicyComparisonProperties properties;

    public PolicyComparisonCheckExecutor(
            SemanticDecisionService decisionService,
            GenerativeReasoningService reasoningService,
            EvidenceService evidenceService,
            ConceptRegistry conceptRegistry,
            PolicyComparisonProperties properties) {
        this.decisionService = decisionService;
        this.reasoningService = reasoningService;
        this.evidenceService = evidenceService;
        this.conceptRegistry = conceptRegistry;
        this.properties = properties;
    }

    @Override
    public CheckMechanism mechanism() {
        return CheckMechanism.POLICY_COMPARISON;
    }

    @Override
    public CheckOutcome execute(ReviewCheckContext context) {
        if (!context.environment().fullyMatches()) {
            return CheckOutcome.degraded(context.environment().mismatchReason());
        }
        var clause = resolveSubject(context);
        if (clause == null) {
            return CheckOutcome.degraded("subject_clause_not_found");
        }
        var topics = ClauseTopics.effectiveTopics(clause);
        var detail = new LinkedHashMap<String, Object>();
        detail.put("clauseTopicCount", topics.size());
        if (topics.isEmpty()) {
            return CheckOutcome.completed(List.of(), detail);
        }
        var primary = clause.effectivePrimaryConcept().orElse(null);
        var bundle = evidenceService.buildForPolicyComparison(
                request(context, clause, primary == null ? topics.getFirst() : primary),
                topics, primary, properties.maxPositions());
        return compare(context, clause, bundle, detail);
    }

    private CheckOutcome compare(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            Map<String, Object> detail) {
        var positions = bundle.policyItems();
        detail.put("positionCount", positions.size());
        detail.put("positionsTruncated",
                bundle.degradationWarnings().contains("POLICY_POSITIONS_TRUNCATED"));
        if (positions.isEmpty()) {
            // no applicable position: completed check, no signal, no model call
            return CheckOutcome.completed(List.of(), detail, bundle.traceId());
        }
        var state = new PolicyComparisonSpecs.PolicyComparisonState(
                PolicyComparisonSpecs.STATE_SCHEMA_VERSION,
                context.snapshot().contractType().name(),
                context.snapshot().ourSideRole() == null
                        ? "" : context.snapshot().ourSideRole().name(),
                context.headingTexts().getOrDefault(
                        context.check().subjectHeadingNodeId(), ""),
                truncate(clause.contentText(), MAX_CLAUSE_LENGTH));
        DecisionExecution execution;
        try {
            execution = decisionService.evaluate(PolicyComparisonSpecs.spec(
                    PolicyPositionQuestions.build(positions, clause, conceptRegistry)), state);
        } catch (SpecStateMismatchException | UnknownDecisionSpecException
                | NoProviderConfiguredException e) {
            return CheckOutcome.failed(
                    "decision_contract_error:" + e.getClass().getSimpleName());
        } catch (SemanticDecisionException e) {
            return providerUnavailable(context, clause, bundle, positions, detail, e);
        }
        var judgedPositions = new ArrayList<JudgedPosition>(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            var answer = execution.answers().get(PolicyPositionQuestions.key(index));
            if (!(answer instanceof ChoiceAnswer choice)) {
                return CheckOutcome.failed(
                        "position_answer_missing:" + PolicyPositionQuestions.key(index));
            }
            judgedPositions.add(new JudgedPosition(PolicyPositionQuestions.key(index),
                    positions.get(index),
                    PolicyComparisonOutcome.judge(choice, properties.flagThreshold())));
        }
        detail.put("positions", judgedPositions.stream()
                .map(PolicyComparisonCheckExecutor::detailEntry).toList());
        var signals = new ArrayList<RawReviewSignal>();
        for (var judged : judgedPositions) {
            if (judged.judgement().flagged()) {
                signals.addAll(flaggedSignals(context, clause, bundle, judged, execution, detail));
            }
        }
        return CheckOutcome.completed(signals, detail, bundle.traceId())
                .withCitedEvidence(List.of(bundle));
    }

    private List<RawReviewSignal> flaggedSignals(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            JudgedPosition judged, DecisionExecution execution, Map<String, Object> detail) {
        var family = PolicyComparisonOutcome.family(judged.judgement().category());
        var evidence = union(List.of(judged.position().evidenceId()),
                subjectEvidence(bundle, clause.id()));
        validateEvidence(bundle, evidence);
        var signals = new ArrayList<RawReviewSignal>();
        signals.add(signal(context, b -> b
                .signalType(SignalType.POLICY_DEVIATION).issueFamily(family)
                .payload(new PositionDeviationPayload(judged.position().sourceCandidateId(),
                        judged.judgement().category(), judged.judgement().flagProbability(),
                        judged.judgement().probabilities()))
                .evidence(evidence)
                .decisionSpec(PolicyComparisonSpecs.POLICY_COMPARISON_SPEC,
                        PolicyComparisonSpecs.POLICY_COMPARISON_VERSION,
                        execution.executionId())
                .resolvedModel(execution.metadata().resolvedModel())
                .usage(execution.metadata().usage())
                .quality(Map.of("flagged", true,
                        "category", judged.judgement().category(),
                        "flagProbability", judged.judgement().flagProbability(),
                        "confidence", confidence(execution, judged),
                        "probabilities", judged.judgement().probabilities()))
                .dedupeSuffix(judged.key())));
        if (wantsLanguage(judged.judgement().category())) {
            var drafting = chooseDrafting(bundle.approvedDraftingItems());
            if (drafting != null) {
                signals.add(signal(context, b -> b
                        .signalType(SignalType.APPROVED_DRAFTING_AVAILABLE).issueFamily(family)
                        .payload(new ApprovedDraftingPayload(drafting.evidenceId(), drafting.text(),
                                drafting.variantRole() == null
                                        ? null : drafting.variantRole().name()))
                        .evidence(List.of(drafting.evidenceId()))
                        .quality(Map.of("draftingAvailable", true))
                        .dedupeSuffix(judged.key())));
            } else {
                var proposal = generate(context, clause, judged, family, evidence, detail);
                if (proposal != null) {
                    signals.add(proposal);
                }
            }
        }
        var facts = judged.position().policyFacts();
        if (facts != null && ((facts.escalationTrigger() != null
                && !facts.escalationTrigger().isBlank())
                || Boolean.TRUE.equals(facts.requiresApproval()))) {
            signals.add(signal(context, b -> b
                    .signalType(SignalType.ESCALATION_REQUIRED).issueFamily(family)
                    .payload(new EscalationPayload(facts.escalationTrigger(),
                            Boolean.TRUE.equals(facts.requiresApproval())))
                    .evidence(List.of(judged.position().evidenceId()))
                    .quality(Map.of("requiresApproval",
                            Boolean.TRUE.equals(facts.requiresApproval()),
                            "escalationTrigger", facts.escalationTrigger() == null
                                    ? "" : facts.escalationTrigger()))
                    .dedupeSuffix(judged.key())));
        }
        return signals;
    }

    private RawReviewSignal generate(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            IssueFamily family, List<UUID> evidence, Map<String, Object> detail) {
        try {
            var generation = reasoningService.generate(M7ReasoningSpecs.openEndedPosition(),
                    new M7ReasoningSpecs.OpenEndedPositionState(
                            M7ReasoningSpecs.OPEN_ENDED_STATE_SCHEMA,
                            clause.contentText(), judged.position().text()));
            var proposed = generation.fields().get(M7ReasoningSpecs.PROPOSED_LANGUAGE);
            var explanation = generation.fields().get(M7ReasoningSpecs.EXPLANATION);
            validateCandidate(proposed, explanation, clause.contentText());
            var verification = verifyCandidate(context, clause, judged, proposed);
            detail.put("openEnded", true);
            return signal(context, b -> b
                    .signalType(SignalType.OPEN_ENDED_ANALYSIS_RESULT).issueFamily(family)
                    .payload(new OpenEndedAnalysisPayload(proposed, explanation,
                            verification.outcome()))
                    .evidence(evidence)
                    .decisionSpec(null, null, null)
                    .generativeExecution(generation.executionId())
                    .verificationOutcome(verification.outcome())
                    .resolvedModel(generation.metadata().resolvedModel())
                    .usage(generation.metadata().usage())
                    .quality(Map.of("openEnded", true,
                            "verificationOutcome", verification.outcome(),
                            "verificationExecutionId", verification.executionId() == null
                                    ? "" : verification.executionId().toString(),
                            "verificationSatisfiesProbability", verification.satisfiesProbability(),
                            "verificationNewRiskProbability", verification.newRiskProbability()))
                    .dedupeSuffix(judged.key()));
        } catch (RuntimeException e) {
            // no approved wording and no machine candidate: the finding escalates to a human
            detail.put("openEnded", false);
            detail.put("openEndedUnavailable", e.getClass().getSimpleName());
            return null;
        }
    }

    private CheckOutcome providerUnavailable(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            List<EvidenceItem> positions, Map<String, Object> detail, SemanticDecisionException e) {
        var signals = new ArrayList<RawReviewSignal>();
        for (int index = 0; index < positions.size(); index++) {
            var item = positions.get(index);
            var positionKey = PolicyPositionQuestions.key(index);
            var evidence = union(List.of(item.evidenceId()),
                    subjectEvidence(bundle, clause.id()));
            validateEvidence(bundle, evidence);
            signals.add(signal(context, b -> b
                    .signalType(SignalType.DEVIATION_UNCERTAIN)
                    .issueFamily(IssueFamily.POSITION_UNCERTAIN)
                    .payload(new DeviationUncertainPayload("bounded_decision_unavailable"))
                    .evidence(evidence)
                    .quality(Map.of("uncertaintyReason", "bounded_decision_unavailable"))
                    .dedupeSuffix(positionKey)));
        }
        var reason = "provider_unavailable:" + e.getClass().getSimpleName();
        detail.put("uncertaintyReason", "bounded_decision_unavailable");
        return CheckOutcome.degraded(reason, signals, detail, bundle.traceId())
                .withCitedEvidence(List.of(bundle));
    }

    private ClauseSemantics resolveSubject(ReviewCheckContext context) {
        return context.semantics().clauses().stream()
                .filter(clause -> clause.headingNodeId()
                        .equals(context.check().subjectHeadingNodeId()))
                .findFirst().orElse(null);
    }

    private static boolean wantsLanguage(String category) {
        return PolicyComparisonSpecs.WALK_AWAY.equals(category)
                || PolicyComparisonSpecs.BEYOND_FALLBACK.equals(category);
    }

    private static double confidence(DecisionExecution execution, JudgedPosition judged) {
        return execution.answers().get(judged.key()) instanceof ChoiceAnswer choice
                ? choice.confidence() : 0.0;
    }

    private record JudgedPosition(
            String key, EvidenceItem position, PolicyComparisonOutcome.Judgement judgement) {
    }

    private static Map<String, Object> detailEntry(JudgedPosition judged) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("positionKey", judged.key());
        entry.put("positionCandidateId", judged.position().sourceCandidateId() == null
                ? "" : judged.position().sourceCandidateId().toString());
        entry.put("category", judged.judgement().category() == null
                ? "" : judged.judgement().category());
        entry.put("flagProbability", judged.judgement().flagProbability());
        entry.put("probabilities", judged.judgement().probabilities());
        entry.put("flagged", judged.judgement().flagged());
        return entry;
    }

    private static EvidenceRequest request(
            ReviewCheckContext context, ClauseSemantics clause, ConceptKey concept) {
        var snapshot = context.snapshot();
        return EvidenceRequest.forClauseReview(
                snapshot.organizationId(), concept,
                snapshot.contractId(), snapshot.contractVersionNumber(), clause.id(),
                clause.contentText(),
                new ReviewProfile(
                        snapshot.contractType(), snapshot.ourSideRole(),
                        snapshot.paperSource(), snapshot.jurisdiction(),
                        snapshot.contractPackKey()),
                snapshot.effectiveOn(),
                snapshot.knowledgePinNumber() == 0L
                        ? KnowledgePin.none()
                        : KnowledgePin.at(snapshot.knowledgePinNumber()));
    }

    private Verification verifyCandidate(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            String proposed) {
        try {
            var execution = decisionService.evaluate(
                    com.rivell.workbench.review.M7DecisionSpecs.findingVerification(),
                    new com.rivell.workbench.review.M7DecisionSpecs.FindingVerificationState(
                            com.rivell.workbench.review.M7DecisionSpecs
                                    .FINDING_VERIFICATION_STATE_SCHEMA,
                            proposed, judged.position().text(), clause.contentText()));
            var satisfies = probability(
                    execution.answers().get(com.rivell.workbench.review.M7DecisionSpecs
                            .SATISFIES_POLICY));
            var newRisk = probability(
                    execution.answers().get(com.rivell.workbench.review.M7DecisionSpecs
                            .INTRODUCED_NEW_RISK));
            var outcome = band(newRisk) != Band.FALSE ? "DISPUTED"
                    : band(satisfies) == Band.TRUE ? "VERIFIED" : "UNCERTAIN";
            return new Verification(outcome, execution.executionId(), satisfies, newRisk);
        } catch (SemanticDecisionException e) {
            return new Verification("UNCERTAIN", null, 0.5, 0.5);
        }
    }

    private static double probability(com.rivell.workbench.intelligence.SemanticAnswer answer) {
        return answer instanceof YesNoAnswer yesNo ? yesNo.probability() : 0.5;
    }

    private static Band band(double probability) {
        if (probability > TRUE_BAND) {
            return Band.TRUE;
        }
        if (probability < FALSE_BAND) {
            return Band.FALSE;
        }
        return Band.AMBIGUOUS;
    }

    private enum Band {
        FALSE, AMBIGUOUS, TRUE
    }

    private record Verification(
            String outcome, UUID executionId, double satisfiesProbability,
            double newRiskProbability) {
    }

    private static void validateCandidate(
            String proposed, String explanation, String clauseText) {
        if (proposed == null || proposed.isBlank() || explanation == null
                || explanation.isBlank()
                || proposed.length() > MAX_CANDIDATE_LENGTH
                || explanation.length() > MAX_CANDIDATE_LENGTH
                || nearCopy(proposed, clauseText)) {
            throw new IllegalStateException("open_ended_candidate_failed_validation");
        }
    }

    private static boolean nearCopy(String proposed, String source) {
        var proposedTokens = tokens(proposed);
        var sourceTokens = tokens(source);
        if (proposedTokens.isEmpty() || sourceTokens.isEmpty()) {
            return false;
        }
        var intersection = new java.util.LinkedHashSet<>(proposedTokens);
        intersection.retainAll(sourceTokens);
        var smaller = Math.min(proposedTokens.size(), sourceTokens.size());
        return smaller > 0
                && (double) intersection.size() / smaller >= MAX_COPY_OVERLAP;
    }

    private static java.util.Set<String> tokens(String value) {
        return java.util.Arrays.stream(java.text.Normalizer
                        .normalize(value, java.text.Normalizer.Form.NFKC)
                        .toLowerCase(java.util.Locale.ROOT)
                        .replaceAll("[^a-z0-9]+", " ")
                        .trim()
                        .split("\\s+"))
                .filter(token -> !token.isBlank())
                .collect(java.util.stream.Collectors.toCollection(
                        java.util.LinkedHashSet::new));
    }

    /** Recommend the acceptable second position (fallback) before the ideal position. */
    private static EvidenceItem chooseDrafting(List<EvidenceItem> items) {
        return items.stream()
                .filter(item -> item.variantRole()
                        == com.rivell.workbench.knowledge.PositionRole.FALLBACK)
                .findFirst()
                .orElseGet(() -> items.stream()
                        .filter(item -> item.variantRole()
                                == com.rivell.workbench.knowledge.PositionRole.PREFERRED)
                        .findFirst()
                        .orElseGet(() -> items.isEmpty() ? null : items.get(0)));
    }

    private static List<UUID> subjectEvidence(EvidenceBundle bundle, UUID clauseId) {
        return bundle.currentContractItems().stream()
                .filter(item -> clauseId.equals(item.sourceClauseId()))
                .map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> union(List<UUID> first, List<UUID> second) {
        var all = new java.util.LinkedHashSet<UUID>();
        all.addAll(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static List<UUID> validateEvidence(EvidenceBundle bundle, List<UUID> ids) {
        var available = bundle.items().stream().map(EvidenceItem::evidenceId).toList();
        if (!available.containsAll(ids)) {
            throw new IllegalStateException("evidence references not present in the bundle");
        }
        return ids;
    }

    private static String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private RawReviewSignal signal(
            ReviewCheckContext context, java.util.function.Consumer<SignalBuilder> customiser) {
        var builder = new SignalBuilder(context);
        customiser.accept(builder);
        return builder.build();
    }

    /** Small builder so each signal states its own type, family, payload and evidence. */
    private static final class SignalBuilder {

        private final ReviewCheckContext context;
        private SignalType signalType;
        private IssueFamily issueFamily;
        private SignalPayload payload;
        private List<UUID> evidence = List.of();
        private String decisionSpecId;
        private Integer decisionSpecVersion;
        private UUID jevExecutionId;
        private UUID generativeExecutionId;
        private String verificationOutcome;
        private String resolvedModel;
        private com.rivell.workbench.intelligence.TokenUsage usage;
        private Map<String, Object> quality = Map.of();
        private String dedupeSuffix = "";

        SignalBuilder(ReviewCheckContext context) {
            this.context = context;
        }

        SignalBuilder signalType(SignalType value) {
            this.signalType = value;
            return this;
        }

        SignalBuilder issueFamily(IssueFamily value) {
            this.issueFamily = value;
            return this;
        }

        SignalBuilder payload(SignalPayload value) {
            this.payload = value;
            return this;
        }

        SignalBuilder evidence(List<UUID> value) {
            this.evidence = value;
            return this;
        }

        SignalBuilder decisionSpec(String id, Integer version, UUID executionId) {
            this.decisionSpecId = id;
            this.decisionSpecVersion = version;
            this.jevExecutionId = executionId;
            return this;
        }

        SignalBuilder generativeExecution(UUID executionId) {
            this.generativeExecutionId = executionId;
            return this;
        }

        SignalBuilder verificationOutcome(String value) {
            this.verificationOutcome = value;
            return this;
        }

        SignalBuilder resolvedModel(String value) {
            this.resolvedModel = value;
            return this;
        }

        SignalBuilder usage(com.rivell.workbench.intelligence.TokenUsage value) {
            this.usage = value;
            return this;
        }

        SignalBuilder quality(Map<String, Object> value) {
            this.quality = value;
            return this;
        }

        SignalBuilder dedupeSuffix(String value) {
            this.dedupeSuffix = value;
            return this;
        }

        RawReviewSignal build() {
            var check = context.check();
            return new RawReviewSignal(
                    UUID.randomUUID(), check.organizationId(), check.reviewId(),
                    context.runId(), check.id(), signalType, FindingSubjectKind.CLAUSE,
                    check.subjectHeadingNodeId(), null, check.concept(),
                    check.secondaryConcept(), issueFamily, payload, evidence,
                    decisionSpecId, decisionSpecVersion, jevExecutionId,
                    generativeExecutionId, verificationOutcome, resolvedModel, usage,
                    quality, signalType.name() + ":" + check.id() + ":" + dedupeSuffix,
                    context.executedAt());
        }
    }
}
