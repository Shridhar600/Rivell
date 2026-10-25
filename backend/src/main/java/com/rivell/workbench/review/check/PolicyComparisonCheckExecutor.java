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
import com.rivell.workbench.intelligence.ProviderFailures;
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
 * become findings with approved fallback wording when the clause library carries it and
 * it fits the position, verified machine language otherwise, and escalation whenever the
 * position names an approver. A search-found position is applied only when it governs the
 * clause's subject; anything else stays in the diagnostics as "not covered". Provider
 * failure is recorded per position, never a silent pass.
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
    /** Severity R3 lever tags (P7) and the route reason of a position P2 sent to the second opinion. */
    private static final String P1 = "P1";
    private static final String P2 = "P2";
    private static final String P2_WITHIN_SPLIT = "P2_WITHIN_SPLIT";

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
    private final boolean agreedWorseCategory;
    private final boolean fallbackConditionsEnabled;
    private final int maxConditionChecks;
    private final FallbackConditionChecker conditionChecker;
    private final AdjudicationBudget conditionBudget;
    /** P2's own second-opinion budget, so its extra calls never use up the one the doubt band needs. */
    private final AdjudicationBudget splitBudget;
    /** The one fit check both lanes use: search hits must govern, library wording must meet and cure. */
    private final ClauseFitCheck fitCheck;

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
        this.agreedWorseCategory = engineProperties.severity().agreedWorseCategory();
        this.fallbackConditionsEnabled = engineProperties.fallbackConditions().enabled();
        this.maxConditionChecks = engineProperties.fallbackConditions().maxChecks();
        this.splitBudget = new AdjudicationBudget(routing.maxAdjudications());
        this.conditionChecker = new FallbackConditionChecker(reasoningService);
        this.conditionBudget = new AdjudicationBudget(engineProperties.fallbackConditions().maxChecks());
        this.fitCheck = new ClauseFitCheck(decisionService);
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
                    context.evidenceCache().buildForUncoveredClause(
                            request(context, clause, ClauseTopics.NO_TOPIC)), detail);
        }
        var primary = clause.effectivePrimaryConcept().orElse(null);
        var bundle = context.evidenceCache().buildForPolicyComparison(
                request(context, clause, primary == null ? topics.getFirst() : primary),
                topics, primary, properties.maxPositions());
        return compare(context, clause, bundle, detail);
    }

    private CheckOutcome compare(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            Map<String, Object> detail) {
        var positions = bundle.policyItems();
        // reviews stay reproducible: the lever settings this comparison ran with are kept with it
        detail.put("levers", Map.of("agreedWorseCategory", agreedWorseCategory,
                "withinSplitMargin", routing.withinSplitMargin(),
                "fallbackConditions", fallbackConditionsEnabled,
                "fallbackConditionsMaxChecks", maxConditionChecks));
        detail.put("positionCount", positions.size());
        detail.put("positionsTruncated",
                bundle.degradationWarnings().contains("POLICY_POSITIONS_TRUNCATED"));
        var state = comparisonState(context, clause);
        // engine v2 P3: the judge reads the definitions of the terms the clause uses; with the
        // property off the judge state stays as before. The second opinion always reads them: it
        // is the fallback that decides whether the clause's own words were enough.
        var definitions = ClauseDefinitions.of(bundle);
        var judgeDefinitions = definitionsEnabled ? definitions.texts() : List.<String>of();
        var judgeState = comparisonState(context, clause, judgeDefinitions);
        var definitionsUsed = new boolean[1];
        var judgedPositions = new ArrayList<JudgedPosition>(positions.size());
        Map<String, PolicyComparisonOutcome.Judgement> probe = Map.of();
        if (!positions.isEmpty()) {
            DecisionExecution execution;
            try {
                execution = decisionService.evaluate(PolicyComparisonSpecs.spec(
                        PolicyPositionQuestions.build(positions, clause, conceptRegistry)), judgeState);
                definitionsUsed[0] |= !judgeDefinitions.isEmpty();
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
        Map<String, Object> searchDetail = null;
        if (judgedPositions.stream().noneMatch(PolicyComparisonCheckExecutor::addresses)) {
            searchBundle = context.evidenceCache().buildForPositionSearch(bundle.request(),
                    judgedPositions.stream().map(judged -> judged.position().sourceCandidateId())
                            .collect(java.util.stream.Collectors.toSet()),
                    removedTopicKeys(clause),
                    properties.searchLimit());
            var found = searchBundle.policyItems();
            var search = new LinkedHashMap<String, Object>();
            search.put("found", found.size());
            search.put("traceId", searchBundle.traceId() == null
                    ? "" : searchBundle.traceId().toString());
            searchDetail = search;
            detail.put("search", search);
            // the route records that the search ran, whatever it found (DW-071)
            detail.put("route", SEARCH);
            if (!found.isEmpty()) {
                try {
                    var execution = decisionService.evaluate(PolicyComparisonSpecs.spec(
                            PolicyPositionQuestions.build(found, clause, conceptRegistry)), judgeState);
                    definitionsUsed[0] |= !judgeDefinitions.isEmpty();
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
                    ProviderFailures.rethrowIfOutOfCredit(e);
                    // never an invented "not addressed": nobody judged the found positions
                    searchFailed = true;
                    search.put("outcome", "FAILED");
                }
            }
        }
        var opinions = secondOpinions(context, clause, judgeState, judgedPositions,
                definitions.texts(), definitionsUsed);
        var resolutions = new LinkedHashMap<String, Resolution>();
        var contractFacts = fallbackConditionsEnabled ? contractFacts(context) : List.<String>of();
        for (var judged : judgedPositions) {
            resolutions.put(judged.key(), resolve(context, clause, judgeState, judged, opinions,
                    contractFacts));
        }
        // one fit pack for the clause: the positions the text search found and the library
        // wording a flagged position would offer (engine decision §B.1)
        var wordingByKey = wordingFor(clause, judgedPositions, resolutions);
        var fit = fit(context, clause, judgedPositions, wordingByKey);
        putFitSummary(detail, fit);
        if (searchDetail != null) {
            searchDetail.put("notCovered", (int) judgedPositions.stream()
                    .filter(judged -> judged.searched() && !applied(judged, fit)).count());
        }
        // the clause marks read this: a clause a position addresses was checked against the playbook
        var addressed = judgedPositions.stream()
                .anyMatch(judged -> addresses(judged) && applied(judged, fit));
        detail.put("addressed", addressed);
        if (!judgedPositions.isEmpty()) {
            var probeAnswers = probe;
            detail.put("positions", judgedPositions.stream()
                    .map(judged -> detailEntry(judged, probeAnswers.get(judged.key()),
                            opinions.byKey().get(judged.key()), resolutions.get(judged.key()), fit,
                            wordingByKey))
                    .toList());
        }
        var signals = new ArrayList<RawReviewSignal>();
        var judgeDefinitionsCarried = !judgeDefinitions.isEmpty();
        var opinionDefinitionsCarried = !definitions.isEmpty();
        for (var judged : judgedPositions) {
            if (!applied(judged, fit)) {
                // a search hit that does not govern the clause is not applied; the detail keeps it
                continue;
            }
            var opinion = opinions.effective(judged.key());
            var resolution = resolutions.get(judged.key());
            if (opinion != null && !opinion.agreed()) {
                signals.add(needsYourLook(context, clause, judged, opinion,
                        opinionDefinitionsCarried, resolution, fit));
            } else if (judged.judgement().flagged()) {
                signals.addAll(flaggedSignals(context, clause, judged, opinion, detail,
                        judgeDefinitionsCarried || (opinion != null && opinionDefinitionsCarried),
                        resolution, wordingByKey, fit));
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
            var uncovered = uncovered(context, clause, bundle, state, detail);
            signals.addAll(uncovered.signals());
            // the playbook judged nothing here: the whole-contract read may still point at it
            signals.addAll(spotted(context, clause, bundle, signals, detail));
            if (uncovered.unavailable()) {
                return CheckOutcome.degraded("uncovered_triage_unavailable", signals, detail,
                        bundle.traceId()).withCitedEvidence(cited);
            }
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
        var spotResult = spotter.spots(context, bundle.request());
        if (spotResult.unavailable()) {
            detail.put("spottingUnavailable", true);
            return List.of();
        }
        // An issue the read could not ground in the contract is dropped, and the check says how
        // many were dropped rather than quietly guessing a clause for them.
        detail.put("spottingDropped", spotResult.dropped());
        var spots = spotResult.spots().get(clause.headingNodeId());
        var evidence = subjectEvidence(bundle, clause.id());
        if (spots == null || spots.isEmpty() || evidence.isEmpty()) {
            return List.of();
        }
        detail.put("spotted", highestSeverity(spots));
        var kept = properties.advisorySpots() == PolicyComparisonProperties.AdvisorySpots.HIGH
                ? spots.stream().filter(spot -> "HIGH".equals(spot.severity())).toList()
                : spots;
        if (kept.isEmpty()) {
            // the deployment wants only the read's own HIGH picks; the detail keeps the pick
            return List.of();
        }
        var out = new ArrayList<RawReviewSignal>(kept.size());
        for (int index = 0; index < kept.size(); index++) {
            var spot = kept.get(index);
            var quality = new LinkedHashMap<String, Object>();
            quality.put("spottedSeverity", spot.severity());
            quality.put("spottedReason", spot.reason());
            quality.put("route", NEEDS_YOUR_LOOK);
            quality.put("uncertaintyReason", PLAYBOOK_SILENT);
            if (detail.get("uncoveredProbability") instanceof Double probability) {
                quality.put("uncoveredProbability", probability);
            }
            var suffix = index == 0 ? "spotted" : "spotted-" + index;
            out.add(signal(context, b -> b
                    .signalType(SignalType.POLICY_UNAVAILABLE).issueFamily(IssueFamily.POLICY_UNAVAILABLE)
                    .payload(new PolicyUnavailablePayload(PolicyUnavailablePayload.SPOTTED))
                    .evidence(evidence)
                    .generativeExecution(spot.executionId())
                    .quality(quality)
                    .dedupeSuffix(suffix)));
        }
        return out;
    }

    private static String highestSeverity(List<ContractIssueSpotter.Spot> spots) {
        return spots.stream().anyMatch(spot -> "HIGH".equals(spot.severity()))
                ? "HIGH"
                : spots.getFirst().severity();
    }

    /**
     * Engine v2 (spec §6): every position Jev was unsure about gets one second opinion, within the
     * run's budget; over budget it goes to the user without a call. The definitions the judging
     * state carried go to every call made.
     */
    private Opinions secondOpinions(
            ReviewCheckContext context, ClauseSemantics clause,
            PolicyComparisonSpecs.PolicyComparisonState state, List<JudgedPosition> judgedPositions,
            List<String> definitions, boolean[] definitionsUsed) {
        var opinions = new LinkedHashMap<String, LlmAdjudicator.SecondOpinion>();
        var splitRouted = new java.util.HashSet<String>();
        // the doubt band asks first: P2's extra calls never use up the budget it needs
        for (var splitPass : new boolean[] {false, true}) {
            for (var judged : judgedPositions) {
                var addresses = addresses(judged);
                var bySplit = DecisionRouter.routedByWithinSplit(judged.judgement(), addresses, routing);
                if (bySplit != splitPass || DecisionRouter.route(judged.judgement(), addresses, routing)
                        != DecisionRouter.Route.ADJUDICATE) {
                    continue;
                }
                if (bySplit) {
                    splitRouted.add(judged.key());
                }
                if (!(bySplit ? splitBudget : budget).take(context.runId())) {
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
        }
        return new Opinions(opinions, splitRouted);
    }

    /**
     * The second opinions of one clause by position key, and which of those positions only P2 sent.
     * P2 is a speculative extra look at a judgement Jev was sure of: when it gets no answer (failed,
     * invalid, over budget) Jev's finding stands as it was, so {@link #effective} hides that opinion.
     */
    private record Opinions(Map<String, LlmAdjudicator.SecondOpinion> byKey, java.util.Set<String> splitRouted) {

        LlmAdjudicator.SecondOpinion effective(String key) {
            var opinion = byKey.get(key);
            return opinion != null && splitRouted.contains(key) && !opinion.answered() ? null : opinion;
        }
    }

    /**
     * Severity R3 on one judged position. P1: an agreed second opinion lends its category when it is
     * worse. P3: a final WITHIN_FALLBACK on the clause's own topic is checked against the conditions of
     * its fallback; the conditions are recorded on the finding and never change its category.
     * {@code levers} lists the levers whose work this finding's category or kind depends on, so a
     * counterfactual can drop them; a lever that could not answer changes nothing.
     */
    private Resolution resolve(
            ReviewCheckContext context, ClauseSemantics clause,
            PolicyComparisonSpecs.PolicyComparisonState state, JudgedPosition judged, Opinions opinions,
            List<String> contractFacts) {
        var opinion = opinions.effective(judged.key());
        var routeReason = opinions.splitRouted().contains(judged.key()) ? P2_WITHIN_SPLIT : null;
        var levers = new ArrayList<String>();
        if (opinion != null && !opinion.agreed()) {
            // Jev's WITHIN note became a "needs your look" item because P2 asked
            if (routeReason != null) {
                levers.add(P2);
            }
            return new Resolution(null, List.copyOf(levers), routeReason, null, null);
        }
        if (!judged.judgement().flagged()) {
            return new Resolution(null, List.of(), routeReason, null, null);
        }
        var jevCategory = judged.judgement().category();
        var category = jevCategory;
        // with P1 off a finding carries nothing of the second opinion's category, as before P7
        var secondOpinionCategory = agreedWorseCategory && opinion != null && opinion.answered()
                ? opinion.category() : null;
        if (secondOpinionCategory != null) {
            category = PolicyComparisonOutcome.worse(jevCategory, secondOpinionCategory);
            if (!category.equals(jevCategory)) {
                levers.add(P1);
                if (routeReason != null) {
                    levers.add(P2);
                }
            }
        }
        FallbackConditionChecker.Result conditions = null;
        if (fallbackConditionsEnabled && PolicyComparisonSpecs.WITHIN_FALLBACK.equals(category)
                && (judged.searched() || !governsOnlySecondaryTopic(clause, judged.position()))) {
            conditions = checkConditions(context, clause, state, judged, contractFacts);
        }
        return new Resolution(category, List.copyOf(levers), routeReason, secondOpinionCategory, conditions);
    }

    private FallbackConditionChecker.Result checkConditions(
            ReviewCheckContext context, ClauseSemantics clause,
            PolicyComparisonSpecs.PolicyComparisonState state, JudgedPosition judged,
            List<String> contractFacts) {
        if (!conditionBudget.take(context.runId())) {
            return FallbackConditionChecker.Result.failed(FallbackConditionChecker.Result.OVER_BUDGET);
        }
        var listed = FallbackConditionChecker.listed(judged.position().policyFacts());
        return conditionChecker.check(new FallbackConditionChecker.Ask(
                OurSide.describe(context.snapshot().ourSideRole()), state.contractType(), state.heading(),
                state.clause(), PolicyPositionQuestions.instructions(judged.position(), clause, conceptRegistry),
                listed, Boolean.TRUE.equals(listed) ? judged.position().policyFacts().fallbacks().size() : null,
                contractFacts));
    }

    /**
     * What the contract says about itself outside the clause, as quotable lines for the P3 check: our
     * side, the parties with their roles and the jurisdiction declared for the review. The governing-law
     * metadata is left out because it repeats the governing-law clause's own words; the declared
     * jurisdiction above is the review's context, not a claim about the clause.
     */
    private static List<String> contractFacts(ReviewCheckContext context) {
        var facts = new ArrayList<String>();
        var snapshot = context.snapshot();
        if (snapshot.ourSideRole() != null) {
            facts.add("Our side: " + snapshot.ourSideRole().name());
        }
        for (var party : context.semantics().parties()) {
            var roles = party.effectiveRoles().stream().map(Enum::name).toList();
            facts.add("Party: " + party.rawName() + (roles.isEmpty() ? "" : " (" + String.join(", ", roles) + ")"));
        }
        facts.add("Jurisdiction (declared for this review): " + snapshot.jurisdiction().name());
        return List.copyOf(facts);
    }

    /**
     * The lever work on one judged position. {@code category}: the final category of a flagged finding
     * (null when the position yields none or a "needs your look" item); {@code conditions}: the P3 check
     * when one ran.
     */
    private record Resolution(
            String category, List<String> levers, String routeReason, String secondOpinionCategory,
            FallbackConditionChecker.Result conditions) {
    }

    /**
     * Jev and the second opinion disagree, or the second opinion could not be had (failed, invalid,
     * over budget): the position becomes a LOW "needs your look" item with both views, never a
     * MEDIUM+ finding and never a silent pass (spec §6 HUMAN, §7). No null goes into the quality.
     */
    private RawReviewSignal needsYourLook(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            LlmAdjudicator.SecondOpinion opinion, boolean definitionsCarried, Resolution resolution,
            ClauseFitCheck.Outcome fit) {
        var bundle = judged.bundle();
        var execution = judged.execution();
        // when the definitions of this position's own bundle were read, the finding cites them
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
        if (resolution.routeReason() != null) {
            quality.put("routeReason", resolution.routeReason());
        }
        if (!resolution.levers().isEmpty()) {
            quality.put("levers", resolution.levers());
            quality.put("categoryBeforeLevers", jev.category());
        }
        if (judged.searched()) {
            quality.put("foundBy", SEARCH);
            putFit(quality, fit, judged);
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

    /** The fit verdict of a searched position, written where the finding and the eval read it. */
    private static void putFit(Map<String, Object> quality, ClauseFitCheck.Outcome fit,
            JudgedPosition judged) {
        var judgment = fit.judgments().get(judged.key());
        if (judgment == null) {
            return;
        }
        quality.put("fitVerdict", judgment.verdict().name());
        quality.put("fitReason", judgment.reasons().getFirst().name());
        quality.put("fitReasons", judgment.reasons().stream().map(Enum::name).toList());
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
            boolean definitionsCarried, Resolution resolution,
            Map<String, Wording> wordingByKey, ClauseFitCheck.Outcome fit) {
        var bundle = judged.bundle();
        var execution = judged.execution();
        var category = resolution.category();
        var family = PolicyComparisonOutcome.family(category);
        // when the definitions of this position's own bundle were read, the finding cites them
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
        var wantsLanguage = wantsLanguage(category);
        var wording = wantsLanguage ? wordingByKey.get(judged.key()) : null;
        var drafting = wording == null ? null : wording.item();
        var wordingFit = drafting == null ? null : fit.judgments().get(wordingKey(judged.key()));
        var fitVerdict = wordingFit == null ? ClauseFitCheck.Verdict.UNKNOWN : wordingFit.verdict();
        // a wording that does not fit is replaced by Rivell's own proposal, never offered as
        // approved; an unchecked one is still offered, labelled as unchecked
        LanguageAttempt attempt = wantsLanguage && !secondaryTopic
                && (drafting == null || fitVerdict == ClauseFitCheck.Verdict.DOES_NOT_FIT)
                        ? generate(context, clause, judged, family, evidence, detail)
                        : null;
        var signals = new ArrayList<RawReviewSignal>();
        signals.add(signal(context, b -> b
                .signalType(SignalType.POLICY_DEVIATION).issueFamily(family)
                .payload(new PositionDeviationPayload(judged.position().sourceCandidateId(),
                        category, judged.judgement().flagProbability(),
                        judged.judgement().probabilities(), secondaryTopic))
                .evidence(evidence)
                .decisionSpec(PolicyComparisonSpecs.POLICY_COMPARISON_SPEC,
                        PolicyComparisonSpecs.POLICY_COMPARISON_VERSION,
                        execution.executionId())
                .resolvedModel(execution.metadata().resolvedModel())
                .usage(execution.metadata().usage())
                .generativeExecution(opinion == null ? null : opinion.executionId())
                .quality(flaggedQuality(judged, secondaryTopic,
                        opinion == null ? JEV : SECOND_OPINION, resolution, opinion,
                        attempt == null ? null : attempt.failure(), fit))
                .dedupeSuffix(judged.key())));
        if (drafting != null && !secondaryTopic) {
            var quality = new LinkedHashMap<String, Object>();
            quality.put("draftingAvailable", true);
            quality.put("wordingFitVerdict", fitVerdict.name());
            if (wordingFit != null) {
                quality.put("wordingFitReasons",
                        wordingFit.reasons().stream().map(Enum::name).toList());
                quality.put("wordingFitReason", wordingFit.reasons().getFirst().name());
                if (wordingFit.quote() != null) {
                    quality.put("wordingFitQuote", wordingFit.quote());
                }
            }
            signals.add(signal(context, b -> b
                    .signalType(SignalType.APPROVED_DRAFTING_AVAILABLE).issueFamily(family)
                    .payload(new ApprovedDraftingPayload(drafting.evidenceId(), drafting.text(),
                            drafting.variantRole() == null
                                    ? null : drafting.variantRole().name(),
                            fitVerdict.name()))
                    .evidence(List.of(drafting.evidenceId()))
                    .quality(quality)
                    .dedupeSuffix(judged.key())));
        }
        if (attempt != null && attempt.signal() != null) {
            signals.add(attempt.signal());
        }
        var facts = judged.position().policyFacts();
        // council §A.3: only a typed rule that covers this finding's stance escalates it
        var authority = EscalationAuthority.decide(facts, category);
        if (authority != null) {
            signals.add(signal(context, b -> b
                    .signalType(SignalType.ESCALATION_REQUIRED).issueFamily(family)
                    .payload(authority.payload(facts.escalationTrigger(), Boolean.TRUE))
                    .evidence(List.of(judged.position().evidenceId()))
                    .quality(escalationQuality(authority, facts.escalationTrigger()))
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
    /** {@code unavailable}: the triage call failed, so the check must degrade rather than pass. */
    private record Uncovered(List<RawReviewSignal> signals, boolean unavailable) {
    }

    private Uncovered uncovered(
            ReviewCheckContext context, ClauseSemantics clause, EvidenceBundle bundle,
            PolicyComparisonSpecs.PolicyComparisonState state, Map<String, Object> detail) {
        var evidence = subjectEvidence(bundle, clause.id());
        var assessment = evidence.isEmpty()
                ? new UncoveredClauseTriage.Assessment(Optional.empty(), false)
                : triage.assess(state);
        if (assessment.unavailable()) {
            detail.put("uncoveredTriageUnavailable", true);
            return new Uncovered(List.of(), true);
        }
        var verdict = assessment.verdict();
        verdict.ifPresent(v -> detail.put("uncoveredProbability", v.probability()));
        if (verdict.isEmpty() || verdict.get().probability() < properties.uncoveredThreshold()) {
            return new Uncovered(List.of(), false);
        }
        var execution = verdict.get().execution();
        return new Uncovered(List.of(signal(context, b -> b
                .signalType(SignalType.POLICY_UNAVAILABLE).issueFamily(IssueFamily.POLICY_UNAVAILABLE)
                .payload(new PolicyUnavailablePayload(PolicyUnavailablePayload.NOT_COVERED))
                .evidence(evidence)
                .decisionSpec(PolicyComparisonSpecs.UNCOVERED_CLAUSE_SPEC,
                        PolicyComparisonSpecs.UNCOVERED_CLAUSE_VERSION, execution.executionId())
                .resolvedModel(execution.metadata().resolvedModel())
                .usage(execution.metadata().usage())
                .quality(Map.of("uncoveredProbability", verdict.get().probability(), "route", JEV))
                .dedupeSuffix("uncovered"))), false);
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
            ProviderFailures.rethrowIfOutOfCredit(e);
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

    /** A generated wording signal, or the reason no wording is proposed (null when not attempted). */
    private record LanguageAttempt(RawReviewSignal signal, String failure) {
    }

    private LanguageAttempt generate(
            ReviewCheckContext context, ClauseSemantics clause, JudgedPosition judged,
            IssueFamily family, List<UUID> evidence, Map<String, Object> detail) {
        var headingText = context.headingTexts().getOrDefault(clause.headingNodeId(), "");
        var runInHeading = context.runInHeadings().get(clause.headingNodeId());
        var body = PositionLanguageGenerator.body(clause.contentText(), headingText, runInHeading);
        if (context.untrackableHeadings().contains(clause.headingNodeId()) || body.isBlank()) {
            // a tracked change cannot carry new wording here: the finding goes to a human
            detail.put("openEnded", false);
            return new LanguageAttempt(null, null);
        }
        var result = languageGenerator.propose(new PositionLanguageGenerator.Draft(
                OurSide.describe(context.snapshot().ourSideRole()),
                context.snapshot().contractType().label(),
                runInHeading != null ? runInHeading : headingText, body,
                judged.position().text()));
        detail.put("openEnded", result.available());
        if (!result.available()) {
            // no approved wording and no machine candidate: the finding escalates to a human
            detail.put("openEndedOutcome", result.failure());
            return new LanguageAttempt(null, result.failure());
        }
        var generated = result.proposal().orElseThrow();
        var verification = generated.verification();
        return new LanguageAttempt(signal(context, b -> b
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
                .dedupeSuffix(judged.key())), null);
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
        var reason = ProviderFailures.degradedReason(e);
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

    /**
     * The fit work this clause cost and the calls the skip rule saved, in the check detail. A
     * clause with no fit question writes neither key, so its detail is unchanged.
     */
    static void putFitSummary(Map<String, Object> detail, ClauseFitCheck.Outcome fit) {
        if (fit.called()) {
            detail.put("fitQuestions", fit.questions());
        }
        if (fit.skipped() > 0) {
            detail.put("fitSkipped", fit.skipped());
        }
    }

    /**
     * The library wording each flagged position would offer, and the gap that wording must cure.
     * Selection is the same one {@code flaggedSignals} uses, so the fit question is asked about
     * exactly the variant that would be proposed. A related position never offers wording, so its
     * variant is not asked about at all.
     */
    private Map<String, Wording> wordingFor(ClauseSemantics clause,
            List<JudgedPosition> judgedPositions, Map<String, Resolution> resolutions) {
        var wording = new LinkedHashMap<String, Wording>();
        for (var judged : judgedPositions) {
            var resolution = resolutions.get(judged.key());
            if (resolution == null || resolution.category() == null
                    || !wantsLanguage(resolution.category())) {
                continue;
            }
            if (!judged.searched() && governsOnlySecondaryTopic(clause, judged.position())) {
                continue;
            }
            // the bundle that judged the position carries its drafting: the search bundle for a
            // search hit, the topic bundle for a paired position
            var item = chooseDrafting(judged.bundle().approvedDraftingItems(), judged.position());
            if (item != null) {
                wording.put(judged.key(), new Wording(item, gapText(resolution.category())));
            }
        }
        return wording;
    }

    /**
     * One fit pack for the clause (engine decision 2026-10-24 §B.1): the search hits that must
     * govern its subject and the library wording offers that must meet their position and cure the
     * gap. The type and side scope already ran in the structured lookup, so a candidate is never
     * dropped here by topic. A single candidate whose own concept is the exact concept it serves
     * skips the question: metadata already proves its subject.
     */
    private ClauseFitCheck.Outcome fit(ReviewCheckContext context, ClauseSemantics clause,
            List<JudgedPosition> judgedPositions, Map<String, Wording> wordingByKey) {
        var candidates = new ArrayList<ClauseFitCheck.Candidate>();
        var primary = clause.effectivePrimaryConcept().orElse(null);
        for (var judged : judgedPositions) {
            if (!judged.searched()) {
                continue;
            }
            var position = judged.position();
            candidates.add(new ClauseFitCheck.Candidate(judged.key(), ClauseFitCheck.Kind.RULE,
                    position.text(),
                    null,
                    null,
                    primary != null && primary.equals(position.legalConcept())));
        }
        for (var entry : wordingByKey.entrySet()) {
            var judged = judgedPositions.stream()
                    .filter(candidate -> candidate.key().equals(entry.getKey()))
                    .findFirst().orElseThrow();
            var position = judged.position();
            var wording = entry.getValue().item();
            candidates.add(new ClauseFitCheck.Candidate(wordingKey(entry.getKey()),
                    ClauseFitCheck.Kind.WORDING, wording.text(),
                    PolicyPositionQuestions.instructions(position, clause, conceptRegistry),
                    entry.getValue().gap(),
                    // metadata never proves meets-and-cures, so a wording is always asked
                    false));
        }
        if (candidates.isEmpty()) {
            return ClauseFitCheck.Outcome.none();
        }
        return fitCheck.judge(new ClauseFitCheck.Clause(
                OurSide.describe(context.snapshot().ourSideRole()),
                context.snapshot().contractType().label(),
                context.headingTexts().getOrDefault(clause.headingNodeId(), ""),
                truncate(clause.contentText(), MAX_CLAUSE_LENGTH)), candidates);
    }

    /** A search hit that does not govern the clause is not applied; UNKNOWN keeps today's behaviour. */
    private static boolean applied(JudgedPosition judged, ClauseFitCheck.Outcome fit) {
        if (!judged.searched()) {
            return true;
        }
        var judgment = fit.judgments().get(judged.key());
        return judgment == null || judgment.verdict() != ClauseFitCheck.Verdict.DOES_NOT_FIT;
    }

    private static String wordingKey(String judgedKey) {
        return judgedKey + "/wording";
    }

    /** What the wording must cure, in the comparison's own words for the category. */
    private static String gapText(String category) {
        return switch (category) {
            case PolicyComparisonSpecs.WALK_AWAY ->
                    "the clause contains or permits what the position's walk-away forbids";
            case PolicyComparisonSpecs.BEYOND_FALLBACK ->
                    "the clause is worse for us than every fallback the position lists";
            default -> "the clause departs from the position";
        };
    }

    private static boolean wantsLanguage(String category) {
        return PolicyComparisonSpecs.WALK_AWAY.equals(category)
                || PolicyComparisonSpecs.BEYOND_FALLBACK.equals(category);
    }

    /**
     * The escalation quality is the escalation payload in map form. An approval the policy did
     * not state is left out rather than written as false.
     */
    static Map<String, Object> escalationQuality(String trigger, Boolean requiresApproval) {
        var quality = new LinkedHashMap<String, Object>();
        quality.put("escalationTrigger", trigger == null ? "" : trigger);
        if (requiresApproval != null) {
            quality.put("requiresApproval", requiresApproval);
        }
        return quality;
    }

    /** The typed rule behind an escalation, so the panel can name the approver and its source. */
    static Map<String, Object> escalationQuality(
            EscalationAuthority.Decision decision, String fallbackTrigger) {
        var payload = decision.payload(fallbackTrigger, Boolean.TRUE);
        var quality = escalationQuality(payload.trigger(), payload.requiresApproval());
        quality.put("escalationApprover", payload.approver());
        quality.put("escalationSource", payload.source());
        quality.put("escalationStance", payload.stance());
        if (payload.condition() != null) {
            quality.put("escalationCondition", payload.condition());
        }
        if (payload.conflictingApprover() != null) {
            quality.put("escalationConflictApprover", payload.conflictingApprover());
            quality.put("escalationConflictSource", payload.conflictingSource());
        }
        return quality;
    }

    private static Map<String, Object> flaggedQuality(
            JudgedPosition judged, boolean secondaryTopic, String route, Resolution resolution,
            LlmAdjudicator.SecondOpinion opinion, String openEndedOutcome,
            ClauseFitCheck.Outcome fit) {
        var quality = new LinkedHashMap<String, Object>();
        quality.put("flagged", true);
        quality.put("category", resolution.category());
        quality.put("flagProbability", judged.judgement().flagProbability());
        quality.put("confidence", judged.confidence());
        quality.put("probabilities", judged.judgement().probabilities());
        quality.put("secondaryTopic", secondaryTopic);
        quality.put("route", route);
        if (openEndedOutcome != null) {
            quality.put("openEndedOutcome", openEndedOutcome);
        }
        if (judged.searched()) {
            quality.put("foundBy", SEARCH);
            putFit(quality, fit, judged);
        }
        if (resolution.secondOpinionCategory() != null) {
            quality.put("secondOpinionCategory", resolution.secondOpinionCategory());
        }
        if (resolution.routeReason() != null) {
            quality.put("routeReason", resolution.routeReason());
        }
        if (!resolution.levers().isEmpty()) {
            quality.put("levers", resolution.levers());
            quality.put("categoryBeforeLevers", judged.judgement().category());
            // the signal links one generation; when a lever changed the category, the second
            // opinion's is kept here
            if (opinion != null && opinion.executionId() != null) {
                quality.put("secondOpinionExecutionId", opinion.executionId().toString());
            }
        }
        var conditions = resolution.conditions();
        if (conditions != null) {
            quality.put("fallbackConditionsOutcome", conditions.outcome());
            if (conditions.answered()) {
                quality.put("fallbackListed", conditions.fallbackListed());
                if (conditions.executionId() != null) {
                    quality.put("fallbackConditionsExecutionId", conditions.executionId().toString());
                }
                quality.put("fallbackConditions", conditions.conditions());
            }
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

    /** The library variant a flagged position would offer, and the gap it must cure. */
    private record Wording(EvidenceItem item, String gap) {
    }

    private static Map<String, Object> detailEntry(
            JudgedPosition judged, PolicyComparisonOutcome.Judgement probe,
            LlmAdjudicator.SecondOpinion opinion, Resolution resolution,
            ClauseFitCheck.Outcome fit, Map<String, Wording> wordingByKey) {
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
            var judgment = fit.judgments().get(judged.key());
            if (judgment != null) {
                entry.put("fitVerdict", judgment.verdict().name());
                entry.put("fitReason", judgment.reasons().getFirst().name());
                entry.put("fitReasons", judgment.reasons().stream().map(Enum::name).toList());
            }
            if (!applied(judged, fit)) {
                // the hit does not govern the clause: not applied, kept visible for diagnostics
                entry.put("fitOutcome", "not_covered");
            }
        }
        // a wording offer is recorded per position, so two offers in one finding stay countable;
        // a position the fit dropped has no offer, so its fit questions are not counted as one
        var wording = wordingByKey.get(judged.key());
        if (wording != null && applied(judged, fit)) {
            var judgment = fit.judgments().get(wordingKey(judged.key()));
            if (judgment != null) {
                entry.put("wordingFitVerdict", judgment.verdict().name());
                entry.put("wordingFitReasons",
                        judgment.reasons().stream().map(Enum::name).toList());
            }
        }
        if (opinion != null) {
            entry.put("secondOpinionOutcome", opinion.outcome());
            entry.put("secondOpinionCategory", opinion.category() == null ? "" : opinion.category());
        }
        if (resolution.routeReason() != null) {
            entry.put("routeReason", resolution.routeReason());
        }
        if (!resolution.levers().isEmpty()) {
            entry.put("levers", resolution.levers());
            if (resolution.category() != null) {
                entry.put("finalCategory", resolution.category());
            }
        }
        if (resolution.conditions() != null) {
            entry.put("fallbackConditionsOutcome", resolution.conditions().outcome());
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
