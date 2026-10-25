package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.intelligence.ChoiceAnswer;
import com.rivell.workbench.intelligence.DecisionExecution;
import com.rivell.workbench.intelligence.NoProviderConfiguredException;
import com.rivell.workbench.intelligence.ProviderFailures;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.intelligence.SemanticDecisionService;
import com.rivell.workbench.intelligence.SpecStateMismatchException;
import com.rivell.workbench.intelligence.UnknownDecisionSpecException;
import com.rivell.workbench.intelligence.YesNoAnswer;
import com.rivell.workbench.retrieval.ApplicablePositions;
import com.rivell.workbench.retrieval.ContractClauseSearch;
import com.rivell.workbench.retrieval.EvidenceItem;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.review.CheckExecutionStatus;
import com.rivell.workbench.review.CheckMechanism;
import com.rivell.workbench.review.ClauseTopics;
import com.rivell.workbench.review.FindingSubjectKind;
import com.rivell.workbench.review.IssueFamily;
import com.rivell.workbench.review.OmissionPayload;
import com.rivell.workbench.review.PolicyComparisonProperties;
import com.rivell.workbench.review.PolicyComparisonSpecs;
import com.rivell.workbench.review.PresenceSpecs;
import com.rivell.workbench.review.RawReviewSignal;
import com.rivell.workbench.review.ReviewAnalysisRepository;
import com.rivell.workbench.review.ReviewCheckExecution;
import com.rivell.workbench.review.ReviewPlanCheck;
import com.rivell.workbench.review.ReviewRepository;
import com.rivell.workbench.review.SignalType;
import com.rivell.workbench.review.WalkAwayAbsenceSpecs;
import com.rivell.workbench.taxonomy.ConceptRegistry;

/**
 * Engine v2 absence lane (spec §4, D4/D5/D6): the trailing check of a plan. It reads the
 * clause checks' judged positions, applies the lane's position-text search and asks one
 * bounded question. The presence trigger (behind {@code rivell.review.omissions}) asks
 * whether a position requires a provision; the walk-away trigger (R3-002) asks whether the
 * position's walk-away describes an absent right. Only a required position becomes a finding;
 * an unsure presence asks for a look. A provider failure never invents an absence, and an
 * incomplete run degrades the check.
 */
public final class OmissionCheckExecutor implements ReviewCheckExecutor {

    /** The plan key of the R3-002 trigger; the presence trigger keeps the key OMISSION. */
    public static final String WALK_AWAY_ABSENCE_CHECK_KEY = "WALK_AWAY_ABSENCE";
    /** Applicable positions judged per run; beyond this the detail says the lane was truncated. */
    private static final int MAX_OMISSIONS = 12;
    /** Clauses judged per candidate; the search ranks all of them, three are compared. */
    private static final int COMPARED_CLAUSES = 3;
    /** The playbook narrowing cap the position search uses (PositionSearch.NARROWING_CAP). */
    private static final int POSITION_CAP = 500;
    private static final double REQUIRES_PROVISION = 0.8;
    private static final double ABSENT_RIGHT = 0.8;
    private static final double UNSURE = 0.5;
    private static final String JEV = "JEV";
    private static final String NEEDS_YOUR_LOOK = "NEEDS_YOUR_LOOK";
    private static final String OMISSION_UNSURE = "omission_unsure";

    private final SemanticDecisionService decisionService;
    private final EvidenceService evidenceService;
    private final ContractClauseSearch clauseSearch;
    private final ConceptRegistry conceptRegistry;
    private final PolicyComparisonProperties properties;
    private final ReviewRepository reviewRepository;
    private final ReviewAnalysisRepository analysisRepository;

    public OmissionCheckExecutor(
            SemanticDecisionService decisionService,
            EvidenceService evidenceService,
            ContractClauseSearch clauseSearch,
            ConceptRegistry conceptRegistry,
            PolicyComparisonProperties properties,
            ReviewRepository reviewRepository,
            ReviewAnalysisRepository analysisRepository) {
        this.decisionService = decisionService;
        this.evidenceService = evidenceService;
        this.clauseSearch = clauseSearch;
        this.conceptRegistry = conceptRegistry;
        this.properties = properties;
        this.reviewRepository = reviewRepository;
        this.analysisRepository = analysisRepository;
    }

