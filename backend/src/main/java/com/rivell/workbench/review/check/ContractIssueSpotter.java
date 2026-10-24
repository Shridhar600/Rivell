package com.rivell.workbench.review.check;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.intelligence.GenerativeReasoningService;
import com.rivell.workbench.intelligence.ProviderFailures;
import com.rivell.workbench.intelligence.SemanticDecisionException;
import com.rivell.workbench.retrieval.EvidenceRequest;
import com.rivell.workbench.retrieval.EvidenceService;
import com.rivell.workbench.review.IssueSpottingSpecs;

/**
 * Runs the whole-contract spotting call once per analysis run (the first clause check that asks
 * runs it, the others wait) and answers which clauses it named. A failed call spots nothing:
 * spotting only adds recall to the position checks, it never replaces them.
 */
final class ContractIssueSpotter {

    /** The model named this clause; {@code executionId} is its generation. */
    record Spot(String severity, String reason, UUID executionId) {
    }

    /**
     * {@code unavailable}: the spotting call failed (or was interrupted), so the whole-contract
     * recall lane is missing and the check says so. A successful call that named nothing is a
     * real empty result.
     */
    record Spots(Map<UUID, Spot> spots, boolean unavailable) {

        static Spots empty() {
            return new Spots(Map.of(), false);
        }

        static Spots failed() {
            return new Spots(Map.of(), true);
        }
    }

    private static final int MAX_RUNS = 16;
    private static final int MAX_POSITIONS = 120;
    private static final int MAX_CLAUSE_TEXT = 3000;
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d+)\\s*\\|\\s*(HIGH|MEDIUM)\\s*\\|\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private final GenerativeReasoningService generation;
    private final EvidenceService evidenceService;
    private final RunMemo<Spots> runs = new RunMemo<>(MAX_RUNS);

    ContractIssueSpotter(GenerativeReasoningService generation, EvidenceService evidenceService) {
        this.generation = generation;
        this.evidenceService = evidenceService;
    }

    Spots spots(ReviewCheckContext context, EvidenceRequest anyClauseRequest) {
        try {
            return runs.get(context.runId(), () -> spot(context, anyClauseRequest));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Spots.failed();
        } catch (ExecutionException e) {
            ProviderFailures.rethrowIfOutOfCredit(e.getCause());
            return Spots.failed();
        }
    }

    private Spots spot(ReviewCheckContext context, EvidenceRequest anyClauseRequest) {
        var clauses = new ArrayList<IssueSpottingSpecs.SpottingClause>();
        var byNumber = new LinkedHashMap<Integer, ClauseSemantics>();
        for (var clause : context.semantics().clauses()) {
            var number = clauses.size() + 1;
            var text = clause.contentText() == null ? "" : clause.contentText();
            clauses.add(new IssueSpottingSpecs.SpottingClause(number,
                    context.headingTexts().getOrDefault(clause.headingNodeId(), ""),
                    text.length() > MAX_CLAUSE_TEXT ? text.substring(0, MAX_CLAUSE_TEXT) : text));
            byNumber.put(number, clause);
        }
        var snapshot = context.snapshot();
        var state = new IssueSpottingSpecs.SpottingState(IssueSpottingSpecs.STATE_SCHEMA,
                snapshot.contractType().label(),
                snapshot.ourSideRole() == null ? "" : snapshot.ourSideRole().name(),
                evidenceService.applicablePositionTexts(anyClauseRequest, MAX_POSITIONS), clauses);
        try {
            var execution = generation.generateLong(IssueSpottingSpecs.spec(), state);
            var spots = new LinkedHashMap<UUID, Spot>();
            for (var line : execution.fields().get(IssueSpottingSpecs.ISSUES).split("\\R")) {
                var matcher = LINE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                var clause = byNumber.get(Integer.parseInt(matcher.group(1)));
                if (clause != null) {
                    spots.putIfAbsent(clause.headingNodeId(), new Spot(
                            matcher.group(2).toUpperCase(java.util.Locale.ROOT), matcher.group(3),
                            execution.executionId()));
                }
            }
            return new Spots(Map.copyOf(spots), false);
        } catch (SemanticDecisionException e) {
            ProviderFailures.rethrowIfOutOfCredit(e);
            return Spots.failed();
        }
    }
}
