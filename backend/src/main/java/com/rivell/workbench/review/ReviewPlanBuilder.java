package com.rivell.workbench.review;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.rivell.workbench.contracts.ClauseSemantics;
import com.rivell.workbench.contracts.ContractSemantics;
import com.rivell.workbench.taxonomy.ConceptKey;
import com.rivell.workbench.taxonomy.CoreConcepts;

/**
 * Deterministic plan construction from snapshot-frozen inputs. Pack definitions own
 * WHICH concepts are checked; this builder owns HOW the plan entries are derived.
 * Subjects are stable canonical heading-node identities.
 */
public final class ReviewPlanBuilder {

    private static final Map<ConceptKey, CheckMechanism> SUPPORTED_CLAUSE_CHECKS =
            Map.of(CoreConcepts.LIMITATION_OF_LIABILITY, CheckMechanism.LIABILITY_CAP);

    private final ReviewPackRegistry packs;

    public ReviewPlanBuilder(ReviewPackRegistry packs) {
        this.packs = packs;
    }

    public List<ReviewPlanCheck> build(
            UUID reviewId, UUID organizationId, ReviewSnapshot snapshot,
            ContractSemantics semantics) {
        var clausesByConcept = clausesByConcept(semantics);
        var checks = new ArrayList<ReviewPlanCheck>();
        var ordinal = new int[] {0};

        if (snapshot.contractPackKey() != null) {
            var pack = packs.find(snapshot.contractPackKey())
                    .orElseThrow(() -> new InvalidReviewConfigurationException(
                            "Unknown contract pack: " + snapshot.contractPackKey()));
            for (var entry : pack.conceptExpectations().entrySet()) {
                checks.add(presenceCheck(reviewId, organizationId, snapshot.createdAt(),
                        ordinal[0]++, entry.getKey(), entry.getValue()));
            }
            for (var entry : pack.clauseChecks().entrySet()) {
                for (var clause : clausesByConcept.getOrDefault(entry.getKey(), List.of())) {
                    checks.add(clauseCheck(reviewId, organizationId, snapshot.createdAt(),
                            ordinal[0]++, entry.getKey(), entry.getValue(),
                            clause.headingNodeId()));
                }
            }
            for (var rule : pack.crossClauseChecks()) {
                if (clausesByConcept.getOrDefault(rule.secondaryConcept(), List.of()).isEmpty()) {
                    continue;
                }
                for (var subject : clausesByConcept.getOrDefault(rule.concept(), List.of())) {
                    checks.add(crossClauseCheck(reviewId, organizationId,
                            snapshot.createdAt(), ordinal[0]++, rule,
                            subject.headingNodeId()));
                }
            }
        } else {
            for (var entry : SUPPORTED_CLAUSE_CHECKS.entrySet()) {
                for (var clause : clausesByConcept.getOrDefault(entry.getKey(), List.of())) {
                    checks.add(clauseCheck(reviewId, organizationId, snapshot.createdAt(),
                            ordinal[0]++, entry.getKey(), entry.getValue(),
                            clause.headingNodeId()));
                }
            }
        }
        return List.copyOf(checks);
    }

    private static Map<ConceptKey, List<ClauseSemantics>> clausesByConcept(
            ContractSemantics semantics) {
        var byConcept = new LinkedHashMap<ConceptKey, List<ClauseSemantics>>();
        for (var clause : semantics.clauses()) {
            clause.effectivePrimaryConcept().ifPresent(concept ->
                    byConcept.computeIfAbsent(concept, key -> new ArrayList<>()).add(clause));
        }
        return byConcept;
    }

    private static ReviewPlanCheck presenceCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal,
            ConceptKey concept, CheckExpectation expectation) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "PRESENCE:" + concept, CheckFamily.CONCEPT_PRESENCE, expectation,
                CheckMechanism.PRESENCE, concept, null, null, createdAt);
    }

    private static ReviewPlanCheck clauseCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal,
            ConceptKey concept, CheckMechanism mechanism, UUID subjectHeadingNodeId) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "CLAUSE:" + mechanism + ":" + concept + ":" + subjectHeadingNodeId,
                CheckFamily.CLAUSE_REVIEW, null, mechanism, concept, null,
                subjectHeadingNodeId, createdAt);
    }

    private static ReviewPlanCheck crossClauseCheck(
            UUID reviewId, UUID organizationId, Instant createdAt, int ordinal,
            ReviewPack.CrossClauseRule rule, UUID subjectHeadingNodeId) {
        return new ReviewPlanCheck(UUID.randomUUID(), organizationId, reviewId, ordinal,
                "CROSS:" + rule.mechanism() + ":" + rule.concept() + ":"
                        + rule.secondaryConcept() + ":" + subjectHeadingNodeId,
                CheckFamily.CROSS_CLAUSE, null, rule.mechanism(), rule.concept(),
                rule.secondaryConcept(), subjectHeadingNodeId, createdAt);
    }
}