    @Override
    public CheckMechanism mechanism() {
        return CheckMechanism.OMISSION;
    }

    @Override
    public CheckOutcome execute(ReviewCheckContext context) {
        if (!context.environment().fullyMatches()) {
            return CheckOutcome.degraded(context.environment().mismatchReason());
        }
        var walkAwayLane = WALK_AWAY_ABSENCE_CHECK_KEY.equals(context.check().checkKey());
        var organizationId = context.check().organizationId();
        var planChecks = reviewRepository.findPlanChecks(organizationId, context.check().reviewId());
        var executions = analysisRepository.findExecutions(organizationId, context.runId());
        var clauses = context.semantics().clauses();
        if (!comparisonsComplete(planChecks, executions)) {
            // a clause check that did not complete leaves positions unread: never call them missing
            return CheckOutcome.degraded("omission_inputs_incomplete", List.of(),
                    emptyDetail(clauses.size()), null);
        }
        if (clauses.isEmpty()) {
            return CheckOutcome.completed(List.of(), emptyDetail(0));
        }
        var detail = new LinkedHashMap<String, Object>();
        var request = PolicyComparisonCheckExecutor.request(
                context, clauses.getFirst(), ClauseTopics.NO_TOPIC);
        var applicable = evidenceService.buildForOmissions(request, POSITION_CAP);
        var addressedIds = addressedCandidateIds(planChecks, executions);
        var applicableCandidates = new ArrayList<Candidate>();
        for (var item : applicable.bundle().policyItems()) {
            var id = item.sourceCandidateId();
            if (id == null || addressedIds.contains(id.toString())) {
                continue;
            }
            var walkAway = walkAwayLane && item.policyFacts() != null
                    ? item.policyFacts().prohibited() : null;
            if (walkAwayLane && (walkAway == null || walkAway.isBlank())) {
                continue;
            }
            applicableCandidates.add(new Candidate(id,
                    applicable.labels().getOrDefault(id, id.toString()), item, walkAway));
        }
        var truncated = applicable.truncated() || applicableCandidates.size() > MAX_OMISSIONS;
        var candidates = applicableCandidates.size() > MAX_OMISSIONS
                ? List.copyOf(applicableCandidates.subList(0, MAX_OMISSIONS))
                : List.copyOf(applicableCandidates);
        detail.put("omissionCandidates", candidates.size());
        detail.put("omissionsTruncated", truncated);
        detail.put("searchedClauses", clauses.size());
        if (candidates.isEmpty()) {
            detail.put("addressedBySearch", List.of());
            detail.put("flaggedBySearch", List.of());
            detail.put("presence", List.of());
            detail.put("walkAwayAbsence", List.of());
            detail.put("searchDegradation", List.of());
            return CheckOutcome.completed(List.of(), detail);
        }
        var triggerAnswers = new ArrayList<Map<String, Object>>();
        var absentRights = new LinkedHashMap<UUID, Double>();
        var survivors = new ArrayList<Candidate>();
        DecisionExecution absenceExecution = null;
        if (walkAwayLane) {
            try {
                absenceExecution = decisionService.evaluate(WalkAwayAbsenceSpecs.spec(
                                candidates.stream().map(Candidate::walkAway).toList()),
                        new WalkAwayAbsenceSpecs.AbsenceState(
                                WalkAwayAbsenceSpecs.STATE_SCHEMA_VERSION,
                                context.snapshot().contractType().label(),
                                context.snapshot().ourSideRole() == null
                                        ? "" : context.snapshot().ourSideRole().name()));
            } catch (SpecStateMismatchException | UnknownDecisionSpecException
                    | NoProviderConfiguredException e) {
                return CheckOutcome.failed(
                        "decision_contract_error:" + e.getClass().getSimpleName());
            } catch (SemanticDecisionException e) {
                ProviderFailures.rethrowIfOutOfCredit(e);
                putTriggerAnswers(detail, true, triggerAnswers);
                return providerUnavailable(detail, applicable);
            }
            for (int index = 0; index < candidates.size(); index++) {
                var key = WalkAwayAbsenceSpecs.key(index);
                if (!(absenceExecution.answers().get(key) instanceof YesNoAnswer answer)) {
                    return CheckOutcome.failed("walk_away_answer_missing:" + key);
                }
                var candidate = candidates.get(index);
                var probability = answer.probability();
                var entry = new LinkedHashMap<String, Object>();
                entry.put("candidateId", candidate.id().toString());
                entry.put("probability", probability);
                triggerAnswers.add(entry);
                if (probability >= ABSENT_RIGHT) {
                    survivors.add(candidate);
                    absentRights.put(candidate.id(), probability);
                }
            }
            detail.put("walkAwayCandidates", survivors.size());
            if (survivors.isEmpty()) {
                detail.put("addressedBySearch", List.of());
                detail.put("flaggedBySearch", List.of());
                putTriggerAnswers(detail, true, triggerAnswers);
                detail.put("searchDegradation", List.of());
                return CheckOutcome.completed(List.of(), detail, applicable.bundle().traceId())
                        .withCitedEvidence(List.of(applicable.bundle()));
            }
        }
        var matched = walkAwayLane ? List.copyOf(survivors) : candidates;
        detail.put("searchedCandidates",
                matched.stream().map(candidate -> candidate.id().toString()).toList());
        var search = clauseSearch.search(
                matched.stream().map(candidate -> candidate.item().text()).toList(),
                clauses.stream().map(clause -> new ContractClauseSearch.Clause(
                        clause.headingNodeId(), clause.contentText())).toList(),
                clauses.size());
        detail.put("searchDegradation", search.degradation());
        var clausesByHeading = new HashMap<UUID, ClauseSemantics>();
        for (var clause : clauses) {
            clausesByHeading.put(clause.headingNodeId(), clause);
        }
        var judged = judgedPairs(planChecks, executions);
        var eligible = new ArrayList<List<UUID>>(matched.size());
        for (int index = 0; index < matched.size(); index++) {
            var candidate = matched.get(index);
            var headings = new ArrayList<UUID>();
            for (var hit : search.hits().get(index)) {
                if (headings.size() >= COMPARED_CLAUSES) {
                    break;
                }
                if (judged.contains(pair(hit.headingNodeId(), candidate.id()))) {
                    continue;
                }
                headings.add(hit.headingNodeId());
            }
            eligible.add(List.copyOf(headings));
        }
        // R-b: one comparison call per clause in the union, with the candidates that hit it
        var groups = new LinkedHashMap<UUID, List<Integer>>();
        for (int index = 0; index < matched.size(); index++) {
            for (var heading : eligible.get(index)) {
                groups.computeIfAbsent(heading, key -> new ArrayList<>()).add(index);
            }
        }
        var compared = new int[matched.size()];
        var addressedBySearch = new LinkedHashSet<UUID>();
        var flaggedBySearch = new ArrayList<Map<String, Object>>();
        try {
            for (var group : groups.entrySet()) {
                var clause = clausesByHeading.get(group.getKey());
                var positions = group.getValue().stream()
                        .map(index -> matched.get(index).item()).toList();
                var execution = decisionService.evaluate(PolicyComparisonSpecs.spec(
                        PolicyPositionQuestions.build(positions, clause, conceptRegistry)),
                        PolicyComparisonCheckExecutor.comparisonState(context, clause));
                for (int index = 0; index < positions.size(); index++) {
                    var key = PolicyPositionQuestions.key(index);
                    if (!(execution.answers().get(key) instanceof ChoiceAnswer choice)) {
                        return CheckOutcome.failed("position_answer_missing:" + key);
                    }
                    var judgement = PolicyComparisonOutcome.judge(choice, properties.flagThreshold());
                    var candidateIndex = group.getValue().get(index);
                    var candidate = matched.get(candidateIndex);
                    if (judgement.flagged()) {
                        // R-d: a flagged pair is recorded for the eval and counts as addressed,
                        // except that a clause matching an absence walk-away confirms the absence
                        if (!walkAwayLane
                                || !PolicyComparisonSpecs.WALK_AWAY.equals(judgement.category())) {
                            addressedBySearch.add(candidate.id());
                        }
                        var flagged = new LinkedHashMap<String, Object>();
                        flagged.put("candidateId", candidate.id().toString());
                        flagged.put("headingNodeId", clause.headingNodeId().toString());
                        flagged.put("category", judgement.category() == null
                                ? "" : judgement.category());
                        flaggedBySearch.add(flagged);
                    } else if (PolicyComparisonOutcome.addresses(
                            judgement.probabilities(), judgement.flagProbability())) {
                        addressedBySearch.add(candidate.id());
                    }
                    compared[candidateIndex]++;
                }
            }
        } catch (SpecStateMismatchException | UnknownDecisionSpecException
                | NoProviderConfiguredException e) {
            return CheckOutcome.failed("decision_contract_error:" + e.getClass().getSimpleName());
        } catch (SemanticDecisionException e) {
            ProviderFailures.rethrowIfOutOfCredit(e);
            detail.put("addressedBySearch",
                    addressedBySearch.stream().map(UUID::toString).toList());
            detail.put("flaggedBySearch", flaggedBySearch);
            putTriggerAnswers(detail, walkAwayLane, triggerAnswers);
            return providerUnavailable(detail, applicable);
        }
        detail.put("addressedBySearch", addressedBySearch.stream().map(UUID::toString).toList());
        detail.put("flaggedBySearch", flaggedBySearch);
        var remaining = new ArrayList<Integer>();
        for (int index = 0; index < matched.size(); index++) {
            if (!addressedBySearch.contains(matched.get(index).id())) {
                remaining.add(index);
            }
        }
        var signals = new ArrayList<RawReviewSignal>();
        if (walkAwayLane) {
            for (int index : remaining) {
                var candidate = matched.get(index);
                signals.add(walkAwaySignal(context, candidate, clauses.size(), compared[index],
                        absentRights.get(candidate.id()), absenceExecution));
            }
        } else if (!remaining.isEmpty()) {
            DecisionExecution presenceExecution;
            try {
                presenceExecution = decisionService.evaluate(PresenceSpecs.spec(
                                remaining.stream()
                                        .map(index -> matched.get(index).item().text()).toList()),
                        new PresenceSpecs.PresenceState(PresenceSpecs.STATE_SCHEMA_VERSION,
                                context.snapshot().contractType().label(),
                                context.snapshot().ourSideRole() == null
                                        ? "" : context.snapshot().ourSideRole().name()));
            } catch (SpecStateMismatchException | UnknownDecisionSpecException
                    | NoProviderConfiguredException e) {
                return CheckOutcome.failed(
                        "decision_contract_error:" + e.getClass().getSimpleName());
            } catch (SemanticDecisionException e) {
                ProviderFailures.rethrowIfOutOfCredit(e);
                putTriggerAnswers(detail, false, triggerAnswers);
                return providerUnavailable(detail, applicable);
            }
            for (int index = 0; index < remaining.size(); index++) {
                var candidateIndex = remaining.get(index);
                var key = PresenceSpecs.key(index);
                if (!(presenceExecution.answers().get(key) instanceof YesNoAnswer answer)) {
                    return CheckOutcome.failed("presence_answer_missing:" + key);
                }
                var candidate = matched.get(candidateIndex);
                var probability = answer.probability();
                var entry = new LinkedHashMap<String, Object>();
                entry.put("candidateId", candidate.id().toString());
                entry.put("probability", probability);
                triggerAnswers.add(entry);
                if (probability >= REQUIRES_PROVISION) {
                    signals.add(signal(context, candidate, clauses.size(),
                            compared[candidateIndex], probability, JEV, presenceExecution));
                } else if (probability >= UNSURE) {
                    signals.add(signal(context, candidate, clauses.size(),
                            compared[candidateIndex], probability, NEEDS_YOUR_LOOK,
                            presenceExecution));
                }
            }
        }
        putTriggerAnswers(detail, walkAwayLane, triggerAnswers);
        return CheckOutcome.completed(signals, detail, applicable.bundle().traceId())
                .withCitedEvidence(List.of(applicable.bundle()));
    }

