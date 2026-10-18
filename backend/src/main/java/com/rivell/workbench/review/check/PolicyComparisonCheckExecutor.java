package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import com.rivell.workbench.review.OurSide;
import com.rivell.workbench.review.PolicyComparisonProperties;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.PolicyUnavailablePayload;
import com.rivell.workbench.review.PositionDeviationPayload;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.ReviewEngineProperties;
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
    private static final String JEV = "JEV";
    private static final String SECOND_OPINION = "SECOND_OPINION";
    private static final String NEEDS_YOUR_LOOK = "NEEDS_YOUR_LOOK";
    private static final String PLAYBOOK_SILENT = "playbook_silent";
    private static final String SEARCH = "SEARCH";
    /** Search-found positions get their own keys, so their signals never merge with topic ones. */
    private static final String SEARCH_KEY_PREFIX = "search_";

    private final SemanticDecisionService decisionService;
    private final PositionLanguageGenerator languageGenerator;
    private final UncoveredClauseTriage triage;
    private final EvidenceService evidenceService;
    private final ConceptRegistry conceptRegistry;
    private final PolicyComparisonProperties properties;
    private final ContractIssueSpotter spotter;
    private final ReviewEngineProperties.Routing routing;
    private final boolean definitionsEnabled;
    private final LlmAdjudicator adjudicator;
    private final AdjudicationBudget budget;

    public PolicyComparisonCheckExecutor(
            SemanticDecisionService decisionService,
            GenerativeReasoningService reasoningService,
            EvidenceService evidenceService,
            ConceptRegistry conceptRegistry,
            PolicyComparisonProperties properties,
            ReviewEngineProperties engineProperties) {
        this.decisionService = decisionService;
        this.languageGenerator = new PositionLanguageGenerator(reasoningService, decisionService);
        this.triage = new UncoveredClauseTriage(decisionService);
        this.evidenceService = evidenceService;
        this.conceptRegistry = conceptRegistry;
        this.properties = properties;
        this.spotter = new ContractIssueSpotter(reasoningService, evidenceService);
        this.routing = engineProperties.routing();
        this.definitionsEnabled = engineProperties.definitions();
        this.adjudicator = new LlmAdjudicator(reasoningService);
        this.budget = new AdjudicationBudget(routing.maxAdjudications());
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
            // no topic means no position can match, but the clause may still be unusual and
            // against us ("the only remedy is damages"): the uncovered-clause triage reads it
            return compare(context, clause,
                    evidenceService.buildForUncoveredClause(request(context, clause, ClauseTopics.NO_TOPIC)), detail);
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
        var state = comparisonState(context, clause);
        // engine v2 P3: the judge reads the definitions of the terms the clause uses; with the
        // property off the state stays as before, and the triage and probe keep it always
        var definitions = definitionsEnabled
                ? ClauseDefinitions.of(bundle) : ClauseDefinitions.empty();
        var judgeState = comparisonState(context, clause, definitions.texts());
        var definitionsUsed = new boolean[1];
        var judgedPositions = new ArrayList<JudgedPosition>(positions.size());
        Map<String, PolicyComparisonOutcome.Judgement> probe = Map.of();
        if (!positions.isEmpty()) {
            DecisionExecution execution;
            try {
                execution = decisionService.evaluate(PolicyComparisonSpecs.spec(
                        PolicyPositionQuestions.build(positions, clause, conceptRegistry)), judgeState);
                definitionsUsed[0] |= !definitions.isEmpty();
            } catch (SpecStateMismatchException | UnknownDecisionSpecException
                    | NoProviderConfiguredException e) {
                return CheckOutcome.failed(
                        "decision_contract_error:" + e.getClass().getSimpleName());
            } catch (SemanticDecisionException e) {
                return providerUnavailable(context, clause, bundle, positions, detail, e);
            }
            var missing = judge(positions, execution, bundle, "", judgedPositions);
            if (missing != null) {
                return CheckOutcome.failed("position_answer_missing:" + missing);
            }
            probe = probe(clause, positions, state, detail);
        }
        // engine v2 (spec §6, SEARCH route): no topic-paired position addresses the clause, so the
        // playbook is searched by the clause's text once; Jev judges what is found the same way
        EvidenceBundle searchBundle = null;
        var searchFailed = false;
        if (judgedPositions.stream().noneMatch(PolicyComparisonCheckExecutor::addresses)) {
            searchBundle = evidenceService.buildForPositionSearch(bundle.request(),
                    judgedPositions.stream().map(judged -> judged.position().sourceCandidateId())
                            .collect(java.util.stream.Collectors.toSet()),
                    removedTopicKeys(clause),
                    properties.searchLimit());
            var found = searchBundle.policyItems();
            var search = new LinkedHashMap<String, Object>();
            search.put("found", found.size());
            search.put("traceId", searchBundle.traceId() == null
                    ? "" : searchBundle.traceId().toString());
            detail.put("search", search);
            if (!found.isEmpty()) {
                detail.put("route", SEARCH);
                try {
                    var execution = decisionService.evaluate(PolicyComparisonSpecs.spec(
                            PolicyPositionQuestions.build(found, clause, conceptRegistry)), judgeState);
                    definitionsUsed[0] |= !definitions.isEmpty();
                    var missing = judge(found, execution, searchBundle, SEARCH_KEY_PREFIX,
                            judgedPositions);
                    if (missing != null) {
                        return CheckOutcome.failed("position_answer_missing:" + missing);
                    }
                } catch (SpecStateMismatchException | UnknownDecisionSpecException
                        | NoProviderConfiguredException e) {
                    return CheckOutcome.failed(
                            "decision_contract_error:" + e.getClass().getSimpleName());
                } catch (SemanticDecisionException e) {
                    // never an invented "not addressed": nobody judged the found positions
                    searchFailed = true;
                    search.put("outcome", "FAILED");
                }
            }
        }
        // the clause marks read this: a clause a position addresses was checked against the playbook
        var addressed = judgedPositions.stream().anyMatch(PolicyComparisonCheckExecutor::addresses);
        detail.put("addressed", addressed);
        var opinions = secondOpinions(context, clause, judgeState, judgedPositions,
                definitions.texts(), definitionsUsed);
        if (!judgedPositions.isEmpty()) {
            var probeAnswers = probe;
            detail.put("positions", judgedPositions.stream()
                    .map(judged -> detailEntry(judged, probeAnswers.get(judged.key()),
                            opinions.get(judged.key())))
                    .toList());
        }
        var signals = new ArrayList<RawReviewSignal>();
        var definitionsCarried = !definitions.isEmpty();
        for (var judged : judgedPositions) {
            var opinion = opinions.get(judged.key());
            if (opinion != null && !opinion.agreed()) {
                signals.add(needsYourLook(context, clause, judged, opinion, definitionsCarried));
            } else if (judged.judgement().flagged()) {
                signals.addAll(flaggedSignals(context, clause, judged, opinion, detail,
                        definitionsCarried));
            }
        }
        if (definitionsUsed[0]) {
            detail.put("definitionTerms", definitions.terms());
        }
        var cited = searchBundle == null ? List.of(bundle) : List.of(bundle, searchBundle);
        if (searchFailed) {
            return CheckOutcome.degraded("position_search_comparison_failed", signals, detail,
                    bundle.traceId()).withCitedEvidence(cited);
        }
        if (!addressed) {
            // no position addresses the clause: the playbook is silent here
            signals.addAll(uncovered(context, clause, bundle, state, detail));
            // the playbook judged nothing here: the whole-contract read may still point at it
            signals.addAll(spotted(context, clause, bundle, signals, detail));
        }
        return CheckOutcome.completed(signals, detail, bundle.traceId()).withCitedEvidence(cited);
    }

    /**
     * Reads one comparison call's answers into judged positions, each keeping the bundle that
     * cites it and the call that judged it. Returns the missing question key, or null.
     */
    private String judge(List<EvidenceItem> positions, DecisionExecution execution,
            EvidenceBundle bundle, String keyPrefix, List<JudgedPosition> into) {
        for (int index = 0; index < positions.size(); index++) {
            var questionKey = PolicyPositionQuestions.key(index);
            if (!(execution.answers().get(questionKey) instanceof ChoiceAnswer choice)) {
                return questionKey;
            }
            into.add(new JudgedPosition(keyPrefix + questionKey, positions.get(index),
                    PolicyComparisonOutcome.judge(choice, properties.flagThreshold()),
                    choice.confidence(), bundle, execution, !keyPrefix.isEmpty()));
        }
        return null;
    }

    /**
     * The whole-contract read named this clause, no position addresses it and no other signal
     * exists for it: a LOW advisory "needs your look" item. Where a position judged the clause,
     * that judgment stands (dev eval: letting the read override it put MEDIUM+ false alarms at
     * 35%). Never a verdict on a position, never wording.
     */
    private List<RawReviewSignal> spotted(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            List<RawReviewSignal> signals, Map<String, Object> detail) {
        if (!signals.isEmpty()) {
            return List.of();
        }
        var spot = spotter.spots(context, bundle.request()).get(clause.headingNodeId());
        var evidence = subjectEvidence(bundle, clause.id());
        if (spot == null || evidence.isEmpty()) {
            return List.of();
        }
        detail.put("spotted", spot.severity());
        if (properties.advisorySpots() == PolicyComparisonProperties.AdvisorySpots.HIGH
                && !"HIGH".equals(spot.severity())) {
            // the deployment wants only the read's own HIGH picks; the detail keeps the pick
            return List.of();
        }
        var quality = new LinkedHashMap<String, Object>();
        quality.put("spottedSeverity", spot.severity());
        quality.put("spottedReason", spot.reason());
        quality.put("route", NEEDS_YOUR_LOOK);
        quality.put("uncertaintyReason", PLAYBOOK_SILENT);
        if (detail.get("uncoveredProbability") instanceof Double probability) {
            quality.put("uncoveredProbability", probability);
        }
        return List.of(signal(context, b -> b
                .signalType(SignalType.POLICY_UNAVAILABLE).issueFamily(IssueFamily.POLICY_UNAVAILABLE)
                .payload(new PolicyUnavailablePayload(PolicyUnavailablePayload.SPOTTED))
                .evidence(evidence)
                .generativeExecution(spot.executionId())
                .quality(quality)
                .dedupeSuffix("spotted")));
    }

    /**
     * Engine v2 (spec §6): every position Jev was unsure about gets one second opinion, within the
     * run's budget; over budget it goes to the user without a call. The definitions the judging
     * state carried go to every call made.
     */
    private Map<String, LlmAdjudicator.SecondOpinion> secondOpinions(
            ReviewCheckContext context, ClauseSemantics clause,
            PolicyComparisonSpecs.PolicyComparisonState state, List<JudgedPosition> judgedPositions,
            List<String> definitions, boolean[] definitionsUsed) {
        var opinions = new LinkedHashMap<String, LlmAdjudicator.SecondOpinion>();
        for (var judged : judgedPositions) {
            if (DecisionRouter.route(judged.judgement(), addresses(judged), routing)
                    != DecisionRouter.Route.ADJUDICATE) {
                continue;
            }
            if (!budget.take(context.runId())) {
                opinions.put(judged.key(),
                        LlmAdjudicator.SecondOpinion.failed(LlmAdjudicator.SecondOpinion.OVER_BUDGET));
                continue;
            }
            definitionsUsed[0] |= !definitions.isEmpty();
            opinions.put(judged.key(), adjudicator.ask(new LlmAdjudicator.Ask(
                    OurSide.describe(context.snapshot().ourSideRole()),
                    state.contractType(), state.heading(), state.clause(),
                    PolicyPositionQuestions.instructions(judged.position(), clause,
                            conceptRegistry),
                    definitions),
                    judged.judgement()));
        }
        return opinions;
    }

    /**
     * Jev and the second opinion disagree, or the second opinion could not be had (failed, invalid,
     * over budget): the position becomes a LOW "needs your look" item with both views, never a
     * MEDIUM+ finding and never a silent pass (spec §6 HUMAN, §7). No null goes into the quality.
     */
    private RawReviewSignal needsYourLook(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            LlmAdjudicator.SecondOpinion opinion, boolean definitionsCarried) {
        var bundle = judged.bundle();
        var execution = judged.execution();
        // when the judge read this position's own bundle's definitions, the finding cites them
        var evidence = definitionsCarried
                ? union(union(List.of(judged.position().evidenceId()),
                        subjectEvidence(bundle, clause.id())),
                        ClauseDefinitions.of(bundle).evidenceIds())
                : union(List.of(judged.position().evidenceId()),
                        subjectEvidence(bundle, clause.id()));
        validateEvidence(bundle, evidence);
        var jev = judged.judgement();
        var quality = new LinkedHashMap<String, Object>();
        quality.put("route", NEEDS_YOUR_LOOK);
        if (judged.searched()) {
            quality.put("foundBy", SEARCH);
        }
        quality.put("jevCategory", jevView(jev));
        quality.put("flagProbability", jev.flagProbability());
        quality.put("probabilities", jev.probabilities());
        quality.put("secondOpinionOutcome", opinion.outcome());
        if (opinion.answered()) {
            quality.put("secondOpinionCategory", opinion.category());
            quality.put("secondOpinionQuote", opinion.quote());
            quality.put("secondOpinionReason", opinion.reason());
        } else {
            quality.put("uncertaintyReason", DeviationUncertainPayload.SECOND_OPINION_UNAVAILABLE);
        }
        return signal(context, b -> b
                .signalType(SignalType.DEVIATION_UNCERTAIN)
                .issueFamily(IssueFamily.POSITION_UNCERTAIN)
                .payload(new DeviationUncertainPayload(opinion.answered()
                        ? DeviationUncertainPayload.SECOND_OPINION_DISAGREES
                        : DeviationUncertainPayload.SECOND_OPINION_UNAVAILABLE))
                .evidence(evidence)
                .decisionSpec(PolicyComparisonSpecs.POLICY_COMPARISON_SPEC,
                        PolicyComparisonSpecs.POLICY_COMPARISON_VERSION, execution.executionId())
                .generativeExecution(opinion.executionId())
                .resolvedModel(execution.metadata().resolvedModel())
                .usage(execution.metadata().usage())
                .quality(quality)
                .dedupeSuffix(judged.key()));
    }

    /** Jev's own answer: its flagging category, else "meets" or "not addressed", whichever is likelier. */
    private static String jevView(PolicyComparisonOutcome.Judgement jev) {
        if (jev.flagged()) {
            return jev.category();
        }
        var probabilities = jev.probabilities();
        return probabilities.getOrDefault(PolicyComparisonSpecs.MEETS_PREFERRED, 0.0)
                >= probabilities.getOrDefault(PolicyComparisonSpecs.NOT_ADDRESSED, 0.0)
                ? PolicyComparisonSpecs.MEETS_PREFERRED : PolicyComparisonSpecs.NOT_ADDRESSED;
    }

    private List<RawReviewSignal> flaggedSignals(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            LlmAdjudicator.SecondOpinion opinion, Map<String, Object> detail,
            boolean definitionsCarried) {
        var bundle = judged.bundle();
        var execution = judged.execution();
        var family = PolicyComparisonOutcome.family(judged.judgement().category());
        // when the judge read this position's own bundle's definitions, the finding cites them
        var evidence = definitionsCarried
                ? union(union(List.of(judged.position().evidenceId()),
                        subjectEvidence(bundle, clause.id())),
                        ClauseDefinitions.of(bundle).evidenceIds())
                : union(List.of(judged.position().evidenceId()),
                        subjectEvidence(bundle, clause.id()));
        validateEvidence(bundle, evidence);
        // a position the text search found and Jev confirmed was not paired through a topic the
        // clause only touches, so the secondary-topic demotion does not apply to it
        var secondaryTopic = !judged.searched()
                && governsOnlySecondaryTopic(clause, judged.position());
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
                .generativeExecution(opinion == null ? null : opinion.executionId())
                .quality(flaggedQuality(judged, secondaryTopic,
                        opinion == null ? JEV : SECOND_OPINION))
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

    private static boolean addresses(JudgedPosition judged) {
        return PolicyComparisonOutcome.addresses(
                judged.judgement().probabilities(), judged.judgement().flagProbability());
    }

    /**
     * A clause no position addresses becomes a finding only when the triage judges it
     * unusual and against our side.
     */
    private List<RawReviewSignal> uncovered(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            PolicyComparisonSpecs.PolicyComparisonState state, Map<String, Object> detail) {
        var evidence = subjectEvidence(bundle, clause.id());
        var verdict = evidence.isEmpty() ? Optional.<UncoveredClauseTriage.Verdict>empty()
                : triage.assess(state);
        verdict.ifPresent(v -> detail.put("uncoveredProbability", v.probability()));
        if (verdict.isEmpty() || verdict.get().probability() < properties.uncoveredThreshold()) {
            return List.of();
        }
        var execution = verdict.get().execution();
        return List.of(signal(context, b -> b
                .signalType(SignalType.POLICY_UNAVAILABLE).issueFamily(IssueFamily.POLICY_UNAVAILABLE)
                .payload(new PolicyUnavailablePayload(PolicyUnavailablePayload.NOT_COVERED))
                .evidence(evidence)
                .decisionSpec(PolicyComparisonSpecs.UNCOVERED_CLAUSE_SPEC,
                        PolicyComparisonSpecs.UNCOVERED_CLAUSE_VERSION, execution.executionId())
                .resolvedModel(execution.metadata().resolvedModel())
                .usage(execution.metadata().usage())
                .quality(Map.of("uncoveredProbability", verdict.get().probability(), "route", JEV))
                .dedupeSuffix("uncovered")));
    }

    /**
     * Measurement only (engine v2 P0): the same comparison asked again, options reversed or
     * as asked. The answers go to the check detail beside the real ones. They never change a
     * finding, and a failed probe only marks the detail.
     */
    private Map<String, PolicyComparisonOutcome.Judgement> probe(
            ClauseSemantics clause, List<EvidenceItem> positions,
            PolicyComparisonSpecs.PolicyComparisonState state, Map<String, Object> detail) {
        var mode = properties.orderProbe();
        if (mode == PolicyComparisonProperties.OrderProbe.OFF) {
            return Map.of();
        }
        DecisionExecution execution;
        try {
            execution = decisionService.evaluate(PolicyComparisonSpecs.orderProbe(
                    PolicyPositionQuestions.build(positions, clause, conceptRegistry,
                            mode == PolicyComparisonProperties.OrderProbe.REVERSED)), state);
        } catch (SemanticDecisionException e) {
            detail.put("probe", "UNAVAILABLE");
            return Map.of();
        }
        var judged = new LinkedHashMap<String, PolicyComparisonOutcome.Judgement>();
        for (int index = 0; index < positions.size(); index++) {
            var key = PolicyPositionQuestions.key(index);
            if (execution.answers().get(key) instanceof ChoiceAnswer choice) {
                judged.put(key, PolicyComparisonOutcome.judge(choice, properties.flagThreshold()));
            }
        }
        detail.put("probe", mode.name());
        return judged;
    }

    private RawReviewSignal generate(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            IssueFamily family, List<UUID> evidence, Map<String, Object> detail) {
        var headingText = context.headingTexts().getOrDefault(clause.headingNodeId(), "");
        var runInHeading = context.runInHeadings().get(clause.headingNodeId());
        var body = PositionLanguageGenerator.body(clause.contentText(), headingText, runInHeading);
        if (context.untrackableHeadings().contains(clause.headingNodeId()) || body.isBlank()) {
            // a tracked change cannot carry new wording here: the finding goes to a human
            detail.put("openEnded", false);
            return null;
        }
        var proposal = languageGenerator.propose(new PositionLanguageGenerator.Draft(
                OurSide.describe(context.snapshot().ourSideRole()),
                context.snapshot().contractType().label(),
                runInHeading != null ? runInHeading : headingText, body,
                judged.position().text()));
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
                        "verificationRemovalHurtsProbability", verification.removalHurtsProbability(),
                        "verificationAdditionHurtsProbability",
                        verification.additionHurtsProbability()))
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
        return primary.isPresent() && !position.topicKeys().isEmpty()
                && !position.topicKeys().contains(primary.get().value());
    }

    /**
     * DW-085: a topic the person removed from this clause must not come back through the text
     * search, so positions carrying it are excluded from this clause's search candidates.
     */
    private static java.util.Set<String> removedTopicKeys(ClauseSemantics clause) {
        return ClauseTopics.removedTopics(clause).stream()
                .map(ConceptKey::value).collect(java.util.stream.Collectors.toSet());
    }

    private static boolean wantsLanguage(String category) {
        return PolicyComparisonSpecs.WALK_AWAY.equals(category)
                || PolicyComparisonSpecs.BEYOND_FALLBACK.equals(category);
    }

    private static Map<String, Object> flaggedQuality(
            JudgedPosition judged, boolean secondaryTopic, String route) {
        var quality = new LinkedHashMap<String, Object>();
        quality.put("flagged", true);
        quality.put("category", judged.judgement().category());
        quality.put("flagProbability", judged.judgement().flagProbability());
        quality.put("confidence", judged.confidence());
        quality.put("probabilities", judged.judgement().probabilities());
        quality.put("secondaryTopic", secondaryTopic);
        quality.put("route", route);
        if (judged.searched()) {
            quality.put("foundBy", SEARCH);
        }
        return quality;
    }

    /**
     * One position judged against the clause: the bundle that cites it, the call that judged it,
     * and whether the text search (not a topic) found it.
     */
    private record JudgedPosition(
            String key, EvidenceItem position, PolicyComparisonOutcome.Judgement judgement,
            double confidence, EvidenceBundle bundle, DecisionExecution execution,
            boolean searched) {
    }

    private static Map<String, Object> detailEntry(
            JudgedPosition judged, PolicyComparisonOutcome.Judgement probe,
            LlmAdjudicator.SecondOpinion opinion) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("positionKey", judged.key());
        entry.put("positionCandidateId", judged.position().sourceCandidateId() == null
                ? "" : judged.position().sourceCandidateId().toString());
        entry.put("category", judged.judgement().category() == null
                ? "" : judged.judgement().category());
        entry.put("flagProbability", judged.judgement().flagProbability());
        entry.put("probabilities", judged.judgement().probabilities());
        entry.put("flagged", judged.judgement().flagged());
        if (probe != null) {
            entry.put("probeCategory", probe.category() == null ? "" : probe.category());
            entry.put("probeFlagged", probe.flagged());
            entry.put("probeProbabilities", probe.probabilities());
        }
        entry.put("route", opinion == null ? "ACCEPT" : "ADJUDICATE");
        if (judged.searched()) {
            var laneInfo = judged.position().laneInfo();
            // a lane that did not find the position has no rank; it is left out, never 0
            var lanes = new LinkedHashMap<String, Object>();
            if (laneInfo.lexicalRank() != null) {
                lanes.put("lexicalRank", laneInfo.lexicalRank());
            }
            if (laneInfo.vectorRank() != null) {
                lanes.put("vectorRank", laneInfo.vectorRank());
            }
            if (laneInfo.fusedRank() != null) {
                lanes.put("fusedRank", laneInfo.fusedRank());
            }
            entry.put("foundBy", SEARCH);
            entry.put("lanes", lanes);
        }
        if (opinion != null) {
            entry.put("secondOpinionOutcome", opinion.outcome());
            entry.put("secondOpinionCategory", opinion.category() == null ? "" : opinion.category());
        }
        return entry;
    }

    /**
     * The comparison state of one clause, shared with the omissions lane: the engine frozen
     * values plus the clause's own heading and text. The definitions are read by the judge only
     * (engine v2 P3); the omissions lane keeps the plain, definitions-free state.
     */
    static PolicyComparisonSpecs.PolicyComparisonState comparisonState(
            ReviewCheckContext context, ClauseSemantics clause) {
        return comparisonState(context, clause, List.of());
    }

    static PolicyComparisonSpecs.PolicyComparisonState comparisonState(
            ReviewCheckContext context, ClauseSemantics clause, List<String> definitions) {
        return new PolicyComparisonSpecs.PolicyComparisonState(
                PolicyComparisonSpecs.STATE_SCHEMA_VERSION,
                context.snapshot().contractType().label(),
                context.snapshot().ourSideRole() == null
                        ? "" : context.snapshot().ourSideRole().name(),
                context.headingTexts().getOrDefault(clause.headingNodeId(), ""),
                truncate(clause.contentText(), MAX_CLAUSE_LENGTH),
                definitions);
    }

    static EvidenceRequest request(
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
     * Approved wording that shares a topic with the position, the acceptable second
     * position (fallback) before the ideal one. A clause on two topics resolves variants
     * for both, so wording for another topic must not answer this position.
     */
    private static EvidenceItem chooseDrafting(List<EvidenceItem> items, EvidenceItem position) {
        var sameTopic = items.stream()
                .filter(item -> item.topicKeys().stream().anyMatch(position.topicKeys()::contains))
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
