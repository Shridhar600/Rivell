package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ReviewProfile;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SpecStateMismatchException;
import com.rivell.workbench.knowledge.PositionRole;
import com.rivell.workbench.intelligence.UnknownDecisionSpecException;
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
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.OpenEndedAnalysisPayload;
import com.rivell.workbench.review.PolicyComparisonProperties;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.PositionDeviationPayload;
import com.rivell.workbench.review.RawReviewSignal;
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

    private final SemanticDecisionService decisionService;
    private final PositionLanguageGenerator languageGenerator;
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
        this.languageGenerator = new PositionLanguageGenerator(reasoningService, decisionService);
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
        var secondaryTopic = governsOnlySecondaryTopic(clause, judged.position());
        var signals = new ArrayList<RawReviewSignal>();
        signals.add(signal(context, b -> b
                .signalType(SignalType.POLICY_DEVIATION).issueFamily(family)
                .payload(new PositionDeviationPayload(judged.position().sourceCandidateId(),
                        judged.judgement().category(), judged.judgement().flagProbability(),
                        judged.judgement().probabilities(), secondaryTopic))
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
                        "probabilities", judged.judgement().probabilities(),
                        "secondaryTopic", secondaryTopic))
                .dedupeSuffix(judged.key())));
        if (wantsLanguage(judged.judgement().category())) {
            var drafting = chooseDrafting(bundle.approvedDraftingItems(), judged.position());
            if (drafting != null) {
                signals.add(signal(context, b -> b
                        .signalType(SignalType.APPROVED_DRAFTING_AVAILABLE).issueFamily(family)
                        .payload(new ApprovedDraftingPayload(drafting.evidenceId(), drafting.text(),
                                drafting.variantRole() == null
                                        ? null : drafting.variantRole().name()))
                        .evidence(List.of(drafting.evidenceId()))
                        .quality(Map.of("draftingAvailable", true))
                        .dedupeSuffix(judged.key())));
            } else if (!secondaryTopic) {
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
        var proposal = languageGenerator.propose(clause.contentText(), judged.position().text());
        detail.put("openEnded", proposal.isPresent());
        if (proposal.isEmpty()) {
            // no approved wording and no machine candidate: the finding escalates to a human
            return null;
        }
        var generated = proposal.get();
        var verification = generated.verification();
        return signal(context, b -> b
                .signalType(SignalType.OPEN_ENDED_ANALYSIS_RESULT).issueFamily(family)
                .payload(new OpenEndedAnalysisPayload(generated.proposedLanguage(),
                        generated.explanation(), verification.outcome()))
                .evidence(evidence)
                .generativeExecution(generated.generationExecutionId())
                .verificationOutcome(verification.outcome())
                .resolvedModel(generated.metadata().resolvedModel())
                .usage(generated.metadata().usage())
                .quality(Map.of("openEnded", true,
                        "verificationOutcome", verification.outcome(),
                        "verificationExecutionId", verification.executionId() == null
                                ? "" : verification.executionId().toString(),
                        "verificationSatisfiesProbability", verification.satisfiesProbability(),
                        "verificationNewRiskProbability", verification.newRiskProbability()))
                .dedupeSuffix(judged.key()));
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

    /**
     * A position that does not cover the clause's primary topic matched through a topic the
     * clause only touches: its judgment is less reliable, so it becomes a note, never an
     * alarm. A clause without a primary topic, or a position stored before topic sets, counts
     * as a primary match.
     */
    private static boolean governsOnlySecondaryTopic(ClauseSemantics clause, EvidenceItem position) {
        var primary = clause.effectivePrimaryConcept();
        var facts = position.policyFacts();
        return primary.isPresent() && facts != null && !facts.topicKeys().isEmpty()
                && !facts.topicKeys().contains(primary.get().value());
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

    /**
     * Approved wording on the position's own topic, the acceptable second position
     * (fallback) before the ideal one. A clause on two topics resolves variants for both,
     * so wording for another topic must not answer this position.
     */
    private static EvidenceItem chooseDrafting(List<EvidenceItem> items, EvidenceItem position) {
        var sameTopic = items.stream()
                .filter(item -> position.legalConcept() == null || item.legalConcept() == null
                        || position.legalConcept().equals(item.legalConcept()))
                .toList();
        return sameTopic.stream()
                .filter(item -> item.variantRole() == PositionRole.FALLBACK)
                .findFirst()
                .or(() -> sameTopic.stream()
                        .filter(item -> item.variantRole() == PositionRole.PREFERRED)
                        .findFirst())
                .or(() -> sameTopic.stream().findFirst())
                .orElse(null);
    }

    private static List<UUID> subjectEvidence(EvidenceBundle bundle, UUID clauseId) {
        return bundle.currentContractItems().stream()
                .filter(item -> clauseId.equals(item.sourceClauseId()))
                .map(EvidenceItem::evidenceId).toList();
    }

    private static List<UUID> union(List<UUID> first, List<UUID> second) {
        var all = new LinkedHashSet<UUID>();
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

    private static RawReviewSignal signal(
            ReviewCheckContext context, Consumer<PolicySignalBuilder> customiser) {
        var builder = new PolicySignalBuilder(context);
        customiser.accept(builder);
        return builder.build();
    }
}