    private static void putTriggerAnswers(
            Map<String, Object> detail, boolean walkAwayLane,
            List<Map<String, Object>> triggerAnswers) {
        detail.put("presence", walkAwayLane ? List.of() : triggerAnswers);
        detail.put("walkAwayAbsence", walkAwayLane ? triggerAnswers : List.of());
    }

    private static CheckOutcome providerUnavailable(
            Map<String, Object> detail, ApplicablePositions applicable) {
        return CheckOutcome.degraded("omission_provider_unavailable", List.of(), detail,
                        applicable.bundle().traceId())
                .withCitedEvidence(List.of(applicable.bundle()));
    }

    private static RawReviewSignal signal(
            ReviewCheckContext context, Candidate candidate, int searchedClauses,
            int comparedClauses, double probability, String route, DecisionExecution execution) {
        var check = context.check();
        var concept = candidate.item().legalConcept() == null
                ? ClauseTopics.NO_TOPIC : candidate.item().legalConcept();
        var quality = new LinkedHashMap<String, Object>();
        quality.put("searchedClauses", searchedClauses);
        quality.put("comparedClauses", comparedClauses);
        quality.put("presenceProbability", probability);
        quality.put("positionLabel", candidate.label());
        quality.put("route", route);
        if (NEEDS_YOUR_LOOK.equals(route)) {
            quality.put("uncertaintyReason", OMISSION_UNSURE);
        }
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(), context.runId(),
                check.id(), SignalType.MISSING_REQUIRED_CONCEPT, FindingSubjectKind.ABSENCE, null,
                concept, concept, null, IssueFamily.MISSING_REQUIRED_PROVISION,
                new OmissionPayload(candidate.id(), candidate.label(), searchedClauses),
                List.of(candidate.item().evidenceId()),
                PresenceSpecs.PRESENCE_SPEC, PresenceSpecs.PRESENCE_VERSION,
                execution.executionId(), null, null,
                execution.metadata().resolvedModel(), execution.metadata().usage(),
                quality, "OMISSION:" + check.id() + ":" + candidate.id(), context.executedAt());
    }

    /** R3-002: a walk-away that states an absent right, and no clause addresses the position. */
    private static RawReviewSignal walkAwaySignal(
            ReviewCheckContext context, Candidate candidate, int searchedClauses,
            int comparedClauses, double probability, DecisionExecution execution) {
        var check = context.check();
        var concept = candidate.item().legalConcept() == null
                ? ClauseTopics.NO_TOPIC : candidate.item().legalConcept();
        var quality = new LinkedHashMap<String, Object>();
        quality.put("searchedClauses", searchedClauses);
        quality.put("comparedClauses", comparedClauses);
        quality.put("absenceProbability", probability);
        quality.put("positionLabel", candidate.label());
        quality.put("walkAway", candidate.walkAway());
        quality.put("route", JEV);
        var facts = candidate.item().policyFacts();
        var authority = EscalationAuthority.decide(facts, PolicyComparisonSpecs.WALK_AWAY);
        if (authority != null) {
            quality.putAll(PolicyComparisonCheckExecutor.escalationQuality(
                    authority, facts.escalationTrigger()));
        }
        return new RawReviewSignal(
                UUID.randomUUID(), check.organizationId(), check.reviewId(), context.runId(),
                check.id(), SignalType.MISSING_REQUIRED_CONCEPT, FindingSubjectKind.ABSENCE, null,
                concept, concept, null, IssueFamily.MISSING_REQUIRED_PROVISION,
                new OmissionPayload(candidate.id(), candidate.label(), searchedClauses,
                        candidate.walkAway()),
                List.of(candidate.item().evidenceId()),
                WalkAwayAbsenceSpecs.WALK_AWAY_ABSENCE_SPEC,
                WalkAwayAbsenceSpecs.WALK_AWAY_ABSENCE_VERSION,
                execution.executionId(), null, null,
                execution.metadata().resolvedModel(), execution.metadata().usage(),
                quality, SignalType.MISSING_REQUIRED_CONCEPT.name() + ":" + check.id() + ":"
                        + candidate.id(), context.executedAt());
    }

    /** Every clause check of the run must have completed, or its positions would read as unaddressed. */
    private static boolean comparisonsComplete(
            List<ReviewPlanCheck> planChecks, List<ReviewCheckExecution> executions) {
        var byCheck = new HashMap<UUID, ReviewCheckExecution>();
        for (var execution : executions) {
            byCheck.putIfAbsent(execution.planCheckId(), execution);
        }
        for (var check : planChecks) {
            if (check.mechanism() != CheckMechanism.POLICY_COMPARISON) {
                continue;
            }
            var execution = byCheck.get(check.id());
            if (execution == null || execution.status() != CheckExecutionStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    /** The candidate ids any clause check judged as addressed (detail went through JSON). */
    private static Set<String> addressedCandidateIds(
            List<ReviewPlanCheck> planChecks, List<ReviewCheckExecution> executions) {
        var ids = new HashSet<String>();
        for (var entry : judgedEntries(planChecks, executions)) {
            if (entry.probabilities() != null && entry.flagProbability() != null
                    && PolicyComparisonOutcome.addresses(
                            entry.probabilities(), entry.flagProbability())) {
                ids.add(entry.positionCandidateId());
            }
        }
        return ids;
    }

    /** The (clause, position) pairs the clause checks already judged (SEARCH-found included). */
    private static Set<String> judgedPairs(
            List<ReviewPlanCheck> planChecks, List<ReviewCheckExecution> executions) {
        var pairs = new HashSet<String>();
        for (var entry : judgedEntries(planChecks, executions)) {
            pairs.add(pair(entry.headingNodeId(), entry.positionCandidateId()));
        }
        return pairs;
    }

    private static List<JudgedEntry> judgedEntries(
            List<ReviewPlanCheck> planChecks, List<ReviewCheckExecution> executions) {
        var byCheck = new HashMap<UUID, ReviewPlanCheck>();
        for (var check : planChecks) {
            byCheck.put(check.id(), check);
        }
        var entries = new ArrayList<JudgedEntry>();
        for (var execution : executions) {
            var check = byCheck.get(execution.planCheckId());
            if (check == null || check.mechanism() != CheckMechanism.POLICY_COMPARISON
                    || check.subjectHeadingNodeId() == null) {
                continue;
            }
            if (!(execution.detail().get("positions") instanceof List<?> positions)) {
                continue;
            }
            for (var position : positions) {
                if (!(position instanceof Map<?, ?> entry)) {
                    continue;
                }
                if (!(entry.get("positionCandidateId") instanceof String candidateId)
                        || candidateId.isBlank()) {
                    continue;
                }
                entries.add(new JudgedEntry(check.subjectHeadingNodeId(), candidateId,
                        numericProbabilities(entry.get("probabilities")),
                        entry.get("flagProbability") instanceof Number number
                                ? number.doubleValue() : null));
            }
        }
        return entries;
    }

    /** Probabilities read back from JSON: every value a Number, whatever its width. */
    private static Map<String, Double> numericProbabilities(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        var probabilities = new LinkedHashMap<String, Double>();
        for (var entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number number) {
                probabilities.put(key, number.doubleValue());
            }
        }
        return probabilities;
    }

    private static String pair(UUID headingNodeId, String candidateId) {
        return headingNodeId + "|" + candidateId;
    }

    private static String pair(UUID headingNodeId, UUID candidateId) {
        return pair(headingNodeId, candidateId.toString());
    }

    private static Map<String, Object> emptyDetail(int searchedClauses) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("omissionCandidates", 0);
        detail.put("omissionsTruncated", false);
        detail.put("searchedClauses", searchedClauses);
        detail.put("addressedBySearch", List.of());
        detail.put("flaggedBySearch", List.of());
        detail.put("presence", List.of());
        detail.put("walkAwayAbsence", List.of());
        detail.put("searchDegradation", List.of());
        return detail;
    }

    private record Candidate(UUID id, String label, EvidenceItem item, String walkAway) {
    }

    private record JudgedEntry(
            UUID headingNodeId, String positionCandidateId,
            Map<String, Double> probabilities, Double flagProbability) {
    }
}
