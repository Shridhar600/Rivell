package com.rivell.workbench.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.rivell.workbench.contracts.ContractType;
import com.rivell.workbench.contracts.Jurisdiction;
import com.rivell.workbench.knowledge.ApplicabilityScope;
import com.rivell.workbench.knowledge.ApplicableKnowledge;
import com.rivell.workbench.knowledge.ArtifactType;
import com.rivell.workbench.knowledge.KnowledgeCandidate;
import com.rivell.workbench.knowledge.KnowledgeQueryService;
import com.rivell.workbench.review.PlaybookCoverage.Entry;
import com.rivell.workbench.review.PlaybookCoverage.Reason;
import com.rivell.workbench.review.PlaybookCoverage.State;
import com.rivell.workbench.review.PlaybookCoverage.Verdict;
import com.rivell.workbench.review.check.PolicyComparisonOutcome;

/**
 * Builds the frozen playbook coverage of one run from the run's own check detail and the
 * published positions the review pinned. No model call, no finding, no live knowledge read.
 *
 * <p>A position is compared when a clause check produced a substantive verdict for it. It is
 * "not found" only when a per-position search ran, its check completed, and no clause addressed
 * it. Every other position is "not compared", with a reason, so an unsearched position can never
 * read as absent.
 */
public final class PlaybookCoverageBuilder {

    private final KnowledgeQueryService knowledgeQueryService;

    public PlaybookCoverageBuilder(KnowledgeQueryService knowledgeQueryService) {
        this.knowledgeQueryService = knowledgeQueryService;
    }

    /**
     * Returns null when the review predates the frozen applicable-knowledge list: there is no
     * honest position set to report. An empty list means the frozen playbooks published no
     * position that applies to this contract type.
     */
    public PlaybookCoverage build(
            ReviewSnapshot snapshot,
            List<ReviewPlanCheck> planChecks,
            List<ReviewCheckExecution> executions,
            List<RawReviewSignal> signals,
            List<Finding> findings) {
        if (snapshot.applicableKnowledge() == null) {
            return null;
        }
        var playbookIds = snapshot.applicableKnowledge().stream()
                .filter(knowledge -> knowledge.artifactType() == ArtifactType.PLAYBOOK)
                .map(ApplicableKnowledge::artifactId)
                .toList();
        if (playbookIds.isEmpty() || snapshot.knowledgePinNumber() < 1) {
            return new PlaybookCoverage(List.of());
        }
        var positions = knowledgeQueryService.listPublishedPositionsForArtifacts(
                snapshot.organizationId(),
                snapshot.knowledgePinNumber(),
                playbookIds,
                snapshot.contractPackKey(),
                snapshot.ourSideRole() == null ? null : snapshot.ourSideRole().name(),
                snapshot.paperSource() == null ? null : snapshot.paperSource().name(),
                snapshot.jurisdiction() == Jurisdiction.UNSPECIFIED
                        ? null : snapshot.jurisdiction().name(),
                snapshot.effectiveOn());
        var checksById = new HashMap<UUID, ReviewPlanCheck>();
        planChecks.forEach(check -> checksById.put(check.id(), check));
        var verdicts = verdictsByCandidate(checksById, executions);
        var search = searchOutcomes(checksById, executions);
        var links = findingLinks(signals, findings);
        var entries = new ArrayList<Entry>(positions.size());
        for (var published : positions) {
            var candidate = published.candidate();
            var id = candidate.id();
            var label = candidate.effectiveTitle();
            var code = candidate.effectiveReference();
            var topic = topic(candidate);
            if (!governsContractType(candidate.scope(), snapshot.contractType())) {
                entries.add(new Entry(id, label, code, topic, State.OUT_OF_SCOPE,
                        null, null, null, null));
                continue;
            }
            var judged = bestVerdict(id, verdicts.get(id), links);
            if (judged != null) {
                entries.add(new Entry(id, label, code, topic, State.COMPARED,
                        judged.verdict(), judged.clauseHeadingNodeId(),
                        links.forCandidate(id, judged.clauseHeadingNodeId()), null));
                continue;
            }
            var outcome = search.outcome(id);
            var findingId = links.any(id);
            entries.add(switch (outcome) {
                case FOUND -> new Entry(id, label, code, topic, State.NOT_COMPARED, null, null,
                        findingId, Reason.FOUND_NOT_JUDGED);
                case CLEAN_NOT_FOUND -> new Entry(id, label, code, topic, State.NOT_FOUND, null,
                        null, findingId, null);
                case DEGRADED_SEARCH -> new Entry(id, label, code, topic, State.NOT_COMPARED,
                        null, null, findingId, Reason.CHECK_DID_NOT_COMPLETE);
                case NONE -> new Entry(id, label, code, topic, State.NOT_COMPARED, null, null,
                        findingId, search.incomplete()
                                ? Reason.CHECK_DID_NOT_COMPLETE : Reason.NOT_CHECKED);
            });
        }
        entries.sort(ENTRY_ORDER);
        return new PlaybookCoverage(entries);
    }

    /**
     * The strongest substantive verdict for one position. A position can be judged by more than
     * one clause; the worst verdict is the one a reviewer needs, and a verdict that produced a
     * finding wins a tie so the row links to the item the reviewer can act on.
     */
    private static CandidateVerdict bestVerdict(
            UUID candidateId, List<CandidateVerdict> candidates, FindingLinks links) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        return candidates.stream()
                .max(Comparator
                        .comparingInt((CandidateVerdict verdict) -> rank(verdict.verdict()))
                        .thenComparing(verdict ->
                                links.forCandidate(candidateId, verdict.clauseHeadingNodeId())
                                        != null))
                .orElse(null);
    }

    private static Map<UUID, List<CandidateVerdict>> verdictsByCandidate(
            Map<UUID, ReviewPlanCheck> checksById, List<ReviewCheckExecution> executions) {
        var byCandidate = new HashMap<UUID, List<CandidateVerdict>>();
        for (var execution : executions) {
            var check = checksById.get(execution.planCheckId());
            if (check == null || check.mechanism() != CheckMechanism.POLICY_COMPARISON) {
                continue;
            }
            if (!(execution.detail().get("positions") instanceof List<?> positions)) {
                continue;
            }
            for (var item : positions) {
                if (!(item instanceof Map<?, ?> entry)) {
                    continue;
                }
                var candidateId = uuid(entry.get("positionCandidateId"));
                if (candidateId == null) {
                    continue;
                }
                var verdict = verdictOf(entry);
                if (verdict == null) {
                    continue;
                }
                byCandidate.computeIfAbsent(candidateId, key -> new ArrayList<>())
                        .add(new CandidateVerdict(verdict, check.subjectHeadingNodeId()));
            }
        }
        return byCandidate;
    }

    /**
     * One judged position's product verdict. A second opinion that disagreed or could not be had
     * leaves the item to the reviewer, which is "unclear" here; it is never the first opinion's
     * category. A search hit the fit check did not apply was not compared at all.
     */
    private static Verdict verdictOf(Map<?, ?> entry) {
        if ("not_covered".equals(text(entry.get("fitOutcome")))) {
            return null;
        }
        var route = text(entry.get("route"));
        var opinion = text(entry.get("secondOpinionOutcome"));
        if ("ADJUDICATE".equals(route) && !"AGREED".equals(opinion)) {
            return Verdict.UNCLEAR;
        }
        var category = text(entry.get("finalCategory"));
        if (category == null) {
            category = text(entry.get("category"));
        }
        if (category == null && "ADJUDICATE".equals(route) && "AGREED".equals(opinion)) {
            category = text(entry.get("secondOpinionCategory"));
        }
        if (category != null) {
            return verdict(category, entry);
        }
        var probabilities = probabilities(entry.get("probabilities"));
        var flagProbability = number(entry.get("flagProbability"));
        if (flagProbability != null
                && PolicyComparisonOutcome.addresses(probabilities, flagProbability)) {
            return probabilities.getOrDefault(PolicyComparisonSpecs.MEETS_PREFERRED, 0.0)
                    >= probabilities.getOrDefault(PolicyComparisonSpecs.NOT_ADDRESSED, 0.0)
                    ? Verdict.MEETS_PREFERRED : Verdict.UNCLEAR;
        }
        return null;
    }

    private static Verdict verdict(String category, Map<?, ?> entry) {
        return switch (category) {
            case PolicyComparisonSpecs.MEETS_PREFERRED -> Verdict.MEETS_PREFERRED;
            case PolicyComparisonSpecs.WITHIN_FALLBACK ->
                    "PARTIAL".equals(text(entry.get("fallbackConditionsOutcome")))
                            ? Verdict.PARTLY_WITHIN_FALLBACK : Verdict.WITHIN_FALLBACK;
            case PolicyComparisonSpecs.BEYOND_FALLBACK -> Verdict.BEYOND_FALLBACK;
            case PolicyComparisonSpecs.WALK_AWAY -> Verdict.WALK_AWAY;
            case PolicyComparisonSpecs.UNCLEAR -> Verdict.UNCLEAR;
            default -> null;
        };
    }

    /**
     * The per-position search outcomes the absence lane stored. A completed search that addressed
     * nothing is the only path to "not found"; a check that did not complete, and a search that
     * found an addressing clause, both stay "not compared".
     */
    private static SearchOutcomes searchOutcomes(
            Map<UUID, ReviewPlanCheck> checksById, List<ReviewCheckExecution> executions) {
        var completedSearches = new HashSet<UUID>();
        var degraded = new HashSet<UUID>();
        var addressed = new HashSet<UUID>();
        var incomplete = new boolean[1];
        for (var execution : executions) {
            var check = checksById.get(execution.planCheckId());
            if (check == null) {
                continue;
            }
            var completed = execution.status() == CheckExecutionStatus.COMPLETED;
            var positionCheck = check.mechanism() == CheckMechanism.POLICY_COMPARISON
                    || check.mechanism() == CheckMechanism.OMISSION;
            if (positionCheck && !completed) {
                incomplete[0] = true;
            }
            if (check.mechanism() != CheckMechanism.OMISSION) {
                continue;
            }
            var detail = execution.detail();
            for (var candidateId : uuids(detail.get("searchedCandidates"))) {
                if (completed) {
                    completedSearches.add(candidateId);
                } else {
                    degraded.add(candidateId);
                }
            }
            addressed.addAll(uuids(detail.get("addressedBySearch")));
        }
        return new SearchOutcomes(completedSearches, degraded, addressed, incomplete[0]);
    }

    private static FindingLinks findingLinks(
            List<RawReviewSignal> signals, List<Finding> findings) {
        var signalsById = new HashMap<UUID, RawReviewSignal>();
        signals.forEach(signal -> signalsById.put(signal.id(), signal));
        var byCandidate = new HashMap<UUID, UUID>();
        var byCandidateAndClause = new HashMap<UUID, Map<UUID, UUID>>();
        for (var finding : findings) {
            for (var signalId : finding.signalIds()) {
                var signal = signalsById.get(signalId);
                if (signal == null) {
                    continue;
                }
                var candidateId = candidateId(signal);
                if (candidateId == null) {
                    continue;
                }
                byCandidate.putIfAbsent(candidateId, finding.id());
                if (finding.subjectHeadingNodeId() != null) {
                    byCandidateAndClause
                            .computeIfAbsent(candidateId, key -> new HashMap<>())
                            .putIfAbsent(finding.subjectHeadingNodeId(), finding.id());
                }
            }
        }
        return new FindingLinks(byCandidateAndClause, byCandidate);
    }

    private static UUID candidateId(RawReviewSignal signal) {
        var fromPayload = payloadCandidateId(signal.payload());
        if (fromPayload != null) {
            return fromPayload;
        }
        // the needs-your-look payload carries no candidate id; its quality does
        var fromQuality = signal.quality().get("positionCandidateId");
        return fromQuality instanceof String text && !text.isEmpty()
                ? UUID.fromString(text) : null;
    }

    private static UUID payloadCandidateId(SignalPayload payload) {
        if (payload instanceof PositionDeviationPayload deviation) {
            return deviation.positionCandidateId();
        }
        if (payload instanceof OmissionPayload omission) {
            return omission.candidateId();
        }
        return null;
    }

    /**
     * The position's own "Applies to", by the same rule retrieval uses: the artifact's declared
     * types must admit the contract, and a non-empty position scope must name the contract type,
     * its family or another type in its area. An unmodelled contract accepts every position.
     */
    private static boolean governsContractType(ApplicabilityScope scope, ContractType contractType) {
        if (contractType == ContractType.OTHER) {
            return true;
        }
        var declared = keys(scope.declaredContractTypes());
        if (!declared.isEmpty() && contractType.applicableCodes().stream()
                .map(PlaybookCoverageBuilder::key).noneMatch(declared::contains)) {
            return false;
        }
        if (scope.sourceContractTypes().isEmpty()) {
            return true;
        }
        var applicable = keys(contractType.positionApplicableCodes());
        return scope.sourceContractTypes().stream().map(PlaybookCoverageBuilder::key)
                .anyMatch(applicable::contains);
    }

    private static Set<String> keys(Set<String> values) {
        var keys = new HashSet<String>(values.size());
        values.forEach(value -> keys.add(key(value)));
        return keys;
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String topic(KnowledgeCandidate candidate) {
        var concept = candidate.effectiveConceptKey();
        if (concept != null && !concept.isBlank()) {
            return concept;
        }
        return candidate.topicKeys().isEmpty() ? null : candidate.topicKeys().getFirst();
    }

    private static int rank(Verdict verdict) {
        return switch (verdict) {
            case WALK_AWAY -> 6;
            case BEYOND_FALLBACK -> 5;
            case UNCLEAR -> 4;
            case PARTLY_WITHIN_FALLBACK -> 3;
            case WITHIN_FALLBACK -> 2;
            case MEETS_PREFERRED -> 1;
        };
    }

    private static int stateOrder(State state) {
        return switch (state) {
            case COMPARED -> 0;
            case NOT_FOUND -> 1;
            case NOT_COMPARED -> 2;
            case OUT_OF_SCOPE -> 3;
        };
    }

    private static String text(Object value) {
        return value instanceof String text && !text.isEmpty() ? text : null;
    }

    private static Double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static Map<String, Double> probabilities(Object value) {
        if (!(value instanceof Map<?, ?> entries)) {
            return Map.of();
        }
        var probabilities = new HashMap<String, Double>();
        for (var entry : entries.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number number) {
                probabilities.put(key, number.doubleValue());
            }
        }
        return probabilities;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        var strings = new ArrayList<String>(entries.size());
        for (var entry : entries) {
            if (entry instanceof String text) {
                strings.add(text);
            }
        }
        return strings;
    }

    private static List<UUID> uuids(Object value) {
        return strings(value).stream().map(UUID::fromString).toList();
    }

    private static UUID uuid(Object value) {
        return value instanceof String text && !text.isEmpty()
                ? UUID.fromString(text) : null;
    }

    private static final Comparator<Entry> ENTRY_ORDER = Comparator
            .comparingInt((Entry entry) -> stateOrder(entry.state()))
            .thenComparing(entry -> entry.topic() == null ? "" : entry.topic())
            .thenComparing(Entry::label, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Entry::positionCandidateId);

    private record CandidateVerdict(Verdict verdict, UUID clauseHeadingNodeId) {
    }

    private record SearchOutcomes(
            Set<UUID> completedSearches, Set<UUID> degraded, Set<UUID> addressed,
            boolean incomplete) {

        SearchOutcome outcome(UUID candidateId) {
            if (addressed.contains(candidateId)) {
                return SearchOutcome.FOUND;
            }
            if (completedSearches.contains(candidateId)) {
                return SearchOutcome.CLEAN_NOT_FOUND;
            }
            if (degraded.contains(candidateId)) {
                return SearchOutcome.DEGRADED_SEARCH;
            }
            return SearchOutcome.NONE;
        }
    }

    private enum SearchOutcome {
        FOUND,
        CLEAN_NOT_FOUND,
        DEGRADED_SEARCH,
        NONE
    }

    private record FindingLinks(
            Map<UUID, Map<UUID, UUID>> byCandidateAndClause,
            Map<UUID, UUID> byCandidate) {

        UUID forCandidate(UUID candidateId, UUID clauseHeadingNodeId) {
            if (clauseHeadingNodeId != null) {
                var byClause = byCandidateAndClause.get(candidateId);
                if (byClause != null) {
                    var findingId = byClause.get(clauseHeadingNodeId);
                    if (findingId != null) {
                        return findingId;
                    }
                }
            }
            return byCandidate.get(candidateId);
        }

        UUID any(UUID candidateId) {
            return byCandidate.get(candidateId);
        }
    }
}
